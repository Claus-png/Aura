package com.teddybear.aura.download

import android.content.Context
import android.util.Log
import com.teddybear.aura.data.TrackMeta
import com.teddybear.aura.network.ProxyConfig
import java.io.File

private const val TAG = "GrooveExtTool"

/**
 * Runs yt-dlp (YouTube Music, SoundCloud, Spotify fallback) as native subprocess.
 *
 * Binary lives at getFilesDir()/bin/yt-dlp (arm64-v8a build), copied from assets/ on first run.
 *
 * Playlist vs single-track is detected from the URL:
 *   - Single track  → --no-playlist, one MP3 returned
 *   - Playlist URL  → download entire playlist, list of MP3s returned via filePaths/metas
 *
 * Bitrate: "auto"/"0" → best quality; numeric → closest available ≤ N kbps.
 */
class ExternalToolRunner(private val context: Context) {

    data class Result(
        val success: Boolean,
        val filePath: String? = null,        // non-null for single-track result
        val filePaths: List<String>? = null, // non-null for playlist result
        val meta: TrackMeta? = null,
        val metas: List<TrackMeta>? = null,
        val error: String? = null,
    ) {
        /** True if this result contains multiple tracks (playlist download). */
        val isPlaylist: Boolean get() = filePaths != null && (filePaths.size > 1 || filePaths.isNotEmpty() && filePath == null)
    }

    private val binDir: File get() = File(context.filesDir, "bin").also { it.mkdirs() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Downloads audio from YouTube Music, SoundCloud, or any yt-dlp URL.
     * Detects playlist URLs automatically and downloads all tracks.
     */
    fun downloadWithYtDlp(
        url: String,
        outputDir: File,
        proxy: ProxyConfig? = null,
        bitrateKbps: String = "0",
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        val ytdlp = ensureBinary("yt-dlp") ?: return Result(
            success = false,
            error   = "yt-dlp binary not found. Place arm64-v8a build in assets/bin/yt-dlp",
        )
        outputDir.mkdirs()

        val isPlaylist  = isPlaylistUrl(url)
        val outTemplate = File(outputDir, "%(artist)s - %(track)s.%(ext)s").absolutePath
        val qualityArg  = if (bitrateKbps == "auto" || bitrateKbps == "0") "0" else bitrateKbps

        val cmd = mutableListOf(
            ytdlp.absolutePath,
            url,
            "--output", outTemplate,
            "--extract-audio",
            "--audio-format", "mp3",
            "--audio-quality", qualityArg,
            "--embed-thumbnail",
            "--add-metadata",
            "--newline",
            "--extractor-args", "youtube:player_client=android_music,web",
        )
        if (!isPlaylist) cmd += "--no-playlist"

        proxy?.let { p ->
            if (p.enabled && p.host.isNotBlank()) {
                val scheme = if (p.type.equals("HTTP", ignoreCase = true)) "http" else "socks5"
                val auth   = if (p.username.isNotBlank()) "${p.username}:${p.password}@" else ""
                cmd += listOf("--proxy", "$scheme://$auth${p.host}:${p.port}")
            }
        }

        return runCommand(cmd, outputDir, onProgress)
    }

    /** Downloads a Spotify track or playlist via spotdl, falls back to yt-dlp. */
    fun downloadSpotify(
        url: String,
        outputDir: File,
        proxy: ProxyConfig? = null,
        bitrateKbps: String = "320",
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        val spotdl = ensureBinary("spotdl")
        if (spotdl == null) {
            Log.w(TAG, "spotdl not found — falling back to yt-dlp for Spotify")
            return downloadWithYtDlp(url, outputDir, proxy, bitrateKbps, onProgress)
        }
        outputDir.mkdirs()

        val outTemplate = File(outputDir, "{artists} - {title}.{output-ext}").absolutePath
        val bitrateArg  = if (bitrateKbps == "auto" || bitrateKbps == "0") "320k" else "${bitrateKbps}k"

        val cmd = mutableListOf(
            spotdl.absolutePath,
            "download", url,
            "--output", outTemplate,
            "--format", "mp3",
            "--bitrate", bitrateArg,
            "--threads", "1",
        )

        val env = buildMap<String, String> {
            if (proxy?.enabled == true && proxy.host.isNotBlank()) {
                val scheme   = if (proxy.type.equals("HTTP", ignoreCase = true)) "http" else "socks5"
                val auth     = if (proxy.username.isNotBlank()) "${proxy.username}:${proxy.password}@" else ""
                put("HTTP_PROXY",  "$scheme://$auth${proxy.host}:${proxy.port}")
                put("HTTPS_PROXY", "$scheme://$auth${proxy.host}:${proxy.port}")
            }
        }
        return runCommand(cmd, outputDir, onProgress, extraEnv = env)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun runCommand(
        cmd: List<String>,
        outputDir: File,
        onProgress: ((Int) -> Unit)? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Result {
        Log.d(TAG, "Running: ${cmd.take(4).joinToString(" ")} ...")
        val beforeFiles = outputDir.listFiles()?.toSet() ?: emptySet()

        return try {
            val proc = ProcessBuilder(cmd).apply {
                directory(outputDir)
                redirectErrorStream(true)
                environment().putAll(extraEnv)
            }.start()

            val output = StringBuilder()
            proc.inputStream.bufferedReader().forEachLine { line ->
                output.appendLine(line)
                val pct = Regex("\\[download]\\s+(\\d+\\.?\\d*)%").find(line)
                    ?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
                pct?.let { onProgress?.invoke(it) }
                Log.v(TAG, line)
            }

            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                return Result(success = false, error = "Tool error (exit $exitCode): ${output.takeLast(400)}")
            }

            val newMp3s = ((outputDir.listFiles()?.toSet() ?: emptySet()) - beforeFiles)
                .filter { it.extension.equals("mp3", ignoreCase = true) }
                .sortedBy { it.lastModified() }

            if (newMp3s.isEmpty()) {
                return Result(success = false, error = "No MP3 file found after download")
            }

            if (newMp3s.size == 1) {
                val mp3 = newMp3s.first()
                Log.i(TAG, "Downloaded: ${mp3.name}")
                Result(success = true, filePath = mp3.absolutePath, meta = readTagsFromFile(mp3))
            } else {
                Log.i(TAG, "Downloaded playlist: ${newMp3s.size} tracks")
                Result(
                    success   = true,
                    filePaths = newMp3s.map { it.absolutePath },
                    metas     = newMp3s.map { readTagsFromFile(it) },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed", e)
            Result(success = false, error = e.message ?: "Process error")
        }
    }

    /**
     * Returns the binary File if ready; for yt-dlp, triggers a sync download
     * from GitHub if missing (blocks the worker thread — acceptable since we
     * are already on Dispatchers.IO inside DownloadWorker).
     */
    private fun ensureBinary(name: String): File? {
        val dest = File(binDir, name)

        // Already cached and executable
        if (dest.exists() && dest.canExecute() && dest.length() > 100_000L) return dest

        // Try assets (developer-bundled build)
        try {
            context.assets.open("bin/$name").use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            dest.setExecutable(true, false)
            if (dest.canExecute() && dest.length() > 100_000L) {
                Log.i(TAG, "$name loaded from assets")
                return dest
            }
        } catch (_: Exception) { /* not bundled */ }

        // For yt-dlp: download from GitHub releases synchronously
        if (name == "yt-dlp") {
            Log.i(TAG, "Downloading yt-dlp from GitHub (blocking)...")
            return try {
                val url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android"
                val tmp = File(binDir, "yt-dlp.tmp")
                // Follow GitHub redirects to get actual download URL
                var conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout    = 300_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 Android")
                conn.connect()
                // Follow redirects manually if needed (GitHub uses 302 → 301 → 200)
                var redirectCount = 0
                while (conn.responseCode in 301..302 && redirectCount < 5) {
                    val location = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = java.net.URL(location).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 30_000
                    conn.readTimeout    = 300_000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 Android")
                    conn.connect()
                    redirectCount++
                }
                conn.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(65536); var n: Int
                        while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                if (tmp.length() > 5_000_000L) {
                    tmp.renameTo(dest)
                    dest.setExecutable(true, false)
                    Log.i(TAG, "yt-dlp downloaded: ${dest.length() / 1024} KB")
                    if (dest.canExecute()) dest else null
                } else {
                    Log.e(TAG, "yt-dlp download too small: ${tmp.length()}")
                    tmp.delete(); null
                }
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp download failed: ${e.message}")
                null
            }
        }

        Log.w(TAG, "Binary '$name' not available")
        return null
    }

    private fun readTagsFromFile(file: File): TrackMeta {
        return try {
            val audio = org.jaudiotagger.audio.AudioFileIO.read(file)
            val tag   = audio.tag
            TrackMeta(
                title       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)        ?: file.nameWithoutExtension,
                artist      = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)       ?: "Unknown",
                album       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM)        ?: "",
                albumArtist = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST) ?: "",
                year        = tag?.getFirst(org.jaudiotagger.tag.FieldKey.YEAR)?.toIntOrNull(),
                genre       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.GENRE)        ?: "",
                durationSec = audio.audioHeader.trackLength.toDouble(),
                source      = detectSource(file.absolutePath),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read tags: ${e.message}")
            val stem  = file.nameWithoutExtension
            val parts = stem.split(" - ", limit = 2)
            TrackMeta(title = parts.getOrNull(1) ?: stem, artist = parts.getOrNull(0) ?: "Unknown")
        }
    }

    private fun detectSource(path: String) = when {
        "spotify"    in path.lowercase() -> "spotify"
        "soundcloud" in path.lowercase() -> "soundcloud"
        else                             -> "ytmusic"
    }

    companion object {
        /** Detects Spotify, YouTube Music, and SoundCloud playlist URLs. */
        fun isPlaylistUrl(url: String): Boolean =
            "/playlist/" in url || "?list=" in url || "&list=" in url || "/sets/" in url
    }
}
