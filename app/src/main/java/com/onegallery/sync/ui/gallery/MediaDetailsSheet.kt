package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.SyncCoordinator

/** Folha modal para vários itens selecionados na grade (não substitui o painel na foto). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsBottomSheet(
    canonicalPaths: List<String>,
    displayItems: List<GalleryDisplayItem>,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var infos by remember(canonicalPaths) { mutableStateOf<List<MediaDetailInfo>>(emptyList()) }
    var loading by remember(canonicalPaths) { mutableStateOf(true) }

    LaunchedEffect(canonicalPaths) {
        loading = true
        infos = canonicalPaths.mapNotNull { path ->
            val entity = syncCoordinator.getMetadata(path) ?: return@mapNotNull null
            val display = displayItems.find { it.canonicalPath == path }
                ?: GalleryMapper.toDisplayItem(entity, mediaCacheRepository)
            buildMediaDetailInfo(entity, display)
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                )
            }
            infos.isEmpty() -> {
                Text(
                    "Não foi possível carregar os detalhes.",
                    modifier = Modifier.padding(24.dp),
                )
            }
            infos.size == 1 -> {
                var showAdvanced by remember { mutableStateOf(false) }
                MediaDetailSamsungSheet(
                    info = infos.first(),
                    loading = false,
                    error = null,
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    showExpandedContent = true,
                )
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text(
                            "${infos.size} itens selecionados",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(infos, key = { it.displayName }) { info ->
                        MediaDetailMultiSummary(info)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetailMultiSummary(info: MediaDetailInfo) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            info.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            info.capturedAtFriendly.ifBlank { info.capturedAtFormatted },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            info.albumLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
