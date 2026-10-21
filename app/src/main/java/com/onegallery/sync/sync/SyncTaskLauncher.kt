package com.onegallery.sync.sync

import com.onegallery.sync.OneGalleryApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Dispara sync/index fora do ciclo de vida da Compose (continua com app minimizado). */
class SyncTaskLauncher(
    private val scope: CoroutineScope,
    private val coordinator: SyncCoordinator,
) {
    private fun launchManaged(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        coordinator.registerManagedSyncJob(job)
    }

    fun runBidirectionalSync(userInitiated: Boolean = false) {
        launchManaged { coordinator.runBidirectionalSync(userInitiated = userInitiated) }
    }

    fun connectGraphSyncFolderAndSync(
        itemId: String,
        driveRelativePath: String,
        label: String,
        userInitiated: Boolean = true,
    ) {
        launchManaged {
            coordinator.connectGraphSyncFolder(itemId, driveRelativePath, label)
            coordinator.runBidirectionalSync(userInitiated = userInitiated)
        }
    }

    fun indexLocalLibrary(userInitiated: Boolean = false) {
        launchManaged { coordinator.indexLocalLibrary(userInitiated = userInitiated) }
    }

    fun refreshPhotoLocations(userInitiated: Boolean = true) {
        launchManaged { coordinator.refreshPhotoLocations(userInitiated = userInitiated) }
    }

    fun runIndexSanitation(userInitiated: Boolean = true) {
        launchManaged { coordinator.runIndexSanitation(userInitiated = userInitiated) }
    }

    /** Zera o índice local e inicia sync completo (não apaga mídia na galeria nem na nuvem). */
    fun resetSyncIndexAndResync(userInitiated: Boolean = true) {
        launchManaged {
            coordinator.resetSyncIndexForFreshStart(userInitiated = userInitiated).getOrElse {
                return@launchManaged
            }
            coordinator.runBidirectionalSync(userInitiated = userInitiated)
        }
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
