package com.onegallery.sync.auth

import android.content.Context

class AuthPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_auth", Context.MODE_PRIVATE)

    var azureClientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    companion object {
        private const val KEY_CLIENT_ID = "azure_client_id"
    }
}
