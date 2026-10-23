package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
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
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.theme.OneDriveBlue
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
    onContentAspectRatio: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val mediaCacheRepository = remember(context) {
        (context.applicationContext as OneGalleryApplication).mediaCacheRepository
    }
    var displayData by remember(item.canonicalPath, sizePx) {
        mutableStateOf<Any?>(initialThumbnailDisplayData(item, mediaCacheRepository))
    }

    LaunchedEffect(item.canonicalPath, item.localUri, item.thumbnailModel, item.oneDriveItemId, sizePx) {
        withContext(Dispatchers.IO) {
            mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId)
                ?.let { cached ->
                    if (item.kind == MediaKind.VIDEO &&
                        !VideoThumbnailHelper.isAcceptableThumbnailFile(cached)
                    ) {
                        mediaCacheRepository.invalidateCommittedThumbnail(
                            item.canonicalPath,
                            item.oneDriveItemId,
                        )
                    } else {
                        return@withContext cached
                    }
                }
            syncCoordinator?.ensureThumbnailCached(item.canonicalPath)?.getOrNull()
                ?.takeIf { mediaCacheRepository.isDisplayableGalleryThumbFile(it) }
                ?.let { return@withContext it }
            if (item.localUri != null && item.oneDriveItemId == null) {
                GalleryThumbnailModels.resolveGridModel(context, item, sizePx)
            } else {
                item.thumbnailModel
            }
        }?.let { resolved ->
            displayData = resolved
        }
    }

    if (displayData != null) {
        val request = remember(displayData, sizePx, style, item.canonicalPath) {
            ImageRequest.Builder(context)
                .data(displayData)
                .size(sizePx)
                .crossfade(style == GalleryThumbnailStyle.Detail)
                .allowHardware(false)
                .memoryCacheKey("${item.canonicalPath}:$sizePx:${style.name}")
                .diskCacheKey("${item.canonicalPath}:$sizePx")
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            onState = { state ->
                if (onContentAspectRatio != null && state is AsyncImagePainter.State.Success) {
                    val size = state.painter.intrinsicSize
                    if (size.width.isFinite() && size.height.isFinite() && size.height > 0f) {
                        onContentAspectRatio(size.width / size.height)
                    }
                }
            },
        )
    } else {
        val showCenterCloud = item.cloudState != CloudSyncState.CLOUD_ONLY &&
            item.cloudState != CloudSyncState.PENDING_DOWNLOAD
        MediaThumbnailPlaceholder(modifier = modifier, showCloudIcon = showCenterCloud)
    }
}

private fun initialThumbnailDisplayData(
    item: GalleryDisplayItem,
    mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
): Any? {
    mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId)?.let { cached ->
        if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isAcceptableThumbnailFile(cached)) {
            mediaCacheRepository.invalidateCommittedThumbnail(item.canonicalPath, item.oneDriveItemId)
        } else {
            return cached
        }
    }
    val thumb = item.cachedPlaceholderThumb
    if (thumb != null && mediaCacheRepository.isDisplayableGalleryThumbFile(thumb)) {
        return thumb
    }
    if (item.kind == MediaKind.VIDEO) {
        return null
    }
    if (item.oneDriveItemId == null) {
        item.localUri?.let { return it }
    }
    item.cachedOriginal?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
    return null
}

@Composable
fun MediaThumbnailPlaceholder(
    modifier: Modifier = Modifier,
    showCloudIcon: Boolean = true,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (showCloudIcon) {
            Icon(
                Icons.Outlined.Cloud,
                contentDescription = null,
                tint = OneDriveBlue,
            )
        }
    }
}
