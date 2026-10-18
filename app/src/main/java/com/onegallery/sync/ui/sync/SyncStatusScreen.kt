package com.onegallery.sync.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.R
import com.onegallery.sync.sync.SyncCapability
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(syncCoordinator: SyncCoordinator) {
    val state by syncCoordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Status de sync") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Card(modifier = Modifier.padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.sync_not_implemented_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.sync_not_implemented_body),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Text("Itens indexados localmente: ${state.lastIndexedLocalCount}")
            Text(
                state.lastMessage,
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { scope.launch { syncCoordinator.indexLocalLibrary() } },
                enabled = !state.isRunning,
            ) {
                Text("Reindexar mídia local")
            }
            Button(
                onClick = { scope.launch { syncCoordinator.runCloudProbe() } },
                enabled = !state.isRunning && SyncCapability.bidirectionalSyncEnabled,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Testar OneDrive (quando sync estiver ativo)")
            }
        }
    }
}
