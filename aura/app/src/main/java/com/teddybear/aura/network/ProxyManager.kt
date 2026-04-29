package com.teddybear.aura.network

import android.util.Log
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val TAG = "GrooveProxy"

data class ProxyConfig(
    val enabled:  Boolean = false,
    val type:     String  = "SOCKS5",   // "SOCKS5" | "HTTP" | "VLESS" | "MTPROTO"
    val host:     String  = "",
    val port:     Int     = 1080,
    val username: String  = "",
    val password: String  = "",
)

object ProxyManager {

    /**
     * Builds an OkHttpClient that routes through the given proxy.
     *
     * VLESS / MTProto are handled transparently:
     * when those protocols are active, the tunnel daemon (v2ray/xray) already
     * runs a local SOCKS5 proxy on 127.0.0.1:10808 — AppPreferences writes
     * proxyHost=127.0.0.1 / proxyPort=10808 / proxyType=SOCKS5 automatically
     * so OkHttp never needs to know the underlying protocol.
     */
    fun buildClient(config: ProxyConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (!config.enabled || config.host.isBlank()) {
            Log.d(TAG, "Proxy disabled — using direct connection")
            return builder.build()
        }

        // For VLESS/MTProto the stored host is 127.0.0.1 and type is effectively SOCKS5
        val resolvedType = when (config.type.uppercase()) {
            "VLESS", "MTPROTO" -> "SOCKS5"
            else                -> config.type.uppercase()
        }

        val proxyType = when (resolvedType) {
            "HTTP"   -> Proxy.Type.HTTP
            "SOCKS5" -> Proxy.Type.SOCKS
            else     -> Proxy.Type.SOCKS
        }

        val proxy = Proxy(proxyType, InetSocketAddress(config.host, config.port))
        builder.proxy(proxy)

        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            builder.proxyAuthenticator(Authenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) return@Authenticator null
                val credential = Credentials.basic(config.username, config.password)
                response.request.newBuilder().header("Proxy-Authorization", credential).build()
            })
        }

        Log.d(TAG, "Proxy enabled: ${config.type} ${config.host}:${config.port}")
        return builder.build()
    }

    /** Plain (non-proxied) client — e.g. Yandex Music, which works globally. */
    fun directClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

// ── VLESS URL parser ──────────────────────────────────────────────────────────

data class VlessConfig(
    val uuid:     String,
    val host:     String,
    val port:     Int,
    val security: String,
    val pbk:      String,
    val fp:       String,
    val sid:      String,
    val flow:     String,
)

data class MtprotoConfig(
    val server: String,
    val port:   Int,
    val secret: String,
)

object ProxyLinkParser {

    /**
     * Parses vless://UUID@host:port?type=tcp&security=reality&pbk=...&fp=...&sid=...&flow=...
     */
    fun parseVless(url: String): VlessConfig? {
        return try {
            if (!url.startsWith("vless://")) return null
            val withoutScheme = url.removePrefix("vless://")
            val atIdx = withoutScheme.lastIndexOf('@')
            if (atIdx < 0) return null

            val uuid       = withoutScheme.substring(0, atIdx)
            val remainder  = withoutScheme.substring(atIdx + 1)
            val qIdx       = remainder.indexOf('?')
            val hostPort   = if (qIdx >= 0) remainder.substring(0, qIdx) else remainder
            val queryStr   = if (qIdx >= 0) remainder.substring(qIdx + 1) else ""

            val colonIdx = hostPort.lastIndexOf(':')
            val host = hostPort.substring(0, colonIdx.coerceAtLeast(0))
            val port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443

            val params = queryStr.split('&').associate {
                val kv = it.split('=', limit = 2)
                kv[0] to (kv.getOrNull(1) ?: "")
            }

            VlessConfig(
                uuid     = uuid,
                host     = host,
                port     = port,
                security = params["security"] ?: "",
                pbk      = params["pbk"] ?: "",
                fp       = params["fp"] ?: "",
                sid      = params["sid"] ?: "",
                flow     = params["flow"] ?: "",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse VLESS url: ${e.message}")
            null
        }
    }

    /**
     * Parses https://t.me/proxy?server=...&port=...&secret=...
     */
    fun parseMtproto(url: String): MtprotoConfig? {
        return try {
            val qIdx = url.indexOf('?')
            if (qIdx < 0) return null
            val params = url.substring(qIdx + 1).split('&').associate {
                val kv = it.split('=', limit = 2)
                kv[0] to (kv.getOrNull(1) ?: "")
            }
            val server = params["server"] ?: return null
            val port   = params["port"]?.toIntOrNull() ?: 443
            val secret = params["secret"] ?: return null
            MtprotoConfig(server = server, port = port, secret = secret)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse MTProto url: ${e.message}")
            null
        }
    }

    fun detectType(link: String): String? = when {
        link.startsWith("vless://")         -> "VLESS"
        "t.me/proxy" in link ||
        link.startsWith("tg://proxy")       -> "MTPROTO"
        else                                -> null
    }
}
