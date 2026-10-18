package com.onegallery.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.SyncCapability

/**
 * Worker reservado para sincronização periódica em segundo plano.
 * Ainda não agenda uploads/downloads — apenas indexação local quando habilitado.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!SyncCapability.bidirectionalSyncEnabled) {
            return Result.success()
        }
        val app = applicationContext as OneGalleryApplication
        return app.syncCoordinator.indexLocalLibrary()
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )
    }
}
