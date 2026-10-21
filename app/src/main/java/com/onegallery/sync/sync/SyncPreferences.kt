package com.onegallery.sync.sync

import android.content.Context

class SyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_sync", Context.MODE_PRIVATE)

    fun migrateForAppVersion(currentVersionCode: Int) {
        val last = prefs.getInt(KEY_LAST_VERSION, 0)
        if (last < currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_VERSION, currentVersionCode).apply()
        }
    }

    /**
     * Corrige instalações que perderam o modo de sync após migrações antigas,
     * mas ainda têm pasta na nuvem ou pasta local salva.
     */
    fun repairPersistedConnection(
        graphFolderConfigured: Boolean,
        localOneDriveFolderConnected: Boolean,
    ) {
        when {
            graphFolderConfigured && syncMode != SyncMode.ONEDRIVE_GRAPH -> {
                syncMode = SyncMode.ONEDRIVE_GRAPH
                hasCompletedSetup = true
            }
            localOneDriveFolderConnected && syncMode == SyncMode.LOCAL_ONLY -> {
                syncMode = SyncMode.ONEDRIVE_FOLDER
                hasCompletedSetup = true
            }
        }
    }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /**
     * Sync automático contínuo: WorkManager periódico, boot, rede e mudanças no MediaStore.
     * Desligar deixa só sync manual e ao abrir o app.
     */
    var backgroundSyncAlwaysEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_SYNC_ALWAYS, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_SYNC_ALWAYS, value).apply()

    var hasCompletedSetup: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    var syncMode: SyncMode
        get() = SyncMode.valueOf(prefs.getString(KEY_SYNC_MODE, SyncMode.LOCAL_ONLY.name)!!)
        set(value) = prefs.edit().putString(KEY_SYNC_MODE, value.name).apply()

    /** Se a mídia sumir do aparelho, apaga também no OneDrive (sem pedir confirmação). */
    var autoDeleteRemoteWhenLocalRemoved: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE_REMOTE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DELETE_REMOTE, value).apply()

    /**
     * Logo após backup no OneDrive, remove a cópia local e mantém miniatura + arquivo na nuvem.
     * Downloads no sync continuam só com miniatura até você abrir o item.
     */
    var autoFreeLocalSpaceWhenSynced: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FREE_LOCAL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FREE_LOCAL, value).apply()

    /** Remove cópia local / cache do original após [localCopyIdleReleaseDays] sem abrir no app. */
    var autoFreeLocalSpaceWhenIdle: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FREE_IDLE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FREE_IDLE, value).apply()

    /** Dias sem abrir a foto no app antes da liberação por inatividade (1–180). */
    var localCopyIdleReleaseDays: Int
        get() = prefs.getInt(KEY_LOCAL_IDLE_RELEASE_DAYS, DEFAULT_LOCAL_IDLE_RELEASE_DAYS)
            .coerceIn(MIN_LOCAL_IDLE_RELEASE_DAYS, MAX_LOCAL_IDLE_RELEASE_DAYS)
        set(value) = prefs.edit()
            .putInt(
                KEY_LOCAL_IDLE_RELEASE_DAYS,
                value.coerceIn(MIN_LOCAL_IDLE_RELEASE_DAYS, MAX_LOCAL_IDLE_RELEASE_DAYS),
            )
            .apply()

    /** Pausa sync/index automáticos após o usuário cancelar (epoch ms). */
    var autoSyncPausedUntilMs: Long
        get() = prefs.getLong(KEY_AUTO_SYNC_PAUSED_UNTIL, 0L)
        private set(value) = prefs.edit().putLong(KEY_AUTO_SYNC_PAUSED_UNTIL, value).apply()

    fun pauseAutoSyncAfterUserCancel(durationMs: Long = CANCEL_COOLDOWN_MS) {
        autoSyncPausedUntilMs = System.currentTimeMillis() + durationMs
    }

    fun autoSyncPausedRemainingMs(): Long {
        return (autoSyncPausedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun isAutoSyncPaused(): Boolean = autoSyncPausedRemainingMs() > 0

    var lastBidirectionalSyncCompletedAtMs: Long
        get() = prefs.getLong(KEY_LAST_BIDIR_SYNC_DONE_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BIDIR_SYNC_DONE_MS, value).apply()

    var mediaLibraryWatchSuppressedUntilMs: Long
        get() = prefs.getLong(KEY_MEDIA_WATCH_SUPPRESS_UNTIL_MS, 0L)
        private set(value) = prefs.edit().putLong(KEY_MEDIA_WATCH_SUPPRESS_UNTIL_MS, value).apply()

    fun extendMediaLibraryWatchSuppression(additionalMs: Long) {
        val until = System.currentTimeMillis() + additionalMs
        if (until > mediaLibraryWatchSuppressedUntilMs) {
            mediaLibraryWatchSuppressedUntilMs = until
        }
    }

    fun isMediaLibraryWatchSuppressed(): Boolean =
        System.currentTimeMillis() < mediaLibraryWatchSuppressedUntilMs

    /** Evita rajadas de sync automático (WorkManager, rede, MediaStore). */
    fun shouldDeferBackgroundSyncByTime(): Boolean {
        if (isAutoSyncPaused()) return true
        if (isMediaLibraryWatchSuppressed()) return true
        val last = lastBidirectionalSyncCompletedAtMs
        if (last <= 0L) return false
        return System.currentTimeMillis() - last < AUTO_BIDIRECTIONAL_MIN_INTERVAL_MS
    }

    companion object {
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_BACKGROUND_SYNC_ALWAYS = "background_sync_always"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_LAST_VERSION = "last_version_code"
        private const val KEY_AUTO_DELETE_REMOTE = "auto_delete_remote"
        private const val KEY_AUTO_FREE_LOCAL = "auto_free_local"
        private const val KEY_AUTO_FREE_IDLE = "auto_free_local_idle"
        private const val KEY_LOCAL_IDLE_RELEASE_DAYS = "local_idle_release_days"
        private const val KEY_AUTO_SYNC_PAUSED_UNTIL = "auto_sync_paused_until"

        const val MIN_LOCAL_IDLE_RELEASE_DAYS = 1
        const val MAX_LOCAL_IDLE_RELEASE_DAYS = 180
        const val DEFAULT_LOCAL_IDLE_RELEASE_DAYS = 90

        /** Tempo de respiro após cancelar antes de sync/index automáticos. */
        const val CANCEL_COOLDOWN_MS = 10 * 60 * 1000L

        /** Intervalo mínimo entre syncs bidirecionais automáticos (sem pendências). */
        const val AUTO_BIDIRECTIONAL_MIN_INTERVAL_MS = 12 * 60 * 1000L

        /** Não reagir ao MediaStore durante sync longo. */
        const val MEDIA_LIBRARY_SUPPRESS_DURING_SYNC_MS = 15 * 60 * 1000L

        /** Após liberar espaço local, o MediaStore dispara muitos eventos — ignorar por um tempo. */
        const val MEDIA_LIBRARY_SUPPRESS_AFTER_LOCAL_RELEASE_MS = 10 * 60 * 1000L

        private const val KEY_LAST_BIDIR_SYNC_DONE_MS = "last_bidir_sync_done_ms"
        private const val KEY_MEDIA_WATCH_SUPPRESS_UNTIL_MS = "media_watch_suppress_until_ms"
    }
}
