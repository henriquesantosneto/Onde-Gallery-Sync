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
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capa na grade: ao aparecer na rolagem ou após revisão do álbum, relê disco (foto/vídeo).
 */
@Composable
fun AlbumGridCoverImage(
    album: MediaAlbum,
    isPlace: Boolean,
    fallbackCover: Any?,
    coverRevision: Long,
    resolveLiveCover: suspend (MediaAlbum) -> LiveAlbumCover,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaCacheRepository = remember(context) {
        (context.applicationContext as OneGalleryApplication).mediaCacheRepository
    }
    var displayModel by remember(album.bucketId) { mutableStateOf<Any?>(fallbackCover) }
    var coverIsVideo by remember(album.bucketId) { mutableStateOf(false) }
    var coverCacheKey by remember(album.bucketId) { mutableStateOf(album.bucketId) }
    var allowHardwareBitmap by remember(album.bucketId) { mutableStateOf(true) }

    LaunchedEffect(displayModel) {
        allowHardwareBitmap = true
    }

    LaunchedEffect(album.bucketId, coverRevision, fallbackCover) {
        val live = withContext(Dispatchers.IO) { resolveLiveCover(album) }
        val isVideo = live.sourceItem?.kind == MediaKind.VIDEO
        val resolved = withContext(Dispatchers.IO) {
            live.sourceItem?.let { item ->
                resolveGalleryCoverModel(item, mediaCacheRepository)
            } ?: live.displayModel
        }
        val cacheKey = live.sourceItem?.canonicalPath ?: album.bucketId
        when {
            resolved != null -> {
                displayModel = resolved
                coverIsVideo = isVideo
                coverCacheKey = cacheKey
            }
            fallbackCover != null -> {
                displayModel = fallbackCover
                coverIsVideo = false
                coverCacheKey = album.bucketId
            }
            else -> {
                displayModel = null
                coverIsVideo = false
                coverCacheKey = album.bucketId
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (displayModel != null) {
            val coverSizePx = 480
            val request = remember(displayModel, coverCacheKey, coverRevision, allowHardwareBitmap) {
                val paint = (displayModel as? java.io.File)?.asGalleryImageModel() ?: displayModel
                ImageRequest.Builder(context)
                    .data(paint)
                    .size(coverSizePx)
                    .applyGalleryThumbnailLoading(GalleryThumbnailStyle.Grid, allowHardwareBitmap)
                    .memoryCacheKey(
                        galleryThumbnailMemoryCacheKey(
                            "album-cover:$coverCacheKey:$coverRevision",
                            coverSizePx,
                            GalleryThumbnailStyle.Grid,
                            allowHardwareBitmap,
                        ),
                    )
                    .diskCacheKey("album-cover:$coverCacheKey:$coverRevision")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .galleryGridThumbnailLayer(GalleryThumbnailStyle.Grid),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error && allowHardwareBitmap) {
                        allowHardwareBitmap = false
                    }
                },
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
