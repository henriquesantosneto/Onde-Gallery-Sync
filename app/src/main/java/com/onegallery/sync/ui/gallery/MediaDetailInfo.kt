package com.onegallery.sync.ui.gallery

import android.content.Context
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.sync.SyncPaths
import com.onegallery.sync.sync.queueDetailLabel
import com.onegallery.sync.sync.syncQueueStatusLabel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

data class MediaDetailInfo(
    val displayName: String,
    val kindLabel: String,
    val albumLabel: String,
    val capturedAtSeconds: Long?,
    val capturedAtFormatted: String,
    val capturedAtFriendly: String,
    val samsungHeadline: String,
    val storagePathLabel: String,
    val captureSpecs: MediaCaptureSpecs?,
    val locationLabel: String?,
    val mapLatitude: Double?,
    val mapLongitude: Double?,
    val syncLabel: String,
    val advancedRows: List<Pair<String, String>>,
) {
    val hasLocationMap: Boolean
        get() = mapLatitude != null && mapLongitude != null
}

private val ptBr = Locale("pt", "BR")

private val detailDateTimeFormatter =
    DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", ptBr)

internal fun formatMediaDateTime(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0L) return "—"
    return Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(detailDateTimeFormatter)
}

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups - 1])
}

private fun cloudSyncStateLabel(state: CloudSyncState): String = when (state) {
    CloudSyncState.LOCAL_ONLY -> "Só no aparelho"
    CloudSyncState.CLOUD_ONLY -> "Só na nuvem"
    CloudSyncState.SYNCED -> "Sincronizado"
    CloudSyncState.PENDING_UPLOAD -> "Envio pendente"
    CloudSyncState.PENDING_DOWNLOAD -> "Download pendente"
    CloudSyncState.CONFLICT -> "Conflito"
    CloudSyncState.ERROR -> "Erro"
}

fun buildMediaDetailInfo(
    entity: SyncMetadataEntity,
    display: GalleryDisplayItem,
    context: Context? = null,
): MediaDetailInfo {
    val kindLabel = when (display.kind) {
        MediaKind.VIDEO -> "Vídeo"
        MediaKind.IMAGE -> "Foto"
    }
    val localCopy = when {
        display.localUri != null -> "Galeria do aparelho"
        display.cachedOriginal != null -> {
            val size = display.cachedOriginal.length()
            "Cache do app (${formatFileSize(size)})"
        }
        else -> "Não (só na nuvem)"
    }
    val coords = when {
        display.latitude != null && display.longitude != null ->
            String.format(Locale.US, "%.5f, %.5f", display.latitude, display.longitude)
        else -> null
    }
    val folderPath = entity.albumName.takeIf { it.isNotBlank() }
        ?: SyncPaths.albumFromCanonical(entity.canonicalPath)
    val userAlbum = entity.userAlbumName?.takeIf { it.isNotBlank() }
    val galleryAlbumLabel = userAlbum
        ?: SyncPaths.albumListDisplayName(folderPath)

    val capturedAt = entity.capturedAtSeconds?.takeIf { it > 0L }
        ?: display.sortTimestamp.takeIf { it > 0L }

    val hasMap = display.latitude != null && display.longitude != null

    val advancedRows = buildList {
        add("Nome" to display.displayName)
        add("Tipo" to kindLabel)
        add("Álbum na galeria" to galleryAlbumLabel)
        if (userAlbum != null) {
            add("Álbum personalizado" to userAlbum)
        }
        if (folderPath.isNotBlank() && folderPath != "Raiz") {
            add("Pasta (caminho completo)" to folderPath)
        }
        capturedAt?.let { add("Quando foi tirada" to formatMediaDateTime(it)) }
        add("Alteração no aparelho" to formatMediaDateTime(entity.localModifiedAt))
        add("Alteração na nuvem" to formatMediaDateTime(entity.remoteModifiedAt))
        if (!hasMap) {
            display.locationLabel?.takeIf { it.isNotBlank() }?.let { add("Local" to it) }
            coords?.let { add("Coordenadas" to it) }
        }
        add("Sincronização" to cloudSyncStateLabel(display.cloudState))
        add("Fila" to entity.syncQueueStatusLabel())
        if (entity.pendingDeleteTarget != PendingDeleteTarget.NONE) {
            add("Exclusão" to entity.queueDetailLabel())
        }
        add("Cópia local" to localCopy)
        entity.cachedFilePath?.let { path ->
            File(path).takeIf { it.isFile }?.let { file ->
                add("Arquivo em cache" to "${file.name} (${formatFileSize(file.length())})")
            }
        }
        add("Caminho OneDrive" to entity.canonicalPath)
        entity.localMediaId?.let { add("ID no aparelho" to it.toString()) }
        entity.oneDriveItemId?.takeIf { it.isNotBlank() }?.let { add("ID OneDrive" to it) }
        entity.lastError?.takeIf { it.isNotBlank() }?.let { add("Último erro" to it) }
    }

    val storagePath = formatStoragePathLabel(
        folderPath.ifBlank { SyncPaths.albumFromCanonical(entity.canonicalPath) },
    )
    val specs = context?.let { readMediaCaptureSpecs(it, entity, display) }

    return MediaDetailInfo(
        displayName = display.displayName,
        kindLabel = kindLabel,
        albumLabel = galleryAlbumLabel,
        capturedAtSeconds = capturedAt,
        capturedAtFormatted = formatMediaDateTime(capturedAt),
        capturedAtFriendly = capturedAt?.let { formatMediaCaptureDate(it) }.orEmpty(),
        samsungHeadline = formatSamsungDetailHeadline(capturedAt),
        storagePathLabel = storagePath,
        captureSpecs = specs,
        locationLabel = display.locationLabel?.takeIf { it.isNotBlank() },
        mapLatitude = display.latitude?.takeIf { display.longitude != null },
        mapLongitude = display.longitude?.takeIf { display.latitude != null },
        syncLabel = cloudSyncStateLabel(display.cloudState),
        advancedRows = advancedRows,
    )
}
