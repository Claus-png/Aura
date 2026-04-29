package com.teddybear.aura.ui.components

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teddybear.aura.data.Track
import com.teddybear.aura.ui.theme.*
import kotlin.math.sin

@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    posMs: Long = 0L,
    durMs: Long = 0L,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    // Waveform animation — only when playing
    val wavePhase = if (isPlaying) {
        val t = rememberInfiniteTransition(label = "wave")
        t.animateFloat(
            0f, (2 * Math.PI).toFloat(),
            infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "wavePhase",
        ).value
    } else 0f

    // Real progress from actual player position / duration
    val progress = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(colors = listOf(Color(0xFF1C1C2E), GrooveBg3)))
            .border(1.dp, GrooveBorder, RoundedCornerShape(18.dp))
            .clickable { onOpen() }
    ) {
        // Progress bar at bottom — driven by real position
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(2.dp)
                .align(Alignment.BottomStart)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(GroovePurple.copy(0.7f), GrooveTeal.copy(0.7f))
                    )
                )
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Cover
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
                if (track.coverPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file://${track.coverPath}").crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(Color(track.sourceColor), Color(track.sourceColor).copy(0.5f)))
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Title + artist
            Column(Modifier.weight(1f)) {
                Text(track.title, color = GrooveText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = GrooveText2, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Waveform (when playing)
            if (isPlaying) {
                WaveformBars(phase = wavePhase, modifier = Modifier.width(28.dp).height(24.dp))
            }

            // Controls
            IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.SkipPrevious, null, tint = GrooveText, modifier = Modifier.size(22.dp))
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(GroovePurple, RoundedCornerShape(10.dp))
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    null, tint = Color.White, modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.SkipNext, null, tint = GrooveText, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun WaveformBars(phase: Float, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val barCount = 5
        val barWidth = size.width / (barCount * 2f)
        for (i in 0 until barCount) {
            val x = i * 2 * barWidth + barWidth / 2
            val h = size.height * (0.3f + 0.65f * ((sin((phase + i * 0.8f).toDouble()).toFloat() + 1f) / 2f))
            val y = (size.height - h) / 2f
            drawRect(
                color   = GroovePurple,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size    = androidx.compose.ui.geometry.Size(barWidth * 0.7f, h),
            )
        }
    }
}
