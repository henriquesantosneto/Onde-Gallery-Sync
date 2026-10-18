package com.onegallery.sync.ui.welcome

import android.app.Activity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.data.onedrive.OneDrivePickerHelper
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncPolicy

@Composable
fun WelcomeScreen(
    syncCoordinator: SyncCoordinator,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val oneDriveInstalled = OneDrivePickerHelper.isOneDriveAppInstalled(context)
    val oneDriveRoots = OneDrivePickerHelper.listOneDriveStorageRoots(context)

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                syncCoordinator.connectOneDriveFolder(uri)
                onFinished()
            }
        }
    }

    fun openLocalFolderPicker(initial: Uri) {
        folderPicker.launch(OneDrivePickerHelper.openTreeIntent(initial))
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Sem Azure — pasta no celular",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "O OneGallery grava em uma pasta do telefone. O app Microsoft OneDrive (já instalado e logado) " +
                        "é quem envia e recebe arquivos na sua conta. Você não precisa registrar nada no Azure.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Escolha a mesma pasta que o OneDrive já usa para backup/sincronização — em geral " +
                        "Pictures ou uma pasta marcada para offline no app OneDrive.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (!oneDriveInstalled) {
            Text(
                "Instale o app Microsoft OneDrive e faça login antes de continuar.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Button(
            onClick = {
                syncCoordinator.useOneDriveLocalMirrorMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Usar Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT} (recomendado)")
        }
        Text(
            "Cria Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT} no armazenamento interno. Ative no OneDrive o backup de fotos/Pictures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = {
                openLocalFolderPicker(OneDrivePickerHelper.preferredLocalSyncPickerInitialUri(context))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Escolher pasta local sincronizada pelo OneDrive")
        }
        Text(
            if (oneDriveRoots.isNotEmpty()) {
                "Abrimos o seletor na pasta OneDrive do aparelho (${oneDriveRoots.first().title}). " +
                    "Confirme ou navegue até a pasta que o OneDrive sincroniza."
            } else {
                "No seletor: menu ☰ → OneDrive (se aparecer) ou Armazenamento interno → Pictures. " +
                    "Só pastas locais é normal; a nuvem é cuidada pelo app OneDrive."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        oneDriveRoots.drop(1).forEach { root ->
            OutlinedButton(
                onClick = { openLocalFolderPicker(root.initialDocumentUri) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                Text("Abrir seletor em: ${root.title}")
            }
        }
        OutlinedButton(
            onClick = {
                if (!OneDrivePickerHelper.openOneDriveApp(context)) {
                    Toast.makeText(context, "Instale o app OneDrive", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = oneDriveInstalled,
        ) {
            Text("Abrir app OneDrive (backup / pastas offline)")
        }
        OutlinedButton(
            onClick = {
                syncCoordinator.useLocalOnlyMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Só galeria local (sem nuvem)")
        }
    }
}
