package com.teddybear.aura.network

import android.content.Context
import android.util.Base64
import android.util.Log
import android.os.Build
import android.provider.Settings
import com.teddybear.aura.crypto.P2PECrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "AuraServer"

/**
 * All communication with the AURA home server.
 *
 * Every request is encrypted with P2PE before sending and decrypted on receipt.
 * Base transport: HTTPS (Cloudflare Tunnel).
 */
class ServerRepository(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("aura_server", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v).apply()

    // Only requires server URL — crypto may not be loaded yet on cold start
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
    val isFullyConfigured: Boolean get() = serverUrl.isNotBlank() && P2PECrypto.isReady

    // ── Device ping ───────────────────────────────────────────────────────────

    /**
     * Register this device on the server.
     * Sends: device_id (Android ID), device name, platform, app version.
     * Called once on setup and every 5 min as heartbeat.
     */
    fun pingServer(context: android.content.Context) {
        if (!isConfigured) return
        try {
            val androidId = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            val deviceName = try {
                Settings.Global.getString(context.contentResolver, "device_name")
                    ?.ifBlank { null } ?: Build.MODEL
            } catch (_: Exception) { Build.MODEL }

            val body = org.json.JSONObject().apply {
                put("device_id", androidId)
                put("name",      deviceName)
                put("android_id", androidId)
                put("platform",  "android")
                put("version",   "1.0.0")
            }.toString()

            val url = serverUrl.trimEnd('/') + "/devices/ping"
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("X-AURA-KEY", getApiKey())
                .header("Content-Type", "application/json")
                .post(body.toByteArray().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                Log.d(TAG, "Ping: HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed: ${e.message}")
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60,  TimeUnit.SECONDS)
        .build()

    // ── Server status ─────────────────────────────────────────────────────────

    suspend fun checkStatus(): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        try {
            val resp = encryptedGet("/status")
            val json = JSONObject(resp)
            json.optString("status") == "ok"
        } catch (e: Exception) {
            Log.w(TAG, "Status check failed: ${e.message}")
            false
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    data class DownloadTask(
        val taskId: String,
        val status: String,       // "pending" | "downloading" | "complete" | "error"
        val fileUrl: String? = null,
        val errorMsg: String? = null,
        val progress: Int = 0,
    )

    /** Request a track download on the server. Returns task_id. */
    suspend fun requestDownload(url: String, service: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("url", url)
            put("service", service)
        }.toString()
        val resp = encryptedPost("/download", P2PECrypto.wrapJson(body))
        JSONObject(resp).getString("task_id")
    }

    /** Poll task status until complete or error. Calls onProgress(0-100). */
    suspend fun awaitTask(
        taskId: String,
        onProgress: ((Int) -> Unit)? = null,
    ): DownloadTask = withContext(Dispatchers.IO) {
        var lastPct = 0
        repeat(360) {  // max 6 minutes
            try {
                val resp = encryptedGet("/task/$taskId")
                val json = JSONObject(resp)
                val status = json.getString("status")
                val pct    = json.optInt("progress", lastPct)
                if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct) }

                when (status) {
                    "complete" -> return@withContext DownloadTask(
                        taskId  = taskId,
                        status  = "complete",
                        fileUrl = json.getString("file_url"),
                    )
                    "error" -> return@withContext DownloadTask(
                        taskId   = taskId,
                        status   = "error",
                        errorMsg = json.optString("error", "Unknown server error"),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll task $taskId: ${e.message}")
            }
            delay(1_000)
        }
        DownloadTask(taskId = taskId, status = "error", errorMsg = "Timeout waiting for server")
    }

    // ── Yandex cookies sync ───────────────────────────────────────────────────

    /** Send Yandex session cookies to server so it can download full tracks. */
    suspend fun syncYandexCookies(cookies: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject(cookies as Map<*, *>).toString()
            val resp = encryptedPost("/sync-yandex", P2PECrypto.wrapJson(body))
            // Server returns {"result": "ok"}, not {"status": "ok"}
            val json = JSONObject(resp)
            json.optString("result") == "ok" || json.optString("status") == "ok"
        } catch (e: Exception) {
            Log.e(TAG, "Cookie sync failed: ${e.message}")
            false
        }
    }

    // ── P2PE transport ────────────────────────────────────────────────────────

    private fun encryptedGet(path: String): String {
        val url = serverUrl.trimEnd('/') + path
        val req = Request.Builder()
            .url(url)
            .header("X-AURA-KEY", getApiKey())
            .header("X-Encrypted", "false")  // GET with no body — just API key auth
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $path")
            return resp.body?.string() ?: "{}"
        }
    }

    private fun encryptedPost(path: String, plaintextTlv: ByteArray): String {
        val url       = serverUrl.trimEnd('/') + path
        val encrypted = P2PECrypto.encrypt(plaintextTlv)
        val b64Body   = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        val req = Request.Builder()
            .url(url)
            .header("X-AURA-KEY",    getApiKey())
            .header("X-Encrypted",   "true")
            .header("Content-Type",  "application/octet-stream")
            .post(b64Body.toByteArray().toRequestBody("application/octet-stream".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $path")
            val body = resp.body?.bytes() ?: return "{}"
            // Server returns Base64-encoded encrypted response
            val decrypted = try {
                val encResp = Base64.decode(body, Base64.DEFAULT)
                P2PECrypto.decrypt(encResp)
            } catch (e: Exception) {
                Log.w(TAG, "Response not encrypted, using raw: ${e.message}")
                body
            }
            val (_, payload) = P2PECrypto.unwrap(decrypted)
            return String(payload)
        }
    }

    private fun getApiKey(): String {
        // API key stored separately (not part of encryption key)
        val encPrefs = try {
            androidx.security.crypto.EncryptedSharedPreferences.create(
                ctx, "aura_secrets",
                androidx.security.crypto.MasterKey.Builder(ctx)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) { null }
        return encPrefs?.getString("api_key", "aura-default-key") ?: "aura-default-key"
    }
}
