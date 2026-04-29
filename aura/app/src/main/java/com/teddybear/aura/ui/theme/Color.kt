package com.teddybear.aura.ui.theme

import androidx.compose.ui.graphics.Color

// Groove dark palette
 val GrooveBg      = Color(0xFF0D0D11)
val GrooveBg2     = Color(0xFF14141A)
val GrooveBg3     = Color(0xFF1C1C26)
val GrooveGreen   = Color(0xFF1db982)
val GrooveRed     = Color(0xFFff4444)
val GrooveText    = Color(0xFFe8e6f0)
val GrooveText2   = Color(0xFF9d9ab0)
val GrooveText3   = Color(0xFF5c5a70)
val GrooveBorder  = Color(0x14ffffff)

// Source colours
val SpotifyGreen  = Color(0xFF1db954)
val YandexRed     = Color(0xFFfc3f1d)
val SoundcloudOrg = Color(0xFFff5500)
val YTMusicRed    = Color(0xFFff2828)

// Gradient pairs for covers [start, end]
val CoverGradients = listOf(
    listOf(Color(0xFF2a1250), Color(0xFF6c2db8)),
    listOf(Color(0xFF0a3040), Color(0xFF0e7a6e)),
    listOf(Color(0xFF3a1010), Color(0xFFb83030)),
    listOf(Color(0xFF4a2800), Color(0xFFd4810a)),
    listOf(Color(0xFF0a2040), Color(0xFF1560b8)),
    listOf(Color(0xFF1a3010), Color(0xFF3a8030)),
)

fun coverGradient(id: Int) = CoverGradients[id.and(0x7fffffff) % CoverGradients.size]

// Material theme colours (kept for Material3 compat)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)
