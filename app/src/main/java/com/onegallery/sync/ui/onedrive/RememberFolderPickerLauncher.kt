package com.onegallery.sync.ui.onedrive

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.onegallery.sync.data.onedrive.FolderPickerResults
import com.onegallery.sync.sync.SyncCoordinator

@Composable
fun rememberConnectFolderLauncher(
    syncCoordinator: SyncCoordinator?,
    onConnected: () -> Unit = {},
    onUriGranted: ((Uri) -> Unit)? = null,
): (Intent) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val uri: Uri? = FolderPickerResults.uriFromResult(result.data)
                if (uri == null) {
                    Toast.makeText(
                        context,
                        "Nenhuma pasta recebida. Tente de novo ou use Meus Arquivos (sistema).",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@rememberLauncherForActivityResult
                }
                if (onUriGranted != null) {
                    onUriGranted.invoke(uri)
                    return@rememberLauncherForActivityResult
                }
                val coordinator = syncCoordinator
                    ?: run {
                        Toast.makeText(context, "Erro interno ao conectar pasta", Toast.LENGTH_SHORT).show()
                        return@rememberLauncherForActivityResult
                    }
                scope.launch {
                    coordinator.connectOneDriveFolder(uri)
                        .onSuccess { onConnected() }
                        .onFailure {
                            Toast.makeText(
                                context,
                                it.message ?: "Não foi possível usar esta pasta",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
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
