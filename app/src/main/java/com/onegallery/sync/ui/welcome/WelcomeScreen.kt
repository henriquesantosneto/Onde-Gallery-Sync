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
    onBrowseOneDriveLikeFileManager: () -> Unit,
) {
    val context = LocalContext.current
    val oneDriveInstalled = OneDrivePickerHelper.isOneDriveAppInstalled(context)
    val pickerAvailable = OneDrivePickerHelper.isDocumentTreePickerAvailable(context)
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

    fun openPicturesPicker() {
        if (!pickerAvailable) {
            Toast.makeText(
                context,
                "Ative o app Arquivos do Samsung (ou Google Arquivos) para escolher pastas.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val initial = OneDrivePickerHelper.preferredLocalSyncPickerInitialUri(context)
        folderPicker.launch(OneDrivePickerHelper.openTreeIntent(context, initial))
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "OneDrive não aparece no seletor?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    OneDrivePickerHelper.ONEDRIVE_NOT_IN_FOLDER_PICKER_REASON,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (oneDriveRoots.isEmpty()) {
                    Text(
                        "Neste aparelho o OneDrive também não expõe pasta pelo provedor de documentos " +
                            "(detectado: nenhuma raiz). Use o modo automático abaixo.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (!oneDriveInstalled) {
            Text(
                "Instale o app Microsoft OneDrive e faça login.",
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
            Text("Configurar sem seletor (recomendado)")
        }
        Text(
            "Grava em Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT} no celular. " +
                "Depois, no app OneDrive: Configurações → backup/sincronização de fotos e inclua Pictures/Câmera. " +
                "É assim que a nuvem fica ligada sem Azure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = onBrowseOneDriveLikeFileManager,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Navegar OneDrive (estilo gerenciador de arquivos)")
        }
        Text(
            "Só funciona se o OneDrive expuser pastas ao sistema; em muitos Samsung a lista vem vazia.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = { openPicturesPicker() },
            modifier = Modifier.fillMaxWidth(),
            enabled = pickerAvailable,
        ) {
            Text("Escolher pasta Pictures manualmente")
        }
        Text(
            "Só verá Armazenamento interno / SD — não OneDrive. Escolha Pictures ou ${SyncPolicy.ONEDRIVE_MIRROR_ROOT}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        if (!pickerAvailable) {
            Text(
                "Seletor de pastas indisponível: ative o app Arquivos (Samsung ou Google) nas configurações do aparelho.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
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
            Text("Abrir OneDrive → ativar backup de fotos")
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
