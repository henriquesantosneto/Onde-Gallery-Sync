package com.onegallery.sync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Indicador vertical à direita — deixa claro que a lista continua fora da tela. */
@Composable
fun LazyColumnVerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return

    val scrollable = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 0 ||
        visibleItems.last().index < totalItems - 1 ||
        visibleItems.last().offset + visibleItems.last().size > layoutInfo.viewportEndOffset
    if (!scrollable) return

    val visibleCount = visibleItems.size

    val scrollRange = (totalItems - visibleCount).coerceAtLeast(1)
    val scrollIndex = listState.firstVisibleItemIndex.coerceIn(0, scrollRange)
    val thumbFraction = visibleCount.toFloat() / totalItems.toFloat()
    val thumbOffsetFraction = scrollIndex.toFloat() / scrollRange.toFloat()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val thumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)

    BoxWithConstraints(modifier = modifier.width(5.dp)) {
        val trackHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(trackColor, RoundedCornerShape(3.dp)),
        )
        val thumbHeight = trackHeight * thumbFraction.coerceIn(0.18f, 1f)
        val maxTravel = (trackHeight - thumbHeight).coerceAtLeast(0.dp)
        val thumbTop = maxTravel * thumbOffsetFraction
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(thumbHeight)
                .offset(y = thumbTop)
                .background(thumbColor, RoundedCornerShape(3.dp)),
        )
    }
}

/** Espaço reservado à direita para a barrinha não cobrir texto. */
val LazyColumnScrollbarEndPadding = 12.dp
