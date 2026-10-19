package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Duração no estilo Samsung Galeria (ex.: 0:05, 1:23, 1:02:03). */
fun formatVideoDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = durationMs / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

@Composable
fun VideoDurationOverlay(
    durationMs: Long?,
    gridZoomScale: Float,
    modifier: Modifier = Modifier,
) {
    val scale = gridZoomScale.coerceIn(0.45f, 1f)
    val iconSize = (14f * scale).dp.coerceAtLeast(10.dp)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * scale,
        fontWeight = FontWeight.Medium,
        color = Color.White,
    )
    val durationLabel = durationMs?.let { formatVideoDuration(it) }.orEmpty()
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
        if (durationLabel.isNotEmpty()) {
            Text(
                durationLabel,
                style = labelStyle,
                modifier = Modifier.padding(start = 1.dp, end = 2.dp),
            )
        }
    }
}

private fun Dp.coerceAtLeast(min: Dp): Dp = if (this < min) min else this
