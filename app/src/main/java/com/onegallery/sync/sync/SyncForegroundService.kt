package com.onegallery.sync.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.onegallery.sync.R

/**
 * Mantém indexação/sync ativos com o app minimizado (Android não mata o processo tão cedo).
 * Diálogos do MediaStore para excluir arquivos só funcionam com a UI em primeiro plano.
 */
class SyncForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.sync_notification_message)
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
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MESSAGE = "message"
        fun start(context: Context, message: String? = null) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                message?.let { putExtra(EXTRA_MESSAGE, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
