package com.teddybear.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.teddybear.aura.ui.theme.coverGradient

@Composable
fun CoverArt(
    trackId:   Int,
    coverUrl:  String?,
    size:      Dp,
    radius:    Dp = 10.dp,
    modifier:  Modifier = Modifier,
) {
    val grad   = coverGradient(trackId)
    val shape  = RoundedCornerShape(radius)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(grad[0], grad[1])))
    ) {
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model            = coverUrl,
                contentDescription = null,
                contentScale     = ContentScale.Crop,
                modifier         = Modifier.fillMaxSize(),
            )
        }
    }
}
