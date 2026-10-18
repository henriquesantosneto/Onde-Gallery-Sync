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
    val samsung = OneDrivePickerHelper.isSamsungDevice()
    val cloudRoots = OneDrivePickerHelper.listCloudRelatedRoots(context)

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

    fun openSamsungStylePicker() {
        if (!pickerAvailable) {
            Toast.makeText(
                context,
                "Ative o app Arquivos / Meus Arquivos para escolher pastas.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val initial = OneDrivePickerHelper.preferredLocalSyncPickerInitialUri(context)
        folderPicker.launch(OneDrivePickerHelper.openFullTreeIntent(context, initial))
    }

    fun openInternalPicker() {
        if (!pickerAvailable) return
        folderPicker.launch(
            OneDrivePickerHelper.openInternalStorageTreeIntent(
                context,
                OneDrivePickerHelper.picturesFolderUri(),
            ),
        )
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
        if (samsung) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "OneDrive no Meus Arquivos",
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
        }
        if (!oneDriveInstalled) {
            Text(
                "Instale o app Microsoft OneDrive e faça login.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Button(
            onClick = { openSamsungStylePicker() },
            modifier = Modifier.fillMaxWidth(),
            enabled = pickerAvailable,
        ) {
            Text(
                if (samsung) {
                    "Escolher pasta (Meus Arquivos / OneDrive)"
                } else {
                    "Escolher pasta (seletor completo / nuvem)"
                },
            )
        }
        Text(
            if (cloudRoots.isNotEmpty()) {
                "Raízes detectadas: ${cloudRoots.joinToString { it.title }}. " +
                    "Se o seletor abrir no lugar certo, entre no OneDrive e toque em “Usar esta pasta”."
            } else if (samsung) {
                "Se ainda não aparecer OneDrive, use o menu lateral do Meus Arquivos dentro do seletor."
            } else {
                OneDrivePickerHelper.ONEDRIVE_NOT_IN_FOLDER_PICKER_REASON
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = onBrowseOneDriveLikeFileManager,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Navegar pastas OneDrive dentro do app")
        }
        OutlinedButton(
            onClick = {
                syncCoordinator.useOneDriveLocalMirrorMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sem seletor: Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT}")
        }
        OutlinedButton(
            onClick = { openInternalPicker() },
            modifier = Modifier.fillMaxWidth(),
            enabled = pickerAvailable,
        ) {
            Text("Só armazenamento interno (Pictures)")
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
