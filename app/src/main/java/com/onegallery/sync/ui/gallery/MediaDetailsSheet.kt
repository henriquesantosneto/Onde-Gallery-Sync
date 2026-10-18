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
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.queueDetailLabel
import com.onegallery.sync.sync.syncQueueStatusLabel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

private val ptBr = Locale("pt", "BR")

private val detailDateTimeFormatter =
    DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", ptBr)

private fun formatMediaDateTime(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0L) return "—"
    return Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(detailDateTimeFormatter)
}

private fun formatFileSize(bytes: Long): String {
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

private data class MediaDetailSection(
    val title: String,
    val rows: List<Pair<String, String>>,
)

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
    var sections by remember(canonicalPaths) { mutableStateOf<List<MediaDetailSection>>(emptyList()) }
    var loading by remember(canonicalPaths) { mutableStateOf(true) }

    LaunchedEffect(canonicalPaths) {
        loading = true
        sections = canonicalPaths.mapNotNull { path ->
            val entity = syncCoordinator.getMetadata(path) ?: return@mapNotNull null
            val display = displayItems.find { it.canonicalPath == path }
                ?: GalleryMapper.toDisplayItem(entity, mediaCacheRepository)
            buildDetailSection(entity, display)
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
            sections.isEmpty() -> {
                Text(
                    "Não foi possível carregar os detalhes.",
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (sections.size > 1) {
                        item {
                            Text(
                                "${sections.size} itens selecionados",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    items(sections, key = { it.title }) { section ->
                        MediaDetailSectionBlock(section, showTitle = sections.size > 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetailSectionBlock(section: MediaDetailSection, showTitle: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (showTitle) {
            Text(
                section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            Text(
                "Detalhes",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        section.rows.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

private fun buildDetailSection(
    entity: SyncMetadataEntity,
    display: GalleryDisplayItem,
): MediaDetailSection {
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
    val folderAlbum = entity.albumName.takeIf { it.isNotBlank() }
    val userAlbum = entity.userAlbumName?.takeIf { it.isNotBlank() }

    val rows = buildList {
        add("Nome" to display.displayName)
        add("Tipo" to kindLabel)
        add("Álbum na galeria" to display.albumName)
        if (userAlbum != null && userAlbum != folderAlbum) {
            add("Álbum personalizado" to userAlbum)
        }
        if (folderAlbum != null && folderAlbum != display.albumName) {
            add("Pasta na nuvem" to folderAlbum)
        }
        add("Data no aparelho" to formatMediaDateTime(entity.localModifiedAt))
        add("Data na nuvem" to formatMediaDateTime(entity.remoteModifiedAt))
        display.locationLabel?.takeIf { it.isNotBlank() }?.let { add("Local" to it) }
        coords?.let { add("Coordenadas" to it) }
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
    return MediaDetailSection(title = display.displayName, rows = rows)
}
