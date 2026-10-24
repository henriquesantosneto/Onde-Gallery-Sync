package com.onegallery.sync.ui.gallery

/**
 * Janela da grade: quantos [GalleryDisplayItem] entram no LazyGrid de uma vez.
 * Valores maiores = menos “recarregar” ao subir/descer, mais RAM de composição.
 */
object GalleryGridPagination {
    /** ~4–6 telas de fotos em grade média (antes 72). */
    const val initialLimit: Int = 280

    /** Itens adicionados ao chegar perto do fim da janela. */
    const val pageSize: Int = 240

    /** Quantos itens visíveis antes do fim da janela carregada disparam [GalleryViewModel.loadMoreGridItems]. */
    const val loadMoreVisibleTailMargin: Int = 48

    const val maxPersistedWindow: Int = 4_000
}
