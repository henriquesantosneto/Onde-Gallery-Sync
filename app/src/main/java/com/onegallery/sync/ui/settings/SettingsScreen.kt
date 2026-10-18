package com.onegallery.sync.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.onegallery.sync.data.onedrive.OneDriveFolderPreferences
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPolicy
import com.onegallery.sync.sync.SyncPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: MsalAuthManager,
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
    oneDriveFolderPreferences: OneDriveFolderPreferences,
) {
    val authState by authManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(syncPreferences.wifiOnly) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            syncCoordinator.connectOneDriveFolder(uri)
            scope.launch { syncCoordinator.runBidirectionalSync() }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ajustes") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Modo de sync", style = MaterialTheme.typography.titleMedium)
            Text(
                when (syncPreferences.syncMode) {
                    SyncMode.ONEDRIVE_FOLDER -> "OneDrive por pasta (simples, sem Azure)"
                    SyncMode.ONEDRIVE_GRAPH -> "API Microsoft (Azure)"
                    SyncMode.LOCAL_ONLY -> "Só galeria local"
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Text("Pasta OneDrive", style = MaterialTheme.typography.titleMedium)
            Text(
                oneDriveFolderPreferences.displayName ?: "Não conectada",
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(onClick = { folderPicker.launch(null) }) {
                Text("Escolher / trocar pasta OneDrive")
            }
            Text(
                "Use o app OneDrive já logado. Subpasta: ${SyncPolicy.ONEDRIVE_MIRROR_ROOT}/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Text("Conta Microsoft (modo avançado)", style = MaterialTheme.typography.titleMedium)
            Text(
                authState.displayName ?: "—",
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
