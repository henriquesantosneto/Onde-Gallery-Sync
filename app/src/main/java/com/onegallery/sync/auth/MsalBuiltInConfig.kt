package com.onegallery.sync.auth

import com.onegallery.sync.BuildConfig

object MsalBuiltInConfig {
    fun isEmbeddedClientConfigured(): Boolean {
        val id = BuildConfig.MSAL_CLIENT_ID
        return id.isNotBlank() && !id.startsWith("00000000")
    }
}
