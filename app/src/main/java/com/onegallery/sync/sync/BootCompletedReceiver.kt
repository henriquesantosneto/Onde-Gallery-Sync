package com.onegallery.sync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.onegallery.sync.worker.BackgroundSyncScheduler

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            -> BackgroundSyncScheduler.onDeviceBoot(context)
        }
    }

    companion object {
        /** Alguns fabricantes (HTC, Xiaomi, etc.). */
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
