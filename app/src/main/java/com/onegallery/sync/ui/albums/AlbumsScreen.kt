package com.onegallery.sync.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.ui.gallery.AlbumListSort
import com.onegallery.sync.ui.gallery.GalleryPreferences
import com.onegallery.sync.ui.gallery.GalleryViewModel
import com.onegallery.sync.ui.gallery.gridAdaptiveMinSize
import com.onegallery.sync.ui.gallery.rememberGalleryGridZoom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    viewModel: GalleryViewModel,
    galleryPreferences: GalleryPreferences,
    onOpenAlbum: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val (gridZoom, pinchModifier) = rememberGalleryGridZoom(galleryPreferences.albumGridZoom) { scale ->
        galleryPreferences.albumGridZoom = scale
    }
    val albumCellMin = gridAdaptiveMinSize(baseMinDp = 156f, zoomScale = gridZoom)
    val compactTiles = albumCellMin < 112.dp
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Álbuns") },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Outlined.Sort, contentDescription = "Ordenar álbuns")
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        AlbumListSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label) },
                                onClick = {
                                    viewModel.setAlbumListSort(sort)
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Novo álbum")
            }
        },
    ) { padding ->
        when {
            state.albums.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Nenhum álbum ainda. Sincronize ou crie um álbum.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = albumCellMin),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .then(pinchModifier),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.albums, key = { it.bucketId }) { album ->
                        AlbumGridTile(
                            album = album,
                            compact = compactTiles,
                            onClick = {
                                viewModel.setAlbumFilter(album.title)
                                onOpenAlbum(album.title)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Novo álbum") },
            text = {
                Column {
                    Text(
                        "Álbuns personalizados organizam a galeria; o OneDrive mantém a pasta original.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it },
                        label = { Text("Nome") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newAlbumName.isNotBlank()) {
                            viewModel.createUserAlbum(newAlbumName.trim())
                            newAlbumName = ""
                            showCreateDialog = false
                        }
                    },
                ) { Text("Criar") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun AlbumGridTile(
    album: MediaAlbum,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(if (compact) 10.dp else 16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (album.coverUri != null) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Outlined.Collections,
                    contentDescription = null,
                    modifier = Modifier.padding(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!compact) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            Text(
                text = "${album.itemCount} ${if (album.itemCount == 1) "item" else "itens"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                text = album.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    }
}
