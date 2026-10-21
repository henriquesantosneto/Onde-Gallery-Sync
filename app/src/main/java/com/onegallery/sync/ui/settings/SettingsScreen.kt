package com.onegallery.sync.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.R
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.auth.MsalBuiltInConfig
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import com.onegallery.sync.sync.SyncTaskLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: MsalAuthManager,
    syncPreferences: SyncPreferences,
    onOpenCloudFolderPicker: () -> Unit,
    onMicrosoftSignIn: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OneGalleryApplication
    val cloudFolderReindexHint = stringResource(R.string.settings_cloud_folder_reindex_hint)
    val syncCoordinator = app.syncCoordinator
    val syncTasks = remember { SyncTaskLauncher.from(app) }
    val scope = rememberCoroutineScope()
    val authState by authManager.state.collectAsStateWithLifecycle()
    val syncState by syncCoordinator.state.collectAsStateWithLifecycle()
    var wifiOnly by remember { mutableStateOf(syncPreferences.wifiOnly) }
    var backgroundAlways by remember { mutableStateOf(syncPreferences.backgroundSyncAlwaysEnabled) }
    var autoDeleteRemote by remember { mutableStateOf(syncPreferences.autoDeleteRemoteWhenLocalRemoved) }
    var autoFreeLocalAfterSync by remember {
        mutableStateOf(syncPreferences.autoFreeLocalSpaceWhenSynced)
    }
    var autoFreeLocalWhenIdle by remember {
        mutableStateOf(syncPreferences.autoFreeLocalSpaceWhenIdle)
    }
    var idleReleaseDays by remember {
        mutableStateOf(syncPreferences.localCopyIdleReleaseDays.toFloat())
    }
    var settingsInfo by remember { mutableStateOf<SettingsInfoDialog?>(null) }

    val cloudFolderLabel = when (syncPreferences.syncMode) {
        SyncMode.ONEDRIVE_GRAPH -> syncCoordinator.graphSyncRootLabel() ?: "Pasta na nuvem não escolhida"
        else -> "Conecte a conta e escolha a pasta na nuvem"
    }

    settingsInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { settingsInfo = null },
            title = { Text(info.title) },
            text = {
                Text(
                    info.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { settingsInfo = null }) {
                    Text("Entendi")
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = ZeroScaffoldContentInsets,
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
                    if (
                        syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH &&
                        !authState.isSignedIn &&
                        !authState.isCheckingStoredAccount
                    ) {
                        Button(
                            onClick = onMicrosoftSignIn,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.auth_sign_in))
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenCloudFolderPicker,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Escolher pasta na nuvem")
                    }
                    TextButton(
                        onClick = {
                            settingsInfo = SettingsInfoDialog(
                                title = "Pasta na nuvem",
                                body = if (
                                    syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH &&
                                    !authState.isSignedIn &&
                                    !authState.isCheckingStoredAccount
                                ) {
                                    "Entre na Microsoft antes de escolher ou alterar a pasta de sync."
                                } else {
                                    cloudFolderReindexHint
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("Sobre a pasta de sync", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
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
                    headlineContent = {
                        SettingsHeadlineWithInfo(
                            title = "Espaço local",
                            onInfoClick = {
                                settingsInfo = SettingsInfoDialog(
                                    title = "Espaço local",
                                    body = "Você pode usar as duas opções abaixo juntas ou só uma. " +
                                        "Miniatura no app e arquivo no OneDrive sempre permanecem. " +
                                        "Liberar espaço manualmente na foto continua imediato.",
                                )
                            },
                        )
                    },
                )
            }
            item {
                SettingsSwitchRow(
                    title = "Após backup",
                    summary = "Remove cópia local na hora",
                    info = SettingsInfoDialog(
                        title = "Após backup",
                        body = "Assim que o item estiver no OneDrive, apaga a cópia na galeria do aparelho. " +
                            "Se você abrir depois e o app fizer recall, volta o original até você liberar " +
                            "de novo ou até valer a opção por inatividade.",
                    ),
                    checked = autoFreeLocalAfterSync,
                    onInfoClick = { settingsInfo = it },
                    onCheckedChange = {
                        autoFreeLocalAfterSync = it
                        syncPreferences.autoFreeLocalSpaceWhenSynced = it
                    },
                )
            }
            item {
                SettingsSwitchRow(
                    title = "Por inatividade",
                    summary = "Remove se não abrir no prazo",
                    info = SettingsInfoDialog(
                        title = "Por inatividade",
                        body = "Para fotos que ainda têm cópia no aparelho ou que você baixou de novo da " +
                            "nuvem (recall ao abrir). Se passar o prazo sem abrir de novo, o app remove " +
                            "essa cópia ou cache — não apaga o arquivo no OneDrive.",
                    ),
                    checked = autoFreeLocalWhenIdle,
                    onInfoClick = { settingsInfo = it },
                    onCheckedChange = {
                        autoFreeLocalWhenIdle = it
                        syncPreferences.autoFreeLocalSpaceWhenIdle = it
                    },
                )
            }
            if (autoFreeLocalWhenIdle) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SettingsHeadlineWithInfo(
                            title = "${idleReleaseDays.toInt()} dias sem abrir",
                            onInfoClick = {
                                settingsInfo = SettingsInfoDialog(
                                    title = "Prazo de inatividade",
                                    body = "O prazo recomeça cada vez que você abre a foto (incluindo depois " +
                                        "de um recall: ao ver o original baixado da nuvem, a contagem zera). " +
                                        "Se nunca abriu no app, usa a data da captura (EXIF). " +
                                        "Intervalo: ${SyncPreferences.MIN_LOCAL_IDLE_RELEASE_DAYS}–" +
                                        "${SyncPreferences.MAX_LOCAL_IDLE_RELEASE_DAYS} dias.",
                                )
                            },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Slider(
                            value = idleReleaseDays,
                            onValueChange = { raw ->
                                idleReleaseDays = raw.roundToInt().coerceIn(
                                    SyncPreferences.MIN_LOCAL_IDLE_RELEASE_DAYS,
                                    SyncPreferences.MAX_LOCAL_IDLE_RELEASE_DAYS,
                                ).toFloat()
                            },
                            onValueChangeFinished = {
                                syncPreferences.localCopyIdleReleaseDays = idleReleaseDays.toInt()
                            },
                            valueRange = SyncPreferences.MIN_LOCAL_IDLE_RELEASE_DAYS.toFloat()..
                                SyncPreferences.MAX_LOCAL_IDLE_RELEASE_DAYS.toFloat(),
                            steps = SyncPreferences.MAX_LOCAL_IDLE_RELEASE_DAYS -
                                SyncPreferences.MIN_LOCAL_IDLE_RELEASE_DAYS - 1,
                        )
                    }
                }
            }
            item {
                SettingsSwitchRow(
                    title = "Espelhar exclusão",
                    summary = "Apaga na nuvem se sumir do aparelho",
                    info = SettingsInfoDialog(
                        title = "Espelhar exclusão",
                        body = "Sem confirmação — quando você apaga na galeria do sistema, o app tenta " +
                            "apagar também no OneDrive.",
                    ),
                    checked = autoDeleteRemote,
                    onInfoClick = { settingsInfo = it },
                    onCheckedChange = {
                        autoDeleteRemote = it
                        syncPreferences.autoDeleteRemoteWhenLocalRemoved = it
                    },
                )
            }
            item {
                SettingsSwitchRow(
                    title = "Sync em segundo plano",
                    summary = "Boot, rede, ~15 min e mudanças na galeria",
                    info = SettingsInfoDialog(
                        title = "Sync em segundo plano",
                        body = "Após reiniciar o aparelho, a cada ~15 min, ao voltar a internet e quando " +
                            "fotos/vídeos mudam na galeria. Durante o sync aparece a notificação em " +
                            "primeiro plano.",
                    ),
                    checked = backgroundAlways,
                    onInfoClick = { settingsInfo = it },
                    onCheckedChange = { enabled ->
                        backgroundAlways = enabled
                        syncPreferences.backgroundSyncAlwaysEnabled = enabled
                        app.rescheduleBackgroundSync()
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Só Wi‑Fi") },
                    supportingContent = {
                        Text("Evita dados móveis")
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            syncTasks.runIndexSanitation(userInitiated = true)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !syncState.isRunning,
                    ) {
                        Text("Limpar índice")
                    }
                    IconButton(
                        onClick = {
                            settingsInfo = SettingsInfoDialog(
                                title = "Limpar índice",
                                body = "Remove entradas órfãs do índice. Trocar a pasta na nuvem recria o " +
                                    "índice no próximo sync; cada sync também faz uma verificação rápida.",
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = "Mais informações")
                    }
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, bottom = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private data class SettingsInfoDialog(
    val title: String,
    val body: String,
)

@Composable
private fun SettingsHeadlineWithInfo(
    title: String,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "Mais informações",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    summary: String,
    info: SettingsInfoDialog,
    checked: Boolean,
    onInfoClick: (SettingsInfoDialog) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            SettingsHeadlineWithInfo(
                title = title,
                onInfoClick = { onInfoClick(info) },
            )
        },
        supportingContent = {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = leadingContent,
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
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
