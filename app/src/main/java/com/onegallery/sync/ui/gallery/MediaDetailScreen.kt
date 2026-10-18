package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailScreen(
    canonicalPath: String,
    syncCoordinator: SyncCoordinator,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<GalleryDisplayItem?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(canonicalPath) {
        loading = true
        error = null
        var display = syncCoordinator.getMetadata(canonicalPath)?.let { GalleryMapper.toDisplayItem(it) }
        if (display != null && display.localUri == null && display.cachedOriginal == null) {
            syncCoordinator.downloadOriginalOnDemand(canonicalPath)
                .onSuccess { file -> display = display?.copy(cachedOriginal = file) }
                .onFailure { error = it.message }
        }
        item = display
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.displayName ?: "Detalhe") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Voltar") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                loading -> CircularProgressIndicator()
                item == null -> Text("Item não encontrado.")
                else -> {
                    val display = item!!
                    val model = display.thumbnailModel
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = display.displayName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("Original na nuvem — use o botão abaixo para baixar.")
                    }
                    Text(
                        "Estado: ${display.cloudState.name}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    if (error != null) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    }
                    if (display.localUri == null && display.cachedOriginal == null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    syncCoordinator.downloadOriginalOnDemand(canonicalPath)
                                        .onSuccess { file -> item = display.copy(cachedOriginal = file) }
                                        .onFailure { err -> error = err.message }
                                }
                            },
                        ) { Text("Baixar original") }
                    }
                    Button(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Excluir…") }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Confirmar exclusão") },
            text = {
                Text(
                    "A exclusão só propaga para o OneDrive e o aparelho após confirmação. " +
                        "Itens na lixeira do sistema não entram na sincronização.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            syncCoordinator.requestDelete(canonicalPath, PendingDeleteTarget.BOTH)
                                .getOrThrow()
                            syncCoordinator.resolvePendingDelete(canonicalPath, confirmed = true)
                                .getOrThrow()
                            onBack()
                        }
                    },
                ) { Text("Excluir nos dois") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancelar") }
            },
        )
    }
}
