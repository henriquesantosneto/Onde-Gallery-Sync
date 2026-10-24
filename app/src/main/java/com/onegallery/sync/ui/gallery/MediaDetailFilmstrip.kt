package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Miniaturas do álbum / local / lista atual (estilo faixa Samsung). */
@Composable
fun MediaDetailFilmstrip(
    items: List<GalleryDisplayItem>,
    currentCanonicalPath: String?,
    onSelectPath: (String) -> Unit,
    syncCoordinator: SyncCoordinator,
    modifier: Modifier = Modifier,
) {
    if (items.size <= 1) return

    val listState = rememberLazyListState()
    val thumbPx = remember { 144 }
    val context = LocalContext.current
    val thumbCache = remember(context) {
        (context.applicationContext as OneGalleryApplication).galleryThumbnailCache
    }

    var filmstripThumbNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(items) {
        withContext(Dispatchers.IO) {
            items.asSequence()
                .filter { it.kind == MediaKind.VIDEO }
                .take(64)
                .forEach { item ->
                    thumbCache.ensureThumbnailCached(
                        item.canonicalPath,
                        preferCloudFirst = item.oneDriveItemId != null,
                        cloudVideoAggressive = true,
                        lightweightPrefetch = false,
                    )
                }
        }
        filmstripThumbNonce++
    }

    LaunchedEffect(currentCanonicalPath, items) {
        val index = items.indexOfFirst { it.canonicalPath == currentCanonicalPath }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(Color.Black.copy(alpha = 0.92f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items, key = { it.canonicalPath }) { item ->
            val selected = item.canonicalPath == currentCanonicalPath
            val shape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(shape)
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, Color.White, shape)
                        } else {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                shape,
                            )
                        },
                    )
                    .clickable { onSelectPath(item.canonicalPath) },
            ) {
                GalleryThumbnailImage(
                    item = item,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    style = GalleryThumbnailStyle.Filmstrip,
                    sizePx = thumbPx,
                    refreshNonce = filmstripThumbNonce,
                )
            }
        }
    }
}
