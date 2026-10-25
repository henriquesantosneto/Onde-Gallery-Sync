package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Grade rápida: JPEG em disco ou URI local na hora.
 * OneDrive sem arquivo só baixa **depois que a rolagem para** (Coil), sem prefetch por célula.
 */
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

    var revision by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var allowRemote by remember(item.canonicalPath) {
        mutableStateOf(!GalleryGridScrollGate.isUserScrollingGrid)
    }

    LaunchedEffect(item.canonicalPath) {
        GalleryThumbRevisionBus.commits.collect { path ->
            if (path == item.canonicalPath) revision++
        }
    }
    LaunchedEffect(item.canonicalPath) {
        snapshotFlow { GalleryGridScrollGate.isUserScrollingGrid }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) allowRemote = true
            }
    }

    val thumbFile = remember(
        item.canonicalPath,
        item.oneDriveItemId,
        revision,
    ) {
        GalleryThumbStore.displayFile(item, mediaCache)
    }

    val cloudKey = remember(item.canonicalPath, item.oneDriveItemId) {
        item.oneDriveItemId?.takeIf { it.isNotBlank() }?.let { id ->
            OneDriveCloudThumbnailKey(item.canonicalPath, id)
        }
    }

    val paintModel: Any? = when {
        thumbFile != null -> thumbFile
        item.kind == MediaKind.IMAGE && item.localUri != null -> item.localUri
        allowRemote && cloudKey != null && item.localUri == null -> cloudKey
        else -> null
    }

    val layerModifier = modifier.galleryGridThumbnailLayer(style)
    if (paintModel == null) {
        val cloudOnly = item.cloudState == CloudSyncState.CLOUD_ONLY ||
            item.cloudState == CloudSyncState.PENDING_DOWNLOAD ||
            (item.isCloudBackedGalleryItem() && item.localUri == null)
        MediaThumbnailPlaceholder(
            modifier = modifier,
            showCloudIcon = cloudOnly,
            showLoading = allowRemote && cloudKey != null,
        )
        return
    }

    val request = remember(paintModel, sizePx, item.canonicalPath) {
        ImageRequest.Builder(context)
            .data(paintModel)
            .size(sizePx)
            .apply {
                when (paintModel) {
                    is java.io.File -> allowHardware(false)
                    is android.net.Uri -> applyGalleryThumbnailLoading(
                        style = style,
                        allowHardwareBitmap = false,
                        videoDecode = false,
                    )
                    else -> applyGalleryThumbnailLoading(
                        style = style,
                        allowHardwareBitmap = false,
                        videoDecode = false,
                    )
                }
            }
            .memoryCacheKey("${item.canonicalPath}:grid:$sizePx")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = layerModifier,
        contentScale = contentScale,
    )
}
