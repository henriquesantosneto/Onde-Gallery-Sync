package com.onegallery.sync.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object MsalConfigFactory {
    fun computeRedirectUri(context: Context): String {
        val packageName = context.packageName
        val hash = sha1SignatureHash(context, packageName)
            ?: return "msauth://$packageName/UNABLE_TO_READ_SIGNATURE"
        val encodedPackage = URLEncoder.encode(packageName, StandardCharsets.UTF_8.name())
        val encodedHash = URLEncoder.encode(hash, StandardCharsets.UTF_8.name())
        return "msauth://$encodedPackage/$encodedHash"
    }

    fun signatureHashForManifest(context: Context): String {
        return sha1SignatureHash(context, context.packageName)?.let { "/$it" } ?: "/unknown"
    }

    fun writeConfigFile(context: Context, clientId: String): File {
        val redirectUri = computeRedirectUri(context)
        val json = """
            {
              "client_id": "$clientId",
              "authorization_user_agent": "DEFAULT",
              "redirect_uri": "$redirectUri",
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
