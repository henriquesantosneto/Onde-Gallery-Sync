package com.onegallery.sync.sync

import android.content.Context

class SyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_sync", Context.MODE_PRIVATE)

    fun migrateForAppVersion(currentVersionCode: Int) {
        val last = prefs.getInt(KEY_LAST_VERSION, 0)
        if (last < currentVersionCode) {
            prefs.edit()
                .putBoolean(KEY_SETUP_DONE, false)
                .putString(KEY_SYNC_MODE, SyncMode.LOCAL_ONLY.name)
                .putInt(KEY_LAST_VERSION, currentVersionCode)
                .apply()
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

    companion object {
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_LAST_VERSION = "last_version_code"
        private const val KEY_AUTO_DELETE_REMOTE = "auto_delete_remote"
        private const val KEY_AUTO_FREE_LOCAL = "auto_free_local"
    }
}
