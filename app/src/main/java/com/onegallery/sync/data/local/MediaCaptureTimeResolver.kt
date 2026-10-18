package com.onegallery.sync.data.local

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Lê quando a foto/vídeo foi capturado (EXIF / MediaStore), não quando o arquivo foi alterado. */
class MediaCaptureTimeResolver(context: Context) {
    private val appContext = context.applicationContext

    fun resolveFromLocalItem(item: LocalMediaItem): Long? {
        item.dateTakenSeconds?.takeIf { it > 0L }?.let { return it }
        return resolveFromUri(item.uri)
    }

    fun resolveFromUri(uri: Uri): Long? {
        readTakenFromMediaStore(uri)?.let { return it }
        readFromFileDescriptor(uri)?.let { return it }
        readFromInputStream(uri)?.let { return it }
        return null
    }

    fun resolveFromFile(file: File): Long? {
        if (!file.isFile) return null
        return readFromExif(ExifInterface(file.absolutePath))
    }

    private fun readTakenFromMediaStore(uri: Uri): Long? {
        if (uri.scheme != "content") return null
        val projection = arrayOf(MediaStore.MediaColumns.DATE_TAKEN)
        return runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                if (col < 0) return@use null
                val takenMs = cursor.getLong(col)
                if (takenMs <= 0L) null else takenMs / 1000L
            }
        }.getOrNull()
    }

    private fun readFromFileDescriptor(uri: Uri): Long? {
        if (!MediaAccessPermissions.hasMediaLocationAccess(appContext)) return null
        return runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@runCatching null
                readFromExif(ExifInterface(pfd.fileDescriptor))
            }
        }.getOrNull()
    }

    private fun readFromInputStream(uri: Uri): Long? {
        if (!MediaAccessPermissions.hasMediaLocationAccess(appContext)) return null
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                readFromExif(ExifInterface(input))
            }
        }.getOrNull()
    }

    private fun readFromExif(exif: ExifInterface): Long? {
        val tags = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME,
        )
        for (tag in tags) {
            parseExifDate(exif.getAttribute(tag))?.let { return it }
        }
        return null
    }

    private fun parseExifDate(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val patterns = listOf(
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        )
        for (pattern in patterns) {
            try {
                val local = LocalDateTime.parse(value, pattern)
                return local.atZone(ZoneId.systemDefault()).toEpochSecond()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }
}
