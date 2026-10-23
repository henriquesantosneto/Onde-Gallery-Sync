package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
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
    var resolvingThumb by remember(item.canonicalPath, sizePx) {
        mutableStateOf(
            displayData == null &&
                syncCoordinator != null &&
                (
                    item.oneDriveItemId != null ||
                        (item.kind == MediaKind.VIDEO && item.localUri != null)
                    ),
        )
    }

    LaunchedEffect(item.canonicalPath, item.localUri, item.thumbnailModel, item.oneDriveItemId, sizePx) {
        if (displayData != null && !isStaleThumbnailDisplayData(item, displayData, mediaCacheRepository)) {
            resolvingThumb = false
            return@LaunchedEffect
        }
        val isVideo = item.kind == MediaKind.VIDEO
        val preferCloud = item.cloudState == CloudSyncState.CLOUD_ONLY ||
            item.cloudState == CloudSyncState.PENDING_DOWNLOAD
        resolvingThumb = syncCoordinator != null &&
            (item.oneDriveItemId != null || (isVideo && item.localUri != null))
        try {
            withContext(Dispatchers.IO) {
                mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId)
                    ?.let { cached ->
                        if (isVideo && !VideoThumbnailHelper.isDisplayableVideoThumbnailFile(cached)) {
                            mediaCacheRepository.invalidateCommittedThumbnail(
                                item.canonicalPath,
                                item.oneDriveItemId,
                            )
                        } else {
                            return@withContext cached
                        }
                    }
                if (isVideo && preferCloud && syncCoordinator != null) {
                    syncCoordinator.ensureThumbnailCached(
                        item.canonicalPath,
                        preferCloudFirst = true,
                        cloudVideoAggressive = true,
                    ).getOrNull()
                        ?.takeIf { VideoThumbnailHelper.isDisplayableVideoThumbnailFile(it) }
                        ?.let { return@withContext it }
                }
                if (isVideo && item.localUri != null && syncCoordinator != null) {
                    syncCoordinator.ensureThumbnailCached(
                        item.canonicalPath,
                        preferCloudFirst = false,
                        localSourcesOnly = true,
                    ).getOrNull()
                        ?.takeIf { VideoThumbnailHelper.isDisplayableVideoThumbnailFile(it) }
                        ?.let { return@withContext it }
                }
                if (item.localUri != null) {
                    GalleryThumbnailModels.resolveGridModel(context, item, sizePx)?.let { local ->
                        return@withContext local
                    }
                    if (isVideo) {
                        return@withContext item.localUri
                    }
                }
                syncCoordinator?.ensureThumbnailCached(
                    item.canonicalPath,
                    preferCloudFirst = preferCloud,
                    cloudVideoAggressive = isVideo && preferCloud,
                )?.getOrNull()
                    ?.takeIf {
                        if (isVideo) {
                            VideoThumbnailHelper.isDisplayableVideoThumbnailFile(it)
                        } else {
                            mediaCacheRepository.isDisplayableGalleryThumbFile(it)
                        }
                    }
                    ?.let { return@withContext it }
                item.thumbnailModel
            }?.let { resolved ->
                displayData = resolved
            }
        } finally {
            resolvingThumb = false
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
        val cloudOnly = item.cloudState == CloudSyncState.CLOUD_ONLY ||
            item.cloudState == CloudSyncState.PENDING_DOWNLOAD
        MediaThumbnailPlaceholder(
            modifier = modifier,
            showCloudIcon = !resolvingThumb &&
                (
                    cloudOnly ||
                        (
                            item.cloudState != CloudSyncState.CLOUD_ONLY &&
                                item.cloudState != CloudSyncState.PENDING_DOWNLOAD
                            )
                    ),
            showLoading = resolvingThumb,
        )
    }
}

private fun isStaleThumbnailDisplayData(
    item: GalleryDisplayItem,
    displayData: Any?,
    mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
): Boolean {
    val file = displayData as? java.io.File ?: return false
    if (mediaCacheRepository.isSyncFailurePlaceholderThumbnail(file)) {
        return item.localUri != null && item.kind != MediaKind.VIDEO
    }
    if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isDisplayableVideoThumbnailFile(file)) {
        return true
    }
    return false
}

private fun initialThumbnailDisplayData(
    item: GalleryDisplayItem,
    mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
): Any? {
    item.localUri?.let { uri ->
        return uri
    }
    mediaCacheRepository.displayableThumbnailIfCached(item.canonicalPath, item.oneDriveItemId)?.let { cached ->
        if (item.kind == MediaKind.VIDEO && !VideoThumbnailHelper.isDisplayableVideoThumbnailFile(cached)) {
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
    item.cachedOriginal?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
    return null
}

@Composable
fun MediaThumbnailPlaceholder(
    modifier: Modifier = Modifier,
    showCloudIcon: Boolean = true,
    showLoading: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            showLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                )
            }
            showCloudIcon -> {
                Icon(
                    Icons.Outlined.Cloud,
                    contentDescription = null,
                    tint = OneDriveBlue,
                )
            }
        }
    }
}
