package com.onegallery.sync.sync

/** Miniaturas gravadas vs itens do índice que podem ter miniatura (foto/vídeo). */
data class ThumbnailCacheStats(
    val indexTotal: Int,
    val indexedEligible: Int,
    val indexedWithThumbnail: Int,
    val indexedPending: Int,
    /** Arquivos em `cache/thumbs` (pode incluir aliases; ≥ miniaturas indexadas). */
    val savedOnDisk: Int,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    fun summaryLine(): String {
        if (indexTotal == 0) return "Índice vazio — sincronize para indexar."
        if (indexedEligible == 0) return "$indexTotal no índice · nenhum item com miniatura gerável."
        return "Miniaturas: $indexedWithThumbnail / $indexedEligible no índice" +
            if (indexedPending > 0) " · $indexedPending pendente(s)" else " · galeria completa" +
            " · $savedOnDisk arquivo(s) em cache"
    }
}
