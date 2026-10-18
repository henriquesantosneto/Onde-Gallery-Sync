package com.onegallery.sync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.auth.MsalBuiltInConfig
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: MsalAuthManager,
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
    onOpenCloudFolderPicker: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OneGalleryApplication
    val authState by authManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val syncState by syncCoordinator.state.collectAsStateWithLifecycle()
    var wifiOnly by remember { mutableStateOf(syncPreferences.wifiOnly) }
    var autoDeleteRemote by remember { mutableStateOf(syncPreferences.autoDeleteRemoteWhenLocalRemoved) }
    var autoFreeLocal by remember { mutableStateOf(syncPreferences.autoFreeLocalSpaceWhenSynced) }

    val cloudFolderLabel = when (syncPreferences.syncMode) {
        SyncMode.ONEDRIVE_GRAPH -> syncCoordinator.graphSyncRootLabel() ?: "Pasta na nuvem não escolhida"
        else -> "Conecte a conta e escolha a pasta na nuvem"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ajustes") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                SettingsSectionTitle("OneDrive (Microsoft Graph)", Icons.Outlined.Cloud)
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            when {
                                authState.isSignedIn ->
                                    authState.displayName ?: "Conta Microsoft"
                                authState.isCheckingStoredAccount ->
                                    "Verificando conta salva…"
                                else -> "Não conectado"
                            },
                        )
                    },
                    supportingContent = {
                        Column {
                            Text(
                                if (MsalBuiltInConfig.isEmbeddedClientConfigured()) {
                                    cloudFolderLabel
                                } else {
                                    "Client ID não embutido no APK — veja docs/BUILTIN_MSAL.md"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            authState.lastError?.let { err ->
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(
                        onClick = onOpenCloudFolderPicker,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Escolher pasta na nuvem")
                    }
                    Text(
                        stringResource(R.string.settings_cloud_folder_reindex_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(
                        onClick = { scope.launch { authManager.signOut() } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = authState.isSignedIn,
                    ) {
                        Text(stringResource(R.string.auth_sign_out))
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }

            item {
                SettingsSectionTitle("Sincronização", Icons.Outlined.Sync)
            }
            item {
                ListItem(
                    headlineContent = { Text("Liberação automática de espaço") },
                    supportingContent = {
                        Text(
                            "Após backup no OneDrive, remove só a cópia local e mantém miniatura " +
                                "no app; o arquivo na nuvem não é apagado.",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = autoFreeLocal,
                            onCheckedChange = {
                                autoFreeLocal = it
                                syncPreferences.autoFreeLocalSpaceWhenSynced = it
                            },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Apagar na nuvem se sumir do aparelho") },
                    supportingContent = {
                        Text("Sem confirmação — espelha exclusões da galeria do sistema")
                    },
                    trailingContent = {
                        Switch(
                            checked = autoDeleteRemote,
                            onCheckedChange = {
                                autoDeleteRemote = it
                                syncPreferences.autoDeleteRemoteWhenLocalRemoved = it
                            },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Sync apenas em Wi‑Fi") },
                    supportingContent = {
                        Text("Evita uso de dados móveis")
                    },
                    trailingContent = {
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = { enabled ->
                                wifiOnly = enabled
                                syncPreferences.wifiOnly = enabled
                                app.rescheduleBackgroundSync()
                                syncCoordinator.onWifiOnlyPreferenceChanged(enabled)
                                if (!enabled) {
                                    app.syncTaskLauncher.runBidirectionalSync(userInitiated = true)
                                }
                            },
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Wifi, contentDescription = null)
                    },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch { syncCoordinator.runIndexSanitation(userInitiated = true) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !syncState.isRunning,
                    ) {
                        Text("Verificar e limpar índice")
                    }
                    Text(
                        "Remove entradas órfãs do índice. Trocar a pasta na nuvem recria o índice " +
                            "no próximo sync; cada sync também faz uma verificação rápida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.titleMedium)
        },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
    )
}
