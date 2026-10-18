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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.R
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.auth.MsalBuiltInConfig
import com.onegallery.sync.data.onedrive.OneDriveFolderPreferences
import com.onegallery.sync.data.onedrive.OneDrivePickerHelper
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.onedrive.rememberConnectFolderLauncher
import android.widget.Toast
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: MsalAuthManager,
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
    oneDriveFolderPreferences: OneDriveFolderPreferences,
    onOpenCloudFolderPicker: () -> Unit,
) {
    val context = LocalContext.current
    val authState by authManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(syncPreferences.wifiOnly) }
    val launchFolderPicker = rememberConnectFolderLauncher(syncCoordinator) {
        scope.launch { syncCoordinator.runBidirectionalSync() }
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
            Text("OneDrive (nuvem)", style = MaterialTheme.typography.titleMedium)
            Text(
                if (MsalBuiltInConfig.isEmbeddedClientConfigured()) {
                    "Login Microsoft — como gerenciadores de arquivos. Você não cria Azure."
                } else {
                    "APK sem Client ID embutido: use msal.properties ao compilar (docs/BUILTIN_MSAL.md)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Text(
                authState.displayName ?: "Não conectado",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                when (syncPreferences.syncMode) {
                    SyncMode.ONEDRIVE_GRAPH -> syncCoordinator.graphSyncRootLabel() ?: "Pasta na nuvem não escolhida"
                    else -> "Modo nuvem inativo"
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(onClick = onOpenCloudFolderPicker) {
                Text("Escolher pasta OneDrive na nuvem")
            }
            Button(
                onClick = { scope.launch { authManager.signOut() } },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.auth_sign_out))
            }
            Text("Alternativa local (Samsung)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            Text(
                "Modo: ${
                    when (syncPreferences.syncMode) {
                        SyncMode.ONEDRIVE_LOCAL_MIRROR -> "Pictures + backup OneDrive"
                        SyncMode.ONEDRIVE_FOLDER -> "Pasta local escolhida"
                        SyncMode.ONEDRIVE_GRAPH -> "Nuvem (Graph)"
                        SyncMode.LOCAL_ONLY -> "Só local"
                    }
                }",
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (syncPreferences.syncMode == SyncMode.ONEDRIVE_LOCAL_MIRROR) {
                Text(syncCoordinator.localMirrorPathLabel(), modifier = Modifier.padding(bottom = 8.dp))
            } else if (syncPreferences.syncMode == SyncMode.ONEDRIVE_FOLDER) {
                Text(oneDriveFolderPreferences.displayName ?: "—", modifier = Modifier.padding(bottom = 8.dp))
            }
            Button(
                onClick = {
                    syncCoordinator.useOneDriveLocalMirrorMode()
                    scope.launch { syncCoordinator.runBidirectionalSync() }
                },
            ) {
                Text("Usar Pictures/OneGallerySync")
            }
            if (OneDrivePickerHelper.isSamsungDevice() && OneDrivePickerHelper.isSamsungMyFilesInstalled(context)) {
                Button(
                    onClick = {
                        val intent = OneDrivePickerHelper.openSamsungMyFilesTreeIntent(context)
                        if (intent == null) {
                            Toast.makeText(context, "Meus Arquivos indisponível", Toast.LENGTH_SHORT).show()
                        } else {
                            launchFolderPicker(intent)
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Meus Arquivos (OneDrive)")
                }
            }
            Button(
                onClick = { launchFolderPicker(OneDrivePickerHelper.openFolderPickerChooser(context)) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Outro seletor de pasta")
            }
            Text("Sync apenas em Wi‑Fi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
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
        }
    }
}
