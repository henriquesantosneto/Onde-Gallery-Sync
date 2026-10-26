package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput

internal data class GridSelectionHit(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val canonicalPath: String?,
)

/** Primeira célula de foto que contém o ponto, nas coordenadas da grade. */
internal fun canonicalPathAtGridPoint(
    hits: List<GridSelectionHit>,
    x: Float,
    y: Float,
): String? {
    for (hit in hits) {
        val path = hit.canonicalPath ?: continue
        if (x >= hit.left && y >= hit.top && x < hit.left + hit.width && y < hit.top + hit.height) {
            return path
        }
    }
    return null
}

/** Converte o dedo na célula para a foto visível embaixo dele. */
internal class PhotoGridHitTester {
    var entries: List<GalleryGridEntry> = emptyList()
    var gridState: LazyGridState? = null

    fun pathAt(anchorPath: String, local: Offset): String? {
        val state = gridState ?: return null
        val visible = state.layoutInfo.visibleItemsInfo
        val hits = ArrayList<GridSelectionHit>(visible.size)
        var left = 0
        var top = 0
        var found = false
        for (info in visible) {
            val path = (entries.getOrNull(info.index) as? GalleryGridEntry.Photo)
                ?.item
                ?.canonicalPath
            hits.add(
                GridSelectionHit(
                    left = info.offset.x,
                    top = info.offset.y,
                    width = info.size.width,
                    height = info.size.height,
                    canonicalPath = path,
                ),
            )
            if (path == anchorPath) {
                left = info.offset.x
                top = info.offset.y
                found = true
            }
        }
        if (!found) return null
        return canonicalPathAtGridPoint(hits, left + local.x, top + local.y)
    }
}

/**
 * Segurar marca a foto e, sem soltar, o arrasto marca as próximas.
 * O toque longo escuta antes do scroll e tolera um pouco de tremor do dedo.
 */
@Composable
fun Modifier.photoMultiSelectGesture(
    gesturesBlocked: () -> Boolean,
    selectionMode: () -> Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Offset) -> Unit,
): Modifier {
    val blockedState = rememberUpdatedState(gesturesBlocked)
    val selectionState = rememberUpdatedState(selectionMode)
    val openState = rememberUpdatedState(onOpen)
    val toggleState = rememberUpdatedState(onToggle)
    val longPressState = rememberUpdatedState(onLongPress)
    val dragState = rememberUpdatedState(onDrag)
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                if (blockedState.value()) {
                    waitForPointerRelease(down.id)
                    return@awaitEachGesture
                }
                val slop = viewConfiguration.touchSlop * 3f
                val origin = down.position
                val releasedAsTap = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull false
                        if (!change.pressed) return@withTimeoutOrNull true
                        if ((change.position - origin).getDistance() > slop) {
                            return@withTimeoutOrNull false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                when (releasedAsTap) {
                    true -> {
                        if (selectionState.value()) toggleState.value() else openState.value()
                    }
                    false -> Unit
                    null -> {
                        longPressState.value()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            dragState.value(change.position)
                        }
                    }
                }
            }
        }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForPointerRelease(
    pointerId: PointerId,
) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (!change.pressed) return
    }
}
