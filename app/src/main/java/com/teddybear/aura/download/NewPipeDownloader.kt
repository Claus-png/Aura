package com.teddybear.aura.download

import android.util.Log
import com.teddybear.aura.data.TrackMeta
import com.teddybear.aura.network.ProxyConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader as ExtractorDownloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "GrooveNewPipe"

/**
 * Downloads YouTube Music and SoundCloud tracks via NewPipeExtractor.
 */
object NewPipeDownloader {

    data class Result(
        val success: Boolean,
        val filePath: String? = null,
        val filePaths: List<String>? = null,
        val meta: TrackMeta? = null,
        val metas: List<TrackMeta>? = null,
        val error: String? = null,
    )

    fun supportsUrl(url: String) = when {
        "spotify.com"    in url -> false
        "youtube.com"    in url -> true
        "youtu.be"       in url -> true
        "soundcloud.com" in url -> true
        else                    -> false
    }

    // ── OkHttp bridge ────────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private object OkHttpFetcher : ExtractorDownloader() {
        override fun execute(req: ExtractorRequest): ExtractorResponse {
            val builder = Request.Builder().url(req.url())

            // Добавляем заголовки
            req.headers().forEach { (k, vs) -> vs.forEach { builder.addHeader(k, it) } }

            // Исправлено: создание RequestBody для OkHttp 4+
            val bodyData = req.dataToSend()
            if (bodyData != null) {
                builder.post(bodyData.toRequestBody(null))
            }

            val resp = httpClient.newCall(builder.build()).execute()
            val headers = resp.headers.toMultimap()
            val bodyStr = resp.body?.string() ?: ""

            /**
             * Исправлено: Конструктор ExtractorResponse в новых версиях требует:
             * (responseCode, responseMessage, responseHeaders, responseBody, latestUrl)
             */
            return ExtractorResponse(
                resp.code,
                resp.message,
                headers,
                bodyStr,
                req.url()
            )
        }
    }

    @Volatile private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                NewPipe.init(OkHttpFetcher)
                initialized = true
                Log.d(TAG, "NewPipeExtractor ready")
            } catch (e: Exception) {
                Log.w(TAG, "NewPipe init: ${e.message}")
            }
        }
    }

    // ── Single track ──────────────────────────────────────────────────────────

    fun downloadTrack(
        url: String,
        outputDir: File,
        proxy: ProxyConfig? = null,
        @Suppress("UNUSED_PARAMETER") bitrateKbps: String = "0",
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        if (!supportsUrl(url)) return Result(false,
            error = "NewPipeExtractor не поддерживает этот URL")
        ensureInit()
        outputDir.mkdirs()

        return try {
            val service    = NewPipe.getServiceByUrl(url)
            val streamInfo = StreamInfo.getInfo(service, url)

            val title  = streamInfo.name?.ifEmpty { "Unknown" } ?: "Unknown"
            val artist = streamInfo.uploaderName?.ifEmpty { "Unknown" } ?: "Unknown"

            // Выбираем лучший поток (или фильтруем по bitrateKbps, если нужно)
            val audioStream = streamInfo.audioStreams.maxByOrNull { it.averageBitrate }
                ?: return Result(false, error = "Нет аудио-потоков")

            val streamUrl = audioStream.content
                ?: return Result(false, error = "Пустой URL потока")

            val ext     = if (audioStream.format?.suffix == "mp3") "mp3" else "m4a"
            val outFile = File(outputDir, "${sanitize(artist)} - ${sanitize(title)}.$ext")

            downloadStream(streamUrl, outFile, proxy, onProgress)

            Log.i(TAG, "Downloaded: ${outFile.name}")
            Result(true, filePath = outFile.absolutePath,
                meta = TrackMeta(title = title, artist = artist, source = detectSource(url)))
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe track failed: ${e.message}")
            Result(false, error = "NewPipeExtractor: ${e.message}")
        }
    }

    // ── Playlist ──────────────────────────────────────────────────────────────

    @Suppress("unused")
    fun downloadPlaylist(
        url: String,
        outputDir: File,
        proxy: ProxyConfig? = null,
        @Suppress("UNUSED_PARAMETER") bitrateKbps: String = "0",
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result {
        if (!supportsUrl(url)) return Result(false, error = "URL не поддерживается")
        ensureInit()
        outputDir.mkdirs()

        return try {
            val service      = NewPipe.getServiceByUrl(url)
            val playlistInfo = PlaylistInfo.getInfo(service, url)
            val items        = playlistInfo.relatedItems ?: emptyList()
            val filePaths    = mutableListOf<String>()
            val metas        = mutableListOf<TrackMeta>()

            items.forEachIndexed { idx, item ->
                onProgress?.invoke(idx, items.size)
                try {
                    val r = downloadTrack(item.url ?: return@forEachIndexed, outputDir, proxy)
                    if (r.success && r.filePath != null && r.meta != null) {
                        filePaths.add(r.filePath); metas.add(r.meta)
                    }
                } catch (e: Exception) { Log.w(TAG, "Skip $idx: ${e.message}") }
            }
            onProgress?.invoke(items.size, items.size)
            Result(true, filePaths = filePaths, metas = metas)
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe playlist: ${e.message}")
            Result(false, error = "NewPipeExtractor: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun downloadStream(
        url: String, dest: File, proxy: ProxyConfig?, onProgress: ((Int) -> Unit)?,
    ) {
        val b = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)

        if (proxy?.enabled == true && proxy.host.isNotBlank()) {
            val type = if (proxy.type.equals("HTTP", ignoreCase = true))
                java.net.Proxy.Type.HTTP else java.net.Proxy.Type.SOCKS
            b.proxy(java.net.Proxy(type, java.net.InetSocketAddress(proxy.host, proxy.port)))
        }

        b.build().newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body?.use { body ->
                val total = body.contentLength(); var done = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(65536); var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n); done += n
                            if (total > 0) onProgress?.invoke((done * 100 / total).toInt())
                        }
                    }
                }
            }
        }
    }

    private fun detectSource(url: String) =
        if ("soundcloud.com" in url) "soundcloud" else "ytmusic"

    private fun sanitize(s: String) = s
        .replace("/", "-").replace("\\", "-").replace(":", " -")
        .replace("*", "").replace("?", "").replace("\"", "'")
        .replace("<", "").replace(">", "").replace("|", "-")
        .trim('.', ' ').ifEmpty { "Unknown" }
}