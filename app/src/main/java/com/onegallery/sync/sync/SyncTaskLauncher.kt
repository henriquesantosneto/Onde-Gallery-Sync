package com.onegallery.sync.sync

import com.onegallery.sync.OneGalleryApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Dispara sync/index fora do ciclo de vida da Compose (continua com app minimizado). */
class SyncTaskLauncher(
    private val scope: CoroutineScope,
    private val coordinator: SyncCoordinator,
) {
    fun runBidirectionalSync(userInitiated: Boolean = false) {
        scope.launch { coordinator.runBidirectionalSync(userInitiated = userInitiated) }
    }

    fun connectGraphSyncFolderAndSync(
        itemId: String,
        driveRelativePath: String,
        label: String,
        userInitiated: Boolean = true,
    ) {
        scope.launch {
            coordinator.connectGraphSyncFolder(itemId, driveRelativePath, label)
            coordinator.runBidirectionalSync(userInitiated = userInitiated)
        }
    }

    fun indexLocalLibrary(userInitiated: Boolean = false) {
        scope.launch { coordinator.indexLocalLibrary(userInitiated = userInitiated) }
    }

    fun refreshPhotoLocations(userInitiated: Boolean = true) {
        scope.launch { coordinator.refreshPhotoLocations(userInitiated = userInitiated) }
    }

    fun runIndexSanitation(userInitiated: Boolean = true) {
        scope.launch { coordinator.runIndexSanitation(userInitiated = userInitiated) }
    }

    fun clearStoredItemErrors(onDone: (Int) -> Unit = {}) {
        scope.launch {
            val count = coordinator.clearStoredItemErrors()
            onDone(count)
        }
    }

    companion object {
        fun from(app: OneGalleryApplication): SyncTaskLauncher =
            SyncTaskLauncher(app.applicationScope, app.syncCoordinator)
    }
}
