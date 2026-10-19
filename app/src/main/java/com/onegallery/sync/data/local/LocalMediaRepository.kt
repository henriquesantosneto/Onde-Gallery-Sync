package com.onegallery.sync.data.local

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.onegallery.sync.sync.SyncPaths
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
                coverModel = items.maxByOrNull { it.dateAddedSeconds }?.uri,
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
    /**
     * Coloca um arquivo baixado de volta na galeria do Android (recall após liberar espaço).
     * Usa a pasta do [canonicalPath] (ex.: DCIM/Camera/).
     */
    fun importIntoMediaStore(
        canonicalPath: String,
        displayName: String,
        kind: MediaKind,
        sourceFile: java.io.File,
    ): Result<LocalMediaItem> {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            return Result.failure(IllegalArgumentException("Arquivo inválido para importar."))
        }
        val collection = mediaCollectionUri(kind)
        val folder = SyncPaths.albumFromCanonical(canonicalPath)
        val relativePath = when {
            folder.equals("Raiz", ignoreCase = true) -> "Pictures/OneGallerySync/"
            else -> "${folder.trim('/')}/"
        }
        val mime = guessMimeType(displayName, kind)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        var insertedUri: Uri? = null
        return runCatching {
            val uri = context.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("Não foi possível criar entrada na galeria.")
            insertedUri = uri
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Não foi possível gravar na galeria.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, done, null, null)
            }
            val id = ContentUris.parseId(uri)
            val modified = sourceFile.lastModified().takeIf { it > 0L }?.div(1000L)
                ?: (System.currentTimeMillis() / 1000L)
            LocalMediaItem(
                id = id,
                uri = uri,
                displayName = displayName,
                bucketDisplayName = SyncPaths.albumListDisplayName(folder),
                relativePath = relativePath,
                dateAddedSeconds = System.currentTimeMillis() / 1000L,
                dateModifiedSeconds = modified,
                sizeBytes = sourceFile.length(),
                kind = kind,
                mimeType = mime,
            )
        }.onFailure {
            insertedUri?.let { pending ->
                runCatching { context.contentResolver.delete(pending, null, null) }
            }
        }
    }

    private fun mediaCollectionUri(kind: MediaKind): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (kind) {
                MediaKind.IMAGE ->
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                MediaKind.VIDEO ->
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else {
            when (kind) {
                MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }
    }

    private fun guessMimeType(displayName: String, kind: MediaKind): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            else -> when (kind) {
                MediaKind.VIDEO -> "video/mp4"
                MediaKind.IMAGE -> "image/jpeg"
            }
        }
    }

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
