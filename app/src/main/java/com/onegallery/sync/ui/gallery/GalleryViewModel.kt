package com.onegallery.sync.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GalleryUiState(
    val isLoading: Boolean = true,
    val items: List<GalleryDisplayItem> = emptyList(),
    val albums: List<MediaAlbum> = emptyList(),
    val albumFilter: String? = null,
    val error: String? = null,
)

class GalleryViewModel(
    private val localMediaRepository: LocalMediaRepository,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    private val albumFilter = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(true)
    private val errorState = MutableStateFlow<String?>(null)

    val state: StateFlow<GalleryUiState> = combine(
        syncCoordinator.galleryMetadata,
        albumFilter,
        loading,
        errorState,
    ) { metadata, filter, isLoading, error ->
        val items = metadata
            .map { GalleryMapper.toDisplayItem(it) }
            .filter { filter == null || it.albumName == filter }
            .sortedByDescending { it.sortTimestamp }
        GalleryUiState(
            isLoading = isLoading,
            items = items,
            albums = buildAlbums(items),
            albumFilter = filter,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    init {
        refresh()
    }

    fun setAlbumFilter(album: String?) {
        albumFilter.value = album
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            errorState.value = null
            syncCoordinator.indexLocalLibrary()
                .onFailure { errorState.value = it.message }
            syncCoordinator.runBidirectionalSync()
                .onFailure {
                    if (errorState.value == null) {
                        errorState.value = it.message
                    }
                }
            loading.value = false
        }
    }
}

private fun buildAlbums(mirrored: List<GalleryDisplayItem>): List<MediaAlbum> {
    return mirrored.groupBy { it.albumName }.map { (title, items) ->
        MediaAlbum(
            bucketId = title,
            title = title,
            itemCount = items.size,
            coverUri = items.firstOrNull()?.localUri ?: items.firstOrNull()?.cachedOriginal?.let {
                android.net.Uri.fromFile(it)
            },
        )
    }.sortedByDescending { it.itemCount }
}

class GalleryViewModelFactory(
    private val localMediaRepository: LocalMediaRepository,
    private val syncCoordinator: SyncCoordinator,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(localMediaRepository, syncCoordinator) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
