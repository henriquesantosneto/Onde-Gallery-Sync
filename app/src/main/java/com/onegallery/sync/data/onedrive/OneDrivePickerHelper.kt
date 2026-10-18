package com.onegallery.sync.data.onedrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object OneDrivePickerHelper {
    const val ONEDRIVE_PACKAGE = "com.microsoft.skydrive"

    fun isOneDriveAppInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(ONEDRIVE_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /** Abre o app OneDrive para o usuário ativar backup de Pictures (não abre pasta na nuvem). */
    fun openOneDriveApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(ONEDRIVE_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    /** Abre o seletor já em Pictures (armazenamento interno). */
    fun picturesFolderUri(): Uri {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_PICTURES}",
        )
    }

    /** Tentativa opcional de abrir na raiz do OneDrive (nem todo aparelho expõe). */
    fun oneDriveInitialUri(): Uri? {
        return runCatching {
            DocumentsContract.buildRootUri(
                "com.microsoft.skydrive.content.StorageAccessProvider",
                "OneDrive",
            )
        }.getOrNull()
    }

    fun openTreeIntent(initialUri: Uri? = null): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    fun displayName(context: Context, treeUri: Uri): String? {
        return DocumentFile.fromTreeUri(context, treeUri)?.name
    }
}
