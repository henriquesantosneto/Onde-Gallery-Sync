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
    onExploreOneDriveInCloud: () -> Unit,
) {
    val context = LocalContext.current
    val oneDriveInstalled = OneDrivePickerHelper.isOneDriveAppInstalled(context)

    val picturesPicker = rememberLauncherForActivityResult(
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

    val cloudPicker = rememberLauncherForActivityResult(
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
                    "Não aparece pasta do OneDrive?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "No Samsung/Android isso é normal: o seletor do sistema só mostra pastas do telefone " +
                        "(Armazenamento interno, SD). A Microsoft não expõe a nuvem OneDrive nesse seletor " +
                        "como a galeria antiga fazia.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Use uma pasta local em Pictures; o app OneDrive envia para sua conta na nuvem " +
                        "quando você ativa o backup de fotos.",
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
            onClick = onExploreOneDriveInCloud,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Explorar OneDrive na nuvem (escolher pasta)")
        }
        Text(
            "Navega pastas do seu OneDrive como no site/app e define onde a galeria sincronizada ficará. " +
                "Requer login Microsoft (não usa o seletor de arquivos do Android).",
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
            Text("Usar Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT} (sem login)")
        }
        Text(
            "Cria e usa a pasta interna Pictures/${SyncPolicy.ONEDRIVE_MIRROR_ROOT}. " +
                "Depois ative no OneDrive o backup/sincronização de Pictures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = {
                val intent = OneDrivePickerHelper.openTreeIntent(OneDrivePickerHelper.picturesFolderUri())
                picturesPicker.launch(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Escolher pasta Pictures no seletor")
        }
        Text(
            "No seletor: Armazenamento interno → Pictures (ou subpasta ${SyncPolicy.ONEDRIVE_MIRROR_ROOT}). " +
                "Só ver pastas locais aqui é o comportamento esperado.",
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
            enabled = oneDriveInstalled,
        ) {
            Text("Abrir app OneDrive (ativar backup)")
        }
        OutlinedButton(
            onClick = {
                val initial = OneDrivePickerHelper.oneDriveInitialUri()
                val intent = OneDrivePickerHelper.openTreeIntent(initial)
                cloudPicker.launch(intent)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Tentar seletor na nuvem (raro no Samsung)")
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
