package com.onegallery.sync.auth

object AzureClientIdValidator {
    private val GUID =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isValid(clientId: String): Boolean = clientId.trim().matches(GUID)
}
