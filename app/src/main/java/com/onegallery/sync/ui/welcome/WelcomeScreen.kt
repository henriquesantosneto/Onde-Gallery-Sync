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
                    "Como ES File Explorer / Meus Arquivos",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Login Microsoft → navegar pastas do OneDrive na nuvem → escolher onde sincronizar. " +
                        "Você não cria conta Azure; isso é igual aos gerenciadores de arquivos.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!msalReady) {
                    Text(
                        "Login na nuvem: na próxima tela cole o Application (client) ID do seu app no Azure " +
                            "(Registros de aplicativo → Visão geral). Não precisa recompilar o APK.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Button(
            onClick = onConnectOneDriveCloud,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Conectar OneDrive (login Microsoft)")
        }
        Text(
            "Depois do login, escolha a pasta na nuvem (WhatsApp, álbuns, etc.).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = {
                syncCoordinator.useOneDriveLocalMirrorMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Alternativa: Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT} + backup OneDrive")
        }
        OutlinedButton(
            onClick = onBrowseLocalOneDrive,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Explorar OneDrive no app (gerenciador)")
        }
        Text(
            "Lista pastas OneDrive/Meus Arquivos dentro do app — como antes. " +
                "Sem login Microsoft; precisa do OneDrive instalado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
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
                Text("Meus Arquivos (OneDrive)")
            }
        }
        OutlinedButton(
            onClick = {
                if (!diagnostics.documentTreeAvailable) {
                    Toast.makeText(context, "Seletor indisponível", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                launchFolderPicker(OneDrivePickerHelper.openFolderPickerChooser(context))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Outro seletor de pasta")
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
