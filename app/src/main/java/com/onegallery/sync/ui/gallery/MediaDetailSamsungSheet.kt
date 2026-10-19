package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Painel de detalhes estilo Samsung: só a alça no peek; conteúdo completo ao expandir.
 * Mapa só quando há coordenadas GPS.
 */
@Composable
fun MediaDetailSamsungSheet(
    info: MediaDetailInfo?,
    loading: Boolean,
    error: String?,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    showExpandedContent: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showExpandedContent) {
                    Modifier.verticalScroll(scroll)
                } else {
                    Modifier
                },
            )
            .padding(start = 20.dp, end = 20.dp, bottom = if (showExpandedContent) 28.dp else 10.dp),
    ) {
        if (!showExpandedContent) {
            Text(
                "Deslize para cima para ver detalhes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
            error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@Column
        }

        when {
            loading && info == null -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 16.dp),
                )
            }
            info != null -> {
                val headline = info.capturedAtFriendly.ifBlank { info.capturedAtFormatted }
                Text(
                    headline.ifBlank { info.displayName },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (info.capturedAtFriendly.isNotBlank() && info.capturedAtFormatted != "—") {
                    Text(
                        info.capturedAtFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    "${info.kindLabel} · ${info.albumLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                info.locationLabel?.let { place ->
                    Text(
                        place,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    info.syncLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (info.hasLocationMap) {
                    PhotoDetailLocationMap(
                        latitude = info.mapLatitude!!,
                        longitude = info.mapLongitude!!,
                        locationLabel = info.locationLabel,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                TextButton(
                    onClick = onToggleAdvanced,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                    )
                    Text(
                        if (showAdvanced) " Ocultar detalhes técnicos" else " Detalhes técnicos",
                    )
                }

                if (showAdvanced) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    info.advancedRows.forEachIndexed { index, (label, value) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
        error?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
