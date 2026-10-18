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
     * Após backup no OneDrive, remove a cópia local e mantém miniatura + placeholder na nuvem.
     */
    var autoFreeLocalSpaceWhenSynced: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FREE_LOCAL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FREE_LOCAL, value).apply()

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

    companion object {
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_LAST_VERSION = "last_version_code"
        private const val KEY_AUTO_DELETE_REMOTE = "auto_delete_remote"
        private const val KEY_AUTO_FREE_LOCAL = "auto_free_local"
        private const val KEY_AUTO_SYNC_PAUSED_UNTIL = "auto_sync_paused_until"

        /** Tempo de respiro após cancelar antes de sync/index automáticos. */
        const val CANCEL_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
