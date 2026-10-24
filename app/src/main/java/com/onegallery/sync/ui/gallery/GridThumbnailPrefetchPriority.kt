package com.onegallery.sync.ui.gallery

/** Menor valor = maior prioridade na fila de prefetch da grade. */
object GridThumbnailPrefetchPriority {
    const val VISIBLE = 0
    const val AHEAD = 1
    const val WARMUP = 2
}
