package com.onegallery.sync.data.onedrive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File
import kotlin.math.max

/** Normaliza JPEG/PNG baixados do Graph antes de commit no cache local. */
object CloudThumbnailStaging {
    const val THUMB_CACHE_MAX_SIDE_PX = 1280
    const val THUMB_CACHE_JPEG_QUALITY = 88

    fun acceptDownloadedCloudThumbnail(
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

    fun acceptDownloadedCloudVideoThumbnail(
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

    fun reencodeFileToDisplayableThumbnail(
        source: File,
        destination: File,
        mediaCacheRepository: MediaCacheRepository,
        maxSidePx: Int = THUMB_CACHE_MAX_SIDE_PX,
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
            val sample = thumbnailInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
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
        return isStagingDisplayableThumbnail(destination, mediaCacheRepository)
    }

    private fun isStagingDisplayableThumbnail(
        staging: File,
        mediaCacheRepository: MediaCacheRepository,
    ): Boolean {
        return staging.isFile &&
            staging.length() > 0L &&
            mediaCacheRepository.isDisplayableGalleryThumbFile(staging)
    }

    /**
     * Valida e grava miniatura de vídeo (poster Graph ou frame extraído).
     * @return arquivo commitado ou null se inválido.
     */
    fun commitGalleryVideoThumbnailIfValid(
        staging: File,
        mediaCacheRepository: MediaCacheRepository,
        canonicalPath: String,
        oneDriveItemId: String?,
        fromGraphPoster: Boolean,
    ): File? {
        if (!mediaCacheRepository.isDisplayableGalleryThumbFile(staging)) {
            if (!reencodeFileToDisplayableThumbnail(staging, staging, mediaCacheRepository)) {
                staging.delete()
                return null
            }
        }
        val frameOk = VideoThumbnailHelper.isDisplayableVideoThumbnailFile(staging)
        val posterOk = fromGraphPoster && VideoThumbnailHelper.isCloudPosterThumbnailFile(staging)
        if (!frameOk && !posterOk) {
            staging.delete()
            return null
        }
        return mediaCacheRepository.commitThumbnail(staging, canonicalPath, oneDriveItemId)
    }

    fun thumbnailInSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
        var sample = 1
        while (width / sample > maxSidePx || height / sample > maxSidePx) {
            sample *= 2
        }
        return max(1, sample)
    }
}
