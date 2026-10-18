package com.onegallery.sync.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GalleryUiState(
    val isLoading: Boolean = true,
    val items: List<LocalMediaItem> = emptyList(),
    val albums: List<MediaAlbum> = emptyList(),
    val error: String? = null,
)

class GalleryViewModel(
    private val localMediaRepository: LocalMediaRepository,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching {
                syncCoordinator.indexLocalLibrary()
                val items = localMediaRepository.loadAllMedia()
                val albums = localMediaRepository.loadAlbums()
                _state.value = GalleryUiState(
                    isLoading = false,
                    items = items,
                    albums = albums,
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = it.message,
                )
            }
        }
    }
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
