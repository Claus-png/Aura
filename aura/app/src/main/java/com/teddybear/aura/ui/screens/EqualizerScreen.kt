package com.teddybear.aura.ui.screens
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.player.EqPreset
import com.teddybear.aura.player.EqualizerManager
import com.teddybear.aura.ui.theme.*

@Composable
fun EqualizerScreen(vm: MainViewModel, onClose: () -> Unit) {
    val eqState   by vm.eqState.collectAsState()

    val bandFreqs  = vm.eqBandFrequencies
    val bandMin    = vm.eqBandMin
    val bandMax    = vm.eqBandMax

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GrooveBg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 56.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Эквалайзер",
                color = GrooveText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, null, tint = GrooveText2)
            }
        }

        // ── Enable toggle ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Включить", color = GrooveText, fontSize = 15.sp)
            Switch(
                checked         = eqState.enabled,
                onCheckedChange = { vm.setEqEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor       = Color.White,
                    checkedTrackColor       = GroovePurple,
                    uncheckedThumbColor     = GrooveText3,
                    uncheckedTrackColor     = GrooveBg3,
                ),
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Animated EQ visualizer + sliders ─────────────────────────────────
        EqBandEditor(
            bands     = eqState.bands,
            freqs     = bandFreqs,
            enabled   = eqState.enabled,
            bandMin   = bandMin,
            bandMax   = bandMax,
            onBandChange = { i, v -> vm.setEqBand(i, v) },
        )

        Spacer(Modifier.height(24.dp))

        // ── Presets ───────────────────────────────────────────────────────────
        Text(
            "ПРЕСЕТЫ",
            color = GrooveText3, fontSize = 10.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(EqualizerManager.PRESETS) { index, preset ->
                PresetChip(
                    preset   = preset,
                    selected = eqState.presetIndex == index && eqState.enabled,
                    onClick  = { vm.applyEqPreset(index) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Bass Boost ────────────────────────────────────────────────────────
        EffectSlider(
            label    = "Усиление басов",
            value    = eqState.bassBoost,
            range    = 0..1000,
            enabled  = eqState.enabled,
            color    = GroovePurple,
            onValueChange = { vm.setEqBassBoost(it) },
        )

        Spacer(Modifier.height(12.dp))

        // ── Virtualizer (surround) ────────────────────────────────────────────
        EffectSlider(
            label    = "Объёмный звук",
            value    = eqState.virtualizer,
            range    = 0..1000,
            enabled  = eqState.enabled,
            color    = GrooveTeal,
            onValueChange = { vm.setEqVirtualizer(it) },
        )
    }
}

// ── Band Editor ────────────────────────────────────────────────────────────────

@Composable
private fun EqBandEditor(
    bands: List<Int>,
    freqs: List<String>,
    enabled: Boolean,
    bandMin: Int,
    bandMax: Int,
    onBandChange: (Int, Int) -> Unit,
) {
    val density = LocalDensity.current

    // Animated band values for smooth visual
    // Animate only when not actively dragging to prevent glitches
    val animBands = bands.mapIndexed { index, v ->
        animateFloatAsState(
            targetValue = v.toFloat(),
            animationSpec = tween(durationMillis = 100, easing = LinearEasing),
            label = "eqBand_$index",
        ).value
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GrooveBg2)
            .border(1.dp, GrooveBorder, RoundedCornerShape(16.dp))
    ) {
        // Background grid
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawEqGrid(bandMin.toFloat(), bandMax.toFloat(), enabled)
        }

        // Band curve visualization
        if (bands.size >= 2) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawEqCurve(animBands, bandMin.toFloat(), bandMax.toFloat(), enabled)
            }
        }

        // Draggable band handles
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bands.forEachIndexed { index, value ->
                BandHandle(
                    freq     = freqs.getOrElse(index) { "?" },
                    value    = value,
                    bandMin  = bandMin,
                    bandMax  = bandMax,
                    enabled  = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onValueChange = { onBandChange(index, it) },
                )
            }
        }
    }
}

@Composable
private fun BandHandle(
    freq: String,
    value: Int,
    bandMin: Int,
    bandMax: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val range   = bandMax - bandMin

    // Animated handle position
    val animValue by animateFloatAsState(value.toFloat(), tween(150), label = "band")
    val alpha = if (enabled) 1f else 0.4f

    Column(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectVerticalDragGestures(
                onVerticalDrag = { _, dy ->
                    val trackHeightPx = size.height - 48.dp.toPx()
                    if (trackHeightPx > 0) {
                        val delta = (-dy / trackHeightPx * range).toInt()
                        val newVal = (value + delta).coerceIn(bandMin, bandMax)
                        if (newVal != value) onValueChange(newVal)  // Only call if value actually changed
                    }
                }
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // dB label
        val db = if (value >= 0) "+${value / 100}" else "${value / 100}"
        Text(
            "$db dB",
            color    = if (enabled) GroovePurple else GrooveText3,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.weight(1f))

        // Frequency label
        Text(
            freq,
            color = GrooveText3.copy(alpha = alpha),
            fontSize = 8.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

private fun DrawScope.drawEqGrid(bandMin: Float, bandMax: Float, enabled: Boolean) {
    val alpha = if (enabled) 0.15f else 0.07f
    val color = Color.White.copy(alpha = alpha)
    val range = bandMax - bandMin

    // Horizontal lines at 0dB, ±half
    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
        val y = size.height * frac
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawEqCurve(
    bands: List<Float>, bandMin: Float, bandMax: Float, enabled: Boolean,
) {
    val range  = bandMax - bandMin
    val alpha  = if (enabled) 0.6f else 0.2f
    val n      = bands.size
    if (n < 2) return

    val pts = bands.mapIndexed { i, v ->
        val x = size.width * (i + 0.5f) / n
        val y = size.height * (1f - (v - bandMin) / range)
        Offset(x, y)
    }

    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val cx = (pts[i].x + pts[i + 1].x) / 2f
            cubicTo(cx, pts[i].y, cx, pts[i + 1].y, pts[i + 1].x, pts[i + 1].y)
        }
    }

    // Filled area
    val fillPath = Path().apply {
        addPath(path)
        lineTo(pts.last().x, size.height)
        lineTo(pts.first().x, size.height)
        close()
    }
    drawPath(
        fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x608B5CF6), Color(0x008B5CF6)),
        ),
    )

    // Stroke
    drawPath(
        path,
        color       = Color(0xFF8B5CF6).copy(alpha = alpha),
        style       = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 3f,
            cap   = StrokeCap.Round,
            join  = StrokeJoin.Round,
        ),
    )
}

// ── Preset chip ────────────────────────────────────────────────────────────────

@Composable
private fun PresetChip(preset: EqPreset, selected: Boolean, onClick: () -> Unit) {
    val bg     by animateColorAsState(
        if (selected) GroovePurple else GrooveBg2,
        tween(200), label = "presetBg",
    )
    val border by animateColorAsState(
        if (selected) GroovePurple else GrooveBorder,
        tween(200), label = "presetBorder",
    )
    val textColor by animateColorAsState(
        if (selected) Color.White else GrooveText2,
        tween(200), label = "presetText",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(preset.name, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Effect slider (Bass Boost, Virtualizer) ────────────────────────────────────

@Composable
private fun EffectSlider(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    color: Color,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = GrooveText2, fontSize = 13.sp)
            Text(
                "${value / 10}%",
                color = if (enabled) color else GrooveText3,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value         = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange    = range.first.toFloat()..range.last.toFloat(),
            enabled       = enabled,
            colors = SliderDefaults.colors(
                activeTrackColor   = color,
                thumbColor         = color,
                inactiveTrackColor = GrooveBg3,
            ),
        )
    }
}
