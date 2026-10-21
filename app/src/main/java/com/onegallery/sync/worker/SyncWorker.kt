package com.onegallery.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.onegallery.sync.OneGalleryApplication

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as OneGalleryApplication
        val prefs = app.syncPreferences
        if (!prefs.hasCompletedSetup) {
            return Result.success()
        }
        val userInitiated = inputData.getBoolean(KEY_USER_INITIATED, false)
        if (!userInitiated && !prefs.backgroundSyncAlwaysEnabled) {
            runAutoReleaseOnly(app)
            return Result.success()
        }
        if (!userInitiated && prefs.isAutoSyncPaused()) {
            runAutoReleaseOnly(app)
            return Result.success()
        }
        var syncResult: Result = Result.success()
        if (userInitiated || !app.syncCoordinator.shouldDeferAutomaticBidirectionalSync()) {
            syncResult = app.syncCoordinator.runBidirectionalSync(userInitiated = userInitiated)
                .fold(
                    onSuccess = { Result.success() },
                    onFailure = {
                        if (it.message?.contains("Offline", ignoreCase = true) == true ||
                            it.message?.contains("Wi‑Fi", ignoreCase = true) == true ||
                            it.message?.contains("pausa", ignoreCase = true) == true
                        ) {
                            Result.success()
                        } else {
                            Result.retry()
                        }
                    },
                )
        }
        runAutoReleaseOnly(app)
        return syncResult
    }

    private suspend fun runAutoReleaseOnly(app: OneGalleryApplication) {
        runCatching { app.syncCoordinator.runAutoReleaseIfNeeded() }
    }

    companion object {
        const val UNIQUE_NAME = "onegallery_bidirectional_sync"
        const val KEY_USER_INITIATED = "user_initiated"

        fun input(userInitiated: Boolean): Data =
            Data.Builder().putBoolean(KEY_USER_INITIATED, userInitiated).build()
    }
}
