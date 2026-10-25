package com.onegallery.sync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.SyncPreferences
import java.util.concurrent.TimeUnit

/** Agenda sync periódico, pós-boot e reativo (rede / MediaStore). */
object BackgroundSyncScheduler {

    const val IMMEDIATE_UNIQUE = "onegallery_bidirectional_sync_now"
    const val DEFERRED_UNIQUE = "onegallery_bidirectional_sync_deferred"
    /** Segura o sync já iniciado quando a activity some. KEEP não cancela a execução atual. */
    const val HANDOFF_UNIQUE = "onegallery_sync_background_handoff"

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
            WorkManager.getInstance(app).cancelUniqueWork(DEFERRED_UNIQUE)
            cancelThumbnailContinuation(app)
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
        val cooldownMs = prefs.millisUntilBackgroundSyncAllowed()
        if (cooldownMs > 0L) {
            enqueueDeferredAutoSync(
                context = context,
                prefs = prefs,
                extraDelayMs = delaySeconds * 1_000L,
            )
            return
        }
        enqueueSyncWork(
            context = context,
            prefs = prefs,
            userInitiated = false,
            delaySeconds = delaySeconds,
            policy = policy,
            uniqueWorkName = IMMEDIATE_UNIQUE,
        )
    }

    /**
     * Agenda sync automático após cooldown (MediaStore/rede/periódico que caiu no intervalo mínimo).
     * [ExistingWorkPolicy.KEEP] evita empurrar o prazo a cada evento repetido.
     */
    fun enqueueDeferredAutoSync(
        context: Context,
        prefs: SyncPreferences,
        extraDelayMs: Long = 0L,
    ) {
        if (!prefs.backgroundSyncAlwaysEnabled || !prefs.hasCompletedSetup) return
        if (prefs.isAutoSyncPaused()) return
        val waitMs = (prefs.millisUntilBackgroundSyncAllowed() + extraDelayMs).coerceAtLeast(0L)
        if (waitMs <= 0L) {
            enqueueSyncSoon(context, prefs, delaySeconds = 0L)
            return
        }
        val delaySec = ((waitMs + 999L) / 1_000L).coerceAtLeast(1L)
        enqueueSyncWork(
            context = context,
            prefs = prefs,
            userInitiated = false,
            delaySeconds = delaySec,
            policy = ExistingWorkPolicy.KEEP,
            uniqueWorkName = DEFERRED_UNIQUE,
        )
    }

    /**
     * Próxima leva de miniaturas, sem listar o OneDrive de novo.
     * [runningSlot] nulo usa KEEP no trabalho primário (não cancela uma leva já enfileirada).
     * Com slot, agenda o outro nome — o que está em execução não se cancela.
     */
    fun enqueueThumbnailContinuation(
        context: Context,
        prefs: SyncPreferences,
        delaySeconds: Long = ThumbnailContinuationPolicy.NORMAL_DELAY_SECONDS,
        runningSlot: String? = null,
    ) {
        if (!ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = true,
                setupComplete = prefs.hasCompletedSetup,
                backgroundEnabled = prefs.backgroundSyncAlwaysEnabled,
                paused = prefs.isAutoSyncPaused(),
                userCancelled = false,
            )
        ) {
            return
        }
        val uniqueName = ThumbnailContinuationPolicy.followUpUniqueName(runningSlot)
        val policy = if (runningSlot == null) {
            ExistingWorkPolicy.KEEP
        } else {
            if (isUniqueWorkLive(context, uniqueName)) return
            ExistingWorkPolicy.REPLACE
        }
        val slot = ThumbnailContinuationPolicy.slotForUniqueName(uniqueName)
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(SyncWorker.thumbnailContinuationInput(slot))
            .setConstraints(syncConstraints(prefs))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
        if (delaySeconds > 0L) {
            builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueName,
            policy,
            builder.build(),
        )
    }

    /**
     * O sync tocado na tela roda no processo. Este worker fica em primeiro plano
     * até essa execução soltar o mutex — minimizar não derruba a notificação.
     * Se o processo morrer, o WorkManager refaz o sync.
     */
    fun enqueueRunningSyncHandoff(context: Context, prefs: SyncPreferences) {
        if (!prefs.hasCompletedSetup) return
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(SyncWorker.input(userInitiated = true))
            .setConstraints(syncConstraints(prefs))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            HANDOFF_UNIQUE,
            ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    fun cancelThumbnailContinuation(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(ThumbnailContinuationPolicy.PRIMARY_UNIQUE)
        workManager.cancelUniqueWork(ThumbnailContinuationPolicy.FOLLOW_UP_UNIQUE)
    }

    /** Interrompe sync one-shot (inclui worker em andamento). Não cancela o periódico de 15 min. */
    fun cancelPendingSyncWork(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(IMMEDIATE_UNIQUE)
        workManager.cancelUniqueWork(HANDOFF_UNIQUE)
        cancelThumbnailContinuation(context)
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
        uniqueWorkName: String = IMMEDIATE_UNIQUE,
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
            uniqueWorkName,
            policy,
            builder.build(),
        )
    }

    private fun isUniqueWorkLive(context: Context, uniqueName: String): Boolean {
        return runCatching {
            val infos = WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWork(uniqueName)
                .get(2, TimeUnit.SECONDS)
            infos.any { info ->
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING
            }
        }.getOrDefault(false)
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
        enqueueThumbnailContinuation(
            context = app,
            prefs = app.syncPreferences,
            delaySeconds = 90,
        )
    }
}
