package com.onegallery.sync.data.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.onegallery.sync.sync.SyncPolicy
import kotlin.math.abs

class LocalMediaRepository(private val context: Context) {

    fun loadAlbums(): List<MediaAlbum> {
        val albums = linkedMapOf<String, MutableList<LocalMediaItem>>()
        loadAllMedia().forEach { item ->
            val bucketKey = item.bucketDisplayName.ifBlank { "Sem álbum" }
            albums.getOrPut(bucketKey) { mutableListOf() }.add(item)
        }
        return albums.map { (title, items) ->
            MediaAlbum(
                bucketId = title,
                title = title,
                itemCount = items.size,
                photoCount = items.count { it.kind == MediaKind.IMAGE },
                videoCount = items.count { it.kind == MediaKind.VIDEO },
                coverUri = items.maxByOrNull { it.dateAddedSeconds }?.uri,
            )
        }.sortedByDescending { it.itemCount }
    }

    fun loadAllMedia(): List<LocalMediaItem> {
        val images = queryCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            kind = MediaKind.IMAGE,
        )
        val videos = queryCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            kind = MediaKind.VIDEO,
        )
        return (images + videos)
            .filter { SyncPolicy.shouldIncludeLocalItem(it.relativePath, it.displayName) }
            .sortedByDescending { it.dateAddedSeconds }
    }

    fun loadMediaForAlbum(albumTitle: String): List<LocalMediaItem> {
        return loadAllMedia().filter { item ->
            item.bucketDisplayName.ifBlank { "Sem álbum" } == albumTitle
        }
    }

    fun findById(mediaId: Long): LocalMediaItem? {
        return loadAllMedia().firstOrNull { it.id == mediaId }
    }

    fun contentUriForMediaId(mediaId: Long): Uri? {
        val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        if (uriExists(imageUri)) return imageUri
        val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
        if (uriExists(videoUri)) return videoUri
        return null
    }

    private fun uriExists(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    fun materializeToTempFile(item: LocalMediaItem): java.io.File? {
        return runCatching {
            val suffix = item.displayName.substringAfterLast('.', "bin")
            val temp = java.io.File.createTempFile("ogs-local-", ".$suffix", context.cacheDir)
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            temp
        }.getOrNull()
    }

    fun deleteMediaItem(mediaId: Long): Boolean {
        val item = findById(mediaId) ?: return false
        val collection = when (item.kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = ContentUris.withAppendedId(collection, mediaId)
        return context.contentResolver.delete(uri, null, null) > 0
    }

    /** Substitui o conteúdo de um item da galeria (mesmo nome/caminho, versão mais nova na nuvem). */
    fun overwriteMediaContent(mediaId: Long, sourceFile: java.io.File): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false
        val item = findById(mediaId) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(item.uri, "wt")?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun queryCollection(collection: Uri, kind: MediaKind): List<LocalMediaItem> {
        val includeGps = kind == MediaKind.IMAGE &&
            MediaAccessPermissions.hasMediaLocationAccess(context)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (kind == MediaKind.VIDEO) {
                add(MediaStore.Video.VideoColumns.DURATION)
            }
            if (includeGps) {
                add(MediaStore.Images.ImageColumns.LATITUDE)
                add(MediaStore.Images.ImageColumns.LONGITUDE)
            }
        }.toTypedArray()
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val result = mutableListOf<LocalMediaItem>()
        context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val dateTakenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val durationCol = if (kind == MediaKind.VIDEO) {
                cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
            } else {
                -1
            }
            val latCol = if (includeGps) cursor.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE) else -1
            val lonCol = if (includeGps) cursor.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE) else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val (lat, lon) = if (latCol >= 0 && lonCol >= 0) {
                    normalizeGps(cursor.getDouble(latCol), cursor.getDouble(lonCol))
                } else {
                    null to null
                }
                val durationMs = if (durationCol >= 0) {
                    cursor.getLong(durationCol).takeIf { it > 0L }
                } else {
                    null
                }
                result.add(
                    LocalMediaItem(
                        id = id,
                        uri = contentUri,
                        displayName = cursor.getString(nameCol).orEmpty(),
                        bucketDisplayName = cursor.getString(bucketCol).orEmpty(),
                        relativePath = cursor.getString(pathCol),
                        dateAddedSeconds = cursor.getLong(dateAddedCol),
                        dateTakenSeconds = if (dateTakenCol >= 0) {
                            val takenMs = cursor.getLong(dateTakenCol)
                            if (takenMs > 0L) takenMs / 1000L else null
                        } else {
                            null
                        },
                        latitude = lat,
                        longitude = lon,
                        dateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        kind = kind,
                        mimeType = cursor.getString(mimeCol),
                        durationMs = durationMs,
                    ),
                )
            }
        }
        return result
    }

    private fun normalizeGps(latitude: Double, longitude: Double): Pair<Double?, Double?> {
        if (latitude == 0.0 && longitude == 0.0) return null to null
        if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return null to null
        return latitude to longitude
    }
}
