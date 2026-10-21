package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MoveToAlbumDialog(
    userAlbumTitles: List<String>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
    onCreateAlbum: (String) -> Unit,
) {
    var newAlbumName by remember { mutableStateOf("") }
    var showCreateField by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover para álbum") },
        text = {
            Column {
                Text(
                    "Organização só no app — o caminho no OneDrive não muda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(onClick = { onMove(null) }) {
                    Text("Sem álbum (pasta original)")
                }
                userAlbumTitles.forEach { title ->
                    TextButton(onClick = { onMove(title) }) {
                        Text(title)
                    }
                }
                if (showCreateField) {
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it },
                        label = { Text("Nome do álbum") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    TextButton(onClick = { showCreateField = true }) {
                        Text("+ Criar álbum…")
                    }
                }
            }
        },
        confirmButton = {
            if (showCreateField) {
                TextButton(
                    onClick = {
                        if (newAlbumName.isNotBlank()) {
                            onCreateAlbum(newAlbumName.trim())
                        }
                    },
                ) { Text("Criar e mover") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}

@Composable
fun DeleteSelectionDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Excluir $count item(ns)?") },
        text = {
            Text(
                "Os arquivos serão removidos no aparelho e no OneDrive quando houver cópia na nuvem. " +
                    "Esta ação não pode ser desfeita.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Excluir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

enum class LocalMediaRemovalPrompt {
    DELETE,
    RELEASE_SPACE,
}

/** Android 11+: sem “acesso a todos os arquivos”, o SO abre diálogo a cada lote. */
@Composable
fun AllFilesAccessForLocalRemovalDialog(
    count: Int,
    purpose: LocalMediaRemovalPrompt,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinueWithSystemPrompt: () -> Unit,
) {
    val actionLabel = when (purpose) {
        LocalMediaRemovalPrompt.DELETE -> "exclusão"
        LocalMediaRemovalPrompt.RELEASE_SPACE -> "liberação de espaço"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissão para remover cópias no aparelho") },
        text = {
            Column {
                Text(
                    "No Android 11+, liberar espaço e excluir fotos no aparelho exigem " +
                        "“Acesso a todos os arquivos” uma única vez. Sem isso, o Android pode " +
                        "não mostrar nenhum diálogo e a operação falha.",
                )
                Text(
                    "Toque Conceder acesso, ative para o One Gallery Sync e volte ao app — " +
                        "a $actionLabel de $count item(ns) continua automaticamente.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Conceder acesso") }
        },
        dismissButton = {
            TextButton(onClick = onContinueWithSystemPrompt) { Text("Tentar diálogo do Android") }
        },
    )
}
