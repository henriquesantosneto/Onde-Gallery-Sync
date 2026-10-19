package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class GalleryThumbnailStyle {
    /** Grade: sem crossfade, decode no tamanho da célula. */
    Grid,
    /** Detalhe / zoom: crossfade suave. */
    Detail,
}

@Composable
fun GalleryThumbnailImage(
    item: GalleryDisplayItem,
    syncCoordinator: SyncCoordinator?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    style: GalleryThumbnailStyle = GalleryThumbnailStyle.Grid,
    sizePx: Int,
) {
    val context = LocalContext.current
    var displayData by remember(item.canonicalPath, sizePx) {
        mutableStateOf<Any?>(item.localUri ?: item.thumbnailModel)
    }

    LaunchedEffect(item.canonicalPath, item.localUri, item.thumbnailModel, item.oneDriveItemId, sizePx) {
        val fromItem = item.localUri ?: item.thumbnailModel
        if (fromItem != null) {
            displayData = fromItem
        }
        if (item.localUri != null) {
            withContext(Dispatchers.IO) {
                GalleryThumbnailModels.resolveGridModel(context, item, sizePx)
            }?.let { resolved ->
                displayData = resolved
            }
            return@LaunchedEffect
        }
        if (displayData != null) return@LaunchedEffect
        val coordinator = syncCoordinator ?: return@LaunchedEffect
        coordinator.ensureThumbnailCached(item.canonicalPath).getOrNull()?.let { cached ->
            displayData = cached
        }
    }

    if (displayData != null) {
        val request = remember(displayData, sizePx, style, item.canonicalPath) {
            ImageRequest.Builder(context)
                .data(displayData)
                .size(sizePx)
                .crossfade(true)
                .allowHardware(style == GalleryThumbnailStyle.Grid)
                .memoryCacheKey("${item.canonicalPath}:$sizePx:${style.name}")
                .diskCacheKey("${item.canonicalPath}:$sizePx")
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        MediaThumbnailPlaceholder(modifier = modifier)
    }
}

@Composable
fun MediaThumbnailPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
    }
}
