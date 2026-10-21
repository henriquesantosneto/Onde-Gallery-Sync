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
import com.onegallery.sync.sync.SyncQueueDisplayRow
import com.onegallery.sync.sync.SyncUiState
import com.onegallery.sync.sync.runningHeadlineFallback

/** Painel reutilizável: título do sync + barras por transferência paralela. */
@Composable
fun SyncRunningProgressPanel(
    state: SyncUiState,
    runQueue: List<SyncQueueDisplayRow> = emptyList(),
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (!state.isRunning) return
    val headline = state.activityHeadline.ifBlank { state.progressPhase.runningHeadlineFallback() }
    val detail = state.activityDetail.ifBlank {
        state.lastMessage.lineSequence().drop(1).joinToString("\n")
    }
    val transferRows = resolveTransferRowsForUi(state, runQueue)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            headline,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (transferRows.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                if (transferRows.size == 1) {
                    "Transferência ativa"
                } else {
                    "Em paralelo (${transferRows.size})"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            transferRows.forEach { item ->
                SyncActiveTransferRow(item)
            }
        }
    }
}

@Composable
fun SyncActivityBanner(
    state: SyncUiState,
    runQueue: List<SyncQueueDisplayRow>,
    modifier: Modifier = Modifier,
) {
    if (!state.isRunning) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        SyncRunningProgressPanel(
            state = state,
            runQueue = runQueue,
            compact = true,
        )
    }
}

fun resolveTransferRowsForUi(
    state: SyncUiState,
    runQueue: List<SyncQueueDisplayRow>,
): List<ActiveSyncItem> {
    if (state.activeItems.isNotEmpty()) return state.activeItems
    if (!state.isRunning || runQueue.isEmpty()) return emptyList()
    val phaseLabel = when (state.progressPhase) {
        SyncProgressPhase.PREFETCH_THUMBNAILS -> "Baixando miniatura…"
        SyncProgressPhase.BACKFILL_GPS -> "Indexando GPS…"
        SyncProgressPhase.BACKFILL_CAPTURE_DATE -> "Indexando data…"
        else -> "Processando…"
    }
    if (state.progressPhase !in listOf(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            SyncProgressPhase.BACKFILL_GPS,
            SyncProgressPhase.BACKFILL_CAPTURE_DATE,
        )
    ) {
        return emptyList()
    }
    return runQueue.take(8).map { row ->
        ActiveSyncItem(
            canonicalPath = row.canonicalPath,
            displayName = row.displayName,
            operationLabel = phaseLabel,
        )
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
