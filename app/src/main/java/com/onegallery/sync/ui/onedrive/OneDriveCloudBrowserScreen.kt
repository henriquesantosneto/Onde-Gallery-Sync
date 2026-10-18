package com.onegallery.sync.ui.onedrive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.onedrive.OneDriveBrowseEntry
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.sync.SyncPolicy
import kotlinx.coroutines.launch

private data class FolderCrumb(
    val id: String,
    val name: String,
    /** Caminho relativo à raiz do drive (vazio na raiz). */
    val drivePath: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneDriveCloudBrowserScreen(
    oneDriveRepository: OneDriveRepository,
    onFolderSelected: (itemId: String, drivePath: String, label: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val crumbs = remember { mutableStateListOf<FolderCrumb>() }
    var folders by remember { mutableStateOf<List<OneDriveBrowseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }

    val currentCrumb: FolderCrumb? = crumbs.lastOrNull()

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching {
            oneDriveRepository.getDriveRootId()
                .onSuccess { rootId ->
                    crumbs.clear()
                    crumbs.add(FolderCrumb(rootId, "OneDrive", ""))
                }
                .onFailure {
                    error = it.message ?: "Faça login Microsoft primeiro"
                }
        }.onFailure {
            error = it.message ?: "Erro ao abrir OneDrive"
        }
        loading = false
    }

    LaunchedEffect(currentCrumb?.id) {
        val folderId = currentCrumb?.id ?: return@LaunchedEffect
        loading = true
        error = null
        oneDriveRepository.listBrowseChildren(folderId)
            .onSuccess { folders = it }
            .onFailure { error = it.message ?: "Falha ao listar pastas" }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentCrumb?.let { if (it.drivePath.isEmpty()) it.name else it.drivePath }
                            ?: "OneDrive",
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (crumbs.size > 1) {
                                crumbs.removeAt(crumbs.lastIndex)
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(
                        enabled = currentCrumb != null && !creatingFolder,
                        onClick = {
                            val parent = currentCrumb ?: return@IconButton
                            creatingFolder = true
                            scope.launch {
                                oneDriveRepository.createSubfolder(parent.id, SyncPolicy.ONEDRIVE_MIRROR_ROOT)
                                    .onSuccess { entry ->
                                        folders = folders + entry
                                    }
                                    .onFailure { error = it.message }
                                creatingFolder = false
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Nova pasta")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Navegue como no OneDrive e escolha onde a galeria sincronizada ficará na nuvem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(folders, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            entry.childCount?.let { count ->
                                Text("$count itens")
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Folder, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            val parent = currentCrumb ?: return@clickable
                            val newPath = if (parent.drivePath.isEmpty()) {
                                entry.name
                            } else {
                                "${parent.drivePath}/${entry.name}"
                            }
                            crumbs.add(FolderCrumb(entry.id, entry.name, newPath))
                        },
                    )
                }
            }
            currentCrumb?.let { crumb ->
                Button(
                    onClick = {
                        val path = crumb.drivePath
                        val label = if (path.isEmpty()) crumb.name else path
                        onFolderSelected(crumb.id, path, label)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) {
                    Text(
                        if (crumb.drivePath.isEmpty()) {
                            "Sincronizar na raiz do OneDrive"
                        } else {
                            "Sincronizar nesta pasta: ${crumb.drivePath}"
                        },
                    )
                }
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Text("Cancelar")
            }
        }
    }
}
