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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.R
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.data.onedrive.OneDriveFolderPreferences
import com.onegallery.sync.data.onedrive.OneDrivePickerHelper
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
    onOpenOptionalMicrosoftLogin: () -> Unit,
    onBrowseOneDriveCloud: () -> Unit,
    onBrowseOneDriveSaf: () -> Unit,
) {
    val context = LocalContext.current
    val authState by authManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(syncPreferences.wifiOnly) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                syncCoordinator.connectOneDriveFolder(uri)
                    .onSuccess { scope.launch { syncCoordinator.runBidirectionalSync() } }
            }
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
                    SyncMode.ONEDRIVE_LOCAL_MIRROR -> "Pictures/OneGallerySync + backup OneDrive"
                    SyncMode.ONEDRIVE_FOLDER -> "Pasta local (app OneDrive sincroniza)"
                    SyncMode.ONEDRIVE_GRAPH -> "API Microsoft (Azure)"
                    SyncMode.LOCAL_ONLY -> "Só galeria local"
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Text("Pasta no celular", style = MaterialTheme.typography.titleMedium)
            Text(
                when (syncPreferences.syncMode) {
                    SyncMode.ONEDRIVE_GRAPH -> syncCoordinator.graphSyncRootLabel() ?: "Nuvem (não escolhida)"
                    else -> oneDriveFolderPreferences.displayName ?: "Não conectada"
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (syncPreferences.syncMode == SyncMode.ONEDRIVE_LOCAL_MIRROR) {
                Text(
                    syncCoordinator.localMirrorPathLabel(),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Button(onClick = {
                folderPicker.launch(OneDrivePickerHelper.openFolderPickerChooser(context))
            }) {
                Text("Tentar escolher pasta (experimental)")
            }
            Button(onClick = onBrowseOneDriveSaf, modifier = Modifier.padding(top = 8.dp)) {
                Text("Navegar OneDrive dentro do app")
            }
            Text(
                if (OneDrivePickerHelper.isSamsungDevice()) {
                    OneDrivePickerHelper.SAMSUNG_ONEDRIVE_HINT
                } else {
                    OneDrivePickerHelper.ONEDRIVE_NOT_IN_FOLDER_PICKER_REASON
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = {
                    syncCoordinator.useOneDriveLocalMirrorMode()
                    scope.launch { syncCoordinator.runBidirectionalSync() }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Modo automático Pictures/OneGallerySync")
            }
            Text(
                "Sem Azure: escolha a pasta do telefone que o app OneDrive já sincroniza. " +
                    "Os arquivos da galeria ficam diretamente nessa pasta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Text("Modo avançado (Azure)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Opcional — acesso direto à nuvem sem passar pelo app OneDrive no celular.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(onClick = onBrowseOneDriveCloud) {
                Text("Explorar nuvem (requer Azure)")
            }
            Button(onClick = onOpenOptionalMicrosoftLogin, modifier = Modifier.padding(top = 8.dp)) {
                Text("Client ID / login Microsoft…")
            }
            Text(
                authState.displayName ?: "Não conectado",
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
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
