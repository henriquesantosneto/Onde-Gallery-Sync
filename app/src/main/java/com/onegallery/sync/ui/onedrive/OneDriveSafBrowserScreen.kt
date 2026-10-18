package com.onegallery.sync.ui.onedrive

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.onedrive.OneDriveDocumentBrowser
import com.onegallery.sync.data.onedrive.OneDrivePickerHelper

private data class SafCrumb(
    val entry: OneDriveDocumentBrowser.BrowseEntry,
    val pathLabel: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneDriveSafBrowserScreen(
    onFolderConfirmed: (Uri) -> Unit,
    onBack: () -> Unit,
    onOpenSystemPicker: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val crumbs = remember { mutableStateListOf<SafCrumb>() }
    var roots by remember { mutableStateOf<List<OneDriveDocumentBrowser.BrowseEntry>>(emptyList()) }
    var folders by remember { mutableStateOf<List<OneDriveDocumentBrowser.BrowseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val current = crumbs.lastOrNull()?.entry
    val atRootPicker = crumbs.isEmpty()

    val launchGrant = rememberConnectFolderLauncher(
        syncCoordinator = null,
        onConnected = {},
        onUriGranted = onFolderConfirmed,
    )

    LaunchedEffect(Unit) {
        loading = true
        error = null
        roots = OneDriveDocumentBrowser.loadRoots(context)
        if (roots.isEmpty()) {
            error =
                "OneDrive não expôs pastas ao sistema (0 raízes). Use Meus Arquivos abaixo ou login na nuvem."
        }
        loading = false
    }

    LaunchedEffect(current?.authority, current?.documentId) {
        val node = current ?: return@LaunchedEffect
        loading = true
        error = null
        OneDriveDocumentBrowser.loadChildren(context, node.authority, node.documentId)
            .onSuccess { folders = it }
            .onFailure { error = it.message ?: "Não foi possível listar pastas" }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            atRootPicker -> "OneDrive — escolha a origem"
                            else -> crumbs.last().pathLabel
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (crumbs.size > 1) crumbs.removeAt(crumbs.lastIndex)
                            else if (crumbs.size == 1) crumbs.clear()
                            else onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
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
                "Gerenciador dentro do app: navegue pelas pastas expostas pelo OneDrive/Meus Arquivos. " +
                    "Ao confirmar, o Android pede permissão na pasta escolhida.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (atRootPicker) {
                    items(roots, key = { "root-${it.documentId}" }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name) },
                            leadingContent = {
                                Icon(Icons.Outlined.Cloud, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                crumbs.add(SafCrumb(entry, entry.name))
                            },
                        )
                    }
                } else {
                    items(folders, key = { it.documentId }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name) },
                            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            modifier = Modifier.clickable {
                                val parentPath = crumbs.last().pathLabel
                                val newPath = if (parentPath.isEmpty()) entry.name else "$parentPath/${entry.name}"
                                crumbs.add(SafCrumb(entry, newPath))
                            },
                        )
                    }
                }
            }
            current?.let { node ->
                Button(
                    onClick = {
                        val intent = OneDrivePickerHelper.openSamsungMyFilesTreeIntent(context)
                            ?: OneDrivePickerHelper.openFullTreeIntent(context, node.documentUri)
                        launchGrant(intent)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text("Sincronizar esta pasta")
                }
            }
            if (onOpenSystemPicker != null) {
                OutlinedButton(
                    onClick = onOpenSystemPicker,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Abrir Meus Arquivos (sistema)")
                }
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Text("Voltar")
            }
        }
    }
}
