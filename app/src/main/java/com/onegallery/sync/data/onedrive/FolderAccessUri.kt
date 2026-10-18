package com.onegallery.sync.data.onedrive

import android.net.Uri
import android.provider.DocumentsContract

object FolderAccessUri {
    fun normalizeToTreeUri(uri: Uri): Uri {
        if (DocumentsContract.isTreeUri(uri)) return uri
        val authority = uri.authority ?: return uri
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri
        return DocumentsContract.buildTreeDocumentUri(authority, docId)
    }
}
