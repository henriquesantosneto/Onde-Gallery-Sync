package com.onegallery.sync.ui.gallery

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

/** Ícone no canto da grade / detalhe: celular (só local), nuvem ou nuvem com ✓. */
fun galleryCloudOverlayState(item: GalleryDisplayItem, context: Context): CloudSyncState? {
    val hasCloud = item.oneDriveItemId != null
    if (!hasCloud) {
        return if (item.isDeviceOnlyBadge(context)) CloudSyncState.LOCAL_ONLY else null
    }
    if (item.hasViewableLocalOriginal(context)) return CloudSyncState.SYNCED
    val badge = cloudBadgeState(item, context)
    return when (badge) {
        CloudSyncState.CLOUD_ONLY,
        CloudSyncState.PENDING_DOWNLOAD,
        CloudSyncState.PENDING_UPLOAD,
        -> badge
        else -> null
    }
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
    val description = when (state) {
        CloudSyncState.LOCAL_ONLY -> "Só no aparelho"
        CloudSyncState.SYNCED -> "Sincronizado com a nuvem"
        CloudSyncState.CLOUD_ONLY -> "Só na nuvem"
        else -> state.name
    }
    val tint = when (state) {
        CloudSyncState.LOCAL_ONLY -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> OneDriveBlue
    }
    Icon(
        icon,
        contentDescription = description,
        modifier = modifier.size(iconSize),
        tint = tint,
    )
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (recalling) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = OneDriveBlue,
            )
        }
        GalleryCloudBadge(state = state, iconSize = 22.dp)
    }
}
