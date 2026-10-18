package com.onegallery.sync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = OneDriveBlue,
    onPrimary = Color.White,
    secondary = OneDriveBlue,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondary,
)

private val DarkColors = darkColorScheme(
    primary = OneDriveBlue,
    onPrimary = Color.White,
    secondary = OneDriveBlue,
)

@Composable
fun OneGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
