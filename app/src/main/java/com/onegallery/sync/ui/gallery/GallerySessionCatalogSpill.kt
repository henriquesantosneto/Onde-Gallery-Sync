package com.onegallery.sync.ui.gallery

import android.content.Context
import android.net.Uri
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quando o Android mata o processo (ex.: fechar nos recentes), grava o último catálogo
 * montado em disco **só ao ir para segundo plano** — não no caminho quente do sync.
 * Na abertura seguinte, hidrata [GalleryProcessCatalogCache] antes da galeria montar de novo.
 */
object GallerySessionCatalogSpill {
    private const val FILE_NAME = "gallery_session_v1.gz"
    private const val FORMAT_MAGIC = 0x4F475353 // OGSS
    private const val FORMAT_VERSION = 3
    private const val MAX_PERSISTED_ITEMS = 8_000
    private const val MAX_CACHE_BYTES = 24L * 1024L * 1024L

    suspend fun hydrateProcessCache(context: Context, cache: GalleryProcessCatalogCache) {
        if (cache.peek() != null) return
        val entry = load(context) ?: return
        cache.replace(entry)
    }

    suspend fun save(context: Context, bundle: GalleryCatalogSession.SnapshotBundle) {
        if (bundle.visibleItems.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                writeFile(context, bundle)
            }
        }
    }

    private suspend fun load(context: Context): GalleryProcessCatalogCache.Entry? =
        withContext(Dispatchers.IO) {
            runCatching { readFile(context) }.getOrElse {
                runCatching { cacheFile(context).delete() }
                null
            }
        }

    private fun cacheFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun writeFile(context: Context, bundle: GalleryCatalogSession.SnapshotBundle) {
        val source = bundle.visibleItems
        val toPersist = if (source.size <= MAX_PERSISTED_ITEMS) {
            source
        } else {
            source.sortedByDescending { it.sortTimestamp }.take(MAX_PERSISTED_ITEMS)
        }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        GZIPOutputStream(FileOutputStream(tmp)).use { gzip ->
            DataOutputStream(gzip).use { out ->
                out.writeInt(FORMAT_MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeLong(bundle.localRevision)
                out.writeInt(bundle.fingerprint.rowCount)
                out.writeLong(bundle.fingerprint.maxModifiedSeconds)
                out.writeLong(bundle.fingerprint.pathChecksum)
                out.writeInt(toPersist.size)
                for (item in toPersist) {
                    writeItem(out, item)
                }
                writeUserAlbums(out, bundle.userAlbums)
            }
        }
        val dest = cacheFile(context)
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    private fun readFile(context: Context): GalleryProcessCatalogCache.Entry? {
        val file = cacheFile(context)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_CACHE_BYTES) return null
        return GZIPInputStream(FileInputStream(file)).use { gzip ->
            DataInputStream(gzip).use { input ->
                if (input.readInt() != FORMAT_MAGIC) return null
                val formatVersion = input.readInt()
                if (formatVersion !in 1..3) return null
                val localRevision = input.readLong()
                val rowCount = input.readInt()
                val maxMod = input.readLong()
                val fingerprint = when (formatVersion) {
                    3 -> {
                        val checksum = input.readLong()
                        GalleryCatalogFingerprint(rowCount, checksum, maxMod)
                    }
                    else -> {
                        val head = readNullableUtf(input)
                        val tail = readNullableUtf(input)
                        GalleryCatalogFingerprint.legacyFromSpill(rowCount, head, tail, maxMod)
                    }
                }
                val count = input.readInt()
                if (count <= 0 || count > MAX_PERSISTED_ITEMS) return null
                val items = ArrayList<GalleryDisplayItem>(count)
                repeat(count) {
                    items.add(readItem(input))
                }
                val userAlbums = if (formatVersion >= 2) {
                    readUserAlbums(input)
                } else {
                    emptyList()
                }
                GalleryProcessCatalogCache.Entry(
                    catalogItems = items,
                    visibleItems = items,
                    userAlbums = userAlbums,
                    fingerprint = fingerprint,
                    localRevision = localRevision,
                )
            }
        }
    }

    private fun writeUserAlbums(out: DataOutputStream, userAlbums: List<com.onegallery.sync.data.local.db.UserAlbumEntity>) {
        out.writeInt(userAlbums.size.coerceAtMost(512))
        userAlbums.take(512).forEach { album ->
            out.writeUTF(album.id)
            out.writeUTF(album.title)
            out.writeLong(album.createdAtEpoch)
        }
    }

    private fun readUserAlbums(input: DataInputStream): List<com.onegallery.sync.data.local.db.UserAlbumEntity> {
        val count = input.readInt()
        if (count < 0 || count > 512) return emptyList()
        return buildList(count) {
            repeat(count) {
                add(
                    com.onegallery.sync.data.local.db.UserAlbumEntity(
                        id = input.readUTF(),
                        title = input.readUTF(),
                        createdAtEpoch = input.readLong(),
                    ),
                )
            }
        }
    }

    private fun writeItem(out: DataOutputStream, item: GalleryDisplayItem) {
        out.writeUTF(item.canonicalPath)
        out.writeUTF(item.displayName)
        out.writeUTF(item.albumName)
        out.writeInt(item.kind.ordinal)
        out.writeInt(item.cloudState.ordinal)
        writeNullableLong(out, item.localMediaId)
        writeNullableUtf(out, item.oneDriveItemId)
        out.writeInt(item.pendingDeleteTarget.ordinal)
        writeNullableUtf(out, item.localUri?.toString())
        writeNullableUtf(out, item.cachedOriginal?.takeIf { it.isFile }?.absolutePath)
        writeNullableUtf(out, item.cachedPlaceholderThumb?.takeIf { it.isFile }?.absolutePath)
        out.writeLong(item.sortTimestamp)
        writeNullableUtf(out, item.locationLabel)
        writeNullableDouble(out, item.latitude)
        writeNullableDouble(out, item.longitude)
        writeNullableLong(out, item.videoDurationMs)
        out.writeBoolean(item.localReleasedForSpace)
    }

    private fun readItem(input: DataInputStream): GalleryDisplayItem {
        val canonical = input.readUTF()
        val displayName = input.readUTF()
        val albumName = input.readUTF()
        val kind = MediaKind.entries.getOrNull(input.readInt()) ?: MediaKind.IMAGE
        val cloudState = CloudSyncState.entries.getOrNull(input.readInt()) ?: CloudSyncState.LOCAL_ONLY
        val localMediaId = readNullableLong(input)
        val oneDriveItemId = readNullableUtf(input)
        val pendingDelete = PendingDeleteTarget.entries.getOrNull(input.readInt()) ?: PendingDeleteTarget.NONE
        val localUri = readNullableUtf(input)?.let { Uri.parse(it) }
        val cachedOriginal = readNullableUtf(input)?.let { path ->
            java.io.File(path).takeIf { it.isFile && it.length() > 0L }
        }
        val placeholder = readNullableUtf(input)?.let { path ->
            java.io.File(path).takeIf { it.isFile && it.length() > 0L }
        }
        return GalleryDisplayItem(
            canonicalPath = canonical,
            displayName = displayName,
            albumName = albumName,
            kind = kind,
            cloudState = cloudState,
            localMediaId = localMediaId,
            oneDriveItemId = oneDriveItemId,
            pendingDeleteTarget = pendingDelete,
            localUri = localUri,
            cachedOriginal = cachedOriginal,
            cachedPlaceholderThumb = placeholder,
            sortTimestamp = input.readLong(),
            locationLabel = readNullableUtf(input),
            latitude = readNullableDouble(input),
            longitude = readNullableDouble(input),
            videoDurationMs = readNullableLong(input),
            localReleasedForSpace = input.readBoolean(),
            showInBrowseGrid = true,
        )
    }

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

    private fun writeNullableDouble(out: DataOutputStream, value: Double?) {
        if (value == null) out.writeBoolean(false) else {
            out.writeBoolean(true)
            out.writeDouble(value)
        }
    }

    private fun readNullableDouble(input: DataInputStream): Double? =
        if (input.readBoolean()) input.readDouble() else null
}
