package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Camada transparente sobre vídeo (ExoPlayer): repassa arraste horizontal ao [PagerState]
 * para o movimento acompanhar o dedo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaDetailPagerDragRelay(
    enabled: Boolean,
    pagerState: PagerState,
    pagerScope: CoroutineScope,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pagerState.currentPage, enabled) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                totalDrag += dragAmount
                                change.consume()
                                pagerScope.launch {
                                    pagerState.scroll { scrollBy(-dragAmount) }
                                }
                            },
                            onDragEnd = {
                                pagerScope.launch {
                                    val offset = pagerState.currentPageOffsetFraction
                                    val fling = abs(totalDrag) > size.width * 0.12f
                                    when {
                                        offset > 0.22f || (fling && totalDrag < 0f) -> {
                                            if (pagerState.currentPage < pagerState.pageCount - 1) {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            } else {
                                                pagerState.animateScrollToPage(pagerState.currentPage)
                                            }
                                        }
                                        offset < -0.22f || (fling && totalDrag > 0f) -> {
                                            if (pagerState.currentPage > 0) {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            } else {
                                                pagerState.animateScrollToPage(pagerState.currentPage)
                                            }
                                        }
                                        else -> pagerState.animateScrollToPage(pagerState.currentPage)
                                    }
                                }
                            },
                        )
                    },
            )
        }
    }
}
