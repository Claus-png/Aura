package com.teddybear.aura.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Palette ────────────────────────────────────────────────────────────────────

val GroovePurple  = Color(0xFF8B5CF6)
val GroovePurple2 = Color(0xFF7C3AED)
val GroovePurpleDim = Color(0x408B5CF6)
val GrooveTeal    = Color(0xFF2EC4B6)

val SourceColorYandex     = Color(0xFFfc3f1d)
val SourceColorSpotify    = Color(0xFF1db954)
val SourceColorSoundCloud = Color(0xFFff5500)
val SourceColorYtMusic    = Color(0xFFff2828)

private val DarkColors = darkColorScheme(
    primary          = GroovePurple,
    onPrimary        = Color.White,
    primaryContainer = GrooveBg2,
    secondary        = GrooveTeal,
    background       = GrooveBg,
    surface          = GrooveBg2,
    onBackground     = GrooveText,
    onSurface        = GrooveText,
    error            = GrooveRed,
)

@Composable
fun MyProgramTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content,
    )
}

fun sourceColor(source: String): Color = when (source) {
    "yandex"     -> SourceColorYandex
    "spotify"    -> SourceColorSpotify
    "soundcloud" -> SourceColorSoundCloud
    "ytmusic"    -> SourceColorYtMusic
    else         -> GroovePurple
}

fun sourceLabel(source: String): String = when (source) {
    "yandex"     -> "Яндекс Музыка"
    "spotify"    -> "Spotify"
    "soundcloud" -> "SoundCloud"
    "ytmusic"    -> "YT Music"
    "local"      -> "Локальный"
    else         -> source
}
