package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import com.onegallery.sync.debug.DebugCrashLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** Sinaliza rolagem da grade para adiar decode pesado e refresh do catálogo. */
object GalleryGridScrollGate {
    @Volatile
    var isUserScrollingGrid: Boolean = false

    fun shouldDeferCatalogThumbnailRefresh(): Boolean = isUserScrollingGrid

    fun shouldDeferBulkThumbnailPrefetch(): Boolean = isUserScrollingGrid

    suspend fun awaitIdleIfZeroDecodeEnabled(enabled: Boolean) {
        if (!enabled) return
        while (isUserScrollingGrid) {
            delay(16)
        }
    }
}

val LocalZeroDecodeGridScroll = staticCompositionLocalOf { true }

/**
 * Atualiza [GalleryGridScrollGate] sem recomposição da grade (evita piscar miniaturas).
 */
@Composable
fun ProvideGalleryGridScrollState(
    gridState: LazyGridState,
    zeroDecodeWhileScrolling: Boolean,
    onScrollEnd: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    LaunchedEffect(gridState) {
        try {
            snapshotFlow { gridState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    GalleryGridScrollGate.isUserScrollingGrid = scrolling
                    DebugCrashLog.mark(if (scrolling) "rolagem-inicio" else "rolagem-fim")
                    if (!scrolling) {
                        delay(64)
                        if (!gridState.isScrollInProgress) {
                            onScrollEnd()
                        }
                    }
                }
        } finally {
            GalleryGridScrollGate.isUserScrollingGrid = false
        }
    }
    CompositionLocalProvider(LocalZeroDecodeGridScroll provides zeroDecodeWhileScrolling) {
        content()
    }
}
