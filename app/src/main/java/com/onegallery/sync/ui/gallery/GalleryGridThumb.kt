package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.debug.DebugCrashLog

/**
 * Grade: só o JPEG já gravado, ou a foto que está no aparelho.
 * Não lê o arquivo inteiro nem abre vídeo enquanto a lista rola.
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
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCache = remember(app) { app.mediaCacheRepository }
    remember(item.canonicalPath) {
        DebugCrashLog.mark("celula ${item.kind} ${item.displayName}")
    }
    val epoch by GalleryThumbRevisionBus.epoch.collectAsStateWithLifecycle()
    val revision = remember(epoch, item.canonicalPath) {
        GalleryThumbRevisionBus.revisionFor(item.canonicalPath)
    }
    val thumbFile = remember(item.canonicalPath, item.oneDriveItemId, revision) {
        gridPaintFile(item, mediaCache)
    }
    val localPhotoUri = if (thumbFile == null && item.isLocalPhotoOnDevice()) item.localUri else null
    val paintModel = thumbFile?.let(::DiskThumb) ?: localPhotoUri ?: return
    val cacheStamp = thumbFile?.lastModified() ?: 0L
    var useGpu by remember(paintModel, sizePx, item.canonicalPath, cacheStamp, revision) {
        mutableStateOf(true)
    }
    val request = remember(paintModel, sizePx, item.canonicalPath, cacheStamp, revision, useGpu) {
        galleryGridImageRequest(
            context = context,
            data = paintModel,
            sizePx = sizePx,
            cacheKey = "${item.canonicalPath}:grid:$sizePx:$cacheStamp:$revision:" +
                if (useGpu) "gpu" else "cpu",
            useGpu = useGpu,
        )
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.galleryGridThumbnailLayer(style),
        contentScale = contentScale,
        onState = { painterState ->
            if (useGpu && painterState is AsyncImagePainter.State.Error) {
                useGpu = false
            }
        },
    )
}
