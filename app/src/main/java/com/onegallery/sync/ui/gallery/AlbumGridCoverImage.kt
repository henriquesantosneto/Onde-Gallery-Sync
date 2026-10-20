package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.onegallery.sync.data.local.MediaAlbum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capa na grade de álbuns: ao entrar na rolagem, relê o disco e usa a mídia mais recente
 * que já tenha miniatura/URI local (sem esperar o snapshot global).
 */
@Composable
fun AlbumGridCoverImage(
    album: MediaAlbum,
    isPlace: Boolean,
    fallbackCover: Any?,
    coverRefreshEpoch: Long,
    resolveLiveCover: suspend (MediaAlbum) -> Any?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var displayModel by remember(album.bucketId) { mutableStateOf<Any?>(fallbackCover) }

    LaunchedEffect(album.bucketId, fallbackCover, coverRefreshEpoch) {
        displayModel = fallbackCover
        val live = withContext(Dispatchers.IO) { resolveLiveCover(album) }
        if (live != null) {
            displayModel = live
        }
    }

    if (displayModel != null) {
        AsyncImage(
            model = displayModel,
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(
            imageVector = if (isPlace) Icons.Outlined.Place else Icons.Outlined.PhotoAlbum,
            contentDescription = null,
            modifier = Modifier.padding(32.dp),
            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
