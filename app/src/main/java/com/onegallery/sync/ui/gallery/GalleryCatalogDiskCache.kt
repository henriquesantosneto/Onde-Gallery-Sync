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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Catálogo pronto para a UI — reabertura rápida antes do Room terminar de emitir.
 * Invalida quando o índice ou a revisão local mudam ([GalleryCatalogFingerprint] + localRevision).
 */
class GalleryCatalogDiskCache(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val cacheFile = File(context.filesDir, "gallery_catalog_v1.gz")

    @Volatile
    private var lastSavedKey: String? = null

    suspend fun loadWarmSnapshot(): GalleryWarmCatalog? = withContext(Dispatchers.IO) {
        runCatching { readFile() }.getOrElse { error ->
            runCatching { cacheFile.delete() }
            null
        }
    }

    fun scheduleSave(
        catalogItems: List<GalleryDisplayItem>,
        fingerprint: GalleryCatalogFingerprint,
        localRevision: Long,
    ) {
        if (catalogItems.isEmpty()) return
        val key = persistKey(fingerprint, localRevision)
        if (key == lastSavedKey) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val toPersist = if (catalogItems.size <= MAX_PERSISTED_ITEMS) {
                    catalogItems
                } else {
                    catalogItems
                        .sortedByDescending { it.sortTimestamp }
                        .take(MAX_PERSISTED_ITEMS)
                }
                writeFile(toPersist, fingerprint, localRevision)
                lastSavedKey = key
            }
        }
    }

    private fun persistKey(fingerprint: GalleryCatalogFingerprint, localRevision: Long): String =
        "${fingerprint.rowCount}|${fingerprint.maxModifiedSeconds}|" +
            "${fingerprint.headPath}|${fingerprint.tailPath}|$localRevision"

    private fun writeFile(
        items: List<GalleryDisplayItem>,
        fingerprint: GalleryCatalogFingerprint,
        localRevision: Long,
    ) {
        val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        GZIPOutputStream(FileOutputStream(tmp)).use { gzip ->
            DataOutputStream(gzip).use { out ->
                out.writeInt(FORMAT_MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeLong(System.currentTimeMillis())
                out.writeLong(localRevision)
                out.writeInt(fingerprint.rowCount)
                out.writeLong(fingerprint.maxModifiedSeconds)
                writeNullableUtf(out, fingerprint.headPath)
                writeNullableUtf(out, fingerprint.tailPath)
                out.writeInt(items.size)
                for (item in items) {
                    writeItem(out, item)
                }
            }
        }
        if (cacheFile.exists()) cacheFile.delete()
        tmp.renameTo(cacheFile)
    }

    private fun readFile(): GalleryWarmCatalog? {
        if (!cacheFile.isFile || cacheFile.length() <= 0L) return null
        if (cacheFile.length() > MAX_CACHE_BYTES) {
            cacheFile.delete()
            return null
        }
        return GZIPInputStream(FileInputStream(cacheFile)).use { gzip ->
            DataInputStream(gzip).use { input ->
                if (input.readInt() != FORMAT_MAGIC) return null
                if (input.readInt() != FORMAT_VERSION) return null
                input.readLong() // savedAtMs
                val localRevision = input.readLong()
                val rowCount = input.readInt()
                val maxMod = input.readLong()
                val head = readNullableUtf(input)
                val tail = readNullableUtf(input)
                val count = input.readInt()
                if (count <= 0 || count > MAX_PERSISTED_ITEMS) {
                    return null
                }
                val items = ArrayList<GalleryDisplayItem>(count)
                repeat(count) {
                    items.add(readItem(input))
                }
                GalleryWarmCatalog(
                    catalogItems = items,
                    fingerprint = GalleryCatalogFingerprint(rowCount, head, tail, maxMod),
                    localRevision = localRevision,
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
        val kind = MediaKind.entries.getOrNull(input.readInt())
            ?: throw IllegalStateException("kind inválido no cache: $canonical")
        val cloudState = CloudSyncState.entries.getOrNull(input.readInt())
            ?: throw IllegalStateException("cloudState inválido no cache: $canonical")
        val localMediaId = readNullableLong(input)
        val oneDriveItemId = readNullableUtf(input)
        val pendingDelete = PendingDeleteTarget.entries.getOrNull(input.readInt())
            ?: PendingDeleteTarget.NONE
        val localUri = readNullableUtf(input)?.let { Uri.parse(it) }
        val cachedOriginal = readNullableUtf(input)?.let { path ->
            File(path).takeIf { it.isFile && it.length() > 0L }
        }
        val placeholder = readNullableUtf(input)?.let { path ->
            File(path).takeIf { it.isFile && it.length() > 0L }
        }
        val sort = input.readLong()
        val locationLabel = readNullableUtf(input)
        val lat = readNullableDouble(input)
        val lon = readNullableDouble(input)
        val duration = readNullableLong(input)
        val released = input.readBoolean()
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
            sortTimestamp = sort,
            locationLabel = locationLabel,
            latitude = lat,
            longitude = lon,
            videoDurationMs = duration,
            localReleasedForSpace = released,
        )
    }

    private fun writeNullableUtf(out: DataOutputStream, value: String?) {
        if (value == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeUTF(value)
        }
    }

    private fun readNullableUtf(input: DataInputStream): String? =
        if (input.readBoolean()) input.readUTF() else null

    private fun writeNullableLong(out: DataOutputStream, value: Long?) {
        if (value == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeLong(value)
        }
    }

    private fun readNullableLong(input: DataInputStream): Long? =
        if (input.readBoolean()) input.readLong() else null

    private fun writeNullableDouble(out: DataOutputStream, value: Double?) {
        if (value == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeDouble(value)
        }
    }

    private fun readNullableDouble(input: DataInputStream): Double? =
        if (input.readBoolean()) input.readDouble() else null

    data class GalleryWarmCatalog(
        val catalogItems: List<GalleryDisplayItem>,
        val fingerprint: GalleryCatalogFingerprint,
        val localRevision: Long,
    )

    companion object {
        private const val FORMAT_MAGIC = 0x4F474C52 // OGLR
        private const val FORMAT_VERSION = 1
        /** Evita OOM ao reabrir com biblioteca enorme — só as mais recentes. */
        const val MAX_PERSISTED_ITEMS = 4_000
        private const val MAX_CACHE_BYTES = 16L * 1024L * 1024L
    }
}
