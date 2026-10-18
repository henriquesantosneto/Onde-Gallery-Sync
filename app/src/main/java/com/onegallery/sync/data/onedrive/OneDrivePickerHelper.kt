package com.onegallery.sync.data.onedrive

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object OneDrivePickerHelper {
    const val ONEDRIVE_PACKAGE = "com.microsoft.skydrive"
    private const val ONEDRIVE_DOCS_AUTHORITY = "com.microsoft.skydrive.content.StorageAccessProvider"

    data class StorageRoot(
        val title: String,
        val initialDocumentUri: Uri,
    )

    fun isOneDriveAppInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(ONEDRIVE_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun openOneDriveApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(ONEDRIVE_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    /** Raízes expostas pelo app OneDrive no celular (pasta local / cache sincronizado), se existirem. */
    fun listOneDriveStorageRoots(context: Context): List<StorageRoot> {
        if (!isOneDriveAppInstalled(context)) return emptyList()
        val rootsUri = DocumentsContract.buildRootsUri(ONEDRIVE_DOCS_AUTHORITY)
        val out = mutableListOf<StorageRoot>()
        runCatching {
            context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                appendRootsFromCursor(cursor, out)
            }
        }
        return out
    }

    private fun appendRootsFromCursor(cursor: Cursor, out: MutableList<StorageRoot>) {
        val idCol = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
        val titleCol = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
        val docCol = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
        while (cursor.moveToNext()) {
            val documentId = if (docCol >= 0) cursor.getString(docCol) else null
            val rootId = if (idCol >= 0) cursor.getString(idCol) else null
            val title = if (titleCol >= 0) cursor.getString(titleCol) else "OneDrive"
            val docKey = documentId ?: rootId ?: continue
            out.add(
                StorageRoot(
                    title = title ?: "OneDrive",
                    initialDocumentUri = DocumentsContract.buildDocumentUri(ONEDRIVE_DOCS_AUTHORITY, docKey),
                ),
            )
        }
    }

    fun picturesFolderUri(): Uri {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_PICTURES}",
        )
    }

    /** Melhor ponto de partida: pasta do provedor OneDrive no aparelho, senão Pictures. */
    fun preferredLocalSyncPickerInitialUri(context: Context): Uri {
        return listOneDriveStorageRoots(context).firstOrNull()?.initialDocumentUri
            ?: picturesFolderUri()
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
