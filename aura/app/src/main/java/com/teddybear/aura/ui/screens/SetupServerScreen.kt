package com.teddybear.aura.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.ui.theme.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/**
 * Server setup screen.
 * Allows scanning a QR code from the home server to configure:
 *  - server_url (Cloudflare Tunnel public URL)
 *  - encryption_key (666-bit Base64 master key)
 */
@Composable
fun SetupServerScreen(vm: MainViewModel, onDone: () -> Unit) {
    val ctx           = LocalContext.current
    var manualUrl     by remember { mutableStateOf("") }
    var statusMsg     by remember { mutableStateOf<String?>(null) }
    var statusOk      by remember { mutableStateOf(false) }
    var checking      by remember { mutableStateOf(false) }

    // QR scanner launcher (ZXing)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            try {
                val json       = JSONObject(raw)
                val serverUrl  = json.getString("server_url")
                val encKey     = json.optString("encryption_key")
                val apiKey     = json.optString("api_key")

                if (encKey.isBlank() && apiKey.isBlank()) {
                    statusMsg = "Ошибка QR: ключи не найдены"
                    statusOk  = false
                    return@let
                }

                vm.setupServer(serverUrl, encKey, apiKey)
                statusMsg = "✓ Сервер подключён: $serverUrl"
                statusOk  = true
                onDone()
            } catch (e: Exception) {
                statusMsg = "Ошибка QR: ${e.message}"
                statusOk  = false
            }
        }
    }

    // Camera permission launcher
    val cameraPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val opts = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Наведите камеру на QR-код сервера AURA")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            scanLauncher.launch(opts)
        } else {
            statusMsg = "Нет разрешения на камеру"
            statusOk  = false
        }
    }

    fun startScan() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            val opts = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Наведите камеру на QR-код сервера AURA")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            scanLauncher.launch(opts)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GrooveBg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        // Header
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(GroovePurple.copy(0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Router, null, tint = GroovePurple, modifier = Modifier.size(44.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("Подключение к серверу", color = GrooveText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Запустите сервер AURA на домашнем ПК и отсканируйте QR-код для настройки защищённого соединения.",
            color = GrooveText2, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
        )

        Spacer(Modifier.height(36.dp))

        // QR Scan button
        Button(
            onClick  = { startScan() },
            colors   = ButtonDefaults.buttonColors(containerColor = GroovePurple),
            shape    = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Сканировать QR-код сервера", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = GrooveBorder)
            Text("или вручную", color = GrooveText3, fontSize = 11.sp)
            HorizontalDivider(modifier = Modifier.weight(1f), color = GrooveBorder)
        }

        Spacer(Modifier.height(16.dp))

        // Manual URL input
        OutlinedTextField(
            value         = manualUrl,
            onValueChange = { manualUrl = it },
            label         = { Text("URL сервера", color = GrooveText3, fontSize = 12.sp) },
            placeholder   = { Text("https://random.trycloudflare.com", color = GrooveText3, fontSize = 12.sp) },
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = GroovePurple,
                unfocusedBorderColor = GrooveBorder,
                focusedTextColor     = GrooveText,
                unfocusedTextColor   = GrooveText,
            ),
            singleLine = true,
            shape      = RoundedCornerShape(10.dp),
            modifier   = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Link, null, tint = GrooveText3, modifier = Modifier.size(18.dp)) },
        )

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick  = {
                if (manualUrl.isNotBlank()) {
                    vm.setupServer(manualUrl.trim(), "")
                    statusMsg = "Сервер сохранён (без шифрования)"
                    statusOk  = true
                }
            },
            shape    = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = GroovePurple),
            border   = BorderStroke(1.dp, GroovePurple.copy(0.6f)),
            enabled  = manualUrl.isNotBlank(),
        ) {
            Text("Подключиться вручную", color = GroovePurple, fontSize = 14.sp)
        }

        // Status message
        statusMsg?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (statusOk) GrooveGreen.copy(0.12f) else GrooveRed.copy(0.12f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (statusOk) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                    null,
                    tint = if (statusOk) GrooveGreen else GrooveRed,
                    modifier = Modifier.size(18.dp),
                )
                Text(msg, color = if (statusOk) GrooveGreen else GrooveRed, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Info card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GrooveBg2)
                .border(1.dp, GrooveBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Как запустить сервер", color = GrooveText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                listOf(
                    "1. Установите Python и зависимости: pip install aura-server",
                    "2. Запустите: python -m aura_server",
                    "3. Отсканируйте QR-код, появившийся в консоли",
                    "4. Сервер скачивает треки и отправляет их в приложение",
                ).forEach { step ->
                    Text(step, color = GrooveText2, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
