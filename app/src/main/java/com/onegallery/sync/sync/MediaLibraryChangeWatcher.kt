package com.onegallery.sync.sync

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.onegallery.sync.worker.BackgroundSyncScheduler

/** Dispara sync debounced quando fotos/vídeos entram ou saem do MediaStore. */
class MediaLibraryChangeWatcher(
    private val context: Context,
    private val syncPreferences: SyncPreferences,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val debounceRunnable = Runnable {
        BackgroundSyncScheduler.enqueueSyncSoon(
            context = context,
            prefs = syncPreferences,
            delaySeconds = LIBRARY_CHANGE_DELAY_SEC,
        )
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            scheduleDebouncedSync()
        }
    }

    private fun scheduleDebouncedSync() {
        if (!syncPreferences.backgroundSyncAlwaysEnabled) return
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    fun start() {
        if (registered || !syncPreferences.backgroundSyncAlwaysEnabled) return
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(debounceRunnable)
        registered = false
    }

    companion object {
        private const val DEBOUNCE_MS = 8_000L
        private const val LIBRARY_CHANGE_DELAY_SEC = 45L
    }
}
