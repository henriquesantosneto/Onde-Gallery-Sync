package com.onegallery.sync.ui.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.onegallery.sync.data.local.MediaDeleteRequestRouter
import com.onegallery.sync.data.local.MediaKind

private val uriExistsRouter = MediaDeleteRequestRouter()

/** Original completo realmente abrível (Galeria, cache do app), alinhado ao [SyncCoordinator.needsOriginalRecall]. */
fun GalleryDisplayItem.hasViewableLocalOriginal(context: Context): Boolean {
    localUri?.let { uri ->
        if (uriExistsRouter.uriExists(context, uri)) return true
    }
    localMediaId?.let { id ->
        val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        if (uriExistsRouter.uriExists(context, imageUri)) return true
        val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        if (uriExistsRouter.uriExists(context, videoUri)) return true
    }
    val cached = cachedOriginal
    return cached?.isFile == true && cached.length() > 0L
}
