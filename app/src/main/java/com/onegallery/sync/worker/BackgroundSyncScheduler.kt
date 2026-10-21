package com.onegallery.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.SyncPreferences
import java.util.concurrent.TimeUnit

/** Agenda sync periódico, pós-boot e reativo (rede / MediaStore). */
object BackgroundSyncScheduler {

    const val IMMEDIATE_UNIQUE = "onegallery_bidirectional_sync_now"

    fun syncConstraints(prefs: SyncPreferences): Constraints {
        val networkType = if (prefs.wifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }

    /** Liga ou desliga política “sempre em segundo plano” (WorkManager + observador). */
    fun applyAlwaysOnPolicy(app: OneGalleryApplication) {
        val prefs = app.syncPreferences
        if (!prefs.backgroundSyncAlwaysEnabled || !prefs.hasCompletedSetup) {
            WorkManager.getInstance(app).cancelUniqueWork(SyncWorker.UNIQUE_NAME)
            WorkManager.getInstance(app).cancelUniqueWork(IMMEDIATE_UNIQUE)
            app.mediaLibraryChangeWatcher.stop()
            return
        }
        enqueuePeriodic(app)
        app.mediaLibraryChangeWatcher.start()
    }

    fun enqueuePeriodic(app: OneGalleryApplication) {
        val prefs = app.syncPreferences
        if (!prefs.backgroundSyncAlwaysEnabled || !prefs.hasCompletedSetup) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(syncConstraints(prefs))
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Sync único com atraso (debounce para MediaStore / rede).
     * [ExistingWorkPolicy.REPLACE] reinicia o timer se houver rajada de eventos.
     */
    fun enqueueSyncSoon(
        context: Context,
        prefs: SyncPreferences,
        delaySeconds: Long = 30,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        if (!prefs.backgroundSyncAlwaysEnabled || !prefs.hasCompletedSetup) return
        if (prefs.isAutoSyncPaused()) return
        if (prefs.shouldDeferBackgroundSyncByTime()) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(syncConstraints(prefs))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_UNIQUE,
            policy,
            request,
        )
    }

    fun onDeviceBoot(context: Context) {
        val app = context.applicationContext as? OneGalleryApplication ?: return
        applyAlwaysOnPolicy(app)
        enqueueSyncSoon(
            context = app,
            prefs = app.syncPreferences,
            delaySeconds = 90,
            policy = ExistingWorkPolicy.KEEP,
        )
    }
}
