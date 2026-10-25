package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Miniatura da grade: prioriza **JPEG no cache**; enquanto não existe, placeholder estável
 * e prefetch (sync/Graph). Atualiza só esta célula quando o arquivo é commitado.
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
    val thumbCache = remember(app) { app.galleryThumbnailCache }

    var revision by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var coilRetry by remember(item.canonicalPath) { mutableIntStateOf(0) }

    LaunchedEffect(item.canonicalPath) {
        GalleryThumbRevisionBus.commits.collect { path ->
            if (path == item.canonicalPath) revision++
        }
    }

    val thumbFile = remember(
        item.canonicalPath,
        item.oneDriveItemId,
        item.cachedPlaceholderThumb?.absolutePath,
        revision,
    ) {
        item.resolvedGalleryGridThumbFile(mediaCache)
    }

    val cloudVideoKey = remember(item.canonicalPath, item.oneDriveItemId, item.localUri) {
        if (item.kind != MediaKind.VIDEO || item.localUri != null) null
        else GalleryThumbnailResolver.cloudVideoCoilKey(item, forFilmstrip = true)
    }

    LaunchedEffect(item.canonicalPath, item.oneDriveItemId, item.localUri) {
        withContext(Dispatchers.IO) {
            thumbCache.ensureThumbnailCached(
                canonicalPath = item.canonicalPath,
                preferCloudFirst = item.isCloudBackedGalleryItem() && item.localUri == null,
                cloudVideoAggressive = item.kind == MediaKind.VIDEO,
                lightweightPrefetch = item.localUri != null && item.kind != MediaKind.VIDEO,
            )
            if (item.resolvedGalleryGridThumbFile(mediaCache) != null) {
                revision++
            }
        }
    }

    val layerModifier = modifier.galleryGridThumbnailLayer(style)
    val paintModel: Any? = thumbFile ?: item.localUri ?: cloudVideoKey

    when {
        paintModel != null -> {
            var showUnderlay by remember(item.canonicalPath) {
                mutableStateOf(thumbFile == null && cloudVideoKey != null)
            }
            val request = remember(paintModel, sizePx, coilRetry, item.canonicalPath) {
                ImageRequest.Builder(context)
                    .data(paintModel)
                    .size(sizePx)
                    .apply {
                        when (paintModel) {
                            is java.io.File -> allowHardware(false)
                            is android.net.Uri -> applyGalleryThumbnailLoading(
                                style = style,
                                allowHardwareBitmap = false,
                                videoDecode = item.kind == MediaKind.VIDEO,
                            )
                            else -> applyGalleryThumbnailLoading(
                                style = style,
                                allowHardwareBitmap = false,
                                videoDecode = false,
                            )
                        }
                    }
                    .memoryCacheKey("${item.canonicalPath}:grid:${sizePx}:$coilRetry:$revision")
                    .build()
            }
            Box(modifier = layerModifier) {
                if (showUnderlay && thumbFile == null) {
                    MediaThumbnailPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        showCloudIcon = item.isCloudBackedGalleryItem(),
                        showLoading = true,
                    )
                }
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> {
                                showUnderlay = false
                                if (thumbFile == null) revision++
                            }
                            is AsyncImagePainter.State.Error -> {
                                if (thumbFile == null) coilRetry++
                            }
                            else -> Unit
                        }
                    },
                )
            }
        }
        else -> {
            val cloudOnly = item.cloudState == CloudSyncState.CLOUD_ONLY ||
                item.cloudState == CloudSyncState.PENDING_DOWNLOAD
            MediaThumbnailPlaceholder(
                modifier = modifier,
                showCloudIcon = cloudOnly || item.isCloudBackedGalleryItem(),
                showLoading = true,
            )
        }
    }
}
