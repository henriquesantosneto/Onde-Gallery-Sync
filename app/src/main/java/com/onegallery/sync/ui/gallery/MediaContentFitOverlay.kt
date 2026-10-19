package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Alinha [overlay] ao canto do retângulo onde a mídia aparece com [ContentScale.Fit]
 * (mesma lógica de letterbox), não ao retângulo cheio da tela/célula.
 */
@Composable
fun MediaContentFitOverlayHost(
    contentAspectRatio: Float?,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = contentAspectRatio?.takeIf { it.isFinite() && it > 0f }
        if (ratio != null) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val fitModifier = if (maxWidth / maxHeight > ratio) {
                    Modifier.fillMaxHeight().aspectRatio(ratio)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(ratio)
                }
                Box(
                    modifier = fitModifier,
                    contentAlignment = Alignment.TopEnd,
                    content = overlay,
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopEnd,
                content = overlay,
            )
        }
    }
}
