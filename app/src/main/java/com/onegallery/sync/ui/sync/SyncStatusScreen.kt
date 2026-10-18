package com.onegallery.sync.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences
import com.onegallery.sync.sync.SyncTaskLauncher
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
) {
    val coordinatorState by syncCoordinator.state.collectAsStateWithLifecycle()
    val metadata by syncCoordinator.galleryMetadata.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingOps by syncCoordinator.pendingOperationCount.collectAsStateWithLifecycle(initialValue = 0)
    val app = LocalContext.current.applicationContext as OneGalleryApplication
    val syncTasks = remember { SyncTaskLauncher.from(app) }

    val stats = remember(metadata) { computeSyncStats(metadata) }
    val problemCount = remember(metadata) {
        metadata.count { syncCoordinator.shouldShowAttentionItem(it) }
    }
    val problemItems = remember(metadata) {
        metadata.filter { syncCoordinator.shouldShowAttentionItem(it) }.take(25)
    }
    var clearingErrors by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync") }) },
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
                Column(Modifier.padding(top = 8.dp)) {
                    if (coordinatorState.isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text(
                                when {
                                    coordinatorState.lastMessage.contains("Cancelando", ignoreCase = true) ->
                                        "Cancelando…"
                                    coordinatorState.lastMessage.contains("Indexando", ignoreCase = true) ->
                                        "Indexando biblioteca local…"
                                    else -> "Sincronizando com OneDrive…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

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
                        Text(
                            coordinatorState.lastMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        if (coordinatorState.isOffline) {
                            Text(
                                "Sem internet — leitura local e cache.",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Button(
                            onClick = { syncTasks.runBidirectionalSync() },
                            enabled = !coordinatorState.isRunning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) { Text("Sincronizar agora") }
                        if (coordinatorState.isRunning) {
                            OutlinedButton(
                                onClick = { syncCoordinator.requestCancelSync() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) { Text("Cancelar sincronismo") }
                        }
                    }
                }
            }

            item {
                SectionTitle("Resumo da biblioteca")
            }
            item {
                StatsCard(stats, pendingOps)
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatsCard(stats: SyncStats, pendingFromDb: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow("Itens no índice", stats.total.toString())
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

private fun computeSyncStats(metadata: List<SyncMetadataEntity>): SyncStats {
    return SyncStats(
        total = metadata.size,
        withLocal = metadata.count { it.localMediaId != null },
        synced = metadata.count { it.cloudState == CloudSyncState.SYNCED },
        pendingUpload = metadata.count { it.cloudState == CloudSyncState.PENDING_UPLOAD },
        pendingDownload = metadata.count { it.cloudState == CloudSyncState.PENDING_DOWNLOAD },
        cloudOnly = metadata.count { it.cloudState == CloudSyncState.CLOUD_ONLY },
        localOnly = metadata.count { it.cloudState == CloudSyncState.LOCAL_ONLY },
        errors = metadata.count { it.cloudState == CloudSyncState.ERROR },
        conflicts = metadata.count {
            it.cloudState == CloudSyncState.CONFLICT || it.pendingDeleteTarget != PendingDeleteTarget.NONE
        },
    )
}

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
