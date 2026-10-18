package com.onegallery.sync.data.onedrive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object OneDrivePickerHelper {
    const val ONEDRIVE_PACKAGE = "com.microsoft.skydrive"
    private const val ONEDRIVE_DOCS_AUTHORITY = "com.microsoft.skydrive.content.StorageAccessProvider"

    data class StorageRoot(
        val title: String,
        val initialDocumentUri: Uri,
    )

    /** Por que o OneDrive não entra no seletor de pastas (OPEN_DOCUMENT_TREE). */
    const val ONEDRIVE_NOT_IN_FOLDER_PICKER_REASON =
        "O seletor de pastas do Android (OPEN_DOCUMENT_TREE) quase nunca lista OneDrive. " +
            "Gerenciadores de arquivos enxergam OneDrive de outro jeito: login na nuvem (API), " +
            "integração da Samsung, ou consulta direta ao provedor de documentos — não o mesmo diálogo. " +
            "Sem isso, use Pictures/OneGallerySync + backup de fotos no app OneDrive."

    fun isOneDriveAppInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(ONEDRIVE_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isDocumentTreePickerAvailable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        return context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ).isNotEmpty()
    }

    fun openOneDriveApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(ONEDRIVE_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    fun listOneDriveStorageRoots(context: Context): List<StorageRoot> {
        if (!isOneDriveAppInstalled(context)) return emptyList()
        val out = mutableListOf<StorageRoot>()
        discoverAuthorities(context).forEach { authority ->
            runCatching {
                val rootsUri = DocumentsContract.buildRootsUri(authority)
                context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                    appendRootsFromCursor(cursor, authority, out)
                }
            }
        }
        return out.distinctBy { it.initialDocumentUri.toString() }
    }

    private fun discoverAuthorities(context: Context): List<String> {
        val fromPackage = runCatching {
            val intent = Intent(DocumentsContract.PROVIDER_INTERFACE)
            context.packageManager.queryIntentContentProviders(intent, PackageManager.GET_META_DATA)
                .filter { it.providerInfo.packageName == ONEDRIVE_PACKAGE }
                .map { it.providerInfo.authority }
        }.getOrDefault(emptyList())
        return (fromPackage + ONEDRIVE_DOCS_AUTHORITY).distinct()
    }

    private fun appendRootsFromCursor(cursor: Cursor, authority: String, out: MutableList<StorageRoot>) {
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
                    initialDocumentUri = DocumentsContract.buildDocumentUri(authority, docKey),
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

    fun preferredLocalSyncPickerInitialUri(context: Context): Uri {
        return listOneDriveStorageRoots(context).firstOrNull()?.initialDocumentUri
            ?: picturesFolderUri()
    }

    /** Intent do seletor de pastas; no Samsung usa o volume primário quando possível. */
    fun openTreeIntent(context: Context, initialUri: Uri? = null): Intent {
        val base = createDocumentTreeIntent(context)
        if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            base.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        return base
    }

    private fun createDocumentTreeIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val sm = context.getSystemService(StorageManager::class.java)
                val volume = sm.primaryStorageVolume
                val volumeIntent = volume.createOpenDocumentTreeIntent()
                volumeIntent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                return volumeIntent
            }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
    }

    fun displayName(context: Context, treeUri: Uri): String? {
        return DocumentFile.fromTreeUri(context, treeUri)?.name
    }
}
