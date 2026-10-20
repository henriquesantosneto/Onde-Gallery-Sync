package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Arraste horizontal que move o [PagerState] numa única sessão [PagerState.scroll],
 * para a página acompanhar o dedo (fotos e vídeo/ExoPlayer).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.mediaDetailPagerHorizontalDrag(
    enabled: Boolean,
    pagerState: PagerState,
): Modifier {
    if (!enabled) return this
    return pointerInput(pagerState.currentPage, enabled) {
        coroutineScope {
            var scrollScope: ScrollScope? = null
            launch {
                pagerState.scroll {
                    scrollScope = this
                    try {
                        awaitCancellation()
                    } finally {
                        scrollScope = null
                    }
                }
            }

            val touchSlop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalX = 0f
                var totalY = 0f
                var horizontal = false
                var decided = false
                var totalDrag = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.positionChange()
                    if (delta == Offset.Zero) continue

                    if (!decided) {
                        totalX += delta.x
                        totalY += delta.y
                        if (abs(totalX) < touchSlop && abs(totalY) < touchSlop) continue
                        decided = true
                        horizontal = abs(totalX) > abs(totalY) * 1.04f
                        if (!horizontal) break
                    }
                    if (!horizontal) break

                    val scope = scrollScope
                    if (scope == null) break
                    scope.scrollBy(-delta.x)
                    totalDrag += delta.x
                    change.consume()
                }

                if (!horizontal || !decided) return@awaitEachGesture

                val width = size.width.toFloat().coerceAtLeast(1f)
                val offset = pagerState.currentPageOffsetFraction
                val fling = abs(totalDrag) > width * 0.1f
                launch {
                    when {
                        offset > 0.16f || (fling && totalDrag < 0f) -> {
                            val target = (pagerState.currentPage + 1)
                                .coerceAtMost(pagerState.pageCount - 1)
                            pagerState.animateScrollToPage(target)
                        }
                        offset < -0.16f || (fling && totalDrag > 0f) -> {
                            val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                            pagerState.animateScrollToPage(target)
                        }
                        else -> pagerState.animateScrollToPage(pagerState.currentPage)
                    }
                }
            }
        }
    }
}
