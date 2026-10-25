package com.onegallery.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.R
import com.onegallery.sync.sync.SyncForegroundNotifications
import kotlinx.coroutines.CancellationException

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
        if (inputData.getBoolean(KEY_THUMBNAILS_ONLY, false)) {
            return runThumbnailContinuation(app)
        }
        val userInitiated = inputData.getBoolean(KEY_USER_INITIATED, false)
        app.syncCoordinator.setWorkerForegroundSession(true)
        return try {
            runCatching {
                setForeground(
                    SyncForegroundNotifications.foregroundInfo(
                        applicationContext,
                        applicationContext.getString(R.string.sync_notification_message),
                    ),
                )
            }.onFailure { error ->
                android.util.Log.e("SyncWorker", "setForeground failed; sync may stop if minimized", error)
            }
            doWorkBody(app, userInitiated)
        } catch (cancelled: CancellationException) {
            Result.success()
        } finally {
            app.syncCoordinator.setWorkerForegroundSession(false)
        }
    }

    private suspend fun doWorkBody(app: OneGalleryApplication, userInitiated: Boolean): Result {
        val prefs = app.syncPreferences
        if (!userInitiated && app.syncCoordinator.state.value.isRunning) {
            runAutoReleaseOnly(app)
            return Result.success()
        }
        if (!userInitiated && !prefs.backgroundSyncAlwaysEnabled) {
            runAutoReleaseOnly(app)
            return Result.success()
        }
        if (!userInitiated && prefs.isAutoSyncPaused()) {
            runAutoReleaseOnly(app)
            return Result.success()
        }
        app.syncCoordinator.refreshPendingTransferCache()
        var syncResult: Result = Result.success()
        if (userInitiated || !app.syncCoordinator.shouldDeferAutomaticBidirectionalSync()) {
            syncResult = app.syncCoordinator.runBidirectionalSync(userInitiated = userInitiated)
                .fold(
                    onSuccess = { Result.success() },
                    onFailure = {
                        when {
                            it is CancellationException -> Result.success()
                            it.message?.contains("Offline", ignoreCase = true) == true ||
                                it.message?.contains("Wi‑Fi", ignoreCase = true) == true ||
                                it.message?.contains("pausa", ignoreCase = true) == true ||
                                it.message?.contains("cancel", ignoreCase = true) == true ->
                                Result.success()
                            else -> Result.retry()
                        }
                    },
                )
            // O laço em processo já agenda a continuação durável. Não esperar o laço
            // inteiro aqui: o sistema mata o worker e o processo junto.
            if (app.syncCoordinator.shouldPersistThumbnailContinuation()) {
                BackgroundSyncScheduler.enqueueThumbnailContinuation(app, prefs)
            }
        } else {
            BackgroundSyncScheduler.enqueueDeferredAutoSync(app, prefs)
            continuePendingThumbnails(app, runningSlot = null)
        }
        runAutoReleaseOnly(app)
        return syncResult
    }

    /**
     * Leva curta de miniaturas. Sobrevive à morte do processo porque agenda a próxima
     * antes de terminar — sem passar pelo cooldown do sync completo.
     */
    private suspend fun runThumbnailContinuation(app: OneGalleryApplication): Result {
        val prefs = app.syncPreferences
        val slot = inputData.getString(KEY_CONTINUATION_SLOT)
        if (!app.syncCoordinator.shouldPersistThumbnailContinuation()) {
            return Result.success()
        }
        app.syncCoordinator.setWorkerForegroundSession(true)
        return try {
            runCatching {
                setForeground(
                    SyncForegroundNotifications.foregroundInfo(
                        applicationContext,
                        applicationContext.getString(R.string.sync_notification_thumbnails_message),
                    ),
                )
            }.onFailure { error ->
                android.util.Log.e("SyncWorker", "setForeground failed; thumbnails may stop if minimized", error)
            }
            continuePendingThumbnails(app, runningSlot = slot)
        } catch (cancelled: CancellationException) {
            if (app.syncCoordinator.shouldPersistThumbnailContinuation()) {
                BackgroundSyncScheduler.enqueueThumbnailContinuation(
                    context = app,
                    prefs = prefs,
                    delaySeconds = ThumbnailContinuationPolicy.BUSY_DELAY_SECONDS,
                    runningSlot = slot,
                )
            }
            Result.success()
        } finally {
            app.syncCoordinator.setWorkerForegroundSession(false)
        }
    }

    private suspend fun continuePendingThumbnails(
        app: OneGalleryApplication,
        runningSlot: String?,
    ): Result {
        val prefs = app.syncPreferences
        if (!app.syncCoordinator.shouldPersistThumbnailContinuation()) {
            return Result.success()
        }
        val outcome = app.syncCoordinator.runScheduledThumbnailContinuation()
        if (ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = outcome.moreRemain,
                setupComplete = prefs.hasCompletedSetup,
                backgroundEnabled = prefs.backgroundSyncAlwaysEnabled,
                paused = prefs.isAutoSyncPaused(),
                userCancelled = !app.syncCoordinator.shouldPersistThumbnailContinuation(),
            )
        ) {
            BackgroundSyncScheduler.enqueueThumbnailContinuation(
                context = app,
                prefs = prefs,
                delaySeconds = ThumbnailContinuationPolicy.delaySeconds(
                    stalled = outcome.stalled,
                    busy = outcome.busy,
                ),
                runningSlot = runningSlot,
            )
        }
        return Result.success()
    }

    private suspend fun runAutoReleaseOnly(app: OneGalleryApplication) {
        runCatching { app.syncCoordinator.runAutoReleaseIfNeeded() }
    }

    companion object {
        const val UNIQUE_NAME = "onegallery_bidirectional_sync"
        const val KEY_USER_INITIATED = "user_initiated"
        const val KEY_THUMBNAILS_ONLY = "thumbnails_only"
        const val KEY_CONTINUATION_SLOT = "continuation_slot"

        fun input(userInitiated: Boolean): Data =
            Data.Builder().putBoolean(KEY_USER_INITIATED, userInitiated).build()

        fun thumbnailContinuationInput(slot: String): Data =
            Data.Builder()
                .putBoolean(KEY_USER_INITIATED, false)
                .putBoolean(KEY_THUMBNAILS_ONLY, true)
                .putString(KEY_CONTINUATION_SLOT, slot)
                .build()
    }
}
