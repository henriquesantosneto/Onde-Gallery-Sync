package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication

/** Grade: só pinta o JPEG que o sincronismo já gravou. Sem rede e sem decode na rolagem. */
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
    var revision by remember(item.canonicalPath) { mutableIntStateOf(0) }

    LaunchedEffect(item.canonicalPath) {
        GalleryThumbRevisionBus.commits.collect { path ->
            if (path == item.canonicalPath) revision++
        }
    }

    val thumbFile = remember(item.canonicalPath, item.oneDriveItemId, revision) {
        GalleryThumbStore.displayFile(item, mediaCache)
    } ?: return

    val request = remember(thumbFile, sizePx, item.canonicalPath) {
        ImageRequest.Builder(context)
            .data(thumbFile)
            .size(sizePx)
            .allowHardware(false)
            .memoryCacheKey("${item.canonicalPath}:grid:disk:$sizePx")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.galleryGridThumbnailLayer(style),
        contentScale = contentScale,
    )
}
