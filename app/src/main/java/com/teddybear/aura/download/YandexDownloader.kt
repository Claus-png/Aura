package com.teddybear.aura.download

import android.util.Log
import com.teddybear.aura.data.TrackMeta
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "GrooveYandex"

/**
 * Sign key from the Yandex Music Chrome extension (v1.4.8.2.0).
 * IMPORTANT: 'l' (lowercase L) NOT '1' (digit one); 'H' uppercase; 'i' NOT '1'.
 * A single wrong character produces wrong MD5 → CDN returns 30-second preview.
 */
private const val SIGN_KEY   = "XGRlBW9FXlekgbPrRHuSiA"
private const val COVER_SIZE = 400

/**
 * Downloads a single Yandex Music track (full, not preview).
 *
 * Root causes of the 30-second preview problem:
 *   1. Wrong SIGN_KEY (typo: '1' instead of 'l') → wrong MD5 → CDN serves preview
 *   2. Missing session cookies → download/m returns preview src instead of full-track src
 *
 * @param cookies  Yandex session cookies from SecureStorage.getYandexCookies().
 *                 Required fields: Session_id, sessionid2, yandexuid.
 */
class YandexDownloader(
    private val cookies: Map<String, String> = emptyMap(),
) {
    data class Result(
        val success:  Boolean,
        val filePath: String?    = null,
        val meta:     TrackMeta? = null,
        val error:    String?    = null,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Cookie header value, assembled once. */
    private val cookieHeader: String =
        cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    private val baseHeaders = mapOf(
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language"  to "ru-RU,ru;q=0.9,en;q=0.8",
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                              "AppleWebKit/537.36 (KHTML, like Gecko) " +
                              "Chrome/124.0.0.0 Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest",
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun download(
        url:        String,
        outputDir:  File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result = try {
        val (trackId, locale) = parseUrl(url)
            ?: return Result(false, error = "Не удалось извлечь ID трека: $url")

        val hasCookies = cookies.containsKey("Session_id")
        Log.i(TAG, "track=$trackId locale=$locale hasCookies=$hasCookies")

        val host = "music.yandex.$locale"

        // ── 1. Metadata ───────────────────────────────────────────────────────
        val metaUrl = "https://$host/handlers/track.jsx" +
            "?track=$trackId&lang=ru&external-domain=$host&overembed=no"
        val info = getJson(metaUrl, host, sendCookies = true)
            ?: return Result(false, error = "Трек $trackId не найден")

        val trackObj = info.optJSONObject("track")
            ?: info.optJSONObject("data")?.optJSONObject("track")
            ?: return Result(false, error = "Неожиданный формат ответа для трека $trackId")

        val album   = trackObj.optJSONArray("albums")?.optJSONObject(0) ?: JSONObject()
        val artists = trackObj.optJSONArray("artists")
        val artist  = buildString {
            if (artists != null) for (i in 0 until artists.length()) {
                if (i > 0) append(", ")
                append(artists.optJSONObject(i)?.optString("name", "") ?: "")
            }
        }.ifBlank { "Unknown" }

        val lyricObj    = try {
            info.optJSONArray("lyric")?.optJSONObject(0)
        } catch (e: Exception) {
            Log.w("YandexDownloader", "Error parsing lyric object: ${e.message}")
            null
        }
        val durationSec = trackObj.optDouble("durationMs", 0.0) / 1000.0
        val coverBytes  = trackObj.optString("coverUri", "").ifEmpty { null }?.let { fetchCover(it) }

        val lyricsPlain = try {
            lyricObj?.optString("fullLyrics")?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("YandexDownloader", "Error extracting lyrics: ${e.message}")
            null
        }

        val lyricsLrc = try {
            (lyricObj?.optString("lrc")
                ?: lyricObj?.optString("lrcText"))?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("YandexDownloader", "Error extracting LRC: ${e.message}")
            null
        }

        val meta = TrackMeta(
            title       = trackObj.optString("title", "Unknown"),
            artist      = artist,
            album       = album.optString("title", ""),
            albumArtist = album.optJSONArray("artists")
                              ?.optJSONObject(0)?.optString("name", "") ?: "",
            year        = album.optInt("year").takeIf { it > 0 },
            genre       = album.optString("genre", ""),
            trackNumber = album.optJSONObject("trackPosition")?.optInt("index"),
            durationSec = durationSec,
            coverBytes  = coverBytes,
            lyricsPlain = lyricsPlain,
            lyricsLrc   = lyricsLrc,
            source    = "yandex",
            sourceUrl = url,
        )

        // ── 2. CDN URL (HQ → standard fallback) ──────────────────────────────
        val cdnUrl = resolveCdnUrl(trackId, locale, hq = true)
            ?: resolveCdnUrl(trackId, locale, hq = false)
            ?: return Result(
                false,
                error = if (!hasCookies)
                    "Нет авторизации Яндекс Музыки. Войдите в аккаунт в разделе «Настройки»."
                else
                    "Не удалось получить ссылку для трека $trackId"
            )

        // ── 3. Download file ──────────────────────────────────────────────────
        outputDir.mkdirs()
        val outFile = File(outputDir, "${sanitize(artist)} - ${sanitize(meta.title)}.mp3")
        downloadFile(cdnUrl, outFile, onProgress)

        val kb = outFile.length() / 1024
        if (outFile.length() < 1_500_000L && durationSec > 90) {
            Log.w(TAG, "Possible preview: ${kb}KB for ${durationSec.toInt()}s. hasCookies=$hasCookies")
        } else {
            Log.i(TAG, "Done: ${outFile.name} (${kb}KB)")
        }

        Result(true, filePath = outFile.absolutePath, meta = meta)

    } catch (e: Exception) {
        Log.e(TAG, "Error: $url", e)
        Result(false, error = e.message ?: "Неизвестная ошибка")
    }

    fun parseUrl(url: String): Pair<Long, String>? {
        val locale = Regex("music\\.yandex\\.(\\w+)").find(url)?.groupValues?.get(1) ?: "ru"
        // Strip query params for path matching, keep them for query matching
        val path = url.substringBefore("?")
        val id =
            // /album/37083035/track/140238142
            Regex("/album/\\d+/track/(\\d+)").find(path)?.groupValues?.get(1)?.toLongOrNull()
            // /track/140238142  or  /tracks/140238142
            ?: Regex("/tracks?/(\\d+)").find(path)?.groupValues?.get(1)?.toLongOrNull()
            // ?track=140238142  (query param)
            ?: Regex("[?&]track=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            // bare numeric ID
            ?: url.substringBefore("?").trimEnd('/').substringAfterLast("/").toLongOrNull()
        return if (id != null) Pair(id, locale) else null
    }

    // ── CDN resolution ────────────────────────────────────────────────────────

    /**
     * Two-step resolution from the Chrome extension:
     *   Step 1: GET /api/v2.1/handlers/track/{id}/.../download/m  → { src }
     *           Cookies REQUIRED — without them src points to 30s preview.
     *   Step 2: GET {src}&format=json                             → { host, path, ts, s }
     *   Sign:   md5(SIGN_KEY + path[1:] + s)
     *   Final:  https://{host}/get-mp3/{md5}/{ts}{path}?track-id={id}
     */
    private fun resolveCdnUrl(trackId: Long, locale: String, hq: Boolean): String? = try {
        val host  = "music.yandex.$locale"
        val step1 = "https://$host/api/v2.1/handlers/track/$trackId" +
            "/web-album-track-track-main/download/m" +
            "?hq=${if (hq) 1 else 0}&external-domain=$host" +
            "&overembed=no&__t=${System.currentTimeMillis()}"

        // Step 1: needs cookies so Yandex returns full-track src, not preview src
        val j1  = getJson(step1, host, sendCookies = true) ?: return null
        var src = j1.optString("src", "").ifEmpty { return null }
        if (src.startsWith("//")) src = "https:$src"
        Log.d(TAG, "CDN src (hq=$hq): $src")

        // Step 2: request to storage CDN (different domain — must NOT send Yandex cookies)
        val step2 = if ("?" in src) "$src&format=json" else "$src?format=json"
        val j2    = getJson(step2, host, sendCookies = false) ?: return null

        val fHost = j2.optString("host", "").ifEmpty { return null }
        val path  = j2.optString("path", "").ifEmpty { return null }
        val s     = j2.optString("s",    "").ifEmpty { return null }
        val ts    = j2.optString("ts",   "").ifEmpty { return null }

        val sign = md5Hex(SIGN_KEY + path.substring(1) + s)
        val finalUrl = "https://$fHost/get-mp3/$sign/$ts$path?track-id=$trackId"
        Log.d(TAG, "CDN final (hq=$hq): $finalUrl")
        finalUrl
    } catch (e: Exception) {
        Log.w(TAG, "resolveCdnUrl hq=$hq: ${e.message}")
        null
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun buildRequest(url: String, refHost: String, sendCookies: Boolean = true): Request {
        val b = Request.Builder().url(url)
        baseHeaders.forEach { (k, v) -> b.header(k, v) }
        b.header("Referer",     "https://$refHost/")
        b.header("X-Retpath-Y", "https://$refHost/")
        // Only attach cookies for music.yandex.* requests — NOT for storage CDN
        if (sendCookies && cookieHeader.isNotEmpty()) b.header("Cookie", cookieHeader)
        return b.build()
    }

    private fun getJson(url: String, refHost: String, sendCookies: Boolean = true): JSONObject? {
        return try {
            val resp = client.newCall(buildRequest(url, refHost, sendCookies)).execute()
            if (!resp.isSuccessful) { Log.w(TAG, "HTTP ${resp.code}: $url"); return null }
            val body = resp.body?.string() ?: return null
            val trim = body.trimStart()
            when {
                trim.startsWith("{") -> JSONObject(body)
                trim.startsWith("[") -> JSONArray(body).optJSONObject(0)
                else -> { Log.w(TAG, "Non-JSON: ${trim.take(80)}"); null }
            }
        } catch (e: Exception) { Log.w(TAG, "getJson: ${e.message}"); null }
    }

    private fun downloadFile(url: String, dest: File, onProgress: ((Long, Long) -> Unit)?) {
        // CDN audio URL — must NOT send Yandex cookies (different domain, causes 401)
        client.newCall(buildRequest(url, "music.yandex.ru", sendCookies = false)).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for audio")
            resp.body?.use { body ->
                val total = body.contentLength()
                var done  = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(65_536); var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n); done += n
                            onProgress?.invoke(done, total)
                        }
                    }
                }
            }
        }
    }

    private fun fetchCover(uri: String): ByteArray? = try {
        val url = "https://" + uri.replace("%%", "${COVER_SIZE}x${COVER_SIZE}")
        client.newCall(buildRequest(url, "music.yandex.ru", sendCookies = false)).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (_: Exception) { null }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sanitize(s: String): String =
        s.replace(Regex("""[<>:"/\\|?*\u0000]"""), "_")
            .trim('.', ' ').take(180).ifEmpty { "Unknown" }
}
