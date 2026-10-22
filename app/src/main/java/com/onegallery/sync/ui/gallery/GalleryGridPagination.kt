package com.onegallery.sync.ui.gallery

/** Janela inicial da grade (timeline + células) para não montar dezenas de milhares de entradas de uma vez. */
object GalleryGridPagination {
    const val initialLimit: Int = 200
    const val pageSize: Int = 200

    /** Quantos itens visíveis antes do fim da janela carregada disparam [GalleryViewModel.loadMoreGridItems]. */
    const val loadMoreVisibleTailMargin: Int = 24
}
