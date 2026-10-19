package com.onegallery.sync.ui.albums

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.local.MediaAlbum
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ptBr = Locale("pt", "BR")
private val createdFormatter =
    DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ptBr)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailsBottomSheet(
    album: MediaAlbum,
    isPlace: Boolean,
    onDismiss: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp))) {
            Text(album.title, style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    isPlace -> "Álbum automático por local (GPS)"
                    album.isUserManaged -> "Álbum personalizado"
                    else -> "Agrupamento pela pasta no aparelho ou na nuvem"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            DetailRow("Conteúdo", formatAlbumMediaCount(album))
            if (album.isUserManaged && album.createdAtEpoch != null && album.createdAtEpoch > 0L) {
                val created = Instant.ofEpochSecond(album.createdAtEpoch)
                    .atZone(ZoneId.systemDefault())
                    .format(createdFormatter)
                DetailRow("Criado em", created)
            }
            if (album.isUserManaged) {
                Text(
                    "Renomear ou excluir o álbum não apaga as fotos — só a organização na galeria.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (!isPlace) {
                Text(
                    "Para renomear ou excluir a organização, use um álbum personalizado " +
                        "(botão + ou mova fotos para um álbum criado por você).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = {
                    onDismiss()
                    onOpenAlbum()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text("Abrir na galeria")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
}
