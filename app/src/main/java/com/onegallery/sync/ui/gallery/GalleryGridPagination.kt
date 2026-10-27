package com.onegallery.sync.ui.gallery

/**
 * A grade não monta a biblioteca inteira de uma vez.
 * A primeira pintura já traz algumas páginas, e a lista parada pede a próxima
 * enquanto ainda há uma folga abaixo da tela — a rolagem não espera a página nova.
 */
object GalleryGridPagination {
    const val pageSize: Int = 160
    const val initialPages: Int = 4
    const val initialLimit: Int = pageSize * initialPages

    /** Itens já montados abaixo da tela antes de pedir outra página. */
    const val preloadTailSlots: Int = pageSize * 3

    fun needsAnotherPage(slotCount: Int, lastVisibleIndex: Int): Boolean {
        if (slotCount <= 0) return false
        val tail = slotCount - 1 - lastVisibleIndex.coerceAtLeast(0)
        return tail <= preloadTailSlots
    }
}
