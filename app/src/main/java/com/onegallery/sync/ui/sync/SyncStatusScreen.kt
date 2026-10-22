package com.onegallery.sync.ui.sync

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.db.SyncIndexStatsRow
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences
import com.onegallery.sync.sync.SyncTaskLauncher
import com.onegallery.sync.sync.ActiveSyncItem
import com.onegallery.sync.sync.SyncQueueDisplayRow
import com.onegallery.sync.sync.SyncProgressPhase
import com.onegallery.sync.sync.runningHeadlineFallback
import androidx.compose.ui.platform.LocalContext
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
    onNavigateBack: (() -> Unit)? = null,
) {
    val coordinatorState by syncCoordinator.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.CREATED,
    )
    val indexStats by syncCoordinator.syncIndexStats.collectAsStateWithLifecycle(
        initialValue = null,
    )
    val problemItems by syncCoordinator.syncAttentionItems.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val pendingOps by syncCoordinator.pendingOperationCount.collectAsStateWithLifecycle(initialValue = 0)
    val syncQueue by syncCoordinator.syncQueue.collectAsStateWithLifecycle(initialValue = emptyList())
    val app = LocalContext.current.applicationContext as OneGalleryApplication
    val syncTasks = remember { SyncTaskLauncher.from(app) }

    val stats = indexStats?.toSyncStats() ?: SyncStats(0, 0, 0, 0, 0, 0, 0, 0, 0)
    val problemCount = indexStats?.attention ?: problemItems.size
    LaunchedEffect(Unit) {
        syncCoordinator.refreshThumbnailCacheStats(force = true)
    }
    DisposableEffect(Unit) {
        syncCoordinator.setSyncStatusScreenVisible(true)
        onDispose { syncCoordinator.setSyncStatusScreenVisible(false) }
    }
    var clearingErrors by remember { mutableStateOf(false) }
    val thumbnailPrefetchActive = coordinatorState.progressPhase == SyncProgressPhase.PREFETCH_THUMBNAILS &&
        (
            coordinatorState.runQueue.isNotEmpty() ||
                coordinatorState.activeItems.isNotEmpty() ||
                (coordinatorState.thumbnailStats?.indexedPending ?: 0) > 0
            )
    val showLiveActivity = coordinatorState.isRunning || thumbnailPrefetchActive
    val queueForUi = remember(coordinatorState.runQueue, syncQueue) {
        when {
            coordinatorState.runQueue.isNotEmpty() -> coordinatorState.runQueue
            else -> syncQueue.map { SyncQueueDisplayRow.fromEntity(it) }
        }
    }
    val queueIsThumbnailBatch = coordinatorState.runQueue.isNotEmpty() &&
        coordinatorState.progressPhase == SyncProgressPhase.PREFETCH_THUMBNAILS
    val activeItems = coordinatorState.activeItems
    val activePaths = remember(activeItems) {
        activeItems.map { it.canonicalPath }.toSet()
    }
    val queueWaiting = remember(queueForUi, activePaths) {
        queueForUi.filter { it.canonicalPath !in activePaths }
    }

    Scaffold(
        contentWindowInsets = ZeroScaffoldContentInsets,
        topBar = {
            if (onNavigateBack != null) {
                TopAppBar(
                    title = { Text("Sync") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Voltar",
                            )
                        }
                    },
                )
            } else {
                TopAppBar(title = { Text("Sync") })
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleMedium)
                        Text(
                            connectionHeadline(syncPreferences.syncMode, syncCoordinator),
                            modifier = Modifier.padding(top = 6.dp),
                            fontWeight = FontWeight.Medium,
                        )
                        if (showLiveActivity) {
                            val detail = coordinatorState.activityDetail.ifBlank {
                                coordinatorState.lastMessage.lineSequence().drop(1).joinToString("\n")
                            }
                            Text(
                                coordinatorState.activityHeadline.ifBlank {
                                    coordinatorState.progressPhase.runningHeadlineFallback()
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            if (coordinatorState.isRunning) {
                                Text(
                                    "Com o app minimizado, o sync continua pela notificação " +
                                        "em segundo plano (WorkManager).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            } else if (thumbnailPrefetchActive) {
                                Text(
                                    "Miniaturas continuam enquanto esta tela estiver aberta " +
                                        "(fora da galeria, para não travar Fotos/Álbuns).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        } else {
                            Text(
                                coordinatorState.lastMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        if (coordinatorState.isOffline) {
                            Text(
                                "Sem internet — leitura local e cache.",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (showLiveActivity) {
                            if (activeItems.isEmpty()) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp),
                                )
                            } else {
                                Text(
                                    if (activeItems.size == 1) {
                                        "Transferência ativa"
                                    } else {
                                        "Transferências em paralelo (${activeItems.size})"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    activeItems.forEach { item ->
                                        SyncActiveTransferRow(item)
                                    }
                                }
                            }
                            if (queueWaiting.isEmpty() && coordinatorState.activityDetail.isBlank()) {
                                val phaseHint = when (coordinatorState.progressPhase) {
                                    SyncProgressPhase.LISTING_REMOTE ->
                                        "Varrendo todas as subpastas no OneDrive (pode levar vários minutos). " +
                                            "Não mostramos subpasta a subpasta — só a pasta raiz de sync acima."
                                    SyncProgressPhase.SANITIZING_INDEX ->
                                        "Última checagem de entradas duplicadas ou órfãs."
                                    else -> null
                                }
                                phaseHint?.let { hint ->
                                    Text(
                                        hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SyncQueueScrollPanel(
                    queueWaiting = queueWaiting,
                    activeItems = activeItems,
                    isRunning = coordinatorState.isRunning,
                    queueIsThumbnailBatch = queueIsThumbnailBatch,
                    thumbnailPendingTotal = coordinatorState.thumbnailStats?.indexedPending ?: 0,
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { syncTasks.runBidirectionalSync(userInitiated = true) },
                        enabled = !coordinatorState.isRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sincronizar agora") }
                    if (coordinatorState.isRunning) {
                        OutlinedButton(
                            onClick = { syncCoordinator.requestCancelSync() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Parar sincronismo") }
                    }
                }
            }

            item {
                SectionTitle("Resumo da biblioteca")
            }
            item {
                StatsCard(
                    stats = stats,
                    pendingFromDb = pendingOps,
                    thumbnailStats = coordinatorState.thumbnailStats,
                )
            }

            if (problemCount > 0) {
                item {
                    SectionTitle(
                        if (problemCount > problemItems.size) {
                            "Atenção ($problemCount, mostrando ${problemItems.size})"
                        } else {
                            "Atenção ($problemCount)"
                        },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            clearingErrors = true
                            syncTasks.clearStoredItemErrors {
                                clearingErrors = false
                            }
                        },
                        enabled = !clearingErrors && !coordinatorState.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (clearingErrors) "Limpando…" else "Limpar avisos antigos",
                        )
                    }
                    Text(
                        if (coordinatorState.isRunning) {
                            "Aguarde o fim do sync ou toque em Cancelar para limpar avisos."
                        } else {
                            "Só remove mensagens da lista. Fotos e fila de sync não são apagadas. " +
                                "Depois, use Sincronizar agora com internet estável."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    )
                }
                items(
                    problemItems,
                    key = { "attention-${it.canonicalPath}" },
                ) { row ->
                    ProblemItemCard(row)
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    "Opções de sincronização (Wi‑Fi, liberar espaço, apagar na nuvem) ficam em Ajustes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

private val SyncQueueRowStride = 52.dp
private val SyncQueueRowGap = 8.dp
private const val SyncQueueVisibleRowCount = 20

@Composable
private fun syncQueueListViewportHeight(): Dp {
    val screenCap = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    val twentyRows = SyncQueueRowStride * SyncQueueVisibleRowCount +
        SyncQueueRowGap * (SyncQueueVisibleRowCount - 1)
    return minOf(twentyRows, screenCap)
}

@Composable
private fun SyncQueueScrollPanel(
    queueWaiting: List<SyncQueueDisplayRow>,
    activeItems: List<ActiveSyncItem>,
    isRunning: Boolean,
    queueIsThumbnailBatch: Boolean,
    thumbnailPendingTotal: Int,
) {
    val viewportHeight = syncQueueListViewportHeight()
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        val title = when {
            queueIsThumbnailBatch && thumbnailPendingTotal > 0 ->
                "Miniaturas (~$thumbnailPendingTotal no índice · leva atual ${queueWaiting.size})"
            queueIsThumbnailBatch ->
                "Miniaturas (leva atual ${queueWaiting.size})"
            queueWaiting.size > 1 ->
                "Operações de sync (${queueWaiting.size})"
            else ->
                "Operações de sync"
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (!queueIsThumbnailBatch && thumbnailPendingTotal > 0) {
            Text(
                "~$thumbnailPendingTotal miniatura(s) ainda sem cache — aparecem aqui ao processar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewportHeight)
                .border(1.dp, outline, MaterialTheme.shapes.medium),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            if (queueWaiting.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            thumbnailPendingTotal > 0 ->
                                "~$thumbnailPendingTotal miniatura(s) pendentes — iniciando leva…"
                            isRunning ->
                                "Nenhum item aguardando na fila."
                            else ->
                                "Nenhuma operação de sync — toque em Sincronizar agora."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(SyncQueueRowGap),
                ) {
                    items(
                        queueWaiting,
                        key = { "queue-${it.canonicalPath}" },
                    ) { row ->
                        SyncQueueItemRow(
                            row = row,
                            activeItems = activeItems,
                            isRunning = isRunning,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatsCard(
    stats: SyncStats,
    pendingFromDb: Int,
    thumbnailStats: com.onegallery.sync.sync.ThumbnailCacheStats?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow("Itens no índice", stats.total.toString())
            thumbnailStats?.let { thumbs ->
                StatRow("Miniaturas no índice", "${thumbs.indexedWithThumbnail} / ${thumbs.indexedEligible}")
                if (thumbs.indexedPending > 0) {
                    StatRow("Miniaturas pendentes", thumbs.indexedPending.toString(), emphasized = true)
                }
                StatRow("Arquivos em cache (thumbs)", thumbs.savedOnDisk.toString())
            }
            StatRow("Com cópia local", stats.withLocal.toString())
            StatRow("Sincronizados", stats.synced.toString())
            StatRow("Aguardando envio", stats.pendingUpload.toString())
            StatRow("Aguardando download", stats.pendingDownload.toString())
            StatRow("Só na nuvem", stats.cloudOnly.toString())
            StatRow("Só no aparelho", stats.localOnly.toString())
            if (stats.errors > 0) StatRow("Com erro", stats.errors.toString())
            if (stats.conflicts > 0) StatRow("Conflitos / exclusão pendente", stats.conflicts.toString())
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            StatRow(
                label = "Operações pendentes",
                value = pendingFromDb.toString(),
                emphasized = pendingFromDb > 0,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SyncActiveTransferRow(activeItem: ActiveSyncItem) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            activeItem.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        Text(
            activeItem.operationLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SyncTransferProgressBar(activeItem = activeItem)
    }
}

@Composable
private fun SyncTransferProgressBar(activeItem: ActiveSyncItem) {
    val fraction = activeItem.progressFraction
    val byteLabel = activeItem.progressByteLabel()
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
    if (byteLabel != null) {
        Text(
            byteLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SyncQueueItemRow(
    row: SyncQueueDisplayRow,
    activeItems: List<ActiveSyncItem>,
    isRunning: Boolean,
) {
    val activeItem = activeItems.firstOrNull { it.canonicalPath == row.canonicalPath }
    val isActive = isRunning && activeItem != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActive) {
                    Modifier.padding(8.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            row.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
        )
        Text(
            if (isActive) {
                activeItem?.operationLabel ?: row.statusLabel
            } else {
                row.statusLabel
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (isActive && activeItem != null) {
            SyncTransferProgressBar(activeItem = activeItem)
        }
    }
}

@Composable
private fun ProblemItemCard(row: SyncMetadataEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(row.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                cloudStateLabel(row.cloudState),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                row.lastError ?: "Verifique na próxima sincronização.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private data class SyncStats(
    val total: Int,
    val withLocal: Int,
    val synced: Int,
    val pendingUpload: Int,
    val pendingDownload: Int,
    val cloudOnly: Int,
    val localOnly: Int,
    val errors: Int,
    val conflicts: Int,
)

private fun SyncIndexStatsRow.toSyncStats(): SyncStats = SyncStats(
    total = total,
    withLocal = withLocal,
    synced = synced,
    pendingUpload = pendingUpload,
    pendingDownload = pendingDownload,
    cloudOnly = cloudOnly,
    localOnly = localOnly,
    errors = errors,
    conflicts = conflicts,
)

private fun connectionHeadline(mode: SyncMode, coordinator: SyncCoordinator): String = when (mode) {
    SyncMode.ONEDRIVE_GRAPH -> coordinator.graphSyncRootLabel()?.let { "OneDrive: $it" }
        ?: "OneDrive — escolha a pasta em Ajustes"
    SyncMode.ONEDRIVE_FOLDER -> "Modo legado: pasta local (migre para Graph em Ajustes)"
    SyncMode.ONEDRIVE_LOCAL_MIRROR -> "Modo legado: espelho em Pictures (migre para Graph em Ajustes)"
    SyncMode.LOCAL_ONLY -> "Somente galeria local — conecte OneDrive em Ajustes"
}

private fun cloudStateLabel(state: CloudSyncState): String = when (state) {
    CloudSyncState.LOCAL_ONLY -> "Só no aparelho"
    CloudSyncState.CLOUD_ONLY -> "Só na nuvem"
    CloudSyncState.SYNCED -> "Sincronizado"
    CloudSyncState.PENDING_UPLOAD -> "Aguardando envio"
    CloudSyncState.PENDING_DOWNLOAD -> "Aguardando download"
    CloudSyncState.CONFLICT -> "Conflito"
    CloudSyncState.ERROR -> "Erro"
}
