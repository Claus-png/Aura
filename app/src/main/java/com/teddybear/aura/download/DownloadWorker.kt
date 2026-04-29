package com.teddybear.aura.download

import android.content.Context
import android.util.Log
import androidx.work.*
import com.teddybear.aura.data.*
import com.teddybear.aura.data.db.AppDatabase
import com.teddybear.aura.network.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private const val TAG = "GrooveWorker"

const val KEY_JOB_ID        = "job_id"
const val KEY_URL           = "url"
const val KEY_SOURCE        = "source"
const val KEY_PROXY_ENABLED = "proxy_enabled"
const val KEY_PROXY_TYPE    = "proxy_type"
const val KEY_PROXY_HOST    = "proxy_host"
const val KEY_PROXY_PORT    = "proxy_port"
const val KEY_PROXY_USER    = "proxy_user"
const val KEY_PROXY_PASS    = "proxy_pass"
const val KEY_NORMALIZE     = "normalize"
const val KEY_BITRATE       = "bitrate"

/**
 * WorkManager Worker: URL → detect source → download (single or playlist)
 *                         → tag → normalize → save to Room.
 *
 * Progress is written to Room so the UI can observe it via Flow.
 * NEVER leaves a job hanging: any exception results in markError().
 */
class DownloadWorker(
    context: Context,
    params:  WorkerParameters,
) : CoroutineWorker(context, params) {

    private val db       = AppDatabase.get(context)
    private val jobDao   = db.jobDao()
    private val trackDao = db.trackDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId    = inputData.getString(KEY_JOB_ID)  ?: return@withContext Result.failure()
        val url      = inputData.getString(KEY_URL)     ?: return@withContext Result.failure()
        val source   = inputData.getString(KEY_SOURCE)  ?: "unknown"
        val normalize = inputData.getBoolean(KEY_NORMALIZE, true)
        val bitrate   = inputData.getString(KEY_BITRATE) ?: "0"

        val proxy = ProxyConfig(
            enabled  = inputData.getBoolean(KEY_PROXY_ENABLED, false),
            type     = inputData.getString(KEY_PROXY_TYPE) ?: "SOCKS5",
            host     = inputData.getString(KEY_PROXY_HOST) ?: "",
            port     = inputData.getInt(KEY_PROXY_PORT, 1080),
            username = inputData.getString(KEY_PROXY_USER) ?: "",
            password = inputData.getString(KEY_PROXY_PASS) ?: "",
        )

        Log.i(TAG, "Starting [$jobId] source=$source url=$url")
        try { jobDao.updateStatus(jobId, "downloading") } catch (_: Exception) {}

        // Prefer external private storage (no extra permissions on Android 10+).
        // Fall back to internal filesDir if external is unmounted or unavailable.
        val musicBase: File =
            if (android.os.Environment.getExternalStorageState() ==
                    android.os.Environment.MEDIA_MOUNTED) {
                applicationContext.getExternalFilesDir(
                    android.os.Environment.DIRECTORY_MUSIC
                ) ?: File(applicationContext.filesDir, "Music")
            } else {
                File(applicationContext.filesDir, "Music")
            }
        // Sanitise source so it can't contain path-separator characters
        val safeSrc = source.replace(Regex("""[/\\:*?"<>|]"""), "_").ifEmpty { "downloads" }
        var outputDir = File(musicBase, safeSrc)
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            // Last resort: internal cache dir (always writable)
            outputDir = File(applicationContext.cacheDir, "Music/$safeSrc").also { it.mkdirs() }
        }

        return@withContext try {
            when {
                // ── Yandex Playlist ───────────────────────────────────────────
                source == "yandex" && YandexPlaylistDownloader.isPlaylistUrl(url) ->
                    downloadYandexPlaylist(url, outputDir, jobId, normalize)

                // ── Spotify / YT Music / SoundCloud Playlist → server ─────────
                source == "spotify" && ExternalToolRunner.isPlaylistUrl(url) ->
                    downloadServerPlaylist(url, source, jobId, normalize)

                source in listOf("youtube", "soundcloud") && ExternalToolRunner.isPlaylistUrl(url) ->
                    downloadServerPlaylist(url, source, jobId, normalize)

                // ── Single track (all sources) ────────────────────────────────
                else -> {
                    val (filePath, meta) = when (source) {
                        // Yandex: always local, no server needed
                        "yandex" -> downloadYandexSingle(url, outputDir, jobId)
                        // Others: try server first, fall back to local binaries
                        "spotify"               -> downloadSpotifySingle(url, outputDir, proxy, bitrate, jobId)
                        "soundcloud", "ytmusic" -> downloadYtDlpSingle(url, outputDir, proxy, bitrate, jobId)
                        else -> throw IllegalArgumentException("Unknown source: $source")
                    }
                    finishSingleTrack(jobId, filePath, meta, normalize, source, url)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed [$jobId]", e)
            try { jobDao.markError(jobId, e.message ?: "Unknown error") } catch (_: Exception) {}
            when (e) {
                is java.net.UnknownHostException,
                is java.net.SocketTimeoutException -> Result.retry()
                else -> Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
            }
        }
    }

    // ── Single-track finalization ─────────────────────────────────────────────

    private suspend fun finishSingleTrack(
        jobId: String, filePath: String, meta: TrackMeta,
        normalize: Boolean, source: String, url: String,
    ): Result {
        jobDao.updateMeta(jobId, meta.title, meta.artist)
        jobDao.updateStatus(jobId, "tagging", 95f)

        val file      = File(filePath)
        AudioProcessor.writeTags(file, meta)
        val finalFile = if (normalize) AudioProcessor.normalizeReplayGain(file) else file
        val sha       = sha256(finalFile)

        // Duplicate check — if track with same SHA exists, delete the new file and reuse
        val existing = trackDao.getBySha(sha)
        if (existing != null) {
            Log.i(TAG, "Duplicate by SHA256, reusing track ${existing.id}")
            if (finalFile.absolutePath != existing.filePath) finalFile.delete()
            jobDao.markDone(jobId, existing.id)
            return Result.success(workDataOf("track_id" to existing.id, "duplicate" to true))
        }

        val coverPath = saveCover(meta.coverBytes, jobId)
        val track = buildTrack(finalFile, meta, sha, coverPath, source, url)
        val tid   = trackDao.insert(track).toInt()
        jobDao.markDone(jobId, tid)
        Log.i(TAG, "Done [$jobId] → ${finalFile.name}")
        return Result.success(workDataOf("track_id" to tid))
    }

    // ── Download helpers — single track ───────────────────────────────────────

    private fun downloadYandexSingle(url: String, outputDir: File, jobId: String): Pair<String, TrackMeta> {
        val cookies = com.teddybear.aura.network.SecureStorage.getYandexCookies()
        if (cookies.isEmpty()) {
            Log.w(TAG, "[$jobId] No Yandex cookies — will likely get 30s preview. Ask user to log in.")
        }
        val r = YandexDownloader(cookies).download(url, outputDir) { done, total ->
            if (total > 0) reportProgress(jobId, done * 90f / total)
        }
        if (!r.success) throw RuntimeException(r.error ?: "Yandex download error")
        return Pair(r.filePath!!, r.meta!!)
    }

    private fun downloadSpotifySingle(
        url: String, outputDir: File, proxy: ProxyConfig, bitrate: String, jobId: String,
    ): Pair<String, TrackMeta> {
        // Spotify — always via home server (no spotdl/yt-dlp on phone)
        kotlinx.coroutines.runBlocking {
            try { jobDao.updateStatus(jobId, "downloading", 1f) } catch (_: Exception) {}
        }
        val ctx = applicationContext
        val serverUrl = ctx.getSharedPreferences("aura_server", android.content.Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        if (serverUrl.isBlank()) throw RuntimeException(
            "Для загрузки Spotify нужен домашний сервер."
        )
        val serverRepo = com.teddybear.aura.network.ServerRepository(ctx)
        return kotlinx.coroutines.runBlocking {
            val taskId = serverRepo.requestDownload(url, "spotify")
            Log.i(TAG, "[$jobId] Spotify via server task=$taskId")
            var filePath: String? = null
            var meta: com.teddybear.aura.data.TrackMeta? = null
            serverRepo.awaitTask(taskId) { pct -> reportProgress(jobId, pct.toFloat()) }.also { task ->
                when (task.status) {
                    "complete" -> {
                        val relUrl = task.fileUrl ?: throw RuntimeException("Сервер не вернул ссылку")
                        val fileUrl = if (relUrl.startsWith("http")) relUrl else "${serverUrl.trimEnd('/')}$relUrl"
                        outputDir.mkdirs()
                        val outFile = File(outputDir, "spotify_${taskId.take(8)}.mp3")
                        val req = okhttp3.Request.Builder().url(fileUrl)
                            .header("X-AURA-KEY", com.teddybear.aura.network.SecureStorage.getApiKey()).build()
                        val http = okhttp3.OkHttpClient.Builder()
                            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
                        http.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} downloading Spotify track")
                            outFile.outputStream().use { out ->
                                resp.body?.byteStream()?.use { inp ->
                                    val buf = ByteArray(65_536); var n: Int
                                    while (inp.read(buf).also { n = it } != -1) { out.write(buf, 0, n) }
                                }
                            }
                        }
                        filePath = outFile.absolutePath
                        meta = com.teddybear.aura.data.TrackMeta(
                            title = task.errorMsg ?: "Track", artist = "Unknown",
                            source = "spotify", sourceUrl = url)
                    }
                    "error" -> throw RuntimeException(task.errorMsg ?: "Ошибка загрузки Spotify")
                    else -> throw RuntimeException("Неожиданный статус: ${task.status}")
                }
            }
            Pair(filePath!!, meta!!)
        }
    }

    private fun downloadYtDlpSingle(
        url: String, outputDir: File, proxy: ProxyConfig, bitrate: String, jobId: String,
    ): Pair<String, TrackMeta> {
        // YT Music / SoundCloud — download via AURA home server
        kotlinx.coroutines.runBlocking {
            try { jobDao.updateStatus(jobId, "downloading", 1f) } catch (_: Exception) {}
        }
        val ctx       = applicationContext
        val serverUrl = ctx.getSharedPreferences("aura_server", android.content.Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        if (serverUrl.isBlank()) throw RuntimeException(
            "Для загрузки нужен домашний сервер. Настройте в разделе «Настройки»."
        )

        val serverRepo = com.teddybear.aura.network.ServerRepository(ctx)
        val service = when {
            "music.youtube.com" in url || "youtu.be" in url || "youtube.com" in url -> "youtube"
            "soundcloud.com" in url -> "soundcloud"
            "open.spotify.com" in url -> "spotify"
            else -> "youtube"
        }

        return kotlinx.coroutines.runBlocking {
            val taskId = serverRepo.requestDownload(url, service)
            Log.i(TAG, "[$jobId] Server task_id=$taskId for $service")
            var filePath: String? = null
            var meta: com.teddybear.aura.data.TrackMeta? = null

            serverRepo.awaitTask(taskId) { pct -> reportProgress(jobId, pct.toFloat()) }.also { task ->
                when (task.status) {
                    "complete" -> {
                        // file_url is relative (/downloads/id.mp3) — prepend server base
                        val relUrl = task.fileUrl
                            ?: throw RuntimeException("Сервер не вернул ссылку на файл")
                        val baseUrl = serverUrl.trimEnd('/')
                        val fileUrl = if (relUrl.startsWith("http")) relUrl
                                      else "$baseUrl$relUrl"
                        outputDir.mkdirs()
                        val outFile = File(outputDir, "server_${taskId.take(8)}.mp3")
                        val req = okhttp3.Request.Builder()
                            .url(fileUrl)
                            .header("X-AURA-KEY",
                                com.teddybear.aura.network.SecureStorage.getApiKey())
                            .build()
                        val http = okhttp3.OkHttpClient.Builder()
                            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
                        http.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                            val total = resp.body?.contentLength() ?: -1L; var done = 0L
                            outFile.outputStream().use { out ->
                                resp.body?.byteStream()?.use { inp ->
                                    val buf = ByteArray(65_536); var n: Int
                                    while (inp.read(buf).also { n = it } != -1) {
                                        out.write(buf, 0, n); done += n
                                        if (total > 0) reportProgress(jobId, 50f + done * 45f / total)
                                    }
                                }
                            }
                        }
                        filePath = outFile.absolutePath
                        meta = com.teddybear.aura.data.TrackMeta(
                            title = "Track", artist = "Unknown", source = service, sourceUrl = url)
                    }
                    "error" -> throw RuntimeException(
                        task.errorMsg ?: "Ошибка загрузки на сервере")
                    else -> throw RuntimeException("Неожиданный статус задачи: ${task.status}")
                }
            }
            Pair(filePath!!, meta!!)
        }
    }

    // ── Playlist download helpers ─────────────────────────────────────────────

    /** Spotify / YT Music / SoundCloud playlists — delegate to home server. */
    private suspend fun downloadServerPlaylist(
        url: String, source: String, jobId: String, normalize: Boolean,
    ): Result {
        val ctx = applicationContext
        val serverUrl = ctx.getSharedPreferences("aura_server", android.content.Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        if (serverUrl.isBlank()) throw RuntimeException(
            "Для загрузки плейлиста нужен домашний сервер."
        )
        val serverRepo = com.teddybear.aura.network.ServerRepository(ctx)
        val taskId = serverRepo.requestDownload(url, source)
        Log.i(TAG, "[$jobId] Playlist via server task=$taskId source=$source")

        val task = serverRepo.awaitTask(taskId) { pct -> reportProgress(jobId, pct.toFloat()) }
        return when (task.status) {
            "complete" -> {
                jobDao.markDone(jobId, -1)
                Result.success(workDataOf("task_id" to taskId))
            }
            "error" -> throw RuntimeException(task.errorMsg ?: "Ошибка загрузки плейлиста на сервере")
            else -> throw RuntimeException("Неожиданный статус: ${task.status}")
        }
    }

    private suspend fun downloadYandexPlaylist(
        url: String, outputDir: File, jobId: String, normalize: Boolean,
    ): Result {
        val parsed = YandexPlaylistDownloader.parsePlaylistUrl(url)
            ?: throw RuntimeException("Не удалось распознать URL плейлиста Яндекс Музыки")

        val (owner, kind, locale) = parsed
        val cookies = com.teddybear.aura.network.SecureStorage.getYandexCookies()
        val result = YandexPlaylistDownloader(cookies).download(owner, kind, locale, outputDir) { cur, tot ->
            if (tot > 0) reportProgress(jobId, cur.toFloat() / tot * 90f)
        }
        if (!result.success) throw RuntimeException(result.error ?: "Ошибка загрузки плейлиста")

        var lastId = -1
        result.downloadedFiles.forEachIndexed { idx, (fp, meta) ->
            try {
                val file      = File(fp)
                AudioProcessor.writeTags(file, meta)
                val finalFile = if (normalize) AudioProcessor.normalizeReplayGain(file) else file
                val sha       = sha256(finalFile)
                val coverPath = saveCover(meta.coverBytes, "${jobId}_$idx")
                lastId = trackDao.insert(buildTrack(finalFile, meta, sha, coverPath, "yandex", url)).toInt()
            } catch (e: Exception) { Log.w(TAG, "Skip playlist track #$idx: ${e.message}") }
        }

        jobDao.markDone(jobId, lastId)
        Log.i(TAG, "Yandex playlist done [$jobId]: ${result.downloadedFiles.size} tracks")
        return Result.success(workDataOf("track_count" to result.downloadedFiles.size))
    }

    private suspend fun downloadExtPlaylist(
        url: String, source: String, outputDir: File,
        proxy: ProxyConfig, bitrate: String, jobId: String, normalize: Boolean,
    ): Result {
        // Spotify → spotdl / yt-dlp
        if (source == "spotify") {
            val r = ExternalToolRunner(applicationContext).downloadSpotify(url, outputDir, proxy, bitrate) { pct ->
                reportProgress(jobId, pct * 0.9f)
            }
            if (!r.success) throw RuntimeException(r.error ?: "Spotify playlist failed")
            return saveMultipleFiles(r, jobId, normalize, source, url)
        }

        // YT Music / SoundCloud playlists → ExternalToolRunner (yt-dlp fallback)
        val runner = ExternalToolRunner(applicationContext)
        val result = runner.downloadWithYtDlp(url, outputDir, proxy, bitrate) { pct ->
            reportProgress(jobId, pct * 0.9f)
        }

        if (!result.success) throw RuntimeException(result.error ?: "Playlist download error")

        // Single-file result (shouldn't happen for playlist, but handle gracefully)
        if (!result.isPlaylist && result.filePath != null && result.meta != null) {
            return finishSingleTrack(jobId, result.filePath, result.meta, normalize, source, url)
        }

        val paths = result.filePaths ?: emptyList()
        val metas = result.metas ?: emptyList()
        var lastId = -1

        paths.forEachIndexed { idx, fp ->
            try {
                val meta      = metas.getOrElse(idx) { TrackMeta() }
                val file      = File(fp)
                AudioProcessor.writeTags(file, meta)
                val finalFile = if (normalize) AudioProcessor.normalizeReplayGain(file) else file
                val sha       = sha256(finalFile)
                val coverPath = saveCover(meta.coverBytes, "${jobId}_$idx")
                lastId = trackDao.insert(buildTrack(finalFile, meta, sha, coverPath, source, url)).toInt()
            } catch (e: Exception) { Log.w(TAG, "Skip playlist track #$idx: ${e.message}") }
        }

        jobDao.markDone(jobId, lastId)
        Log.i(TAG, "Ext playlist done [$jobId]: ${paths.size} tracks")
        return Result.success(workDataOf("track_count" to paths.size))
    }

    private suspend fun saveMultipleFiles(
        r: ExternalToolRunner.Result, jobId: String, normalize: Boolean, source: String, url: String,
    ): Result {
        val paths  = r.filePaths ?: listOfNotNull(r.filePath)
        val metas  = r.metas     ?: listOfNotNull(r.meta)
        var lastId = -1
        paths.forEachIndexed { idx, fp ->
            try {
                val meta      = metas.getOrElse(idx) { TrackMeta() }
                val file      = File(fp)
                AudioProcessor.writeTags(file, meta)
                val finalFile = if (normalize) AudioProcessor.normalizeReplayGain(file) else file
                val sha       = sha256(finalFile)
                val cover     = saveCover(meta.coverBytes, "${jobId}_$idx")
                lastId = trackDao.insert(buildTrack(finalFile, meta, sha, cover, source, url)).toInt()
            } catch (e: Exception) { Log.w(TAG, "Skip #$idx: ${e.message}") }
        }
        jobDao.markDone(jobId, lastId)
        return Result.success(workDataOf("track_count" to paths.size))
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun buildTrack(
        file: File, meta: TrackMeta, sha: String, coverPath: String?, source: String, url: String,
    ) = Track(
        filePath    = file.absolutePath,
        filename    = file.name,
        title       = meta.title.ifEmpty { file.nameWithoutExtension },
        artist      = meta.artist.ifEmpty { "Unknown" },
        album       = meta.album,
        albumArtist = meta.albumArtist,
        year        = meta.year,
        genre       = meta.genre,
        trackNumber = meta.trackNumber,
        durationSec = meta.durationSec,
        fileSize    = file.length(),
        sha256      = sha,
        coverPath   = coverPath,
        lyricsPlain = meta.lyricsPlain,
        lyricsLrc   = meta.lyricsLrc,
        source      = source,
        sourceUrl   = url,
    )

    private fun saveCover(bytes: ByteArray?, prefix: String): String? {
        if (bytes == null) return null
        return try {
            val dir = File(applicationContext.filesDir, "covers").also { it.mkdirs() }
            val f   = File(dir, "$prefix.jpg")
            f.writeBytes(bytes)
            f.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Could not save cover: ${e.message}")
            null
        }
    }

    private fun reportProgress(jobId: String, pct: Float) {
        kotlinx.coroutines.runBlocking {
            try { jobDao.updateProgress(jobId, pct) } catch (_: Exception) {}
        }
        setProgressAsync(workDataOf("progress" to pct))
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8192); var r: Int
            while (inp.read(buf).also { r = it } != -1) md.update(buf, 0, r)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// ── Convenience builder ───────────────────────────────────────────────────────

fun buildDownloadRequest(
    jobId: String, url: String, source: String,
    proxy: ProxyConfig, normalize: Boolean, bitrate: String = "0",
): OneTimeWorkRequest {
    val data = workDataOf(
        KEY_JOB_ID        to jobId,
        KEY_URL           to url,
        KEY_SOURCE        to source,
        KEY_PROXY_ENABLED to proxy.enabled,
        KEY_PROXY_TYPE    to proxy.type,
        KEY_PROXY_HOST    to proxy.host,
        KEY_PROXY_PORT    to proxy.port,
        KEY_PROXY_USER    to proxy.username,
        KEY_PROXY_PASS    to proxy.password,
        KEY_NORMALIZE     to normalize,
        KEY_BITRATE       to bitrate,
    )
    return OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(data)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .addTag("download")
        .addTag("download_$jobId")
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}
