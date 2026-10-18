package com.onegallery.sync.sync

object SyncPolicy {
    /** Pastas/nomes ignorados na indexação local (lixeira, rascunhos, etc.). */
    private val excludedPathFragments = listOf(
        "/.Trash/",
        "/Trash/",
        "/Lixeira/",
        "/.trash/",
        "/Android/data/com.onegallery.sync/cache/trash/",
    )

    private val excludedNamePrefixes = listOf(
        ".trashed-",
        ".pending-delete-",
    )

    fun shouldIncludeLocalItem(relativePath: String?, displayName: String): Boolean {
        val path = relativePath?.lowercase().orEmpty()
        val name = displayName.lowercase()
        if (excludedPathFragments.any { fragment -> path.contains(fragment.lowercase()) }) {
            return false
        }
        if (excludedNamePrefixes.any { prefix -> name.startsWith(prefix) }) {
            return false
        }
        return true
    }

    /**
     * Exclusões no OneDrive devem exigir confirmação explícita antes de propagar
     * para o dispositivo ou para a nuvem.
     */
    fun requiresDeleteConfirmation(
        @Suppress("UNUSED_PARAMETER") origin: DeleteOrigin,
        @Suppress("UNUSED_PARAMETER") target: DeleteTarget,
    ): Boolean = true

    /** Raiz lógica no OneDrive para espelhar todas as pastas de mídia. */
    const val ONEDRIVE_MIRROR_ROOT = "OneGallerySync"
}

enum class DeleteOrigin {
    USER,
    SYNC_ENGINE,
}

enum class DeleteTarget {
    LOCAL_ONLY,
    REMOTE_ONLY,
    REMOTE_AND_LOCAL,
}

enum class CloudSyncState {
    LOCAL_ONLY,
    CLOUD_ONLY,
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    CONFLICT,
    ERROR,
}
