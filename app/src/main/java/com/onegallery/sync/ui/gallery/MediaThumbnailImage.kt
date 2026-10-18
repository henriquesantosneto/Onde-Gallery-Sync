package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.onegallery.sync.sync.SyncCoordinator
import java.io.File

@Composable
fun MediaThumbnailImage(
    item: GalleryDisplayItem,
    syncCoordinator: SyncCoordinator?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var model by remember(item.canonicalPath) { mutableStateOf<Any?>(item.thumbnailModel) }

    LaunchedEffect(item.canonicalPath, item.thumbnailModel, item.oneDriveItemId) {
        if (model != null) return@LaunchedEffect
        val coordinator = syncCoordinator ?: return@LaunchedEffect
        coordinator.ensureThumbnailCached(item.canonicalPath)
            .getOrNull()
            ?.let { model = it }
    }

    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CloudDownload, contentDescription = null)
        }
    }
}

fun GalleryDisplayItem.playbackUri(): android.net.Uri? {
    localUri?.let { return it }
    cachedOriginal?.takeIf { it.exists() }?.let { return android.net.Uri.fromFile(it) }
    return null
}

fun GalleryDisplayItem.detailPreviewModel(): Any? = thumbnailModel

fun GalleryDisplayItem.withCachedThumb(file: File): GalleryDisplayItem =
    copy(cachedPlaceholderThumb = file)
