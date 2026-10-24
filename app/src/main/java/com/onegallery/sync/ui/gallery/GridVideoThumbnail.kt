package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.sync.CloudSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Miniatura de vídeo na grade — caminho direto (MediaStore → URI Coil → nuvem),
 * sem zero-decode nem estado frágil de [GalleryThumbnailImage].
 */
@Composable
fun GridVideoThumbnail(
    item: GalleryDisplayItem,
    contentDescription: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCache = remember(app) { app.mediaCacheRepository }
    val thumbCache = remember(app) { app.galleryThumbnailCache }

    var frameBitmap by remember(item.canonicalPath, sizePx) { mutableStateOf<Bitmap?>(null) }
    var coilRetry by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var cacheRevision by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var loading by remember(item.canonicalPath) { mutableStateOf(true) }

    val cacheFile = remember(item.canonicalPath, item.oneDriveItemId, cacheRevision) {
        mediaCache.galleryThumbnailResolved(
            item.canonicalPath,
            item.oneDriveItemId,
            requireVideoAcceptable = true,
        )
    }

    val cloudKey = remember(item.canonicalPath, item.oneDriveItemId, item.localUri) {
        GalleryThumbnailResolver.cloudVideoCoilKey(item, forFilmstrip = false)
    }

    LaunchedEffect(
        item.canonicalPath,
        item.localUri,
        item.oneDriveItemId,
        cacheFile?.absolutePath,
        sizePx,
        coilRetry,
    ) {
        loading = cacheFile == null && frameBitmap == null
        withContext(Dispatchers.IO) {
            if (cacheFile == null) {
                val quick = GalleryThumbnailModels.loadGridVideoBitmapFast(context, item, sizePx)
                if (quick != null) {
                    frameBitmap = quick
                    loading = false
                }
                thumbCache.ensureThumbnailCached(
                    item.canonicalPath,
                    preferCloudFirst = item.localUri == null && item.oneDriveItemId != null,
                    cloudVideoAggressive = true,
                    lightweightPrefetch = false,
                )
                if (
                    mediaCache.galleryThumbnailResolved(
                        item.canonicalPath,
                        item.oneDriveItemId,
                        requireVideoAcceptable = true,
                    ) != null
                ) {
                    cacheRevision++
                }
            }
        }
        loading = false
    }

    when {
        cacheFile != null -> {
            val request = remember(cacheFile, sizePx, item.canonicalPath) {
                ImageRequest.Builder(context)
                    .data(cacheFile)
                    .size(sizePx)
                    .allowHardware(false)
                    .allowRgb565(false)
                    .memoryCacheKey("${item.canonicalPath}:gridvid:file:$sizePx")
                    .diskCacheKey("${item.canonicalPath}:gridvid:$sizePx")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = modifier.galleryGridThumbnailLayer(GalleryThumbnailStyle.Grid),
                contentScale = contentScale,
            )
        }
        frameBitmap != null -> {
            Image(
                bitmap = frameBitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = modifier.galleryGridThumbnailLayer(GalleryThumbnailStyle.Grid),
                contentScale = contentScale,
            )
        }
        item.localUri != null -> {
            val uri = item.localUri
            val request = remember(uri, sizePx, item.canonicalPath, coilRetry) {
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(sizePx)
                    .applyGalleryThumbnailLoading(
                        style = GalleryThumbnailStyle.Grid,
                        allowHardwareBitmap = false,
                        videoDecode = true,
                    )
                    .memoryCacheKey("${item.canonicalPath}:gridvid:uri:$sizePx:$coilRetry")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = modifier.galleryGridThumbnailLayer(GalleryThumbnailStyle.Grid),
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        coilRetry++
                    }
                },
            )
        }
        cloudKey != null -> {
            val request = remember(cloudKey, sizePx, coilRetry) {
                ImageRequest.Builder(context)
                    .data(cloudKey)
                    .size(sizePx)
                    .applyGalleryThumbnailLoading(
                        style = GalleryThumbnailStyle.Grid,
                        allowHardwareBitmap = false,
                        videoDecode = false,
                    )
                    .memoryCacheKey("${item.canonicalPath}:gridvid:cloud:$sizePx:$coilRetry")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = modifier.galleryGridThumbnailLayer(GalleryThumbnailStyle.Grid),
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        coilRetry++
                    }
                },
            )
        }
        else -> {
            val cloudOnly = item.cloudState == CloudSyncState.CLOUD_ONLY ||
                item.cloudState == CloudSyncState.PENDING_DOWNLOAD
            MediaThumbnailPlaceholder(
                modifier = modifier,
                showCloudIcon = cloudOnly || !loading,
                showLoading = loading,
            )
        }
    }
}
