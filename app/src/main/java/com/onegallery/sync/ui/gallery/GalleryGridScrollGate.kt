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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Sinaliza rolagem da grade para adiar decode pesado e refresh do catálogo. */
object GalleryGridScrollGate {
    @Volatile
    var isUserScrollingGrid: Boolean = false

    /** Fotos, álbuns ou a mídia aberta — o sync de miniatura cede a vez. */
    @Volatile
    var galleryInUse: Boolean = false

    private val browseThumbnailPermit = Semaphore(1)

    fun shouldDeferCatalogThumbnailRefresh(): Boolean = isUserScrollingGrid

    fun shouldDeferBulkThumbnailPrefetch(): Boolean = isUserScrollingGrid

    fun shouldUseQuietThumbnailSlot(): Boolean = isUserScrollingGrid || galleryInUse

    /**
     * Na galeria, uma miniatura por vez e só com a lista parada.
     * Fora dela (tela Sync, segundo plano) o bloco segue direto.
     */
    suspend fun <T> withGalleryFriendlyThumbnailSlot(block: suspend () -> T): T {
        while (isUserScrollingGrid) {
            delay(32)
        }
        if (!galleryInUse) return block()
        return browseThumbnailPermit.withPermit {
            while (isUserScrollingGrid) {
                delay(32)
            }
            delay(GALLERY_BROWSE_THUMB_GAP_MS)
            while (isUserScrollingGrid) {
                delay(32)
            }
            block()
        }
    }

    suspend fun awaitIdleIfZeroDecodeEnabled(enabled: Boolean, maxWaitMs: Long = Long.MAX_VALUE) {
        if (!enabled) return
        val started = android.os.SystemClock.uptimeMillis()
        while (isUserScrollingGrid) {
            if (maxWaitMs != Long.MAX_VALUE &&
                android.os.SystemClock.uptimeMillis() - started >= maxWaitMs
            ) {
                return
            }
            delay(16)
        }
    }

    private const val GALLERY_BROWSE_THUMB_GAP_MS = 64L
}

internal fun routeKeepsSyncQuiet(route: String?): Boolean {
    if (route.isNullOrBlank()) return false
    if (route == "settings" || route == "settings/sync") return false
    return true
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
