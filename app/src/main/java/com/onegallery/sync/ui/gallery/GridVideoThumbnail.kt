package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.CloudSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vídeo na grade: local com URI; nuvem/cache usa um único AsyncImage (arquivo ou Coil Graph)
 * com paint estável entre refreshes da lista.
 */
@Composable
fun GridVideoThumbnail(
    item: GalleryDisplayItem,
    contentDescription: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    style: GalleryThumbnailStyle = GalleryThumbnailStyle.Grid,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCache = remember(app) { app.mediaCacheRepository }
    val thumbCache = remember(app) { app.galleryThumbnailCache }
    var coilRetry by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var diskPoll by remember(item.canonicalPath) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val diskThumb = remember(
        item.canonicalPath,
        item.oneDriveItemId,
        item.cachedPlaceholderThumb?.absolutePath,
        diskPoll,
    ) {
        item.resolvedGridVideoThumbFile(mediaCache)
            ?: GridVideoThumbPaintCache.recall(item.canonicalPath)?.let { recalled ->
                recalled.takeIf {
                    isCommittedGridVideoThumbFile(it, item.isCloudOnlyVideoThumb())
                }
            }
    }

    if (diskThumb != null) {
        GridVideoThumbPaintCache.remember(item.canonicalPath, diskThumb)
    }

    val cloudKey = remember(item.canonicalPath, item.oneDriveItemId, item.localUri) {
        if (item.localUri != null) null
        else GalleryThumbnailResolver.cloudVideoCoilKey(item, forFilmstrip = true)
    }

    val mediaStoreFrame by produceState<Bitmap?>(
        initialValue = null,
        key1 = item.canonicalPath,
        key2 = item.localUri,
        key3 = sizePx,
    ) {
        if (item.localUri == null) return@produceState
        value = withContext(Dispatchers.IO) {
            GalleryThumbnailModels.loadGridVideoBitmapFast(context, item, sizePx)
        }
    }

    LaunchedEffect(item.canonicalPath, item.localUri, item.oneDriveItemId) {
        withContext(Dispatchers.IO) {
            val allowCloudPoster = item.isCloudOnlyVideoThumb()
            val onDisk = mediaCache.thumbnailIfCached(item.canonicalPath, item.oneDriveItemId)
            if (onDisk != null && isInvalidCommittedVideoThumb(onDisk, allowCloudPoster)) {
                mediaCache.invalidateCommittedThumbnail(item.canonicalPath, item.oneDriveItemId)
                GridVideoThumbPaintCache.forget(item.canonicalPath)
            }
            thumbCache.ensureThumbnailCached(
                item.canonicalPath,
                preferCloudFirst = item.localUri == null && item.oneDriveItemId != null,
                cloudVideoAggressive = true,
                lightweightPrefetch = item.localUri != null,
            )
            if (item.resolvedGridVideoThumbFile(mediaCache) != null) {
                diskPoll++
            }
        }
    }

    val layerModifier = modifier.galleryGridThumbnailLayer(style)

    when {
        item.localUri != null -> {
            Box(modifier = layerModifier) {
                val uri = item.localUri
                val uriRequest = remember(uri, sizePx, item.canonicalPath, coilRetry) {
                    ImageRequest.Builder(context)
                        .data(uri)
                        .size(sizePx)
                        .applyGalleryThumbnailLoading(
                            style = style,
                            allowHardwareBitmap = false,
                            videoDecode = true,
                        )
                        .memoryCacheKey("${item.canonicalPath}:gridvid:uri:$sizePx:$coilRetry")
                        .build()
                }
                AsyncImage(
                    model = uriRequest,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            coilRetry++
                        }
                    },
                )
                mediaStoreFrame?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = contentScale,
                    )
                } ?: diskThumb?.let { file ->
                    val fileRequest = remember(file, sizePx) {
                        ImageRequest.Builder(context)
                            .data(file)
                            .size(sizePx)
                            .allowHardware(false)
                            .build()
                    }
                    AsyncImage(
                        model = fileRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = contentScale,
                    )
                }
            }
        }
        diskThumb != null || cloudKey != null -> {
            val paintModel: Any = diskThumb ?: cloudKey!!
            var showLoadingUnderlay by remember(item.canonicalPath) {
                mutableStateOf(diskThumb == null)
            }
            val request = remember(paintModel, sizePx, coilRetry, item.canonicalPath) {
                ImageRequest.Builder(context)
                    .data(paintModel)
                    .size(sizePx)
                    .apply {
                        if (paintModel is java.io.File) {
                            allowHardware(false)
                        } else {
                            applyGalleryThumbnailLoading(
                                style = style,
                                allowHardwareBitmap = false,
                                videoDecode = false,
                            )
                        }
                    }
                    .memoryCacheKey(
                        when (paintModel) {
                            is java.io.File ->
                                "${item.canonicalPath}:gridvid:file:$sizePx"
                            else ->
                                "${item.canonicalPath}:gridvid:cloud:$sizePx:$coilRetry"
                        },
                    )
                    .build()
            }
            Box(modifier = layerModifier) {
                if (showLoadingUnderlay && diskThumb == null) {
                    MediaThumbnailPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        showCloudIcon = true,
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
                                showLoadingUnderlay = false
                                if (diskThumb == null) {
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) {
                                            item.resolvedGridVideoThumbFile(mediaCache)
                                        } ?: return@launch
                                        GridVideoThumbPaintCache.remember(
                                            item.canonicalPath,
                                            file,
                                        )
                                        diskPoll++
                                    }
                                }
                            }
                            is AsyncImagePainter.State.Error -> {
                                if (diskThumb == null) {
                                    showLoadingUnderlay = true
                                    coilRetry++
                                }
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
                showCloudIcon = cloudOnly,
                showLoading = !cloudOnly,
            )
        }
    }
}
