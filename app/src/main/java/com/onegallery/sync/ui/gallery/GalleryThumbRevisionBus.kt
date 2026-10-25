package com.onegallery.sync.ui.gallery

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Miniatura gravada no disco — a grade atualiza só a célula, sem remontar o catálogo. */
object GalleryThumbRevisionBus {
    private val _commits = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val commits: SharedFlow<String> = _commits.asSharedFlow()

    fun onThumbnailCommitted(canonicalPath: String) {
        if (canonicalPath.isBlank()) return
        _commits.tryEmit(canonicalPath)
    }
}
