package com.onegallery.sync.sync

import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.worker.BackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A UI só agenda trabalho (WorkManager) ou ações pontuais de índice;
 * sync bidirecional e liberação automática rodam no [com.onegallery.sync.worker.SyncWorker].
 */
class SyncTaskLauncher(
    private val app: OneGalleryApplication,
    private val coordinator: SyncCoordinator,
) {
    private val scope = app.applicationScope

    private fun launchManaged(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        coordinator.registerManagedSyncJob(job)
    }

    fun runBidirectionalSync(userInitiated: Boolean = false) {
        val prefs = app.syncPreferences
        if (userInitiated && app.appForegroundState.isInForeground()) {
            launchManaged { coordinator.runBidirectionalSync(userInitiated = true) }
            return
        }
        if (userInitiated) {
            BackgroundSyncScheduler.enqueueUserRequestedSync(app, prefs)
        } else {
            BackgroundSyncScheduler.enqueueSyncSoon(app, prefs, delaySeconds = 0L)
        }
    }

    fun connectGraphSyncFolderAndSync(
        itemId: String,
        driveRelativePath: String,
        label: String,
        userInitiated: Boolean = true,
    ) {
        launchManaged {
            coordinator.connectGraphSyncFolder(itemId, driveRelativePath, label)
            if (userInitiated) {
                BackgroundSyncScheduler.enqueueUserRequestedSync(app, app.syncPreferences)
            } else {
                BackgroundSyncScheduler.enqueueSyncSoon(app, app.syncPreferences, delaySeconds = 0L)
            }
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
            if (userInitiated) {
                BackgroundSyncScheduler.enqueueUserRequestedSync(app, app.syncPreferences)
            } else {
                BackgroundSyncScheduler.enqueueSyncSoon(app, app.syncPreferences, delaySeconds = 0L)
            }
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
            SyncTaskLauncher(app, app.syncCoordinator)
    }
}
