package com.onegallery.sync.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Rotas com foto/conteúdo escuro atrás da status bar transparente — ícones claros (brancos). */
fun galleryStyleStatusBarRoute(route: String?): Boolean {
    if (route == null) return false
    return route == "gallery" ||
        route == "albums" ||
        route == "albums/browse" ||
        route == "places_map" ||
        route.startsWith("media/")
}

/**
 * Ícones escuros (relógio/bateria pretos) só em telas de formulário claro;
 * galeria/foto usam ícones claros, como apps de fotos edge-to-edge.
 */
fun systemStatusBarUsesDarkIcons(route: String?, darkTheme: Boolean): Boolean {
    if (galleryStyleStatusBarRoute(route)) return false
    return !darkTheme
}

/** Edge-to-edge + aparência dos ícones de status/navegação do sistema. */
@Composable
fun OneGalleryEdgeToEdgeEffect(
    currentRoute: String? = null,
) {
    val darkTheme = isSystemInDarkTheme()
    val statusBarUsesDarkIcons = systemStatusBarUsesDarkIcons(currentRoute, darkTheme)
    val view = LocalView.current
    DisposableEffect(currentRoute, darkTheme, statusBarUsesDarkIcons) {
        val window = (view.context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = statusBarUsesDarkIcons
            controller.isAppearanceLightNavigationBars = statusBarUsesDarkIcons
            onDispose {
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
    }
}
