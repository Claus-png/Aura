package com.teddybear.aura.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for sensitive data:
 *   - 666-bit P2PE master key
 *   - Yandex session cookies
 *   - API key
 */
object SecureStorage {

    private const val FILE = "aura_secrets"
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = try {
            val key = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if crypto not available (shouldn't happen on API 26+)
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    fun saveMasterKey(base64Key: String) = prefs?.edit()?.putString("master_key", base64Key)?.apply()
    fun getMasterKey(): String?          = prefs?.getString("master_key", null)

    fun saveApiKey(key: String)          = prefs?.edit()?.putString("api_key", key)?.apply()
    fun getApiKey(): String              = prefs?.getString("api_key", "aura-default-key") ?: "aura-default-key"

    fun saveYandexCookies(cookies: Map<String, String>) {
        val p = prefs ?: return
        with(p.edit()) {
            cookies.forEach { (k, v) -> putString("yandex_$k", v) }
            apply()
        }
    }

    fun getYandexCookies(): Map<String, String> {
        val p = prefs ?: return emptyMap()
        val keys = listOf("Session_id", "sessionid2", "yandexuid", "yandex_login")
        return keys.mapNotNull { k ->
            p.getString("yandex_$k", null)?.let { k to it }
        }.toMap()
    }

    fun getYandexLogin(): String? = prefs?.getString("yandex_yandex_login", null)
    fun clearYandexCookies()      = prefs?.edit()
        ?.remove("yandex_Session_id")?.remove("yandex_sessionid2")
        ?.remove("yandex_yandexuid")?.remove("yandex_yandex_login")
        ?.apply()

    val hasYandexAuth: Boolean get() =
        prefs?.getString("yandex_Session_id", null) != null
}
