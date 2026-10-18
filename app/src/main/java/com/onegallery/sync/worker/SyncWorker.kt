package com.onegallery.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.onegallery.sync.OneGalleryApplication

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as OneGalleryApplication
        return app.syncCoordinator.runBidirectionalSync()
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    if (it.message?.contains("Offline", ignoreCase = true) == true ||
                        it.message?.contains("Wi‑Fi", ignoreCase = true) == true
                    ) {
                        Result.success()
                    } else {
                        Result.retry()
                    }
                },
            )
    }

    companion object {
        const val UNIQUE_NAME = "onegallery_bidirectional_sync"
    }
}
