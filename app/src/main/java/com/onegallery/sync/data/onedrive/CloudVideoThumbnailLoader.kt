package com.onegallery.sync.data.onedrive

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File

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
        /** Sync em massa não extrai quadro de MP4 parcial: isso fecha o processo sem exceção. */
        extractFrames: Boolean = true,
    ): Outcome {
        staging.delete()
        fun acceptCloudImageThumb(): Boolean {
            return CloudThumbnailStaging.acceptDownloadedCloudVideoThumbnail(staging, mediaCacheRepository) ||
                CloudThumbnailStaging.acceptDownloadedCloudThumbnail(
                    staging,
                    mediaCacheRepository,
                    isVideo = false,
                )
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
        if (!extractFrames) {
            staging.delete()
            return Outcome.NONE
        }

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

    private const val CLOUD_VIDEO_THUMB_TAIL_MAX_BYTES = 48L * 1024L * 1024L
    private const val CLOUD_VIDEO_ON_DEMAND_TAIL_MAX_BYTES = 96L * 1024L * 1024L
    private const val CLOUD_VIDEO_THUMB_HEAD_MAX_BYTES = 24L * 1024L * 1024L
    private const val CLOUD_VIDEO_FULL_FETCH_MAX_BYTES = 25L * 1024L * 1024L
    private const val CLOUD_VIDEO_ON_DEMAND_FULL_FETCH_MAX_BYTES = 120L * 1024L * 1024L
}
