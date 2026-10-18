package com.onegallery.sync.data.onedrive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Navega pastas via DocumentsProvider (como gerenciadores de arquivos), sem Azure. */
object OneDriveDocumentBrowser {

    data class BrowseEntry(
        val authority: String,
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val documentUri: Uri,
    )

    fun loadRoots(context: Context): List<BrowseEntry> {
        val cloud = OneDrivePickerHelper.listCloudRelatedRoots(context)
        val all = if (cloud.isNotEmpty()) cloud else OneDrivePickerHelper.listOneDriveStorageRoots(context)
        return all.map { root ->
            val docId = DocumentsContract.getDocumentId(root.initialDocumentUri)
            BrowseEntry(
                authority = root.authority,
                documentId = docId,
                name = root.title,
                isDirectory = true,
                documentUri = root.initialDocumentUri,
            )
        }
    }

    fun loadChildren(context: Context, authority: String, parentDocumentId: String): Result<List<BrowseEntry>> {
        return runCatching {
            val childUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val entries = mutableListOf<BrowseEntry>()
            context.contentResolver.query(childUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: docId
                    val mime = cursor.getString(mimeCol)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    entries.add(
                        BrowseEntry(
                            authority = authority,
                            documentId = docId,
                            name = name,
                            isDirectory = isDir,
                            documentUri = DocumentsContract.buildDocumentUri(authority, docId),
                        ),
                    )
                }
            }
            entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        }
    }
}
