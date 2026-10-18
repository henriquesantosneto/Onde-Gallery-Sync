package com.onegallery.sync.ui.welcome

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@Composable
fun WelcomeScreen(
    syncCoordinator: SyncCoordinator,
    onFinished: () -> Unit,
    onConnectOneDriveCloud: () -> Unit,
) {
    val context = LocalContext.current
    val diagnostics = remember { SyncSetupDiagnosticsFactory.create(context) }
    val msalReady = MsalBuiltInConfig.isEmbeddedClientConfigured()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                syncCoordinator.connectOneDriveFolder(uri)
                    .onSuccess { onFinished() }
                    .onFailure {
                        Toast.makeText(context, it.message ?: "Pasta inválida", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

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
                        "Este APK ainda não tem Client ID embutido (quem compila configura msal.properties). " +
                            "Até lá, use o modo Pictures abaixo.",
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
        Text(
            "Se o seletor do Samsung não mostra OneDrive, use o botão principal acima ou este modo local.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = {
                if (!diagnostics.documentTreeAvailable) {
                    Toast.makeText(context, "Seletor indisponível", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                folderPicker.launch(OneDrivePickerHelper.openFolderPickerChooser(context))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Tentar pasta local (experimental)")
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
