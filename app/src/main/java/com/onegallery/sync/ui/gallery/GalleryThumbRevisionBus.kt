package com.onegallery.sync.ui.gallery

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Miniatura gravada no disco. A revisão fica guardada por caminho: a célula
 * que já está na tela vê o arquivo novo, igual à capa do álbum.
 */
object GalleryThumbRevisionBus {
    private val revisions = ConcurrentHashMap<String, Int>()
    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    fun onThumbnailCommitted(canonicalPath: String) {
        if (canonicalPath.isBlank()) return
        revisions.merge(canonicalPath, 1, Int::plus)
        _epoch.value = _epoch.value + 1
    }

    fun revisionFor(canonicalPath: String): Int = revisions[canonicalPath] ?: 0
}
