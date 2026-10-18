package com.onegallery.sync.auth

import com.onegallery.sync.BuildConfig

object MsalRedirectUri {
    /** MSAL: codifica só +, / e = no hash (não URLEncoder completo). */
    fun encodeHashForRedirect(hashBase64: String): String =
        hashBase64
            .replace("+", "%2B")
            .replace("/", "%2F")
            .replace("=", "%3D")

    fun format(packageName: String, hashBase64: String): String =
        "msauth://$packageName/${encodeHashForRedirect(hashBase64)}"

    fun manifestPath(hashBase64: String): String = "/$hashBase64"

    /** Redirect usado pelo MSAL neste APK (BuildConfig + fallback runtime). */
    fun forMsalConfig(packageName: String, runtimeHashBase64: String?): String {
        val baked = BuildConfig.MSAL_REDIRECT_URI.trim()
        if (baked.isNotEmpty() && !baked.contains("UNCONFIGURED")) return baked
        val hash = runtimeHashBase64 ?: return "msauth://$packageName/UNABLE_TO_READ_SIGNATURE"
        return format(packageName, hash)
    }

    fun bakedSignatureHash(): String = BuildConfig.MSAL_SIGNATURE_HASH.trim()

    fun runtimeMatchesBuild(runtimeHash: String?): Boolean {
        val baked = bakedSignatureHash()
        if (baked.isEmpty() || baked == "UNCONFIGURED") return true
        if (runtimeHash.isNullOrBlank()) return false
        return runtimeHash == baked
    }
}
