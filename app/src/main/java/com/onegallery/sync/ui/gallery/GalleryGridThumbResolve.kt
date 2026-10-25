package com.onegallery.sync.ui.gallery

import android.graphics.BitmapFactory
import android.net.Uri
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import java.io.File

internal fun GalleryDisplayItem.resolvedGalleryGridThumbFile(
    mediaCache: MediaCacheRepository,
): File? {
    return when (kind) {
        MediaKind.VIDEO -> resolvedGridVideoThumbFile(mediaCache)
        MediaKind.IMAGE -> {
            mediaCache.displayableThumbnailIfCached(canonicalPath, oneDriveItemId)
                ?: cachedPlaceholderThumb?.takeIf {
                    mediaCache.isDisplayableGalleryThumbFile(it)
                }
        }
        else -> null
    }
}

private const val MAX_INLINE_IMAGE_BYTES = 2_500_000L

/**
 * Coil ignora arquivo cuja extensão não é de imagem (miniatura de vídeo salva
 * como `.mp4`). Só então lê os bytes — e só se o conteúdo for imagem pequena.
 * Nunca lê o vídeo inteiro: isso derrubava o processo ao abrir a galeria.
 */
internal fun File.asGalleryImageModel(): Any? {
    if (!isFile || length() <= 0L) return null
    val ext = extension.lowercase()
    if (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" || ext == "gif") {
        return this
    }
    if (length() > MAX_INLINE_IMAGE_BYTES) return null
    if (!looksLikeImageHeader()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, bounds)
    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return null
    return runCatching { readBytes() }.getOrNull()
}

private fun File.looksLikeImageHeader(): Boolean {
    val header = ByteArray(12)
    val read = runCatching { inputStream().use { it.read(header) } }.getOrDefault(-1)
    if (read < 3) return false
    if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return true
    if (read >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte()) return true
    if (read >= 6 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte()) return true
    if (read >= 12 &&
        header[0] == 'R'.code.toByte() &&
        header[1] == 'I'.code.toByte() &&
        header[8] == 'W'.code.toByte()
    ) {
        return true
    }
    return false
}

internal fun Uri.isVideoContentUri(): Boolean {
    val value = toString()
    return value.contains("/video/", ignoreCase = true) ||
        value.contains("video%2F", ignoreCase = true)
}

/** URI de vídeo não pode ir para o Coil (VideoFrameDecoder derruba o processo). */
internal fun Any?.asDeviceVideoCover(): DeviceVideoCover? = when (this) {
    is DeviceVideoCover -> this
    is Uri -> if (isVideoContentUri()) DeviceVideoCover(this, null) else null
    else -> null
}

internal fun GalleryDisplayItem.isCloudBackedGalleryItem(): Boolean =
    !oneDriveItemId.isNullOrBlank() ||
        cloudState == com.onegallery.sync.sync.CloudSyncState.CLOUD_ONLY ||
        cloudState == com.onegallery.sync.sync.CloudSyncState.PENDING_DOWNLOAD
