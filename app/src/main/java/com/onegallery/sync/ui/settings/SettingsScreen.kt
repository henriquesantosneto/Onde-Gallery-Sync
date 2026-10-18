package com.onegallery.sync.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.SideEffect
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
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences
import com.onegallery.sync.ui.onedrive.rememberConnectFolderLauncher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: MsalAuthManager,
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
    oneDriveFolderPreferences: OneDriveFolderPreferences,
    onOpenCloudFolderPicker: () -> Unit,
    onOpenLocalSafBrowser: () -> Unit,
) {
    val context = LocalContext.current
    val authState by authManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(syncPreferences.wifiOnly) }
    var autoDeleteRemote by remember { mutableStateOf(syncPreferences.autoDeleteRemoteWhenLocalRemoved) }
    var autoFreeLocal by remember { mutableStateOf(syncPreferences.autoFreeLocalSpaceWhenSynced) }
    SideEffect {
        autoFreeLocal = syncPreferences.autoFreeLocalSpaceWhenSynced
    }
    var localAdvancedExpanded by remember { mutableStateOf(false) }
    val launchFolderPicker = rememberConnectFolderLauncher(syncCoordinator) {
        scope.launch { syncCoordinator.runBidirectionalSync() }
    }

    val cloudFolderLabel = when (syncPreferences.syncMode) {
        SyncMode.ONEDRIVE_GRAPH -> syncCoordinator.graphSyncRootLabel() ?: "Pasta não escolhida"
        else -> "Login feito — escolha a pasta na nuvem"
    }

    val localModeSummary = when (syncPreferences.syncMode) {
        SyncMode.ONEDRIVE_LOCAL_MIRROR -> "Pictures/OneGallerySync + backup OneDrive"
        SyncMode.ONEDRIVE_FOLDER -> oneDriveFolderPreferences.displayName ?: "Pasta local conectada"
        SyncMode.ONEDRIVE_GRAPH -> "Usando pasta na nuvem (Graph)"
        SyncMode.LOCAL_ONLY -> "Só galeria local"
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
                SettingsSectionTitle("Conta Microsoft", Icons.Outlined.Cloud)
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(authState.displayName ?: "Não conectado")
                    },
                    supportingContent = {
                        Text(
                            if (MsalBuiltInConfig.isEmbeddedClientConfigured()) {
                                cloudFolderLabel
                            } else {
                                "Client ID não embutido no APK — veja docs/BUILTIN_MSAL.md"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                    TextButton(
                        onClick = { scope.launch { authManager.signOut() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_sign_out))
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }

            item {
                SettingsSectionTitle("OneDrive no aparelho", Icons.Outlined.Folder)
            }
            item {
                ListItem(
                    headlineContent = { Text("Modo atual") },
                    supportingContent = { Text(localModeSummary) },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(
                        onClick = {
                            launchFolderPicker(OneDrivePickerHelper.openFolderPickerChooser(context))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Conectar pasta local (gerenciador)")
                    }
                    OutlinedButton(
                        onClick = {
                            syncCoordinator.useOneDriveLocalMirrorMode()
                            scope.launch { syncCoordinator.runBidirectionalSync() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Text("Usar Pictures/OneGallerySync")
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Mais opções locais") },
                    supportingContent = {
                        Text("Samsung, PICK e navegador no app")
                    },
                    trailingContent = {
                        Icon(
                            if (localAdvancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { localAdvancedExpanded = !localAdvancedExpanded },
                )
            }
            item {
                AnimatedVisibility(visible = localAdvancedExpanded) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        if (OneDrivePickerHelper.isSamsungDevice() &&
                            OneDrivePickerHelper.isSamsungMyFilesInstalled(context)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val intent = OneDrivePickerHelper.openSamsungMyFilesTreeIntent(context)
                                    if (intent == null) {
                                        Toast.makeText(context, "Meus Arquivos indisponível", Toast.LENGTH_SHORT)
                                            .show()
                                    } else {
                                        launchFolderPicker(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Meus Arquivos — árvore")
                            }
                        }
                        OneDrivePickerHelper.openSamsungPickDataIntent()?.let { pickData ->
                            OutlinedButton(
                                onClick = { launchFolderPicker(pickData) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) {
                                Text("Meus Arquivos — PICK OneDrive")
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenLocalSafBrowser,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text("Lista no app (experimental)")
                        }
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
                            "Ligue ou desligue quando quiser. Ligado: após backup no OneDrive, " +
                                "remove a cópia local e mantém miniatura (placeholder). Desligado: " +
                                "mantém o arquivo no aparelho.",
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
                            onCheckedChange = {
                                wifiOnly = it
                                syncPreferences.wifiOnly = it
                            },
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Wifi, contentDescription = null)
                    },
                )
            }
            item {
                OutlinedButton(
                    onClick = { scope.launch { syncCoordinator.runBidirectionalSync() } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Sincronizar agora")
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
