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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Painel de detalhes estilo Samsung: peek mínimo; expandido = data, arquivo, EXIF, mapa+endereço.
 */
@Composable
fun MediaDetailSamsungSheet(
    info: MediaDetailInfo?,
    loading: Boolean,
    error: String?,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    showExpandedContent: Boolean = true,
    contentOnDark: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val primaryText = if (contentOnDark) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryText = if (contentOnDark) {
        Color.White.copy(alpha = 0.62f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

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
                color = secondaryText,
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
                val headline = info.samsungHeadline.ifBlank {
                    info.capturedAtFormatted.takeIf { it != "—" }.orEmpty()
                }
                Text(
                    headline.ifBlank { info.displayName },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText,
                )
                Text(
                    info.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    info.storagePathLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText,
                    modifier = Modifier.padding(top = 2.dp),
                )

                info.captureSpecs?.deviceLabel?.let { device ->
                    Text(
                        device,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryText,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                info.captureSpecs?.statsPrimaryLine?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryText,
                        modifier = Modifier.padding(top = if (info.captureSpecs.deviceLabel != null) 6.dp else 14.dp),
                    )
                }
                info.captureSpecs?.statsSecondaryLine?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (info.hasLocationMap) {
                    PhotoDetailLocationSection(
                        latitude = info.mapLatitude!!,
                        longitude = info.mapLongitude!!,
                        locationLabel = info.locationLabel,
                        contentOnDark = contentOnDark,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else if (!info.locationLabel.isNullOrBlank()) {
                    Text(
                        info.locationLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = primaryText,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                TextButton(
                    onClick = onToggleAdvanced,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(
                        if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = if (contentOnDark) Color.White else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (showAdvanced) " Ocultar mais informações" else " Mais informações",
                        color = if (contentOnDark) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                    )
                }

                if (showAdvanced) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = if (contentOnDark) {
                            Color.White.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        },
                    )
                    Text(
                        info.syncLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryText,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    info.advancedRows.forEachIndexed { index, (label, value) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = if (contentOnDark) {
                                    Color.White.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                },
                            )
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryText,
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = primaryText,
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
