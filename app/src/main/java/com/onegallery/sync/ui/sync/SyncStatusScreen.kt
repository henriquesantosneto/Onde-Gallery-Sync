package com.onegallery.sync.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(syncCoordinator: SyncCoordinator) {
    val state by syncCoordinator.state.collectAsStateWithLifecycle()
    val pendingDeletes by syncCoordinator.pendingDeleteItems.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Status de sync") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Sincronização bidirecional", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Espelha mídia local (incl. WhatsApp) em Pictures/OneGallerySync. " +
                                "O app OneDrive envia essa pasta para a nuvem — não é preciso Azure nem escolher OneDrive no seletor.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                Text("Itens locais indexados: ${state.lastIndexedLocalCount}")
                Text("Operações pendentes: ${state.pendingOperations}")
                Text(
                    state.lastMessage,
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.isOffline) {
                    Text("Modo offline — cache local disponível.", color = MaterialTheme.colorScheme.primary)
                }
                Button(
                    onClick = { scope.launch { syncCoordinator.runBidirectionalSync() } },
                    enabled = !state.isRunning,
                ) { Text("Sincronizar agora") }
            }
            if (pendingDeletes.isNotEmpty()) {
                item {
                    Text(
                        "Exclusões aguardando confirmação",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                }
                items(pendingDeletes, key = { it.canonicalPath }) { row ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(row.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("Alvo: ${row.pendingDeleteTarget.name}")
                            Text(
                                row.lastError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        syncCoordinator.deleteItemsImmediately(
                                            listOf(row.canonicalPath),
                                            row.pendingDeleteTarget,
                                        )
                                    }
                                },
                            ) { Text("Confirmar") }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        syncCoordinator.resolvePendingDelete(row.canonicalPath, confirmed = false)
                                    }
                                },
                            ) { Text("Ignorar") }
                        }
                    }
                }
            }
        }
    }
}
