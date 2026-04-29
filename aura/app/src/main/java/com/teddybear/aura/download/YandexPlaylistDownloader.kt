package com.teddybear.aura.download

import android.util.Log
import com.teddybear.aura.data.TrackMeta
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "GrooveYandexPlaylist"

/**
 * Downloads all tracks from a Yandex Music playlist.
 *
 * Supports playlist URL formats:
 *   /users/{owner}/playlists/{kind}    → playlist.jsx?kinds=KIND&owner=OWNER
 *   /playlists/{numericKind}           → same
 *   /playlists/lk.{uuid}              → personal "liked" playlist, uses ym:me owner
 *
 * For lk.{uuid} playlists the API endpoint is different:
 *   GET /api/v2.1/handlers/playlist/ym:me/{kind}/tracks
 */
class YandexPlaylistDownloader(
    private val cookies: Map<String, String> = emptyMap(),
) {
    private val cookieHeader: String = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun addCookies(builder: Request.Builder): Request.Builder {
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)
        return builder
    }

    data class PlaylistResult(
        val success: Boolean,
        val downloadedFiles: List<Pair<String, TrackMeta>> = emptyList(),
        val error: String? = null,
    )

    fun download(
        owner: String,
        kind: String,
        locale: String,
        outputDir: File,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): PlaylistResult {
        return try {
            val hostname = "music.yandex.$locale"
            val trackIds = fetchTrackIds(owner, kind, hostname)

            if (trackIds == null) {
                return PlaylistResult(success = false, error = "Не удалось загрузить плейлист (HTTP 404 или плейлист приватный)")
            }
            if (trackIds.isEmpty()) {
                return PlaylistResult(success = false, error = "Плейлист пуст")
            }

            Log.i(TAG, "Playlist has ${trackIds.size} tracks (owner=$owner, kind=$kind)")

            val singleDl = YandexDownloader(cookies)
            val results  = mutableListOf<Pair<String, TrackMeta>>()

            trackIds.forEachIndexed { idx, trackId ->
                onProgress?.invoke(idx, trackIds.size)
                val trackUrl = "https://$hostname/album/0/track/$trackId"
                val r = singleDl.download(trackUrl, outputDir)
                if (r.success && r.filePath != null && r.meta != null) {
                    results.add(Pair(r.filePath, r.meta))
                } else {
                    Log.w(TAG, "Skipped track $trackId: ${r.error}")
                }
            }

            onProgress?.invoke(trackIds.size, trackIds.size)
            PlaylistResult(success = true, downloadedFiles = results)

        } catch (e: Exception) {
            Log.e(TAG, "Playlist download error", e)
            PlaylistResult(success = false, error = e.message ?: "Неизвестная ошибка")
        }
    }

    /**
     * Fetches track IDs using the appropriate API endpoint.
     * Returns null if the request fails (404, private, etc.).
     * Returns empty list if the playlist is accessible but empty.
     */
    private fun fetchTrackIds(owner: String, kind: String, hostname: String): List<Long>? {
        // Try method 1: handlers/playlist.jsx (works for user playlists)
        val v1Ids = fetchViaPlaylistJsx(owner, kind, hostname)
        if (v1Ids != null) return v1Ids

        // Try method 2: API v2.1 (works for liked/personal playlists, ym:me)
        val v2Ids = fetchViaApiV2(owner, kind, hostname)
        if (v2Ids != null) return v2Ids

        return null
    }

    private fun fetchViaPlaylistJsx(owner: String, kind: String, hostname: String): List<Long>? {
        val cleanOwner = if (owner == "yme-user") "ym:me" else owner
        val url = "https://$hostname/handlers/playlist.jsx" +
                  "?kinds=$kind&owner=$cleanOwner&lang=ru" +
                  "&external-domain=$hostname&overembed=no"

        return try {
            val json = get(url, hostname) ?: return null
            val playlist = json.optJSONObject("playlist") ?: return null
            extractIds(playlist.optJSONArray("tracks") ?: return null)
        } catch (e: Exception) {
            Log.d(TAG, "playlist.jsx failed: ${e.message}")
            null
        }
    }

    private fun fetchViaApiV2(owner: String, kind: String, hostname: String): List<Long>? {
        // lk.uuid = personal liked tracks playlist — use the likes/tracks endpoint instead
        val url = if (kind.startsWith("lk.")) {
            "https://$hostname/api/v2.1/handlers/library/tracks" +
            "?lang=ru&external-domain=$hostname&overembed=no&sign=v2"
        } else {
            val apiOwner = if (owner == "yme-user" || owner == "oid") "ym:me" else owner
            "https://$hostname/api/v2.1/handlers/playlist/$apiOwner/$kind/tracks" +
            "?lang=ru&external-domain=$hostname&overembed=no"
        }

        return try {
            val resp = client.newCall(
                addCookies(Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", "https://$hostname/")
                    .header("X-Requested-With", "XMLHttpRequest")).build()
            ).execute()

            if (!resp.isSuccessful) {
                Log.d(TAG, "API v2 HTTP ${resp.code} for $url")
                return null
            }
            val body = resp.body?.string() ?: return null
            // Response is either a JSON array or object with tracks field
            when {
                body.trimStart().startsWith("[") -> {
                    val arr = org.json.JSONArray(body)
                    val ids = mutableListOf<Long>()
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optLong("id", -1L) ?: -1L
                        if (id > 0) ids.add(id)
                    }
                    ids
                }
                else -> {
                    val json = JSONObject(body)
                    val tracks = json.optJSONArray("tracks") ?: return null
                    extractIds(tracks)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "API v2 failed: ${e.message}")
            null
        }
    }

    private fun extractIds(arr: org.json.JSONArray): List<Long> {
        val ids = mutableListOf<Long>()
        for (i in 0 until arr.length()) {
            val id: Long = when (val item = arr.opt(i)) {
                is JSONObject -> {
                    item.optLong("id", -1L)
                        .let { if (it > 0) it else item.optLong("trackId", -1L) }
                }
                is Number -> item.toLong()
                else -> -1L
            }
            if (id > 0) ids.add(id)
        }
        return ids
    }

    private fun get(url: String, hostname: String): JSONObject? {
        val resp = client.newCall(
            addCookies(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", "https://$hostname/")
                .header("X-Requested-With", "XMLHttpRequest")).build()
        ).execute()
        if (!resp.isSuccessful) return null
        return try { JSONObject(resp.body?.string() ?: return null) } catch (_: Exception) { null }
    }

    companion object {
        /**
         * Handles all Yandex Music playlist URL formats:
         *   /users/{owner}/playlists/{kind}
         *   /playlists/{numericKind}
         *   /playlists/lk.{uuid}   ← personal liked playlist
         */
        fun parsePlaylistUrl(url: String): Triple<String, String, String>? {
            val locale = Regex("music\\.yandex\\.(\\w+)").find(url)?.groupValues?.get(1) ?: "ru"
            val path   = url.substringBefore("?")

            // /users/{owner}/playlists/{kind}
            val userMatch = Regex("/users/([^/]+)/playlists/([^/?]+)").find(path)
            if (userMatch != null) {
                return Triple(userMatch.groupValues[1], userMatch.groupValues[2], locale)
            }

            // /playlists/{kind} — handles lk.uuid, ar.xxx, numeric IDs, and any other kind
            val plMatch = Regex("/playlists/([^/?]+)").find(path)
            if (plMatch != null) {
                val kind = plMatch.groupValues[1]
                // Non-numeric kinds (lk.*, ar.*, etc.) belong to yme-user (personal/generated)
                val owner = if (kind.any { it.isLetter() }) "yme-user" else "oid"
                return Triple(owner, kind, locale)
            }

            return null
        }

        fun isPlaylistUrl(url: String): Boolean =
            "music.yandex" in url && "/playlists/" in url
    }
}
