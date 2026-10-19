package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onegallery.sync.sync.CloudSyncState

fun cloudBadgeState(item: GalleryDisplayItem): CloudSyncState {
    if (item.localReleasedForSpace && item.localUri == null && item.oneDriveItemId != null) {
        return CloudSyncState.CLOUD_ONLY
    }
    return item.cloudState
}

private fun hasLocalMediaCopy(item: GalleryDisplayItem): Boolean {
    if (item.localUri != null) return true
    val cached = item.cachedOriginal
    return cached?.isFile == true && cached.length() > 0L
}

/** Ícone no canto da grade / detalhe: nuvem vazia (só nuvem) ou nuvem com ✓ (local + nuvem). */
fun galleryCloudOverlayState(item: GalleryDisplayItem): CloudSyncState? {
    val hasCloud = item.oneDriveItemId != null
    if (!hasCloud) return null
    if (hasLocalMediaCopy(item)) return CloudSyncState.SYNCED
    val badge = cloudBadgeState(item)
    return when (badge) {
        CloudSyncState.CLOUD_ONLY,
        CloudSyncState.PENDING_DOWNLOAD,
        CloudSyncState.PENDING_UPLOAD,
        -> badge
        else -> null
    }
}

/** Estado do ícone de nuvem na visualização expandida (recall / cópia local). */
fun detailCloudBadgeState(item: GalleryDisplayItem, recalling: Boolean): CloudSyncState? {
    val hasCloud = item.oneDriveItemId != null
    if (!hasCloud && item.cloudState == CloudSyncState.LOCAL_ONLY) return null
    if (recalling) return CloudSyncState.CLOUD_ONLY
    return galleryCloudOverlayState(item)
}

@Composable
fun GalleryCloudBadge(
    state: CloudSyncState,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = when (state) {
        CloudSyncState.SYNCED -> Icons.Outlined.CloudDone
        CloudSyncState.CLOUD_ONLY,
        CloudSyncState.PENDING_DOWNLOAD,
        CloudSyncState.PENDING_UPLOAD,
        -> Icons.Outlined.Cloud
        else -> Icons.Outlined.Cloud
    }
    Icon(
        icon,
        contentDescription = state.name,
        modifier = modifier.size(iconSize),
    )
}

@Composable
fun MediaDetailCloudOverlay(
    item: GalleryDisplayItem,
    recalling: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = detailCloudBadgeState(item, recalling) ?: return
    Box(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (recalling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            }
            GalleryCloudBadge(state = state, iconSize = 24.dp)
        }
    }
}
