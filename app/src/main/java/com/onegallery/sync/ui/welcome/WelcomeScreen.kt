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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.data.onedrive.OneDrivePickerHelper
import com.onegallery.sync.data.onedrive.SyncSetupDiagnosticsFactory
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncPolicy

@Composable
fun WelcomeScreen(
    syncCoordinator: SyncCoordinator,
    onFinished: () -> Unit,
    onBrowseOneDriveLikeFileManager: () -> Unit,
) {
    val context = LocalContext.current
    val diagnostics = remember { SyncSetupDiagnosticsFactory.create(context) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                syncCoordinator.connectOneDriveFolder(uri)
                    .onSuccess { onFinished() }
                    .onFailure {
                        Toast.makeText(
                            context,
                            it.message ?: "Não foi possível usar essa pasta. Use Pictures/OneGallerySync.",
                            Toast.LENGTH_LONG,
                        ).show()
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "OneDrive no seletor de pastas",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "No seu Samsung, o OneDrive aparece no Meus Arquivos, mas a Microsoft não libera " +
                        "escolher essa pasta para outros apps (como a Galeria Samsung antiga fazia). " +
                        "Por isso não aparece pasta do OneDrive aqui — não é bug do OneGallery.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Caminho que funciona sem Azure: gravar em Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT} " +
                        "e deixar o app OneDrive fazer o backup para a nuvem.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                diagnostics.summaryLines().forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Button(
            onClick = {
                syncCoordinator.useOneDriveLocalMirrorMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ativar sync (Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT})")
        }
        Text(
            "1. Toque acima.\n" +
                "2. Abra o app OneDrive → Configurações → backup/sincronização de fotos.\n" +
                "3. Ative backup (Pictures/Câmera). Suas fotos sobem para a conta OneDrive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = {
                if (!OneDrivePickerHelper.openOneDriveApp(context)) {
                    Toast.makeText(context, "Instale o app OneDrive", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = diagnostics.oneDriveInstalled,
        ) {
            Text("Abrir app OneDrive (ativar backup)")
        }
        OutlinedButton(
            onClick = {
                if (!diagnostics.documentTreeAvailable) {
                    Toast.makeText(context, "Seletor de pastas indisponível", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                folderPicker.launch(OneDrivePickerHelper.openFolderPickerChooser(context))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Tentar escolher pasta (experimental)")
        }
        Text(
            "Pode mostrar só armazenamento interno. Se não houver OneDrive, ignore e use o botão principal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = onBrowseOneDriveLikeFileManager,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Navegar provedor OneDrive no app (experimental)")
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
