package com.onegallery.sync.ui.welcome

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.auth.MsalBuiltInConfig
import com.onegallery.sync.data.onedrive.OneDrivePickerHelper
import com.onegallery.sync.data.onedrive.SyncSetupDiagnosticsFactory
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncPolicy
import com.onegallery.sync.ui.onedrive.rememberConnectFolderLauncher

@Composable
fun WelcomeScreen(
    syncCoordinator: SyncCoordinator,
    onFinished: () -> Unit,
    onConnectOneDriveCloud: () -> Unit,
    onBrowseLocalOneDrive: () -> Unit,
) {
    val context = LocalContext.current
    val diagnostics = remember { SyncSetupDiagnosticsFactory.create(context) }
    val msalReady = MsalBuiltInConfig.isEmbeddedClientConfigured()

    val launchFolderPicker = rememberConnectFolderLauncher(syncCoordinator, onFinished)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OneGallery Sync", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "OneDrive local (sem Azure)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    OneDrivePickerHelper.SAMSUNG_ONEDRIVE_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Button(
            onClick = {
                if (!diagnostics.documentTreeAvailable) {
                    Toast.makeText(context, "Seletor indisponível", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                launchFolderPicker(OneDrivePickerHelper.openFolderPickerChooser(context))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Pasta local OneDrive (gerenciador)")
        }
        Text(
            "Abre o seletor completo (Meus Arquivos / árvore) — como quando OneDrive apareceu da primeira vez. " +
                "Se não listar OneDrive, escolha Meus Arquivos no popup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        if (OneDrivePickerHelper.isSamsungDevice() && diagnostics.myFilesInstalled) {
            OutlinedButton(
                onClick = {
                    val intent = OneDrivePickerHelper.openSamsungMyFilesTreeIntent(context)
                    if (intent == null) {
                        Toast.makeText(context, "Meus Arquivos indisponível", Toast.LENGTH_SHORT).show()
                    } else {
                        launchFolderPicker(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Só Meus Arquivos (árvore de pastas)")
            }
        }
        OneDrivePickerHelper.openSamsungPickDataIntent()?.let { pickData ->
            OutlinedButton(
                onClick = { launchFolderPicker(pickData) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Meus Arquivos — modo PICK (OneDrive)")
            }
        }
        OutlinedButton(
            onClick = onBrowseLocalOneDrive,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Lista pastas dentro do app (experimental)")
        }
        OutlinedButton(
            onClick = {
                syncCoordinator.useOneDriveLocalMirrorMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text("Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT} + backup OneDrive")
        }
        Text(
            "Login na nuvem (Azure / Microsoft)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = onConnectOneDriveCloud,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Conectar OneDrive (login Microsoft)")
        }
        if (!msalReady) {
            Text(
                "Cole o Client ID do Azure na próxima tela.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OutlinedButton(
            onClick = {
                if (!OneDrivePickerHelper.openOneDriveApp(context)) {
                    Toast.makeText(context, "Instale o OneDrive", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            enabled = diagnostics.oneDriveInstalled,
        ) {
            Text("Abrir app OneDrive")
        }
        OutlinedButton(
            onClick = {
                syncCoordinator.useLocalOnlyMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Só galeria local")
        }
    }
}
