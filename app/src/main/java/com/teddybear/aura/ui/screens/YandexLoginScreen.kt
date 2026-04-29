package com.teddybear.aura.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.ui.theme.*

private const val YANDEX_AUTH_URL =
    "https://passport.yandex.ru/auth?origin=music&retpath=https://music.yandex.ru/"

private val REQUIRED_COOKIES = listOf(
    "Session_id", "sessionid2", "yandexuid", "yandex_login"
)

/**
 * WebView screen for Yandex Music OAuth.
 * After successful login, intercepts cookies and saves them to EncryptedSharedPreferences.
 * Then syncs cookies to the home server via /sync-yandex.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YandexLoginScreen(vm: MainViewModel, onBack: () -> Unit) {
    var syncing by remember { mutableStateOf(false) }
    var synced  by remember { mutableStateOf(false) }
    var syncMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(GrooveBg)) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = GrooveText,
                )
            }
            Text(
                "Войти в Яндекс Музыку",
                color = GrooveText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (syncing) {
                CircularProgressIndicator(
                    color = GroovePurple,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else if (synced) {
                Icon(Icons.Rounded.CheckCircle, null, tint = GrooveGreen, modifier = Modifier.size(24.dp))
            }
        }

        syncMsg?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (synced) GrooveGreen.copy(0.12f) else GrooveRed.copy(0.12f))
                    .padding(12.dp),
            ) {
                Text(msg, color = if (synced) GrooveGreen else GrooveRed, fontSize = 12.sp)
            }
        }

        // WebView
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    settings.userAgentString    =
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)

                            // Extract cookies once we land on music.yandex.ru
                            if ("music.yandex.ru" in url || "passport.yandex.ru" in url) {
                                val cookieString = CookieManager.getInstance().getCookie(url) ?: return
                                val cookies = parseCookies(cookieString)
                                val needed  = REQUIRED_COOKIES.mapNotNull { k ->
                                    cookies[k]?.let { k to it }
                                }.toMap()

                                if (needed.containsKey("Session_id") && !syncing) {
                                    syncing  = true
                                    syncMsg  = "Синхронизация с сервером..."
                                    vm.syncYandexCookies(needed) { ok ->
                                        syncing = false
                                        synced  = ok
                                        syncMsg = if (ok) "✓ Авторизация успешна! Яндекс доступен."
                                                  else   "⚠ Куки сохранены, но синхронизация с сервером не удалась."
                                    }
                                }
                            }
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            // Stay inside Yandex domain
                            val host = req.url.host ?: return false
                            return !host.endsWith("yandex.ru") && !host.endsWith("yandex.com")
                        }
                    }
                    loadUrl(YANDEX_AUTH_URL)
                }
            },
        )
    }
}

private fun parseCookies(cookieHeader: String): Map<String, String> {
    return cookieHeader.split(";").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null
        else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
    }.toMap()
}
