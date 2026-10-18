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
    private const val SAMSUNG_MY_FILES = "com.sec.android.app.myfiles"
    private const val ONEDRIVE_DOCS_AUTHORITY = "com.microsoft.skydrive.content.StorageAccessProvider"

    data class StorageRoot(
        val title: String,
        val initialDocumentUri: Uri,
        val authority: String,
    )

    const val SAMSUNG_ONEDRIVE_HINT =
        "No Meus Arquivos da Samsung o OneDrive aparece porque é o app da Samsung com integração Microsoft — " +
            "não é o seletor simples de “armazenamento interno”. O OneGallery agora tenta abrir o mesmo tipo de " +
            "seletor completo (Meus Arquivos). Escolha a pasta OneDrive e confirme."

    /** @deprecated texto genérico; prefira [SAMSUNG_ONEDRIVE_HINT] na UI Samsung */
    const val ONEDRIVE_NOT_IN_FOLDER_PICKER_REASON =
        "Se só aparecer armazenamento interno, o aparelho abriu o seletor restrito. Use “Meus Arquivos (OneDrive)” abaixo."

    fun isSamsungDevice(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun isOneDriveAppInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(ONEDRIVE_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isSamsungMyFilesInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SAMSUNG_MY_FILES, 0)
            true
        }.getOrDefault(false)
    }

    fun isDocumentTreePickerAvailable(context: Context): Boolean {
        return resolveDocumentTreeActivities(context).isNotEmpty()
    }

    fun openOneDriveApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(ONEDRIVE_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    /** Raízes de todos os provedores SAF (incl. nuvem se exposta ao sistema). */
    fun listAllDocumentProviderRoots(context: Context): List<StorageRoot> {
        val providers = runCatching {
            context.packageManager.queryIntentContentProviders(
                Intent(DocumentsContract.PROVIDER_INTERFACE),
                PackageManager.GET_META_DATA,
            )
        }.getOrDefault(emptyList())
        val out = mutableListOf<StorageRoot>()
        providers.forEach { info ->
            val authority = info.providerInfo.authority ?: return@forEach
            runCatching {
                context.contentResolver.query(
                    DocumentsContract.buildRootsUri(authority),
                    null,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    appendRootsFromCursor(cursor, authority, out)
                }
            }
        }
        return out.distinctBy { it.initialDocumentUri.toString() }
    }

    fun listCloudRelatedRoots(context: Context): List<StorageRoot> {
        val keywords = listOf("onedrive", "one drive", "microsoft", "drive")
        return listAllDocumentProviderRoots(context).filter { root ->
            val t = root.title.lowercase()
            keywords.any { t.contains(it) }
        }.ifEmpty { listOneDrivePackageRoots(context) }
            .distinctBy { it.initialDocumentUri.toString() }
    }

    fun listOneDriveStorageRoots(context: Context): List<StorageRoot> {
        return (listCloudRelatedRoots(context) + listOneDrivePackageRoots(context))
            .distinctBy { it.initialDocumentUri.toString() }
    }

    private fun listOneDrivePackageRoots(context: Context): List<StorageRoot> {
        if (!isOneDriveAppInstalled(context)) return emptyList()
        val out = mutableListOf<StorageRoot>()
        discoverOneDriveAuthorities(context).forEach { authority ->
            runCatching {
                context.contentResolver.query(
                    DocumentsContract.buildRootsUri(authority),
                    null,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    appendRootsFromCursor(cursor, authority, out)
                }
            }
        }
        return out
    }

    private fun discoverOneDriveAuthorities(context: Context): List<String> {
        val fromPackage = runCatching {
            context.packageManager.queryIntentContentProviders(
                Intent(DocumentsContract.PROVIDER_INTERFACE),
                PackageManager.GET_META_DATA,
            )
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
            val title = if (titleCol >= 0) cursor.getString(titleCol) else "Pasta"
            val docKey = documentId ?: rootId ?: continue
            out.add(
                StorageRoot(
                    title = title ?: "Pasta",
                    initialDocumentUri = DocumentsContract.buildDocumentUri(authority, docKey),
                    authority = authority,
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
        return listCloudRelatedRoots(context).firstOrNull()?.initialDocumentUri
            ?: picturesFolderUri()
    }

    /**
     * Seletor completo — no Samsung prioriza a activity do Meus Arquivos (onde OneDrive costuma aparecer).
     */
    fun openFullTreeIntent(context: Context, initialUri: Uri? = null): Intent {
        val base = resolveBestDocumentTreeIntent(context)
        if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            base.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        return base
    }

    /** Seletor restrito ao volume interno (só pastas locais). */
    fun openInternalStorageTreeIntent(context: Context, initialUri: Uri? = null): Intent {
        val base = createPrimaryVolumeTreeIntent(context)
        if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            base.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        return base
    }

    /** @deprecated use [openFullTreeIntent] ou [openInternalStorageTreeIntent] */
    fun openTreeIntent(context: Context, initialUri: Uri? = null): Intent =
        openFullTreeIntent(context, initialUri)

    private fun treeIntentTemplate(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
    }

    private fun resolveDocumentTreeActivities(context: Context) =
        context.packageManager.queryIntentActivities(
            treeIntentTemplate(),
            PackageManager.MATCH_DEFAULT_ONLY,
        )

    private fun resolveBestDocumentTreeIntent(context: Context): Intent {
        val activities = resolveDocumentTreeActivities(context)
        val samsung = activities.firstOrNull { info ->
            val pkg = info.activityInfo.packageName
            pkg.contains("myfiles", ignoreCase = true) ||
                pkg.contains("sec.android", ignoreCase = true)
        }
        if (samsung != null) {
            return treeIntentTemplate().setClassName(
                samsung.activityInfo.packageName,
                samsung.activityInfo.name,
            )
        }
        return treeIntentTemplate()
    }

    private fun createPrimaryVolumeTreeIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val sm = context.getSystemService(StorageManager::class.java)
                val volumeIntent = sm.primaryStorageVolume.createOpenDocumentTreeIntent()
                volumeIntent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                return volumeIntent
            }
        }
        return treeIntentTemplate()
    }

    fun displayName(context: Context, treeUri: Uri): String? {
        return DocumentFile.fromTreeUri(context, treeUri)?.name
    }
}
