package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class AlbumCoverDiskStamp(
    val revision: Long,
    val isVideo: Boolean,
    val cacheKey: String,
)

internal data class AlbumCoverDiskRead(
    val file: File,
    val stamp: AlbumCoverDiskStamp,
)

internal fun albumCoverDiskName(bucketId: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(bucketId.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun encodeAlbumCoverStamp(stamp: AlbumCoverDiskStamp): String {
    val video = if (stamp.isVideo) "1" else "0"
    return "${stamp.revision}\n$video\n${stamp.cacheKey}"
}

internal fun decodeAlbumCoverStamp(text: String): AlbumCoverDiskStamp? {
    val lines = text.trim().split("\n", limit = 3)
    if (lines.size < 2) return null
    val revision = lines[0].toLongOrNull() ?: return null
    return AlbumCoverDiskStamp(
        revision = revision,
        isVideo = lines[1] == "1",
        cacheKey = lines.getOrElse(2) { "" },
    )
}

/** JPEG pequeno de cada capa. A grade lê isto em vez de procurar a foto de novo. */
internal class AlbumCoverDiskStore(private val root: File) {
    fun read(bucketId: String, revision: Long): AlbumCoverDiskRead? {
        val image = imageFile(bucketId)
        val stampFile = stampFile(bucketId)
        if (!image.isFile || image.length() < 128L || !stampFile.isFile) return null
        val stamp = decodeAlbumCoverStamp(stampFile.readText()) ?: return null
        if (stamp.revision != revision) return null
        return AlbumCoverDiskRead(image, stamp)
    }

    fun write(bucketId: String, bitmap: Bitmap, stamp: AlbumCoverDiskStamp): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (!root.exists() && !root.mkdirs()) return false
        val image = imageFile(bucketId)
        val tmp = File(root, image.name + ".tmp")
        val wrote = runCatching {
            tmp.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
            }
            tmp.isFile && tmp.length() >= 128L
        }.getOrDefault(false)
        if (!wrote) {
            tmp.delete()
            return false
        }
        if (image.exists() && !image.delete()) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(image)) {
            val copied = runCatching { tmp.copyTo(image, overwrite = true) }.isSuccess
            tmp.delete()
            if (!copied || !image.isFile) return false
        }
        return runCatching {
            stampFile(bucketId).writeText(encodeAlbumCoverStamp(stamp))
            true
        }.getOrDefault(false)
    }

    private fun imageFile(bucketId: String): File = File(root, albumCoverDiskName(bucketId) + ".jpg")

    private fun stampFile(bucketId: String): File = File(root, albumCoverDiskName(bucketId) + ".stamp")
}

internal data class PreparedAlbumCover(
    val model: Any?,
    val isVideo: Boolean,
    val cacheKey: String,
)

internal suspend fun decodePreparedAlbumCover(
    context: Context,
    album: MediaAlbum,
    mediaCache: MediaCacheRepository,
    live: LiveAlbumCover,
): PreparedAlbumCover {
    val attempts = ArrayList<Pair<GalleryDisplayItem?, Any?>>(1 + live.alternates.size)
    attempts.add(live.sourceItem to live.displayModel)
    for (alternate in live.alternates) {
        attempts.add(alternate to resolveGalleryCoverModel(alternate, mediaCache))
    }
    var bitmap: Bitmap? = null
    var chosen = live.sourceItem
    var coilFallback: Any? = null
    for ((candidate, model) in attempts) {
        val sources = albumCoverBitmapCandidates(model, candidate)
        for (source in sources) {
            if (source.isBlankSyncedCover(mediaCache)) continue
            val decoded = GalleryThumbnailModels.decodeAlbumCoverBitmap(context, source, 480)
            if (decoded != null && !decoded.isRecycled) {
                bitmap = decoded
                chosen = candidate
                break
            }
            if (coilFallback == null && preparedCoverCoilModel(source) != null) {
                coilFallback = source
            }
        }
        if (bitmap != null) break
    }
    return PreparedAlbumCover(
        model = bitmap ?: coilFallback,
        isVideo = chosen?.kind == MediaKind.VIDEO,
        cacheKey = chosen?.canonicalPath ?: album.bucketId,
    )
}

internal fun preparedCoverCoilModel(model: Any?): Any? {
    if (model.asDeviceVideoCover() != null) return null
    return when (model) {
        is File -> {
            if (!model.isSafeGridImageFile()) return null
            val ext = model.extension.lowercase()
            if (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" || ext == "gif") {
                model
            } else {
                DiskThumb(model)
            }
        }
        else -> model
    }
}

/**
 * Grava e decodifica a capa de cada álbum com a lista parada,
 * para a grade abrir com a foto já em memória.
 */
internal class AlbumCoverWarmer(
    private val context: Context,
    private val disk: AlbumCoverDiskStore,
    private val mediaCache: MediaCacheRepository,
) {
    suspend fun warm(
        albums: List<MediaAlbum>,
        revisions: Map<String, Long>,
        liveCover: suspend (MediaAlbum) -> LiveAlbumCover,
    ) {
        for (album in albums) {
            coroutineContext.ensureActive()
            while (GalleryGridScrollGate.isUserScrollingGrid) delay(48)
            val revision = revisions[album.bucketId] ?: 0L
            val memory = AlbumCoverBitmapCache.get(album.bucketId)
            val memoryBitmap = memory?.model as? Bitmap
            if (memory != null &&
                !memory.empty &&
                memory.revision == revision &&
                memoryBitmap != null &&
                !memoryBitmap.isRecycled
            ) {
                if (disk.read(album.bucketId, revision) == null) {
                    disk.write(
                        album.bucketId,
                        memoryBitmap,
                        AlbumCoverDiskStamp(revision, memory.isVideo, memory.cacheKey),
                    )
                }
                continue
            }
            val saved = disk.read(album.bucketId, revision)
            if (saved != null) {
                val decoded = GalleryThumbnailModels.decodeAlbumCoverBitmap(context, saved.file, 480)
                if (decoded != null && !decoded.isRecycled) {
                    AlbumCoverBitmapCache.put(
                        album.bucketId,
                        decoded,
                        saved.stamp.isVideo,
                        saved.stamp.cacheKey.ifBlank { album.bucketId },
                        revision,
                    )
                    continue
                }
            }
            val live = liveCover(album)
            val prepared = decodePreparedAlbumCover(context, album, mediaCache, live)
            val bitmap = prepared.model as? Bitmap ?: continue
            disk.write(
                album.bucketId,
                bitmap,
                AlbumCoverDiskStamp(revision, prepared.isVideo, prepared.cacheKey),
            )
            AlbumCoverBitmapCache.put(
                album.bucketId,
                bitmap,
                prepared.isVideo,
                prepared.cacheKey,
                revision,
            )
        }
    }
}
