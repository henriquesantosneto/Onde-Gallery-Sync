package com.onegallery.sync.ui.gallery

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Capa já escolhida para um álbum. Troca no máximo uma vez por [ALBUM_COVER_REPLACE_INTERVAL_MS]. */
data class AlbumCoverChoice(
    val canonicalPath: String,
    val sortTimestamp: Long,
    val chosenAtEpochMs: Long,
)

/** Com capa salva, uma foto mais recente só entra depois deste intervalo. */
const val ALBUM_COVER_REPLACE_INTERVAL_MS = 3 * 60 * 1000L

/**
 * Mantém a capa atual se ela ainda existe.
 * Sem capa, ou se a salva sumiu, escolhe na hora a mais recente que dá para pintar.
 * Com capa, uma foto mais nova só substitui depois do intervalo.
 */
fun chooseStickyAlbumCover(
    items: List<GalleryDisplayItem>,
    remembered: AlbumCoverChoice?,
    nowEpochMs: Long,
    minReplaceIntervalMs: Long = ALBUM_COVER_REPLACE_INTERVAL_MS,
    isPaintable: (GalleryDisplayItem) -> Boolean,
): GalleryDisplayItem? {
    if (items.isEmpty()) return null
    val current = remembered?.let { memory ->
        items.find { it.canonicalPath == memory.canonicalPath }
    }?.takeIf(isPaintable)
    if (current != null &&
        remembered != null &&
        nowEpochMs - remembered.chosenAtEpochMs < minReplaceIntervalMs
    ) {
        return current
    }
    val newest = items
        .asSequence()
        .filter(isPaintable)
        .maxWithOrNull(compareBy<GalleryDisplayItem>({ it.sortTimestamp }, { it.canonicalPath }))
    if (newest == null) return current
    if (current == null) return newest
    if (newest.canonicalPath != current.canonicalPath && newest.sortTimestamp > current.sortTimestamp) {
        return newest
    }
    return current
}

/** Memória da capa por álbum, também em disco, para não recalcular a cada miniatura. */
class AlbumCoverChoiceStore {
    private val choices = ConcurrentHashMap<String, AlbumCoverChoice>()
    private val dirty = AtomicBoolean(false)

    fun remembered(bucketId: String): AlbumCoverChoice? = choices[bucketId]

    fun choose(
        bucketId: String,
        items: List<GalleryDisplayItem>,
        nowEpochMs: Long,
        isPaintable: (GalleryDisplayItem) -> Boolean,
    ): GalleryDisplayItem? {
        val picked = chooseStickyAlbumCover(
            items = items,
            remembered = choices[bucketId],
            nowEpochMs = nowEpochMs,
            isPaintable = isPaintable,
        )
        if (picked == null) {
            if (choices.remove(bucketId) != null) dirty.set(true)
            return null
        }
        val previous = choices[bucketId]
        val replaced = previous == null || previous.canonicalPath != picked.canonicalPath
        val intervalElapsed = previous != null &&
            nowEpochMs - previous.chosenAtEpochMs >= ALBUM_COVER_REPLACE_INTERVAL_MS
        if (replaced || intervalElapsed) {
            choices[bucketId] = AlbumCoverChoice(
                canonicalPath = picked.canonicalPath,
                sortTimestamp = picked.sortTimestamp,
                chosenAtEpochMs = nowEpochMs,
            )
            dirty.set(true)
        }
        return picked
    }

    fun load(file: File) {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        text.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@forEach
            val bucket = decode(parts[0]) ?: return@forEach
            val path = decode(parts[1]) ?: return@forEach
            val stamp = parts[2].toLongOrNull() ?: return@forEach
            val chosenAt = parts[3].toLongOrNull() ?: return@forEach
            choices[bucket] = AlbumCoverChoice(path, stamp, chosenAt)
        }
        dirty.set(false)
    }

    fun saveIfDirty(file: File) {
        if (!dirty.compareAndSet(true, false)) return
        val body = buildString {
            choices.forEach { (bucket, choice) ->
                append(encode(bucket))
                append('\t')
                append(encode(choice.canonicalPath))
                append('\t')
                append(choice.sortTimestamp)
                append('\t')
                append(choice.chosenAtEpochMs)
                append('\n')
            }
        }
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val tmp = File(parent, "${file.name}.tmp")
        runCatching {
            tmp.writeText(body)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(body)
                tmp.delete()
            }
        }.onFailure {
            dirty.set(true)
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()
}
