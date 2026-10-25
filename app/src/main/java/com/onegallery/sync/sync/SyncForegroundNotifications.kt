package com.onegallery.sync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.onegallery.sync.MainActivity
import com.onegallery.sync.R

/** Notificação compartilhada: serviço em 1º plano e [androidx.work.CoroutineWorker.setForeground]. */
object SyncForegroundNotifications {
    /** v2: IMPORTANCE_DEFAULT para aparecer na barra de status (v1 era LOW). */
    const val CHANNEL_ID = "onegallery_sync_v2"
    const val NOTIFICATION_ID = 4101
    /** Outro id: o worker de miniaturas não pode apagar a notificação do sync ao terminar. */
    const val THUMBNAIL_NOTIFICATION_ID = 4102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sync_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.sync_notification_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(context: Context, message: String): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun foregroundInfo(
        context: Context,
        message: String,
        notificationId: Int = NOTIFICATION_ID,
    ): ForegroundInfo {
        ensureChannel(context)
        val notification = buildNotification(context, message)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
