package com.teddybear.aura.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

private const val TAG = "GrooveBinary"

/**
 * Manages download and caching of native binaries (yt-dlp) at runtime.
 *
 * Binaries are stored in getFilesDir()/bin/ and persisted across sessions.
 * On first launch (or if deleted), downloads from GitHub releases.
 *
 * yt-dlp arm64 build: ~12 MB, one-time download.
 */
object BinaryManager {

    private const val YTDLP_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android"

    // Minimum valid file size — guards against partial downloads
    private const val YTDLP_MIN_SIZE = 5_000_000L  // 5 MB

    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) {
        val percent: Int get() = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
    }

    /**
     * Ensures yt-dlp binary exists and is executable.
     * Downloads from GitHub if missing or corrupted (< min size).
     *
     * @param onProgress optional progress callback (bytesDownloaded, totalBytes)
     * @return the executable File, or null if download failed
     */
    suspend fun ensureYtDlp(
        context: Context,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        val binDir = File(context.filesDir, "bin").also { it.mkdirs() }
        val dest   = File(binDir, "yt-dlp")

        // Already good
        if (dest.exists() && dest.canExecute() && dest.length() > YTDLP_MIN_SIZE) {
            Log.d(TAG, "yt-dlp already present (${dest.length() / 1024} KB)")
            return@withContext dest
        }

        // Try assets first (developer-bundled binary)
        try {
            context.assets.open("bin/yt-dlp").use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            dest.setExecutable(true, false)
            if (dest.canExecute() && dest.length() > YTDLP_MIN_SIZE) {
                Log.i(TAG, "yt-dlp loaded from assets (${dest.length() / 1024} KB)")
                return@withContext dest
            }
        } catch (_: Exception) {
            Log.d(TAG, "yt-dlp not in assets — will download from GitHub")
        }

        // Download from GitHub releases
        Log.i(TAG, "Downloading yt-dlp from GitHub...")
        return@withContext try {
            val tmp = File(binDir, "yt-dlp.tmp")
            val conn = URL(YTDLP_URL).openConnection().also {
                it.connectTimeout = 30_000
                it.readTimeout    = 120_000
                it.connect()
            }
            val total = conn.contentLengthLong
            var downloaded = 0L

            conn.getInputStream().use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress?.invoke(DownloadProgress(downloaded, total))
                    }
                }
            }

            if (tmp.length() < YTDLP_MIN_SIZE) {
                Log.e(TAG, "Downloaded file too small: ${tmp.length()}")
                tmp.delete()
                null
            } else {
                tmp.renameTo(dest)
                dest.setExecutable(true, false)
                Log.i(TAG, "yt-dlp downloaded: ${dest.length() / 1024} KB")
                if (dest.canExecute()) dest else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "yt-dlp download failed: ${e.message}")
            null
        }
    }

    /** Check if yt-dlp is available without downloading. */
    fun isYtDlpAvailable(context: Context): Boolean {
        val f = File(context.filesDir, "bin/yt-dlp")
        return f.exists() && f.canExecute() && f.length() > YTDLP_MIN_SIZE
    }

    /** Delete cached binaries (e.g. for forced re-download). */
    fun clearBinaries(context: Context) {
        File(context.filesDir, "bin").listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Binaries cleared")
    }
}
