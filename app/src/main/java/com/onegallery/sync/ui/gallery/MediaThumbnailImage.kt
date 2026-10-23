package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
fun MediaThumbnailImage(
    item: GalleryDisplayItem,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onContentAspectRatio: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val sizePx = remember(context) {
        (context.resources.displayMetrics.widthPixels / 2).coerceIn(320, 720)
    }
    GalleryThumbnailImage(
        item = item,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        style = GalleryThumbnailStyle.Detail,
        sizePx = sizePx,
        onContentAspectRatio = onContentAspectRatio,
    )
}

fun GalleryDisplayItem.playbackUri(): android.net.Uri? {
    localUri?.let { return it }
    cachedOriginal?.takeIf { it.exists() }?.let { return android.net.Uri.fromFile(it) }
    return null
}

fun GalleryDisplayItem.detailPreviewModel(): Any? {
    cachedOriginal?.takeIf { it.exists() }?.let { return it }
    return thumbnailModel
}

fun GalleryDisplayItem.withCachedThumb(file: File): GalleryDisplayItem =
    copy(cachedPlaceholderThumb = file)
