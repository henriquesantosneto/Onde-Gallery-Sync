package com.onegallery.sync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.R
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncPolicy
import com.onegallery.sync.sync.SyncPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: MsalAuthManager,
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
) {
    val authState by authManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(syncPreferences.wifiOnly) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ajustes") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Conta Microsoft", style = MaterialTheme.typography.titleMedium)
            Text(
                authState.displayName ?: "—",
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Text("Pasta raiz no OneDrive", style = MaterialTheme.typography.titleMedium)
            Text(
                SyncPolicy.ONEDRIVE_MIRROR_ROOT,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Text("Sync apenas em Wi‑Fi", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    syncPreferences.wifiOnly = it
                },
            )
            Text(
                "Lixeira ignorada; exclusões exigem confirmação na aba Sync ou ao apagar um item.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = { scope.launch { syncCoordinator.runBidirectionalSync() } },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Forçar sincronização") }
            Button(
                onClick = { scope.launch { authManager.signOut() } },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.auth_sign_out))
            }
        }
    }
}
