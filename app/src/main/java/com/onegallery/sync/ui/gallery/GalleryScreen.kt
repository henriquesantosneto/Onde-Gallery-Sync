package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onOpenItem: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = state.albumFilter?.let { "Álbum: $it" } ?: "Galeria espelhada"

    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }) },
    ) { padding ->
        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.error != null && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text(state.error ?: "Erro") }
            }
            state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text("Nenhuma mídia indexada. Conceda permissão e sincronize.") }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.items, key = { it.canonicalPath }) { item ->
                        MediaThumbnail(
                            item = item,
                            onClick = {
                                val encoded = URLEncoder.encode(item.canonicalPath, StandardCharsets.UTF_8.name())
                                onOpenItem(encoded)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(item: GalleryDisplayItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        if (item.thumbnailModel != null) {
            AsyncImage(
                model = item.thumbnailModel,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
            }
        }
        CloudBadge(item.cloudState, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        if (item.kind == MediaKind.VIDEO) {
            Text(
                "Vídeo",
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!item.isOfflineAvailable && item.cloudState == CloudSyncState.CLOUD_ONLY) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = "Somente nuvem",
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun CloudBadge(state: CloudSyncState, modifier: Modifier = Modifier) {
    val icon = when (state) {
        CloudSyncState.SYNCED -> Icons.Outlined.CloudDone
        CloudSyncState.CLOUD_ONLY, CloudSyncState.PENDING_DOWNLOAD -> Icons.Outlined.CloudDownload
        CloudSyncState.PENDING_UPLOAD -> Icons.Outlined.Cloud
        else -> Icons.Outlined.Cloud
    }
    Icon(icon, contentDescription = state.name, modifier = modifier)
}
