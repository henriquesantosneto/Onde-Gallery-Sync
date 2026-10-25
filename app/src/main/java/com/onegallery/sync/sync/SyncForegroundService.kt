package com.onegallery.sync.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.onegallery.sync.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mantém indexação/sync ativos com o app minimizado (Android não mata o processo tão cedo).
 * Diálogos do MediaStore para excluir arquivos só funcionam com a UI em primeiro plano.
 *
 * startForeground() acontece antes de qualquer parada. Parar o serviço no meio do
 * startForegroundService() fecha o app com ForegroundServiceDidNotStartInTimeException.
 */
class SyncForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promote(getString(R.string.sync_notification_message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.sync_notification_message)
        promote(message)
        if (!foregroundReady.get()) {
            return START_NOT_STICKY
        }
        val commandGeneration = intent?.getIntExtra(EXTRA_GENERATION, 0) ?: 0
        if (
            shouldShutdownForegroundService(
                commandGeneration = commandGeneration,
                latestGeneration = generation.get(),
                stopAtGeneration = stopAtGeneration.get(),
                actionStop = intent?.action == ACTION_STOP,
            )
        ) {
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        shutdown()
    }

    override fun onDestroy() {
        foregroundReady.set(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun promote(message: String) {
        try {
            SyncForegroundNotifications.ensureChannel(this)
            val notification = SyncForegroundNotifications.buildNotification(this, message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SyncForegroundNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(SyncForegroundNotifications.NOTIFICATION_ID, notification)
            }
            foregroundReady.set(true)
        } catch (error: RuntimeException) {
            Log.e(TAG, "startForeground falhou", error)
            foregroundReady.set(false)
            stopSelf()
        }
    }

    private fun shutdown() {
        foregroundReady.set(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        private const val TAG = "SyncForegroundService"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_GENERATION = "generation"
        private const val ACTION_STOP = "com.onegallery.sync.sync.STOP_FOREGROUND"

        private val generation = AtomicInteger(0)
        private val stopAtGeneration = AtomicInteger(0)
        private val foregroundReady = AtomicBoolean(false)

        fun start(context: Context, message: String? = null) {
            val gen = generation.incrementAndGet()
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                putExtra(EXTRA_GENERATION, gen)
                if (!message.isNullOrBlank()) putExtra(EXTRA_MESSAGE, message)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "startForegroundService recusado", error)
            }
        }

        fun stop(context: Context, stillIdle: () -> Boolean = { true }) {
            if (!stillIdle()) return
            val gen = generation.get()
            if (gen == 0 || !stillIdle()) return
            stopAtGeneration.updateAndGet { current -> maxOf(current, gen) }
            if (generation.get() != gen || !stillIdle()) return
            if (!foregroundReady.get()) return
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_GENERATION, gen)
            }
            try {
                context.startService(intent)
            } catch (error: RuntimeException) {
                if (foregroundReady.get() && stillIdle() && generation.get() == gen) {
                    runCatching { context.stopService(intent) }
                } else {
                    Log.w(TAG, "parada do serviço adiada", error)
                }
            }
        }

        internal fun resetForTest() {
            generation.set(0)
            stopAtGeneration.set(0)
            foregroundReady.set(false)
        }
    }
}

/**
 * A geração nova (um enter que chegou depois) mantém o serviço.
 * Stop antigo, ou start que já foi cancelado antes do primeiro plano, encerra só depois do promote.
 */
internal fun shouldShutdownForegroundService(
    commandGeneration: Int,
    latestGeneration: Int,
    stopAtGeneration: Int,
    actionStop: Boolean,
): Boolean {
    if (latestGeneration > stopAtGeneration) return false
    if (stopAtGeneration <= 0) return false
    if (actionStop) return commandGeneration <= stopAtGeneration
    return commandGeneration in 1..stopAtGeneration && latestGeneration <= stopAtGeneration
}
