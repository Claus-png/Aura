package com.teddybear.aura.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.ui.theme.*
import kotlinx.coroutines.launch

// ── Bitrate data ──────────────────────────────────────────────────────────────

/**
 * User-visible label → internal value passed to yt-dlp / spotdl.
 * "auto"/"0" = best available. Numeric = closest available ≤ N kbps.
 */
private val BITRATE_OPTIONS = listOf(
    "Авто (макс.)" to "auto",
    "320 кбит/с"   to "320",
    "256 кбит/с"   to "256",
    "192 кбит/с"   to "192",
    "128 кбит/с"   to "128",
    "96 кбит/с"    to "96",
    "64 кбит/с"    to "64",
    "48 кбит/с"    to "48",
    "32 кбит/с"    to "32",
)

private fun bitrateLabel(value: String): String =
    BITRATE_OPTIONS.firstOrNull { it.second == value }?.first ?: "Авто (макс.)"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onSetupServer: () -> Unit = {},
    onYandexLogin: () -> Unit = {},
    onConfigureEq: () -> Unit = {},
) {
    val bitrate       by vm.audioBitrate.collectAsStateWithLifecycle()
    val normalize     by vm.normalizeVolume.collectAsStateWithLifecycle()
    val serverOnline  by vm.serverOnline.collectAsStateWithLifecycle()
    val yandexLogin   by vm.yandexLogin.collectAsStateWithLifecycle()
    val scope         = rememberCoroutineScope()

    var bitrateExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GrooveBg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 130.dp),
    ) {
        Text(
            "Настройки",
            color = GrooveText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp),
        )

        // ── СЕРВЕР ────────────────────────────────────────────────────────────
        SettingsSectionHeader("ДОМАШНИЙ СЕРВЕР")

        // Server status card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background((if (serverOnline) GrooveGreen else GrooveText3).copy(alpha = 0.12f))
                .border(1.dp, (if (serverOnline) GrooveGreen else GrooveText3).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(10.dp)
                        .background(if (serverOnline) GrooveGreen else GrooveText3, androidx.compose.foundation.shape.CircleShape)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (serverOnline) "Сервер подключён" else
                        if (vm.serverRepo.serverUrl.isNotBlank()) "Сервер недоступен" else "Сервер не настроен",
                        color = if (serverOnline) GrooveGreen else GrooveText2,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (vm.serverRepo.serverUrl.isNotBlank()) {
                        Text(vm.serverRepo.serverUrl, color = GrooveText3, fontSize = 11.sp, maxLines = 1)
                    }
                }
                TextButton(onClick = onSetupServer) {
                    Text(if (vm.serverRepo.isConfigured) "Изменить" else "Настроить", color = GroovePurple, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Yandex auth row
        SettingsRow(
            icon  = Icons.Rounded.AccountCircle,
            label = if (yandexLogin != null) "Яндекс: $yandexLogin" else "Войти в Яндекс Музыку",
            value = { },
            onClick = if (yandexLogin == null) ({ onYandexLogin() }) else null,
            trailing = {
                if (yandexLogin != null) {
                    TextButton(onClick = { vm.logoutYandex() }) {
                        Text("Выйти", color = GrooveRed, fontSize = 12.sp)
                    }
                }
            },
        )

        Spacer(Modifier.height(8.dp))

        // ── ЭКВАЛАЙЗЕР ───────────────────────────────────────────────────────────
        val eqState by vm.eqState.collectAsStateWithLifecycle()
        SettingsSectionHeader("ЭКВАЛАЙЗЕР")

        SettingsRow(
            icon  = Icons.Rounded.Equalizer,
            label = "Эквалайзер",
            value = { },
            trailing = {
                Switch(
                    checked         = eqState.enabled,
                    onCheckedChange = { vm.setEqEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = GroovePurple, uncheckedTrackColor = GrooveBg3),
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onConfigureEq) {
                    Text("Настроить", color = GroovePurple, fontSize = 12.sp)
                }
            },
        )

        // ── СТАТИСТИКА ────────────────────────────────────────────────────────
        val trackStatsOn by vm.trackStats.collectAsStateWithLifecycle()
        var showResetConfirm1 by remember { mutableStateOf(false) }
        var showResetConfirm2 by remember { mutableStateOf(false) }

        SettingsSectionHeader("СТАТИСТИКА")

        SettingsRow(
            icon  = Icons.Rounded.BarChart,
            label = "Отслеживать прослушивания",
            value = { },
            trailing = {
                Switch(
                    checked         = trackStatsOn,
                    onCheckedChange = { vm.setTrackStats(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = GroovePurple, uncheckedTrackColor = GrooveBg3),
                )
            },
        )

        if (!trackStatsOn) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp)).background(GrooveBg2).padding(10.dp)) {
                Text(
                    "Если сбор статистики отключён, динамические плейлисты перестанут обновляться, а алгоритм умного перемешивания будет работать некорректно.",
                    color = GrooveText3, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = { showResetConfirm1 = true },
                shape   = RoundedCornerShape(10.dp),
                colors  = ButtonDefaults.outlinedButtonColors(contentColor = GrooveRed),
                modifier = Modifier.fillMaxWidth().height(44.dp),
                border  = androidx.compose.foundation.BorderStroke(1.dp, GrooveRed.copy(0.5f)),
            ) {
                Text("Сбросить статистику прослушиваний", color = GrooveRed, fontSize = 13.sp)
            }
        }

        // Double-confirm dialogs for reset
        if (showResetConfirm1) {
            AlertDialog(
                onDismissRequest = { showResetConfirm1 = false },
                containerColor   = GrooveBg2,
                title = { Text("Вы уверены?", color = GrooveText, fontWeight = FontWeight.Bold) },
                text  = { Text("Это сбросит счётчики прослушиваний для всех треков.", color = GrooveText2) },
                confirmButton = {
                    TextButton(onClick = { showResetConfirm1 = false; showResetConfirm2 = true }) {
                        Text("Продолжить", color = GrooveRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm1 = false }) { Text("Отмена", color = GrooveText3) }
                },
            )
        }
        if (showResetConfirm2) {
            AlertDialog(
                onDismissRequest = { showResetConfirm2 = false },
                containerColor   = GrooveBg2,
                title = { Text("Это действие необратимо", color = GrooveText, fontWeight = FontWeight.Bold) },
                text  = { Text("Все счётчики прослушиваний будут обнулены. Продолжить?", color = GrooveText2) },
                confirmButton = {
                    TextButton(onClick = { vm.resetStats(); showResetConfirm2 = false }) {
                        Text("Сбросить", color = GrooveRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm2 = false }) { Text("Отмена", color = GrooveText3) }
                },
            )
        }

        // ── BLUETOOTH-ПРОФИЛИ ─────────────────────────────────────────────────
        SettingsSectionHeader("BLUETOOTH-ПРОФИЛИ")

        var btCollect by remember { mutableStateOf(vm.btCollectionEnabled) }
        SettingsRow(
            icon  = Icons.Rounded.Bluetooth,
            label = "Запоминать устройства",
            value = { Text("Применяет настройки EQ при подключении", color = GrooveText3, fontSize = 11.sp) },
            trailing = {
                Switch(
                    checked         = btCollect,
                    onCheckedChange = { btCollect = it; vm.setBtCollection(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = GroovePurple, uncheckedTrackColor = GrooveBg3),
                )
            },
        )
        if (!btCollect) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp)).background(GrooveBg2).padding(10.dp)) {
                Text("Профили устройств не сохраняются. EQ не применяется автоматически.",
                    color = GrooveText3, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }

        // ── О ПРИЛОЖЕНИИ ──────────────────────────────────────────────────────
        SettingsSectionHeader("О ПРИЛОЖЕНИИ")
        SettingsRow(icon = Icons.Rounded.Info, label = "Версия", value = { Text("beta - 0.7.0.a", color = GrooveText2, fontSize = 13.sp) })
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text,
        color = GrooveText3, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    value: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GrooveBg2)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = GrooveText3, modifier = Modifier.size(18.dp))
        Text(label, color = GrooveText2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        value()
        if (trailing != null) trailing()
    }
    HorizontalDivider(color = GrooveBorder, modifier = Modifier.padding(start = 46.dp))
}

