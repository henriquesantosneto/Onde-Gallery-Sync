package com.onegallery.sync.ui.gallery

import android.content.Context
import android.net.Uri
import com.onegallery.sync.data.local.MediaAlbum
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GallerySessionAlbumListsSpill {
    private const val FILE_NAME = "gallery_album_lists_v1.gz"
    private const val FORMAT_MAGIC = 0x4F47414C // OGAL
    private const val FORMAT_VERSION = 1
    private const val MAX_ALBUMS = 4_000
    private const val MAX_CACHE_BYTES = 8L * 1024L * 1024L

    private const val COVER_NONE = 0
    private const val COVER_URI = 1
    private const val COVER_FILE = 2

    suspend fun hydrateProcessCache(context: Context, cache: GalleryProcessAlbumListsCache) {
        if (cache.peek() != null) return
        val entry = load(context) ?: return
        cache.replace(entry)
    }

    suspend fun save(context: Context, entry: GalleryProcessAlbumListsCache.Entry) {
        if (!entry.isRestorable()) return
        withContext(Dispatchers.IO) {
            runCatching { writeFile(context, entry) }
        }
    }

    private suspend fun load(context: Context): GalleryProcessAlbumListsCache.Entry? =
        withContext(Dispatchers.IO) {
            runCatching { readFile(context) }.getOrElse {
                runCatching { cacheFile(context).delete() }
                null
            }
        }

    private fun cacheFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun writeFile(context: Context, entry: GalleryProcessAlbumListsCache.Entry) {
        val albums = entry.albums.take(MAX_ALBUMS)
        val places = entry.placeAlbums.take(MAX_ALBUMS)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        GZIPOutputStream(FileOutputStream(tmp)).use { gzip ->
            DataOutputStream(gzip).use { out ->
                out.writeInt(FORMAT_MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeLong(entry.localRevision)
                out.writeLong(entry.coverRevisionStamp)
                out.writeInt(entry.albumListSort.ordinal)
                out.writeInt(entry.fingerprint.rowCount)
                out.writeLong(entry.fingerprint.maxModifiedSeconds)
                writeNullableUtf(out, entry.fingerprint.headPath)
                writeNullableUtf(out, entry.fingerprint.tailPath)
                writeAlbumList(out, albums)
                writeAlbumList(out, places)
            }
        }
        val dest = cacheFile(context)
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    private fun readFile(context: Context): GalleryProcessAlbumListsCache.Entry? {
        val file = cacheFile(context)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_CACHE_BYTES) return null
        return GZIPInputStream(FileInputStream(file)).use { gzip ->
            DataInputStream(gzip).use { input ->
                if (input.readInt() != FORMAT_MAGIC) return null
                if (input.readInt() != FORMAT_VERSION) return null
                val localRevision = input.readLong()
                val coverRevisionStamp = input.readLong()
                val sort = AlbumListSort.entries.getOrNull(input.readInt()) ?: AlbumListSort.NAME_A_Z
                val rowCount = input.readInt()
                val maxMod = input.readLong()
                val head = readNullableUtf(input)
                val tail = readNullableUtf(input)
                val fingerprint = GalleryCatalogFingerprint(rowCount, head, tail, maxMod)
                val albums = readAlbumList(input)
                val placeAlbums = readAlbumList(input)
                GalleryProcessAlbumListsCache.Entry(
                    albums = albums,
                    placeAlbums = placeAlbums,
                    fingerprint = fingerprint,
                    localRevision = localRevision,
                    albumListSort = sort,
                    coverRevisionStamp = coverRevisionStamp,
                )
            }
        }
    }

    private fun writeAlbumList(out: DataOutputStream, albums: List<MediaAlbum>) {
        out.writeInt(albums.size)
        for (album in albums) {
            out.writeUTF(album.bucketId)
            out.writeUTF(album.title)
            out.writeUTF(album.filterKey)
            out.writeInt(album.itemCount)
            out.writeInt(album.photoCount)
            out.writeInt(album.videoCount)
            out.writeLong(album.sortTimestamp)
            out.writeBoolean(album.isUserManaged)
            writeNullableUtf(out, album.userAlbumId)
            writeNullableLong(out, album.createdAtEpoch)
            writeCoverModel(out, album.coverModel)
        }
    }

    private fun readAlbumList(input: DataInputStream): List<MediaAlbum> {
        val count = input.readInt()
        if (count < 0 || count > MAX_ALBUMS) return emptyList()
        return buildList(count) {
            repeat(count) {
                add(readAlbum(input))
            }
        }
    }

    private fun writeCoverModel(out: DataOutputStream, model: Any?) {
        when (model) {
            null -> out.writeInt(COVER_NONE)
            is Uri -> {
                out.writeInt(COVER_URI)
                out.writeUTF(model.toString())
            }
            is File -> {
                out.writeInt(COVER_FILE)
                out.writeUTF(model.absolutePath)
            }
            else -> out.writeInt(COVER_NONE)
        }
    }

    private fun readCoverModel(input: DataInputStream): Any? =
        when (input.readInt()) {
            COVER_URI -> Uri.parse(input.readUTF())
            COVER_FILE -> {
                val path = input.readUTF()
                File(path).takeIf { it.isFile && it.length() > 0L }
            }
            else -> null
        }

    private fun readAlbum(input: DataInputStream): MediaAlbum =
        MediaAlbum(
            bucketId = input.readUTF(),
            title = input.readUTF(),
            filterKey = input.readUTF(),
            itemCount = input.readInt(),
            photoCount = input.readInt(),
            videoCount = input.readInt(),
            coverModel = readCoverModel(input),
            sortTimestamp = input.readLong(),
            isUserManaged = input.readBoolean(),
            userAlbumId = readNullableUtf(input),
            createdAtEpoch = readNullableLong(input),
        )

    private fun writeNullableUtf(out: DataOutputStream, value: String?) {
        if (value == null) out.writeBoolean(false) else {
            out.writeBoolean(true)
            out.writeUTF(value)
        }
    }

    private fun readNullableUtf(input: DataInputStream): String? =
        if (input.readBoolean()) input.readUTF() else null

    private fun writeNullableLong(out: DataOutputStream, value: Long?) {
        if (value == null) out.writeBoolean(false) else {
            out.writeBoolean(true)
            out.writeLong(value)
        }
    }

    private fun readNullableLong(input: DataInputStream): Long? =
        if (input.readBoolean()) input.readLong() else null
}
