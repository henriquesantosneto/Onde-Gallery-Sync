package com.onegallery.sync.sync

/**
 * Feature flags da sincronização. Mantenha [bidirectionalSyncEnabled] false até que
 * upload, download, exclusão com confirmação e resolução de conflitos estejam testados.
 */
object SyncCapability {
    const val bidirectionalSyncEnabled = false

    const val NOT_ENABLED_MESSAGE =
        "Sincronização bidirecional ainda não está habilitada nesta versão alpha."
}
