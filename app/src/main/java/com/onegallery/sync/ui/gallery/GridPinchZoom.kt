package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object GridPinchZoom {
    /** ~32 dp de célula na galeria (120 × 0,28) — visão mês/ano. */
    const val MIN_SCALE = 0.28f
    const val MAX_SCALE = 1.75f
    /** Quanto menor, mais lenta a pinça (0.25 ≈ 25% do gesto bruto). */
    const val PINCH_SENSITIVITY = 0.25f
}

data class GalleryGridPinchZoom(
    val scale: Float,
    val gridModifier: Modifier,
    /** Enquanto true, cliques e long-press nos thumbnails ficam desligados. */
    val blockItemGestures: Boolean,
    val applyPinch: (Float) -> Unit,
    val setMultiTouch: (Boolean) -> Unit,
)

@Composable
fun rememberGalleryGridZoom(initial: Float, onPersist: (Float) -> Unit): GalleryGridPinchZoom {
    var scale by rememberSaveable {
        mutableFloatStateOf(initial.coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE))
    }
    var multiTouch by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { multiTouch = false }
    }
    val nestedScrollConnection = remember(multiTouch) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (multiTouch && source == NestedScrollSource.Drag) {
                    return available
                }
                return Offset.Zero
            }
        }
    }
    val applyPinch: (Float) -> Unit = { zoom ->
        val damped = 1f + (zoom - 1f) * GridPinchZoom.PINCH_SENSITIVITY
        val next = (scale * damped).coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE)
        if (next != scale) {
            scale = next
            onPersist(next)
        }
    }
    val setMultiTouch: (Boolean) -> Unit = { active -> multiTouch = active }
    val gridModifier = Modifier
        .nestedScroll(nestedScrollConnection)
        .gridPinchZoomDetector(
            onMultiTouchActive = setMultiTouch,
            onPinchZoom = applyPinch,
        )
    return GalleryGridPinchZoom(
        scale = scale,
        gridModifier = gridModifier,
        blockItemGestures = multiTouch,
        applyPinch = applyPinch,
        setMultiTouch = setMultiTouch,
    )
}

/** Escala maior → células maiores (menos colunas), como na galeria Samsung. */
fun gridAdaptiveMinSize(baseMinDp: Float, zoomScale: Float): Dp {
    val min = (baseMinDp * zoomScale).coerceIn(32f, 260f)
    return min.dp
}

/** Ícones de nuvem/seleção acompanham o tamanho da célula ao pinçar para fora. */
fun gridOverlayIconSize(zoomScale: Float): Dp {
    val size = (20f * zoomScale).coerceIn(9f, 22f)
    return size.dp
}

fun gridOverlayPadding(zoomScale: Float): Dp {
    val pad = (4f * zoomScale).coerceIn(1.5f, 6f)
    return pad.dp
}

/** Contador na capa do álbum — proporcional ao pinça / tamanho da célula. */
fun albumMediaCountBadgeFontScale(zoomScale: Float): Float {
    return zoomScale.coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE)
        .coerceIn(0.45f, 1.05f)
}

fun albumMediaCountBadgeCorner(zoomScale: Float): Dp {
    val r = (3f * zoomScale).coerceIn(2f, 6f)
    return r.dp
}

fun albumMediaCountBadgeInnerPadding(zoomScale: Float): Pair<Dp, Dp> {
    val scale = zoomScale.coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE)
    val horizontal = (3f + 2.5f * scale).coerceIn(2f, 7f).dp
    val vertical = (1f + 1.2f * scale).coerceIn(1f, 3.5f).dp
    return horizontal to vertical
}
