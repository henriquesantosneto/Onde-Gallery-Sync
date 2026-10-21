package com.onegallery.sync.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.onegallery.sync.data.local.MediaStorageAccess
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
    onOpenSyncStatus: () -> Unit,
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
    var allFilesAccess by remember { mutableStateOf(MediaStorageAccess.hasAllFilesAccess(context)) }
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
    var showResetIndexConfirm by remember { mutableStateOf(false) }
    var showAutoReleasePermissionDialog by remember { mutableStateOf(false) }
    var pendingAutoReleaseAfterSync by remember { mutableStateOf(false) }
    var pendingAutoReleaseWhenIdle by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context, syncCoordinator) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncCoordinator.scheduleThumbnailStatsRefresh(debounceMs = 0L)
                allFilesAccess = MediaStorageAccess.hasAllFilesAccess(context)
                if (allFilesAccess) {
                    if (pendingAutoReleaseAfterSync) {
                        pendingAutoReleaseAfterSync = false
                        autoFreeLocalAfterSync = true
                        syncPreferences.autoFreeLocalSpaceWhenSynced = true
                    }
                    if (pendingAutoReleaseWhenIdle) {
                        pendingAutoReleaseWhenIdle = false
                        autoFreeLocalWhenIdle = true
                        syncPreferences.autoFreeLocalSpaceWhenIdle = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openAllFilesAccessFromSettings() {
        if (!MediaStorageAccess.openAllFilesAccessSettings(context)) {
            app.userMessages.show(
                "Abra Configurações → Apps → One Gallery Sync → Permissões → " +
                    "Arquivos e mídia → Permitir gerenciamento de todos os arquivos.",
            )
        }
    }

    val cloudFolderLabel = when (syncPreferences.syncMode) {
        SyncMode.ONEDRIVE_GRAPH -> syncCoordinator.graphSyncRootLabel() ?: "Pasta na nuvem não escolhida"
        else -> "Conecte a conta e escolha a pasta na nuvem"
    }

    if (showAutoReleasePermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showAutoReleasePermissionDialog = false
                pendingAutoReleaseAfterSync = false
                pendingAutoReleaseWhenIdle = false
            },
            title = { Text("Permissão para liberar espaço") },
            text = {
                Text(
                    "As opções “Após backup” e “Por inatividade” removem cópias na galeria do " +
                        "aparelho. No Android 11+, ative “Acesso a todos os arquivos” uma vez " +
                        "(Remover cópias no aparelho → Conceder).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAutoReleasePermissionDialog = false
                    openAllFilesAccessFromSettings()
                }) {
                    Text("Conceder")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAutoReleasePermissionDialog = false
                    pendingAutoReleaseAfterSync = false
                    pendingAutoReleaseWhenIdle = false
                }) {
                    Text("Cancelar")
                }
            },
        )
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
                    headlineContent = { Text("Sync") },
                    supportingContent = {
                        Column {
                            if (syncState.isRunning) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 6.dp),
                                )
                                if (syncState.activityHeadline.isNotBlank()) {
                                    Text(
                                        syncState.activityHeadline,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                    )
                                }
                                if (syncState.activityDetail.isNotBlank()) {
                                    Text(
                                        syncState.activityDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val thumbLine = syncState.thumbnailStats?.summaryLine()
                            if (thumbLine != null) {
                                Text(
                                    thumbLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = if (syncState.isRunning) 4.dp else 0.dp),
                                )
                            } else if (!syncState.isRunning) {
                                Text(
                                    syncState.lastMessage.lineSequence().firstOrNull()?.trim().orEmpty()
                                        .ifBlank { "Ver fila, transferências e estatísticas" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenSyncStatus),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Remover cópias no aparelho") },
                    supportingContent = {
                        Text(
                            if (allFilesAccess) {
                                "Acesso ativo — liberar espaço e excluir não dependem de diálogo do Android."
                            } else {
                                "Necessário para liberar espaço (manual ou automático) e excluir fotos " +
                                    "no aparelho no Android 11+. Toque Conceder."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Storage, contentDescription = null)
                    },
                    trailingContent = {
                        if (allFilesAccess) {
                            Text(
                                "Ativo",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        } else {
                            Button(onClick = { openAllFilesAccessFromSettings() }) {
                                Text("Conceder")
                            }
                        }
                    },
                )
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
                                        "Liberar espaço manualmente na foto usa a mesma permissão " +
                                        "“Remover cópias no aparelho” acima.",
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
                        body = "Só para itens enviados neste sync para o OneDrive — apaga a cópia na galeria " +
                            "depois do upload. Não remove fotos que já estavam sincronizadas antes. " +
                            "Abrir de novo faz recall da nuvem.",
                    ),
                    checked = autoFreeLocalAfterSync,
                    onInfoClick = { settingsInfo = it },
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            autoFreeLocalAfterSync = false
                            syncPreferences.autoFreeLocalSpaceWhenSynced = false
                            return@SettingsSwitchRow
                        }
                        if (MediaStorageAccess.hasAllFilesAccess(context)) {
                            autoFreeLocalAfterSync = true
                            syncPreferences.autoFreeLocalSpaceWhenSynced = true
                        } else {
                            pendingAutoReleaseAfterSync = true
                            showAutoReleasePermissionDialog = true
                        }
                    },
                )
            }
            item {
                SettingsSwitchRow(
                    title = "Por inatividade",
                    summary = "Remove se não abrir no prazo",
                    info = SettingsInfoDialog(
                        title = "Por inatividade",
                        body = "Usa a última vez que você abriu no app; se nunca abriu, a data de captura " +
                            "ou de criação local (como na galeria). Só remove a cópia após o prazo — " +
                            "o OneDrive permanece.",
                    ),
                    checked = autoFreeLocalWhenIdle,
                    onInfoClick = { settingsInfo = it },
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            autoFreeLocalWhenIdle = false
                            syncPreferences.autoFreeLocalSpaceWhenIdle = false
                            return@SettingsSwitchRow
                        }
                        if (MediaStorageAccess.hasAllFilesAccess(context)) {
                            autoFreeLocalWhenIdle = true
                            syncPreferences.autoFreeLocalSpaceWhenIdle = true
                        } else {
                            pendingAutoReleaseWhenIdle = true
                            showAutoReleasePermissionDialog = true
                        }
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
                        Text("Limpar órfãos")
                    }
                    IconButton(
                        onClick = {
                            settingsInfo = SettingsInfoDialog(
                                title = "Limpar órfãos",
                                body = "Remove entradas do índice que não existem mais no aparelho nem na pasta " +
                                    "de sync na nuvem. Não apaga arquivos.",
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = "Mais informações")
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { showResetIndexConfirm = true },
                        modifier = Modifier.weight(1f),
                        enabled = !syncState.isRunning,
                    ) {
                        Text("Zerar índice e recomeçar")
                    }
                    IconButton(
                        onClick = {
                            settingsInfo = SettingsInfoDialog(
                                title = "Zerar índice e recomeçar",
                                body = "Apaga só a base de dados do app (índice + miniaturas em cache). " +
                                    "Suas fotos na galeria e no OneDrive permanecem. Em seguida roda um sync " +
                                    "completo para montar tudo de novo.",
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

    if (showResetIndexConfirm) {
        AlertDialog(
            onDismissRequest = { showResetIndexConfirm = false },
            title = { Text("Zerar índice e recomeçar?") },
            text = {
                Text(
                    "O app esquece o índice atual (pastas, estados de sync, miniaturas em cache) e " +
                        "sincroniza de novo com a galeria do aparelho e a pasta escolhida no OneDrive.\n\n" +
                        "Não apaga fotos ou vídeos na Samsung/Galeria nem arquivos na nuvem.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetIndexConfirm = false
                        syncTasks.resetSyncIndexAndResync(userInitiated = true)
                    },
                ) {
                    Text("Zerar e sincronizar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetIndexConfirm = false }) {
                    Text("Cancelar")
                }
            },
        )
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
