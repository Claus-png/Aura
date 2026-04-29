package com.teddybear.aura.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.download.BinaryManager
import com.teddybear.aura.data.DownloadJob
import com.teddybear.aura.ui.theme.*

@Composable
fun DownloadScreen(vm: MainViewModel) {
    val jobs          by vm.jobs.collectAsStateWithLifecycle()
    val proxyRequired by vm.proxyRequired.collectAsStateWithLifecycle()
    val vpnActive     by vm.vpnActive.collectAsStateWithLifecycle()
    val focus          = LocalFocusManager.current
    var url            by remember { mutableStateOf("") }
    val detected       = detectSource(url)

    val active = jobs.filter { it.status in listOf("queued", "downloading", "tagging") }
    val done   = jobs.filter { it.status in listOf("done", "error", "cancelled") }

    // FIX #7: Pulse animation when URL is filled
    val pulseAnim = remember { Animatable(1f) }
    LaunchedEffect(url.isNotBlank()) {
        if (url.isNotBlank()) {
            pulseAnim.animateTo(
                targetValue   = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                )
            )
        } else {
            pulseAnim.snapTo(1f)
        }
    }

    LazyColumn(
        modifier       = Modifier.fillMaxSize().background(GrooveBg),
        contentPadding = PaddingValues(bottom = 130.dp),
    ) {
        // ── Title ─────────────────────────────────────────────────────────────
        item {
            Text(
                "Добавить музыку",
                color = GrooveText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 14.dp),
            )
        }

        // ── URL input ─────────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GrooveBg2)
                    .border(1.dp, GrooveBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Bolt, null, tint = GroovePurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                TextField(
                    value         = url,
                    onValueChange = { url = it },
                    placeholder   = { Text("Вставить ссылку: Spotify, Яндекс, SC, YTM...", color = GrooveText3, fontSize = 13.sp) },
                    colors        = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = GrooveText,
                        unfocusedTextColor      = GrooveText,
                    ),
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        focus.clearFocus()
                        if (url.isNotBlank()) { vm.enqueueDownload(url); url = "" }
                    }),
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(url.isNotEmpty()) {
                    IconButton(onClick = { url = "" }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Clear, null, tint = GrooveText3, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // ── yt-dlp availability notice ───────────────────────────────────────────
        item {
            val ctx = LocalContext.current
            val ytdlpReady: Boolean = remember { BinaryManager.isYtDlpAvailable(ctx) }
            if (!ytdlpReady) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1A1A2E))
                        .border(1.dp, GrooveText3.copy(0.3f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⬇", fontSize = 14.sp)
                    Text(
                        "При первой загрузке yt-dlp скачается автоматически (~12 МБ).",
                        color    = GrooveText3,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ── FIX #7: Source chips as 2×2 grid ─────────────────────────────────
        item {
            val sources = listOf(
                "yandex"     to "Яндекс Музыка",
                "spotify"    to "Spotify",
                "soundcloud" to "SoundCloud",
                "ytmusic"    to "YT Music",
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                sources.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { (key, label) ->
                            val color   = sourceColor(key)
                            val isMatch = detected == key
                            val locked  = proxyRequired && !vpnActive && key != "yandex"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isMatch) color.copy(0.18f) else GrooveBg2)
                                    .border(
                                        1.dp,
                                        if (isMatch) color else if (locked) GrooveRed.copy(0.4f) else GrooveBorder,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (locked) {
                                        Icon(Icons.Rounded.Lock, null, tint = GrooveRed, modifier = Modifier.size(11.dp))
                                    }
                                    Text(
                                        label,
                                        color = if (isMatch) color else if (locked) GrooveRed.copy(0.7f) else GrooveText2,
                                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        // Pad to 2 columns if odd row
                        if (row.size < 2) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ── Download button — pulse when URL filled (FIX #7) ─────────────────
        item {
            Button(
                onClick  = {
                    focus.clearFocus()
                    if (url.isNotBlank()) { if (vm.enqueueDownload(url)) url = "" }
                },
                enabled  = url.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = GroovePurple,
                    disabledContainerColor = GroovePurple.copy(0.4f),
                ),
                shape    = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .scale(pulseAnim.value),  // pulse animation
            ) {
                Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Скачать", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // ── Active jobs ───────────────────────────────────────────────────────
        if (active.isNotEmpty()) {
            item { JobSectionLabel("ЗАГРУЖАЕТСЯ") }
            itemsIndexed(active, key = { _, j -> j.id }) { _, job ->
                JobRow(job, onCancel = { vm.cancelDownload(job.id) })
            }
        }

        // ── Completed jobs ────────────────────────────────────────────────────
        if (done.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ЗАВЕРШЕНО", color = GrooveText3, fontSize = 10.sp,
                        letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Очистить историю",
                        color = GroovePurple,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { vm.clearDownloadHistory() }
                    )
                }
            }
            itemsIndexed(done, key = { _, j -> j.id }) { _, job -> JobRow(job) }
        }

        if (active.isEmpty() && done.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⬇", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Вставь ссылку выше и скачай трек", color = GrooveText3, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun JobSectionLabel(text: String) {
    Text(
        text,
        color = GrooveText3, fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun JobRow(job: DownloadJob, onCancel: (() -> Unit)? = null) {
    val srcColor = sourceColor(job.source)
    val isDone   = job.status == "done"
    val isErr    = job.status == "error" || job.status == "cancelled"
    val isActive = job.status in listOf("downloading", "tagging")

    val animProgress by animateFloatAsState(
        targetValue   = job.progress / 100f,
        animationSpec = tween(400),
        label         = "jobProgress",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(srcColor.copy(0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (job.source) { "yandex" -> "Я"; "spotify" -> "S"; "soundcloud" -> "SC"; "ytmusic" -> "YT"; else -> "?" },
                color = srcColor, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                job.title.ifEmpty { job.url.take(40) + "…" },
                color = GrooveText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                append(sourceLabel(job.source))
                if (job.artist.isNotEmpty()) append(" · ${job.artist}")
            }
            Text(sub, color = GrooveText2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (isActive) {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress     = { animProgress },
                    color        = srcColor,
                    trackColor   = GrooveBg3,
                    modifier     = Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
            if (isErr && !job.errorMsg.isNullOrEmpty()) {
                val isBinaryDl = job.errorMsg.contains("yt-dlp") && (job.errorMsg.contains("Загруж") || job.errorMsg.contains("Подождите"))
                Text(
                    job.errorMsg,
                    color    = if (isBinaryDl) GrooveText3 else GrooveRed,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            isActive -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${job.progress.toInt()}%", color = srcColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (onCancel != null) {
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(18.dp),
                    ) { Text("Отмена", color = GrooveText3, fontSize = 9.sp) }
                }
            }
            isDone -> StatusBadge("✓ Готово", GrooveGreen)
            isErr  -> StatusBadge("Ошибка", GrooveRed)
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(0.15f))
            .border(1.dp, color.copy(0.3f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun detectSource(url: String): String? = when {
    "music.yandex"    in url -> "yandex"
    "spotify.com"     in url -> "spotify"
    "soundcloud.com"  in url -> "soundcloud"
    "youtube.com"     in url || "youtu.be" in url -> "ytmusic"
    "music.youtube"   in url -> "ytmusic"
    else -> null
}
