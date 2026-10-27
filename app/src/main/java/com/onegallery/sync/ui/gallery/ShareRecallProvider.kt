package com.onegallery.sync.ui.gallery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.onegallery.sync.OneGalleryApplication
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * O seletor abre na hora. O original só é baixado quando o app de destino
 * lê o arquivo — o compartilhamento já foi confirmado.
 */
class ShareRecallProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val name = canonicalPathFromShareRecallUri(uri).orEmpty().substringAfterLast('/')
        return when {
            GalleryMapper.isVideoFileName(name) -> "video/*"
            GalleryMapper.isAudioFileName(name) -> "audio/*"
            else -> "image/*"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val path = canonicalPathFromShareRecallUri(uri)
            ?: throw FileNotFoundException()
        val name = path.substringAfterLast('/').ifBlank { "midia" }
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> null
                else -> null
            }
        }.toTypedArray()
        cursor.addRow(row)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains("w") || mode.contains("t")) {
            throw FileNotFoundException("Somente leitura")
        }
        val path = canonicalPathFromShareRecallUri(uri)
            ?: throw FileNotFoundException()
        val app = context?.applicationContext as? OneGalleryApplication
            ?: throw FileNotFoundException()
        val file = runBlocking(Dispatchers.IO) {
            app.syncCoordinator.recallOriginalToDevice(path).getOrElse { error ->
                throw FileNotFoundException(error.message)
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
