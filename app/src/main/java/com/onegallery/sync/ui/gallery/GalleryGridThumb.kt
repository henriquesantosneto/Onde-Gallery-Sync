package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import kotlinx.coroutines.delay

/** Grade: JPEG do sync, foto local, ou poster do MediaStore. Sem rede e sem vídeo no Coil. */
@Composable
fun GalleryGridThumb(
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
    val epoch by GalleryThumbRevisionBus.epoch.collectAsStateWithLifecycle()
    val revision = remember(epoch, item.canonicalPath) {
        GalleryThumbRevisionBus.revisionFor(item.canonicalPath)
    }
    val thumbFile = remember(item.canonicalPath, item.oneDriveItemId, revision) {
        GalleryThumbStore.displayFile(item, mediaCache)
    }
    val localPhotoUri = if (thumbFile == null && item.isLocalPhotoOnDevice()) item.localUri else null
    var devicePoster by remember(item.canonicalPath) { mutableStateOf<Bitmap?>(null) }
    val wantsDevicePoster = thumbFile == null &&
        localPhotoUri == null &&
        item.hasLocalVideoCoverOnDevice()

    LaunchedEffect(item.canonicalPath, item.localUri, wantsDevicePoster, sizePx) {
        if (!wantsDevicePoster) {
            devicePoster = null
            return@LaunchedEffect
        }
        val uri = item.localUri ?: return@LaunchedEffect
        while (GalleryGridScrollGate.isUserScrollingGrid) {
            delay(64)
        }
        devicePoster = GalleryThumbnailModels.loadDeviceVideoPoster(
            context,
            uri,
            item.localMediaId,
            sizePx,
        )
    }

    val fileModel = remember(
        thumbFile?.absolutePath,
        thumbFile?.lastModified(),
        thumbFile?.length(),
    ) {
        thumbFile?.asGalleryImageModel()
    }
    val paintModel = fileModel ?: devicePoster ?: localPhotoUri ?: return
    val cacheStamp = thumbFile?.lastModified() ?: revision.toLong()

    val request = remember(paintModel, sizePx, item.canonicalPath, cacheStamp) {
        ImageRequest.Builder(context)
            .data(paintModel)
            .size(sizePx)
            .allowHardware(false)
            .memoryCacheKey("${item.canonicalPath}:grid:disk:$sizePx:$cacheStamp")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.galleryGridThumbnailLayer(style),
        contentScale = contentScale,
    )
}
