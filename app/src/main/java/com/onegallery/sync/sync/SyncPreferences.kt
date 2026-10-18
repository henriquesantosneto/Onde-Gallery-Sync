package com.onegallery.sync.sync

import android.content.Context

class SyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_sync", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    companion object {
        private const val KEY_WIFI_ONLY = "wifi_only"
    }
}
