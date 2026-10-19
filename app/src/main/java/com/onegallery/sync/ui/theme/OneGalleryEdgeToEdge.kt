package com.onegallery.sync.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Mantém desenho edge-to-edge (conteúdo atrás da status bar), como [enableEdgeToEdge] no Activity. */
@Composable
fun OneGalleryEdgeToEdgeEffect(
    lightStatusBarIcons: Boolean = true,
    lightNavigationBarIcons: Boolean = true,
) {
    val view = LocalView.current
    DisposableEffect(lightStatusBarIcons, lightNavigationBarIcons) {
        val window = (view.context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = lightStatusBarIcons
            controller.isAppearanceLightNavigationBars = lightNavigationBarIcons
            onDispose {
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
    }
}
