package com.onegallery.sync.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.sync.ActiveSyncItem
import com.onegallery.sync.sync.SyncProgressPhase
import com.onegallery.sync.sync.SyncUiState
import com.onegallery.sync.sync.runningHeadlineFallback

private val phasesWithParallelTransfers = setOf(
    SyncProgressPhase.RECONCILING_LOCAL,
    SyncProgressPhase.REGISTERING_CLOUD_ONLY,
    SyncProgressPhase.PREFETCH_THUMBNAILS,
    SyncProgressPhase.BACKFILL_GPS,
    SyncProgressPhase.BACKFILL_CAPTURE_DATE,
)

/** Linhas com barra na aba Sync: transferências reais ou leva atual da fila de execução. */
fun resolveTransferRowsForUi(state: SyncUiState): List<ActiveSyncItem> {
    if (state.activeItems.isNotEmpty()) return state.activeItems
    if (!state.isRunning || state.runQueue.isEmpty()) return emptyList()
    if (state.progressPhase !in phasesWithParallelTransfers) return emptyList()
    val phaseLabel = when (state.progressPhase) {
        SyncProgressPhase.PREFETCH_THUMBNAILS -> "Baixando miniatura…"
        SyncProgressPhase.BACKFILL_GPS -> "Indexando GPS…"
        SyncProgressPhase.BACKFILL_CAPTURE_DATE -> "Indexando data…"
        SyncProgressPhase.REGISTERING_CLOUD_ONLY -> "Registrando na nuvem…"
        SyncProgressPhase.RECONCILING_LOCAL -> "Sincronizando…"
        else -> "Processando…"
    }
    return state.runQueue.take(8).map { row ->
        ActiveSyncItem(
            canonicalPath = row.canonicalPath,
            displayName = row.displayName,
            operationLabel = phaseLabel,
        )
    }
}

@Composable
fun SyncRunningHeadlineBlock(
    state: SyncUiState,
    modifier: Modifier = Modifier,
) {
    if (!state.isRunning) return
    val headline = state.activityHeadline.ifBlank { state.progressPhase.runningHeadlineFallback() }
    val detail = state.activityDetail.ifBlank {
        state.lastMessage.lineSequence().drop(1).joinToString("\n")
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            headline,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SyncParallelTransfersCard(
    state: SyncUiState,
    modifier: Modifier = Modifier,
) {
    if (!state.isRunning) return
    val rows = resolveTransferRowsForUi(state)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (rows.size <= 1) {
                "Transferência ativa"
            } else {
                "Transferências em paralelo (${rows.size})"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (rows.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Preparando fila ou aguardando rede…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rows.forEach { item ->
                SyncActiveTransferRow(item)
            }
        }
    }
}

@Composable
fun SyncActiveTransferRow(activeItem: ActiveSyncItem) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            activeItem.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
        )
        Text(
            activeItem.operationLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SyncTransferProgressBar(activeItem = activeItem)
    }
}

@Composable
fun SyncTransferProgressBar(activeItem: ActiveSyncItem) {
    val fraction = activeItem.progressFraction
    val byteLabel = activeItem.progressByteLabel()
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
    if (byteLabel != null) {
        Text(
            byteLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
