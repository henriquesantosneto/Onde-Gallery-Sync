package com.onegallery.sync.ui.onedrive

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.onegallery.sync.data.onedrive.FolderPickerResults
import com.onegallery.sync.sync.SyncCoordinator

@Composable
fun rememberConnectFolderLauncher(
    syncCoordinator: SyncCoordinator,
    onConnected: () -> Unit = {},
): (android.content.Intent) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val uri: Uri? = FolderPickerResults.uriFromResult(result.data)
                if (uri == null) {
                    Toast.makeText(
                        context,
                        "Nenhuma pasta recebida. Tente “Meus Arquivos (Samsung)” de novo.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@rememberLauncherForActivityResult
                }
                syncCoordinator.connectOneDriveFolder(uri)
                    .onSuccess { onConnected() }
                    .onFailure {
                        Toast.makeText(
                            context,
                            it.message ?: "Não foi possível usar esta pasta",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
            android.app.Activity.RESULT_CANCELED -> {
                Toast.makeText(context, "Seleção cancelada", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(
                    context,
                    "Seletor encerrou sem pasta (código ${result.resultCode})",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    return { intent -> launcher.launch(intent) }
}
