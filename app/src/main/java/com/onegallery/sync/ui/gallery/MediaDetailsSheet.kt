package com.onegallery.sync.ui.gallery

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.SyncCoordinator

/** Folha modal escura (padrão Samsung) para detalhes fora da tela de foto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsBottomSheet(
    canonicalPaths: List<String>,
    displayItems: List<GalleryDisplayItem>,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0D0D0D),
        contentColor = Color.White,
    ) {
        MediaSamsungDetailsContent(
            canonicalPaths = canonicalPaths,
            displayItems = displayItems,
            syncCoordinator = syncCoordinator,
            mediaCacheRepository = mediaCacheRepository,
            footer = footer,
        )
    }
}
