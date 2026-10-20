package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capa na grade de álbuns: relê miniaturas locais ao rolar ou após lote de thumbs (sem piscar).
 */
@Composable
fun AlbumGridCoverImage(
    album: MediaAlbum,
    isPlace: Boolean,
    fallbackCover: Any?,
    coverRefreshEpoch: Long,
    resolveLiveCover: suspend (MediaAlbum) -> LiveAlbumCover,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var displayModel by remember(album.bucketId) { mutableStateOf<Any?>(fallbackCover) }
    var coverIsVideo by remember(album.bucketId) { mutableStateOf(false) }
    var coverCacheKey by remember(album.bucketId) { mutableStateOf(album.bucketId) }
    var lastAppliedRefreshEpoch by remember(album.bucketId) { mutableLongStateOf(0L) }

    LaunchedEffect(album.bucketId, fallbackCover) {
        if (displayModel == null && fallbackCover != null) {
            displayModel = fallbackCover
        }
    }

    LaunchedEffect(album.bucketId, coverRefreshEpoch) {
        if (coverRefreshEpoch == 0L || coverRefreshEpoch == lastAppliedRefreshEpoch) return@LaunchedEffect
        lastAppliedRefreshEpoch = coverRefreshEpoch
        applyLiveAlbumCover(
            album = album,
            resolveLiveCover = resolveLiveCover,
            context = context,
            onResult = { model, isVideo, cacheKey ->
                if (model != null) {
                    displayModel = model
                    coverIsVideo = isVideo
                    coverCacheKey = cacheKey
                }
            },
        )
    }

    LaunchedEffect(album.bucketId) {
        if (displayModel != null) return@LaunchedEffect
        applyLiveAlbumCover(
            album = album,
            resolveLiveCover = resolveLiveCover,
            context = context,
            onResult = { model, isVideo, cacheKey ->
                if (model != null) {
                    displayModel = model
                    coverIsVideo = isVideo
                    coverCacheKey = cacheKey
                } else if (fallbackCover != null) {
                    displayModel = fallbackCover
                }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (displayModel != null) {
            val request = remember(displayModel, coverCacheKey) {
                ImageRequest.Builder(context)
                    .data(displayModel)
                    .memoryCacheKey("album-cover:$coverCacheKey")
                    .diskCacheKey("album-cover:$coverCacheKey")
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (coverIsVideo) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(22.dp),
                )
            }
        } else {
            Icon(
                imageVector = if (isPlace) Icons.Outlined.Place else Icons.Outlined.PhotoAlbum,
                contentDescription = null,
                modifier = Modifier.padding(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun applyLiveAlbumCover(
    album: MediaAlbum,
    resolveLiveCover: suspend (MediaAlbum) -> LiveAlbumCover,
    context: android.content.Context,
    onResult: (model: Any?, isVideo: Boolean, cacheKey: String) -> Unit,
) {
    val live = withContext(Dispatchers.IO) { resolveLiveCover(album) }
    val isVideo = live.sourceItem?.kind == MediaKind.VIDEO
    val resolved = withContext(Dispatchers.IO) {
        live.sourceItem?.let { item ->
            GalleryThumbnailModels.resolveGridModel(context, item, 480)
        } ?: live.displayModel
    }
    val cacheKey = live.sourceItem?.canonicalPath ?: album.bucketId
    onResult(resolved, isVideo, cacheKey)
}
