package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.withContext

/**
 * Vídeo na grade: **local com URI → Coil no 1º frame** (como antes); JPEG/cache e MediaStore
 * entram depois; nuvem só quando não há arquivo local.
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
    val forFilmstrip = style == GalleryThumbnailStyle.Filmstrip
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCache = remember(app) { app.mediaCacheRepository }
    val thumbCache = remember(app) { app.galleryThumbnailCache }
    var coilRetry by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var cacheRevision by remember(item.canonicalPath) { mutableIntStateOf(0) }

    val cacheFile = remember(
        item.canonicalPath,
        item.oneDriveItemId,
        item.cachedPlaceholderThumb?.absolutePath,
        cacheRevision,
    ) {
        item.resolvedGridVideoThumbFile(mediaCache)
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
            }
            thumbCache.ensureThumbnailCached(
                item.canonicalPath,
                preferCloudFirst = item.localUri == null && item.oneDriveItemId != null,
                cloudVideoAggressive = true,
                lightweightPrefetch = item.localUri != null,
            )
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
                } ?: cacheFile?.let { file ->
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
        cacheFile != null -> {
            val request = remember(cacheFile, sizePx, item.canonicalPath) {
                ImageRequest.Builder(context)
                    .data(cacheFile)
                    .size(sizePx)
                    .allowHardware(false)
                    .memoryCacheKey("${item.canonicalPath}:gridvid:file:$sizePx")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = layerModifier,
                contentScale = contentScale,
            )
        }
        cloudKey != null && cacheFile == null -> {
            var cloudPosterVisible by remember(item.canonicalPath) { mutableStateOf(false) }
            val request = remember(cloudKey, sizePx, coilRetry) {
                ImageRequest.Builder(context)
                    .data(cloudKey)
                    .size(sizePx)
                    .applyGalleryThumbnailLoading(
                        style = style,
                        allowHardwareBitmap = false,
                        videoDecode = false,
                    )
                    .memoryCacheKey("${item.canonicalPath}:gridvid:cloud:$sizePx:$coilRetry")
                    .build()
            }
            Box(modifier = layerModifier) {
                if (!cloudPosterVisible) {
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
                                if (!cloudPosterVisible) {
                                    cloudPosterVisible = true
                                    cacheRevision++
                                }
                            }
                            is AsyncImagePainter.State.Error -> {
                                cloudPosterVisible = false
                                coilRetry++
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
