package com.onegallery.sync.ui.gallery

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.SyncPaths
import java.io.File
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

data class MediaCaptureSpecs(
    val deviceLabel: String?,
    val statsPrimaryLine: String?,
    val statsSecondaryLine: String?,
)

internal fun formatStoragePathLabel(folderPath: String): String {
    val normalized = folderPath.trim('/').replace('\\', '/')
    if (normalized.isBlank() || normalized == "Raiz") {
        return "/Armazenamento interno"
    }
    return "/Armazenamento interno/$normalized"
}

internal fun formatSamsungDetailHeadline(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0L) return ""
    val zoned = java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
    val datePart = samsungDateFormatter.format(zoned)
    val timePart = samsungTimeFormatter.format(zoned)
    return "$datePart • $timePart"
}

private val ptBr = Locale("pt", "BR")

private val samsungDateFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", ptBr)

private val samsungTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm", ptBr)

internal fun formatFileSizePt(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val digitGroups = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(1024.0))
        .toInt()
        .coerceIn(1, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return String.format(ptBr, "%.2f %s", value, units[digitGroups - 1])
}

private fun megapixelLabel(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    val mp = (width.toLong() * height) / 1_000_000.0
    if (mp < 0.1) return null
    return "${mp.roundToInt()}MP"
}

private fun formatExposureTime(raw: String?): String? {
    val value = raw?.trim()?.toDoubleOrNull() ?: return raw?.takeIf { it.isNotBlank() }
    return if (value >= 1.0) {
        String.format(ptBr, "%.1f s", value)
    } else {
        val denom = (1.0 / value).roundToInt().coerceAtLeast(1)
        "1/$denom s"
    }
}

private fun joinStats(parts: List<String?>): String? {
    val line = parts.filterNot { it.isNullOrBlank() }.joinToString(" | ")
    return line.takeIf { it.isNotBlank() }
}

fun readMediaCaptureSpecs(
    context: Context,
    entity: SyncMetadataEntity,
    display: GalleryDisplayItem,
): MediaCaptureSpecs? {
    val exif = openExifInterface(context, display) ?: return fallbackSpecs(entity, display)
    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        .takeIf { it > 0 }
        ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        .takeIf { it > 0 }
        ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)

    val fileBytes = resolveReadableFile(display)?.length()
    val sizeLabel = fileBytes?.let(::formatFileSizePt)
    val dimensions = if (width > 0 && height > 0) "${width}x$height" else null
    val mp = if (width > 0 && height > 0) megapixelLabel(width, height) else null

    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
    val device = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }

    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
    val isoLabel = iso?.trim()?.takeIf { it.isNotBlank() }?.let { "ISO $it" }

    val focalRaw = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
    val focalLabel = focalRaw?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
        val mm = raw.toDoubleOrNull()
        if (mm != null) "${mm.roundToInt()}mm" else raw
    }

    val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.trim()?.takeIf { it.isNotBlank() }
    val fLabel = fNumber?.let { "F$it" }

    val exposure = formatExposureTime(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))

    return MediaCaptureSpecs(
        deviceLabel = device,
        statsPrimaryLine = joinStats(listOf(sizeLabel, dimensions, mp)),
        statsSecondaryLine = joinStats(listOf(isoLabel, focalLabel, fLabel, exposure)),
    ).takeIf {
        it.deviceLabel != null || it.statsPrimaryLine != null || it.statsSecondaryLine != null
    } ?: fallbackSpecs(entity, display)
}

private fun fallbackSpecs(
    entity: SyncMetadataEntity,
    display: GalleryDisplayItem,
): MediaCaptureSpecs? {
    val file = resolveReadableFile(display)
    val sizeLabel = file?.length()?.let(::formatFileSizePt)
    val primary = joinStats(listOf(sizeLabel))
    if (primary == null) return null
    return MediaCaptureSpecs(
        deviceLabel = null,
        statsPrimaryLine = primary,
        statsSecondaryLine = null,
    )
}

private fun resolveReadableFile(display: GalleryDisplayItem): File? {
    display.cachedOriginal?.takeIf { it.isFile }?.let { return it }
    display.localUri?.let { uri ->
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.takeIf { it.isFile }
        }
    }
    return null
}

private fun openExifInterface(context: Context, display: GalleryDisplayItem): ExifInterface? {
    resolveReadableFile(display)?.let { file ->
        return runCatching { ExifInterface(file.absolutePath) }.getOrNull()
    }
    val uri = display.localUri ?: return null
    return runCatching {
        when (uri.scheme) {
            "content" -> {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ExifInterface(input)
                }
            }
            "file" -> uri.path?.let { ExifInterface(it) }
            else -> null
        }
    }.getOrNull()
}
