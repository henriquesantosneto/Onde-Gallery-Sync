package com.onegallery.sync.data.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.onegallery.sync.sync.SyncPolicy

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

    private fun queryCollection(collection: Uri, kind: MediaKind): List<LocalMediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val result = mutableListOf<LocalMediaItem>()
        context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                result.add(
                    LocalMediaItem(
                        id = id,
                        uri = contentUri,
                        displayName = cursor.getString(nameCol).orEmpty(),
                        bucketDisplayName = cursor.getString(bucketCol).orEmpty(),
                        relativePath = cursor.getString(pathCol),
                        dateAddedSeconds = cursor.getLong(dateCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        kind = kind,
                        mimeType = cursor.getString(mimeCol),
                    ),
                )
            }
        }
        return result
    }
}
