package com.onegallery.sync.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.io.File
import java.security.MessageDigest

object MsalConfigFactory {
    /** Hash Base64 (campo “Assinatura” no Azure Android). */
    fun signatureHashBase64(context: Context): String? =
        sha1SignatureHash(context, context.packageName)

    fun computeRedirectUri(context: Context): String {
        val packageName = context.packageName
        val hash = sha1SignatureHash(context, packageName)
        return MsalRedirectUri.forMsalConfig(packageName, hash)
    }

    fun signatureHashForManifest(context: Context): String {
        val baked = MsalRedirectUri.bakedSignatureHash()
        if (baked.isNotEmpty() && baked != "UNCONFIGURED") {
            return MsalRedirectUri.manifestPath(baked)
        }
        return sha1SignatureHash(context, context.packageName)?.let { MsalRedirectUri.manifestPath(it) }
            ?: "/unknown"
    }

    fun writeConfigFile(context: Context, clientId: String): File {
        val redirectUri = redirectUriForInstalledApk(context)
        val json = """
            {
              "client_id": "$clientId",
              "authorization_user_agent": "BROWSER",
              "redirect_uri": "$redirectUri",
              "broker_redirect_uri_registered": false,
              "account_mode": "SINGLE",
              "authorities": [
                {
                  "type": "AAD",
                  "audience": {
                    "type": "AzureADandPersonalMicrosoftAccount",
                    "tenant_id": "common"
                  }
                }
              ]
            }
        """.trimIndent()
        val file = File(context.filesDir, "msal_auth_config.json")
        file.writeText(json)
        return file
    }

    /** Redirect alinhado ao APK instalado (evita JSON ≠ assinatura do aparelho). */
    private fun redirectUriForInstalledApk(context: Context): String {
        val runtimeHash = sha1SignatureHash(context, context.packageName)
        val baked = MsalRedirectUri.bakedSignatureHash()
        if (runtimeHash != null && baked.isNotBlank() && baked != "UNCONFIGURED" && runtimeHash != baked) {
            return MsalRedirectUri.format(context.packageName, runtimeHash)
        }
        return computeRedirectUri(context)
    }

    private fun sha1SignatureHash(context: Context, packageName: String): String? {
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = context.packageManager.getPackageInfo(packageName, flags)
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            } ?: return null
            if (signatures.isEmpty()) return null
            val md = MessageDigest.getInstance("SHA")
            md.update(signatures[0].toByteArray())
            Base64.encodeToString(md.digest(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
