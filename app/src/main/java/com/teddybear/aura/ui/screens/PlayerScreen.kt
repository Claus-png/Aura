package com.teddybear.aura.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.data.Track
import com.teddybear.aura.ui.theme.*
import kotlin.math.abs

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onClose: () -> Unit) {
    val track   by vm.currentTrack.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val pos     by vm.position.collectAsStateWithLifecycle()
    val dur     by vm.duration.collectAsStateWithLifecycle()
    val repeat  by vm.repeatMode.collectAsStateWithLifecycle()
    val shuffle by vm.shuffleOn.collectAsStateWithLifecycle()

    var showEq      by remember { mutableStateOf(false) }
    var showLyrics  by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = showEq,
        transitionSpec = {
            (slideInVertically { it } + fadeIn(tween(300))) togetherWith
            (slideOutVertically { -it } + fadeOut(tween(200)))
        },
        label = "player_eq_switch",
    ) { eq ->
        if (eq) {
            EqualizerScreen(vm = vm, onClose = { showEq = false })
        } else {
            track?.let { t ->
                PlayerContent(
                    track      = t,
                    playing    = playing,
                    posMs      = pos,
                    durMs      = dur,
                    repeatMode = repeat,
                    shuffleOn  = shuffle,
                    showLyrics = showLyrics,
                    onClose    = onClose,
                    onPrev     = { vm.skipPrev() },
                    onNext     = { vm.skipNext() },
                    onPlayPause = { vm.togglePlayPause() },
                    onSeek     = { vm.seekTo(it) },
                    onRepeat   = { vm.toggleRepeat() },
                    onShuffle  = { vm.toggleShuffle() },
                    onEq         = { showEq = true },
                    onLyrics     = { showLyrics = !showLyrics },
                    onSleepTimer = { vm.setSleepTimer(it) },
                    onSwipeLeft  = { vm.skipNext() },
                    onSwipeRight = { vm.skipPrev() },
                    onSetRating = { rating -> vm.setTrackRating(t.id, rating) },
                )
            } ?: Box(Modifier.fillMaxSize().background(GrooveBg))
        }
    }
}

@Composable
private fun PlayerContent(
    track: Track,
    playing: Boolean,
    posMs: Long,
    durMs: Long,
    repeatMode: Int,
    shuffleOn: Boolean,
    showLyrics: Boolean,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onEq: () -> Unit,
    onLyrics: () -> Unit,
    onSleepTimer: (Int) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onSetRating: (Float) -> Unit,
) {
    var showTimer   by remember { mutableStateOf(false) }
    var timerMin    by remember { mutableStateOf(30) }
    var timerActive by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // Vinyl rotation – smooth pause: freeze angle instead of snapping to 0°
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl")
    val vinylRotation by infiniteTransition.animateFloat(
        initialValue   = 0f,
        targetValue    = 360f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vinylRotate",
    )
    var frozenAngle by remember { mutableStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) frozenAngle = vinylRotation
    }
    val displayRotation by animateFloatAsState(
        targetValue   = if (playing) vinylRotation else frozenAngle,
        animationSpec = if (playing) snap() else tween(500),
        label         = "vinylSmooth",
    )

    // Swipe detection
    var dragX by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GrooveBg)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragX) > 80) {
                            if (dragX < 0) onSwipeLeft() else onSwipeRight()
                        }
                        dragX = 0f
                    },
                ) { _, delta -> dragX += delta }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = GrooveText2, modifier = Modifier.size(28.dp))
            }
            Text(
                "Сейчас играет",
                color = GrooveText2,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Box {
                IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                    Icon(Icons.Default.MoreVert, null, tint = GrooveText2)
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    modifier = Modifier.background(GrooveBg2),
                ) {
                    DropdownMenuItem(
                        text = { Text("Повторить один раз", color = GrooveText) },
                        onClick = {
                            onRepeat()
                            showMoreMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Переключить перемешивание", color = GrooveText) },
                        onClick = {
                            onShuffle()
                            showMoreMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Очередь", color = GrooveText) },
                        onClick = {
                            showMoreMenu = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Cover art with vinyl rotation ─────────────────────────────────────
        AnimatedContent(
            targetState = track.id,
            transitionSpec = {
                (scaleIn(tween(300)) + fadeIn(tween(300))) togetherWith
                (scaleOut(tween(200)) + fadeOut(tween(200)))
            },
            label = "cover_anim",
        ) { _ ->
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .rotate(displayRotation)
                    .clip(CircleShape)
                    .shadow(24.dp, CircleShape, ambientColor = GroovePurple.copy(0.4f)),
            ) {
                if (track.coverPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file://${track.coverPath}")
                            .crossfade(true)
                            .build(),
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(track.sourceColor),
                                        Color(track.sourceColor).copy(0.3f),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            null,
                            tint = Color.White.copy(0.7f),
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
                // Vinyl center hole
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                        .background(GrooveBg, CircleShape)
                        .border(3.dp, GrooveBg2, CircleShape)
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // ── Track info ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                AnimatedContent(track.title, label = "title_anim") { title ->
                    Text(
                        title,
                        color = GrooveText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${track.artist}${if (track.album.isNotEmpty()) " · ${track.album}" else ""}",
                    color = GrooveText2,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                RatingStars(
                    rating = track.rating,
                    onSetRating = onSetRating,
                )
            }
            Icon(
                if (track.rating >= 4f) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                null,
                tint = if (track.rating >= 4f) GroovePurple else GrooveText3,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Seek bar ──────────────────────────────────────────────────────────
        SeekBar(
            posMs    = posMs,
            durMs    = durMs,
            onSeek   = onSeek,
        )

        Spacer(Modifier.height(20.dp))

        // ── Main controls ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Shuffle
            IconButton(onClick = onShuffle) {
                Icon(
                    Icons.Rounded.Shuffle,
                    null,
                    tint = if (shuffleOn) GroovePurple else GrooveText3,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Prev
            IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Rounded.SkipPrevious, null, tint = GrooveText, modifier = Modifier.size(36.dp))
            }

            // Play / Pause — glowing button
            val playScale by animateFloatAsState(
                if (playing) 1f else 0.93f,
                spring(stiffness = Spring.StiffnessMedium),
                label = "playScale",
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .scale(playScale)
                    .drawBehind {
                        drawCircle(
                            color  = GroovePurple.copy(0.35f),
                            radius = size.minDimension * 0.7f,
                        )
                    }
                    .background(GroovePurple, CircleShape)
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }

            // Next
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Rounded.SkipNext, null, tint = GrooveText, modifier = Modifier.size(36.dp))
            }

            // Repeat
            IconButton(onClick = onRepeat) {
                val (icon, tint) = when (repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE ->
                        Pair(Icons.Rounded.RepeatOne, GroovePurple)
                    androidx.media3.common.Player.REPEAT_MODE_ALL ->
                        Pair(Icons.Rounded.Repeat, GroovePurple)
                    else ->
                        Pair(Icons.Rounded.Repeat, GrooveText3)
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Bottom action row: Lyrics, EQ, Timer, Auto ────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BottomAction("Текст",   Icons.Rounded.Lyrics,  showLyrics)  { onLyrics() }
            BottomAction("Таймер", Icons.Rounded.Timer,   timerActive) { showTimer = !showTimer }
        }

        Spacer(Modifier.height(16.dp))

        // ── Lyrics panel (animated) ───────────────────────────────────────────
        AnimatedVisibility(
            visible = showLyrics,
            enter   = slideInVertically { it } + fadeIn(),
            exit    = slideOutVertically { it } + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GrooveBg2)
                    .border(1.dp, GrooveBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                if (track.lyricsPlain != null) {
                    Text(
                        track.lyricsPlain!!,
                        color      = GrooveText2,
                        fontSize   = 13.sp,
                        lineHeight = 20.sp,
                        modifier   = Modifier.verticalScroll(rememberScrollState()),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Текст для этого трека недоступен", color = GrooveText3, fontSize = 13.sp,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
        // ── Sleep timer sheet ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showTimer,
            enter   = slideInVertically { it } + fadeIn(),
            exit    = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GrooveBg2)
                    .border(1.dp, GrooveBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Text("Таймер отключения", color = GrooveText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                // Two rows of 3 — fits all 6 options without overflow
                listOf(listOf(10, 15, 20), listOf(30, 45, 60)).forEach { row ->
                    Row(
                        modifier              = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { min ->
                            val sel = timerMin == min
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) GroovePurple else GrooveBg3)
                                    .clickable { timerMin = min }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$min мин", color = if (sel) Color.White else GrooveText2, fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            timerActive = true
                            showTimer   = false
                            onSleepTimer(timerMin)
                        },
                        colors  = ButtonDefaults.buttonColors(containerColor = GroovePurple),
                        shape   = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(36.dp),
                    ) {
                        Text("Запустить", color = Color.White, fontSize = 12.sp)
                    }
                    if (timerActive) {
                        OutlinedButton(
                            onClick  = { timerActive = false; showTimer = false; onSleepTimer(0) },
                            shape    = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = GrooveRed),
                        ) {
                            Text("Отмена", color = GrooveRed, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingStars(
    rating: Float,
    onSetRating: (Float) -> Unit,
) {
    var lastClickedStar by remember { mutableStateOf(0) }
    
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { value ->
            val filled = rating >= value
            val scale by animateFloatAsState(
                targetValue = if (lastClickedStar == value) 1.2f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "starScale",
            )
            
            Icon(
                if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = if (filled) Color(0xFFFFC857) else GrooveText3,
                modifier = Modifier
                    .size(22.dp)
                    .scale(scale)
                    .clickable(
                        indication = ripple(bounded = true),
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        onSetRating(value.toFloat())
                        lastClickedStar = value
                    },
            )
        }
    }
}

@Composable
private fun SeekBar(posMs: Long, durMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }

    val progress = if (dragging) dragFrac
                   else if (durMs > 0) (posMs.toFloat() / durMs) else 0f

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Slider(
            value         = progress,
            onValueChange = { f -> dragging = true; dragFrac = f },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragFrac * durMs).toLong())
            },
            colors = SliderDefaults.colors(
                activeTrackColor   = GroovePurple,
                thumbColor         = Color.White,
                inactiveTrackColor = GrooveBg3,
            ),
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(posMs), color = GrooveText3, fontSize = 11.sp)
            Text(formatMs(durMs), color = GrooveText3, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BottomAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable { onClick() }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            null,
            tint     = if (active) GroovePurple else GrooveText3,
            modifier = Modifier.size(22.dp),
        )
        Text(label, color = if (active) GroovePurple else GrooveText3, fontSize = 9.sp)
    }
}

@Composable
private fun BluetoothDeviceDialog(
    deviceName: String,
    onType: (com.teddybear.aura.player.BluetoothProfileManager.DeviceType) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GrooveBg2)
                .border(1.dp, GrooveBorder, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text("Новое устройство", color = GrooveText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(deviceName, color = GroovePurple, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Что подключилось?", color = GrooveText2, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            val types = listOf(
                Icons.Rounded.Headphones to "Наушники" to com.teddybear.aura.player.BluetoothProfileManager.DeviceType.HEADPHONES,
                Icons.Rounded.Speaker    to "Колонки"  to com.teddybear.aura.player.BluetoothProfileManager.DeviceType.SPEAKER,
                Icons.Rounded.DirectionsCar to "Авто"  to com.teddybear.aura.player.BluetoothProfileManager.DeviceType.CAR,
                Icons.Rounded.Devices    to "Другое"   to com.teddybear.aura.player.BluetoothProfileManager.DeviceType.OTHER,
            )
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                types.forEach { (iconLabel, type) ->
                    val (icon, label) = iconLabel
                    Column(
                        modifier              = Modifier.weight(1f).clickable { onType(type) }.padding(8.dp),
                        horizontalAlignment   = Alignment.CenterHorizontally,
                        verticalArrangement   = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(icon, null, tint = GroovePurple, modifier = Modifier.size(28.dp))
                        Text(label, color = GrooveText2, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
