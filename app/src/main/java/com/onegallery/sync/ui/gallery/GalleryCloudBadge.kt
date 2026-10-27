package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.ui.theme.OneDriveBlue
import android.content.Context

fun cloudBadgeState(item: GalleryDisplayItem, context: Context? = null): CloudSyncState {
    if (context != null && item.hasViewableLocalOriginal(context)) {
        return CloudSyncState.SYNCED
    }
    if (item.localReleasedForSpace && item.localUri == null && item.oneDriveItemId != null) {
        return CloudSyncState.CLOUD_ONLY
    }
    return item.cloudState
}

/**
 * Ícone da grade. Usa só o item já montado — a rolagem não consulta o MediaStore.
 * O detalhe continua em [galleryCloudOverlayState].
 */
fun galleryGridCloudBadge(item: GalleryDisplayItem): CloudSyncState? {
    if (item.oneDriveItemId != null) {
        val onDevice = item.localUri != null || item.cachedOriginal != null
        if (!onDevice && item.localReleasedForSpace) return CloudSyncState.CLOUD_ONLY
        if (item.cloudState == CloudSyncState.SYNCED && !item.localReleasedForSpace) {
            return CloudSyncState.SYNCED
        }
        if (onDevice || (item.localMediaId != null && !item.localReleasedForSpace)) {
            return CloudSyncState.SYNCED
        }
        return when (item.cloudState) {
            CloudSyncState.PENDING_UPLOAD -> CloudSyncState.LOCAL_ONLY
            else -> CloudSyncState.CLOUD_ONLY
        }
    }
    if (item.localReleasedForSpace) return null
    if (item.cloudState == CloudSyncState.SYNCED) return CloudSyncState.SYNCED
    val onDevice = item.localUri != null || item.cachedOriginal != null || item.localMediaId != null
    return when (item.cloudState) {
        CloudSyncState.LOCAL_ONLY,
        CloudSyncState.PENDING_UPLOAD,
        -> if (onDevice) CloudSyncState.LOCAL_ONLY else null
        else -> null
    }
}

/**
 * A consulta ao MediaStore não pode apagar o visto de Sync.
 * No álbum a grade fica parada e essa consulta terminava trocando a nuvem com ✓
 * por uma nuvem simples.
 */
fun mergeGalleryGridBadge(
    instant: CloudSyncState?,
    precise: CloudSyncState?,
): CloudSyncState? {
    if (instant == CloudSyncState.SYNCED) return CloudSyncState.SYNCED
    return precise ?: instant
}

/** Ícone no canto da grade / detalhe: celular (só local), nuvem ou nuvem com ✓. */
fun galleryCloudOverlayState(item: GalleryDisplayItem, context: Context): CloudSyncState? {
    if (item.oneDriveItemId != null) {
        if (item.hasViewableLocalOriginal(context)) {
            return CloudSyncState.SYNCED
        }
        return when (item.cloudState) {
            CloudSyncState.PENDING_UPLOAD -> CloudSyncState.LOCAL_ONLY
            else -> CloudSyncState.CLOUD_ONLY
        }
    }
    return if (item.isDeviceOnlyBadge(context)) CloudSyncState.LOCAL_ONLY else null
}

/** Estado do ícone de nuvem na visualização expandida (recall / cópia local). */
fun detailCloudBadgeState(
    item: GalleryDisplayItem,
    recalling: Boolean,
    context: Context,
): CloudSyncState? {
    if (recalling) return CloudSyncState.CLOUD_ONLY
    return galleryCloudOverlayState(item, context)
        ?: if (item.isDeviceOnlyBadge(context)) CloudSyncState.LOCAL_ONLY else null
}

private fun GalleryDisplayItem.isDeviceOnlyBadge(context: Context): Boolean {
    if (oneDriveItemId != null) return false
    if (localReleasedForSpace) return false
    return when (cloudState) {
        CloudSyncState.LOCAL_ONLY,
        CloudSyncState.PENDING_UPLOAD,
        -> hasViewableLocalOriginal(context)
        else -> false
    }
}

@Composable
fun GalleryCloudBadge(
    state: CloudSyncState,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    recalling: Boolean = false,
) {
    val icon = when (state) {
        CloudSyncState.SYNCED -> Icons.Outlined.CloudDone
        CloudSyncState.LOCAL_ONLY -> Icons.Outlined.Smartphone
        CloudSyncState.CLOUD_ONLY,
        CloudSyncState.PENDING_DOWNLOAD,
        CloudSyncState.PENDING_UPLOAD,
        -> Icons.Outlined.Cloud
        else -> Icons.Outlined.Cloud
    }
    val description = when {
        recalling -> "Baixando original da nuvem"
        state == CloudSyncState.LOCAL_ONLY -> "Só no aparelho"
        state == CloudSyncState.SYNCED -> "Sincronizado com a nuvem"
        state == CloudSyncState.CLOUD_ONLY -> "Só na nuvem"
        else -> state.name
    }
    val tint = when (state) {
        CloudSyncState.LOCAL_ONLY -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> OneDriveBlue
    }
    Box(modifier = modifier.size(iconSize), contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
        if (recalling) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = (iconSize.value / 8f).coerceAtLeast(1.5f).dp,
                color = OneDriveBlue,
            )
        }
    }
}

/** Ícone de sync na barra do detalhe (fixo abaixo do ⋮), não sobre a mídia. */
@Composable
fun MediaDetailTopBarCloudStatus(
    item: GalleryDisplayItem,
    recalling: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = detailCloudBadgeState(item, recalling, context) ?: return
    Row(
        modifier = modifier.padding(end = 12.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryCloudBadge(state = state, iconSize = 22.dp, recalling = recalling)
    }
}
