package com.onegallery.sync.sync

/** Etapa visível na aba Sync enquanto [SyncUiState.isRunning]. */
enum class SyncProgressPhase {
    IDLE,
    PREPARING,
    INDEXING_LOCAL,
    LISTING_REMOTE,
    RECONCILING_LOCAL,
    REGISTERING_CLOUD_ONLY,
    BACKFILL_GPS,
    BACKFILL_CAPTURE_DATE,
    PREFETCH_THUMBNAILS,
    SANITIZING_INDEX,
    FINISHING,
}

fun SyncProgressPhase.runningHeadlineFallback(): String = when (this) {
    SyncProgressPhase.IDLE -> "Pronto para sincronizar."
    SyncProgressPhase.PREPARING -> "Preparando sincronismo…"
    SyncProgressPhase.INDEXING_LOCAL -> "Indexando biblioteca do aparelho…"
    SyncProgressPhase.LISTING_REMOTE -> "Listando pasta no OneDrive…"
    SyncProgressPhase.RECONCILING_LOCAL -> "Comparando aparelho e nuvem…"
    SyncProgressPhase.REGISTERING_CLOUD_ONLY -> "Registrando itens só na nuvem…"
    SyncProgressPhase.BACKFILL_GPS -> "Indexando localização (GPS)…"
    SyncProgressPhase.BACKFILL_CAPTURE_DATE -> "Indexando data de captura…"
    SyncProgressPhase.PREFETCH_THUMBNAILS -> "Baixando miniaturas da nuvem…"
    SyncProgressPhase.SANITIZING_INDEX -> "Verificando consistência do índice…"
    SyncProgressPhase.FINISHING -> "Finalizando…"
}
