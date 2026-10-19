package com.onegallery.sync.ui.theme

import androidx.compose.foundation.layout.WindowInsets

/**
 * Scaffold sem insets automáticos no conteúdo — com edge-to-edge, status bar e nav bar
 * ficam no TopAppBar / NavigationBar; evita faixa vazia duplicada no topo.
 */
val ZeroScaffoldContentInsets = WindowInsets(0, 0, 0, 0)
