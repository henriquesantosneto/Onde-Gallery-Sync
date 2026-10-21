package com.onegallery.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
        enqueueSyncWork(
            context = context,
            prefs = prefs,
            userInitiated = false,
            delaySeconds = delaySeconds,
            policy = policy,
        )
    }

    /** Botão “Sincronizar agora” / gesto explícito — WorkManager, não a UI Compose. */
    fun enqueueUserRequestedSync(context: Context, prefs: SyncPreferences) {
        if (!prefs.hasCompletedSetup) return
        enqueueSyncWork(
            context = context,
            prefs = prefs,
            userInitiated = true,
            delaySeconds = 0,
            policy = ExistingWorkPolicy.REPLACE,
        )
    }

    private fun enqueueSyncWork(
        context: Context,
        prefs: SyncPreferences,
        userInitiated: Boolean,
        delaySeconds: Long,
        policy: ExistingWorkPolicy,
    ) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(SyncWorker.input(userInitiated))
            .setConstraints(syncConstraints(prefs))
        if (userInitiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        if (delaySeconds > 0L) {
            builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_UNIQUE,
            policy,
            builder.build(),
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
