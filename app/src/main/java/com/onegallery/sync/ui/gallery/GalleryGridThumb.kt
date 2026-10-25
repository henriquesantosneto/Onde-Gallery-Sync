package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Grade: JPEG commitado; placeholder até [GalleryThumbStore] / sync preencherem. */
@Composable
fun GalleryGridThumb(
    item: GalleryDisplayItem,
    contentDescription: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    style: GalleryThumbnailStyle = GalleryThumbnailStyle.Grid,
) {
    if (item.kind == MediaKind.VIDEO && item.localUri != null) {
        GridVideoThumbnail(
            item = item,
            contentDescription = contentDescription,
            sizePx = sizePx,
            modifier = modifier,
            contentScale = contentScale,
            style = style,
        )
        return
    }

    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCache = remember(app) { app.mediaCacheRepository }
    val thumbCache = remember(app) { app.galleryThumbnailCache }

    var revision by remember(item.canonicalPath) { mutableIntStateOf(0) }

    LaunchedEffect(item.canonicalPath) {
        GalleryThumbRevisionBus.commits.collect { path ->
            if (path == item.canonicalPath) revision++
        }
    }

    val thumbFile = remember(
        item.canonicalPath,
        item.oneDriveItemId,
        revision,
    ) {
        GalleryThumbStore.displayFile(item, mediaCache)
    }

    LaunchedEffect(item.canonicalPath, item.oneDriveItemId, revision) {
        if (thumbFile != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val file = GalleryThumbStore.ensureCachedForGrid(item, thumbCache, mediaCache)
            if (file != null) revision++
        }
    }

    val layerModifier = modifier.galleryGridThumbnailLayer(style)
    val cloudBacked = item.isCloudBackedGalleryItem()

    when {
        thumbFile != null -> {
            val request = remember(thumbFile, sizePx, item.canonicalPath) {
                ImageRequest.Builder(context)
                    .data(thumbFile)
                    .size(sizePx)
                    .allowHardware(false)
                    .memoryCacheKey("${item.canonicalPath}:grid:disk:$sizePx")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = layerModifier,
                contentScale = contentScale,
            )
        }
        item.localUri != null && item.kind == MediaKind.IMAGE -> {
            val uri = item.localUri
            val request = remember(uri, sizePx, item.canonicalPath) {
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(sizePx)
                    .applyGalleryThumbnailLoading(
                        style = style,
                        allowHardwareBitmap = false,
                        videoDecode = false,
                    )
                    .memoryCacheKey("${item.canonicalPath}:grid:uri:$sizePx")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = layerModifier,
                contentScale = contentScale,
            )
        }
        else -> {
            val cloudOnly = item.cloudState == CloudSyncState.CLOUD_ONLY ||
                item.cloudState == CloudSyncState.PENDING_DOWNLOAD ||
                (cloudBacked && item.localUri == null)
            MediaThumbnailPlaceholder(
                modifier = modifier,
                showCloudIcon = cloudOnly,
                showLoading = true,
            )
        }
    }
}
