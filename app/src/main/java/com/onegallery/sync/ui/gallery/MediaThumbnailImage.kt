package com.onegallery.sync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.content.Context
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
    cachedOriginal?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
    localUri?.let { return it }
    cachedPlaceholderThumb?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
    return when (val model = thumbnailModel) {
        is File -> model.takeIf { it.isFile && it.length() > 0L }
        else -> model
    }
}

/** Detalhe: não usa URI fantasma; miniatura só enquanto o original não está no aparelho. */
fun GalleryDisplayItem.detailPreviewModel(context: Context): Any? {
    cachedOriginal?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
    if (hasViewableLocalOriginal(context)) {
        localUri?.let { return it }
    }
    if (!oneDriveItemId.isNullOrBlank() && !hasViewableLocalOriginal(context)) {
        cachedPlaceholderThumb?.takeIf { it.isFile && it.length() > 0L }?.let { return it }
        return when (val model = thumbnailModel) {
            is File -> model.takeIf { it.isFile && it.length() > 0L }
            else -> model
        }
    }
    return detailPreviewModel()
}

/** Foto em tela cheia: URI/arquivo local preferido; senão o mesmo modelo da grade. */
fun GalleryDisplayItem.detailFullImageModel(): Any? {
    detailPreviewModel()?.let { return it }
    playbackUri()?.let { return it }
    return null
}

fun GalleryDisplayItem.detailFullImageModel(context: Context): Any? {
    detailPreviewModel(context)?.let { return it }
    if (hasViewableLocalOriginal(context)) {
        playbackUri()?.let { return it }
    }
    return null
}

fun GalleryDisplayItem.detailImageUsesThumbnailPipeline(model: Any?): Boolean {
    if (model == null) return true
    return model !is android.net.Uri && model !is File
}

fun GalleryDisplayItem.withCachedThumb(file: File): GalleryDisplayItem =
    copy(cachedPlaceholderThumb = file)
