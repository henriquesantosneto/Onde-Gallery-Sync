package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun MediaGalleryOverflowMenuButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showDetails: Boolean,
    onDetails: () -> Unit,
    onFreeSpace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Mais opções")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            if (showDetails) {
                DropdownMenuItem(
                    text = { Text("Detalhes") },
                    onClick = {
                        onExpandedChange(false)
                        onDetails()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Liberar espaço") },
                onClick = {
                    onExpandedChange(false)
                    onFreeSpace()
                },
            )
        }
    }
}
