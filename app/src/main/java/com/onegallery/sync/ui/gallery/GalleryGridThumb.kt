package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication

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
    val epoch by GalleryThumbRevisionBus.epoch.collectAsStateWithLifecycle()
    val revision = remember(epoch, item.canonicalPath) {
        GalleryThumbRevisionBus.revisionFor(item.canonicalPath)
    }
    val thumbFile = remember(item.canonicalPath, item.oneDriveItemId, revision) {
        gridPaintFile(item, mediaCache)
    }
    val localPhotoUri = if (thumbFile == null && item.isLocalPhotoOnDevice()) item.localUri else null
    val paintModel = thumbFile ?: localPhotoUri ?: return
    val cacheStamp = thumbFile?.lastModified() ?: 0L

    val request = remember(paintModel, sizePx, item.canonicalPath, cacheStamp, revision) {
        ImageRequest.Builder(context)
            .data(paintModel)
            .size(sizePx)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .memoryCacheKey("${item.canonicalPath}:grid:$sizePx:$cacheStamp:$revision")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.galleryGridThumbnailLayer(style),
        contentScale = contentScale,
    )
}
