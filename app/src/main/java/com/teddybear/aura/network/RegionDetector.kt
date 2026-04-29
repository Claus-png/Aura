package com.teddybear.aura.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.teddybear.aura.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "GrooveRegion"
// Re-check region no more often than once per 24 hours
private const val REGION_TTL_MS = 24 * 60 * 60 * 1000L

// ═══════════════════════════════════════════════════════════════════════════════
// VPN detection
// ═══════════════════════════════════════════════════════════════════════════════

object VpnDetector {
    /**
     * Returns true if an active VPN transport is detected via ConnectivityManager.
     * Does NOT use GPS, does NOT require location permission.
     * Called only at startup and on network changes — not polled.
     */
    fun isVpnActive(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            Log.w(TAG, "VPN check failed: ${e.message}")
            false
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Region detection (IP-based, no GPS, no location permission)
// ═══════════════════════════════════════════════════════════════════════════════

object RegionDetector {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Returns true if we believe the user is in Russia.
     *
     * Detection order (first positive match wins):
     *   1. System locale — "ru" language + RU region → fast, no network
     *   2. Cached region code (refreshed every 24h)
     *   3. IP geo-lookup via ip-api.com (free, no coordinates sent)
     *
     * IMPORTANT: No GPS, no ACCESS_FINE_LOCATION, no ACCESS_COARSE_LOCATION.
     */
    suspend fun isRussiaUser(context: Context, prefs: AppPreferences): Boolean =
        withContext(Dispatchers.IO) {
            // ── 1. Quick locale check ──────────────────────────────────────
            if (isLocaleRussia()) {
                Log.d(TAG, "Region detected as RU via locale")
                return@withContext true
            }

            // ── 2. Cached result ───────────────────────────────────────────
            val cachedCode = prefs.regionCode.first()
            val checkedAt  = prefs.regionCheckedAt.first()
            val age        = System.currentTimeMillis() - checkedAt
            if (cachedCode.isNotEmpty() && age < REGION_TTL_MS) {
                Log.d(TAG, "Region from cache: $cachedCode (age ${age / 60_000}min)")
                return@withContext cachedCode == "RU"
            }

            // ── 3. IP geo-lookup ───────────────────────────────────────────
            val code = fetchCountryCode()
            if (code != null) {
                prefs.cacheRegion(code)
                Log.d(TAG, "Region from IP: $code")
                return@withContext code == "RU"
            }

            // If the lookup failed, fall back to cached value even if stale
            Log.w(TAG, "IP lookup failed, using stale cache: $cachedCode")
            cachedCode == "RU"
        }

    /** Checks system locale/region settings — no network required. */
    private fun isLocaleRussia(): Boolean {
        val locale = Locale.getDefault()
        return locale.language == "ru" && locale.country == "RU"
    }

    /**
     * Fetches country code via ip-api.com.
     * Only the country code is requested — no city, no coordinates.
     * Returns null on network error.
     */
    private fun fetchCountryCode(): String? {
        return try {
            val req = Request.Builder()
                .url("http://ip-api.com/json?fields=countryCode")
                .header("User-Agent", "AURA/2.0 Android")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            JSONObject(body).optString("countryCode")
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "IP lookup error: ${e.message}")
            null
        }
    }
}
