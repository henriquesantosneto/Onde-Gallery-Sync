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
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capa na grade de álbuns: ao entrar na rolagem, relê o disco — foto ou **vídeo** com miniatura
 * local / frame do MediaStore (mesma ideia da grade interna).
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

    LaunchedEffect(album.bucketId, fallbackCover, coverRefreshEpoch) {
        displayModel = fallbackCover
        coverIsVideo = false
        val live = withContext(Dispatchers.IO) { resolveLiveCover(album) }
        coverIsVideo = live.sourceItem?.kind == MediaKind.VIDEO
        val resolved = withContext(Dispatchers.IO) {
            live.sourceItem?.let { item ->
                GalleryThumbnailModels.resolveGridModel(context, item, 480)
            } ?: live.displayModel
        }
        if (resolved != null) {
            displayModel = resolved
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (displayModel != null) {
            AsyncImage(
                model = displayModel,
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
