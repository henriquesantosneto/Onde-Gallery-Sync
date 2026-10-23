package com.onegallery.sync.data.onedrive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File
import kotlin.math.max

/** Graph + amostra parcial de MP4 para miniatura de vídeo na nuvem (grade + cache). */
object CloudVideoThumbnailLoader {

    enum class Outcome {
        NONE,
        GRAPH_POSTER,
        EXTRACTED_FRAME,
    }

    suspend fun loadIntoFile(
        repository: OneDriveRepository,
        mediaCacheRepository: MediaCacheRepository,
        itemId: String,
        staging: File,
        remoteSizeBytes: Long?,
        aggressive: Boolean,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)?,
    ): Outcome {
        staging.delete()
        fun acceptCloudImageThumb(): Boolean {
            return acceptDownloadedCloudVideoThumbnail(staging, mediaCacheRepository) ||
                acceptDownloadedCloudThumbnail(staging, mediaCacheRepository, isVideo = false)
        }
        fun captureFromPartial(partial: File): Boolean {
            if (!partial.isFile || partial.length() <= 0L) return false
            staging.delete()
            val ok = VideoThumbnailHelper.captureToFile(partial, staging) ||
                VideoThumbnailHelper.captureLenientFileToFile(partial, staging)
            if (!ok) {
                staging.delete()
                return false
            }
            if (!VideoThumbnailHelper.isDisplayableVideoThumbnailFile(staging)) {
                staging.delete()
                return false
            }
            return mediaCacheRepository.isDisplayableGalleryThumbFile(staging)
        }

        var graphPoster = false
        repository.downloadBestListedThumbnailToFile(itemId, staging, onProgress).onSuccess {
            graphPoster = acceptCloudImageThumb()
            if (!graphPoster) staging.delete()
        }
        if (graphPoster) return Outcome.GRAPH_POSTER

        repository.downloadThumbnailToFile(itemId, staging, onProgress).onSuccess {
            graphPoster = acceptCloudImageThumb()
            if (!graphPoster) staging.delete()
        }
        if (graphPoster) return Outcome.GRAPH_POSTER

        val remoteSize = remoteSizeBytes?.takeIf { it > 0L }
            ?: repository.fetchItemSizeBytes(itemId)
        val fullCap = if (aggressive) {
            CLOUD_VIDEO_ON_DEMAND_FULL_FETCH_MAX_BYTES
        } else {
            CLOUD_VIDEO_FULL_FETCH_MAX_BYTES
        }
        val tailCap = if (aggressive) {
            CLOUD_VIDEO_ON_DEMAND_TAIL_MAX_BYTES
        } else {
            CLOUD_VIDEO_THUMB_TAIL_MAX_BYTES
        }

        if (remoteSize != null && remoteSize <= fullCap) {
            val full = File(staging.absolutePath + ".partial.full")
            full.delete()
            var extracted = false
            repository.downloadCappedContentForVideoThumbnail(
                itemId,
                full,
                maxBytes = remoteSize,
                knownSizeBytes = remoteSize,
                onProgress = onProgress,
            ).onSuccess {
                extracted = captureFromPartial(full)
            }
            full.delete()
            if (extracted) return Outcome.EXTRACTED_FRAME
        }

        val tail = File(staging.absolutePath + ".partial.vidtail")
        tail.delete()
        var tailOk = false
        repository.downloadFileSuffixForThumbnail(
            itemId,
            tail,
            maxBytes = tailCap,
            knownSizeBytes = remoteSize,
            onProgress = onProgress,
        ).onSuccess {
            tailOk = captureFromPartial(tail)
        }
        tail.delete()
        if (tailOk) return Outcome.EXTRACTED_FRAME

        val head = File(staging.absolutePath + ".partial.vidhead")
        head.delete()
        var headOk = false
        repository.downloadCappedContentForVideoThumbnail(
            itemId,
            head,
            maxBytes = CLOUD_VIDEO_THUMB_HEAD_MAX_BYTES,
            knownSizeBytes = remoteSize,
            onProgress = onProgress,
        ).onSuccess {
            headOk = captureFromPartial(head)
        }
        head.delete()
        if (headOk) return Outcome.EXTRACTED_FRAME

        if (remoteSize != null && remoteSize > CLOUD_VIDEO_THUMB_HEAD_MAX_BYTES * 2L) {
            val mid = File(staging.absolutePath + ".partial.vidmid")
            mid.delete()
            var midOk = false
            repository.downloadMiddleRangeForVideoThumbnail(
                itemId,
                mid,
                maxBytes = CLOUD_VIDEO_THUMB_HEAD_MAX_BYTES,
                knownSizeBytes = remoteSize,
                onProgress = onProgress,
            ).onSuccess {
                midOk = captureFromPartial(mid)
            }
            mid.delete()
            if (midOk) return Outcome.EXTRACTED_FRAME
        }

        staging.delete()
        return Outcome.NONE
    }

    private fun isStagingDisplayableThumbnail(
        staging: File,
        mediaCacheRepository: MediaCacheRepository,
    ): Boolean {
        return staging.isFile &&
            staging.length() > 0L &&
            mediaCacheRepository.isDisplayableGalleryThumbFile(staging)
    }

    private fun acceptDownloadedCloudThumbnail(
        staging: File,
        mediaCacheRepository: MediaCacheRepository,
        isVideo: Boolean,
    ): Boolean {
        if (isVideo) {
            return isStagingDisplayableThumbnail(staging, mediaCacheRepository) &&
                VideoThumbnailHelper.isAcceptableThumbnailFile(staging)
        }
        if (isStagingDisplayableThumbnail(staging, mediaCacheRepository)) return true
        return reencodeFileToDisplayableThumbnail(staging, staging, mediaCacheRepository)
    }

    private fun acceptDownloadedCloudVideoThumbnail(
        staging: File,
        mediaCacheRepository: MediaCacheRepository,
    ): Boolean {
        if (acceptDownloadedCloudThumbnail(staging, mediaCacheRepository, isVideo = true)) return true
        if (reencodeFileToDisplayableThumbnail(staging, staging, mediaCacheRepository) &&
            VideoThumbnailHelper.isAcceptableThumbnailFile(staging)
        ) {
            return true
        }
        staging.delete()
        return false
    }

    private fun reencodeFileToDisplayableThumbnail(
        source: File,
        destination: File,
        mediaCacheRepository: MediaCacheRepository,
    ): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        val normalized = if (source.absolutePath == destination.absolutePath) {
            File(source.absolutePath + ".reencode")
        } else {
            destination
        }
        normalized.delete()
        val ok = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val sample = thumbnailInSampleSize(bounds.outWidth, bounds.outHeight, THUMB_CACHE_MAX_SIDE_PX)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return false
            try {
                normalized.parentFile?.mkdirs()
                normalized.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_CACHE_JPEG_QUALITY, out)
                }
                normalized.isFile && mediaCacheRepository.isDisplayableGalleryThumbFile(normalized)
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
        if (!ok) {
            normalized.delete()
            return false
        }
        if (normalized.absolutePath != destination.absolutePath) {
            destination.delete()
            if (!normalized.renameTo(destination)) {
                normalized.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                normalized.delete()
            }
        }
        return destination.isFile && destination.length() > 0L
    }

    private fun thumbnailInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide || height / sample > maxSide) {
            sample *= 2
        }
        return max(1, sample)
    }

    private const val THUMB_CACHE_MAX_SIDE_PX = 1280
    private const val THUMB_CACHE_JPEG_QUALITY = 88
    private const val CLOUD_VIDEO_THUMB_TAIL_MAX_BYTES = 48L * 1024L * 1024L
    private const val CLOUD_VIDEO_ON_DEMAND_TAIL_MAX_BYTES = 96L * 1024L * 1024L
    private const val CLOUD_VIDEO_THUMB_HEAD_MAX_BYTES = 24L * 1024L * 1024L
    private const val CLOUD_VIDEO_FULL_FETCH_MAX_BYTES = 25L * 1024L * 1024L
    private const val CLOUD_VIDEO_ON_DEMAND_FULL_FETCH_MAX_BYTES = 120L * 1024L * 1024L
}
