package com.onegallery.sync.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.local.db.SyncIndexStatsRow
import com.onegallery.sync.ui.gallery.formatFileSize

data class IndexUsageStats(
    val totalItems: Int,
    val totalBytes: Long,
    val images: Int,
    val videos: Int,
    val audio: Int,
    val documents: Int,
    val otherTypes: Int,
    val imageBytes: Long,
    val videoBytes: Long,
    val audioBytes: Long,
    val documentBytes: Long,
    val otherTypeBytes: Long,
) {
    companion object {
        fun from(row: SyncIndexStatsRow): IndexUsageStats = IndexUsageStats(
            totalItems = row.total,
            totalBytes = row.totalBytes,
            images = row.images,
            videos = row.videos,
            audio = row.audio,
            documents = row.documents,
            otherTypes = row.otherTypes,
            imageBytes = row.imageBytes,
            videoBytes = row.videoBytes,
            audioBytes = row.audioBytes,
            documentBytes = row.documentBytes,
            otherTypeBytes = row.otherTypeBytes,
        )

        val empty = IndexUsageStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

@Composable
fun LibraryIndexUsageCard(
    stats: IndexUsageStats?,
    modifier: Modifier = Modifier,
) {
    val usage = stats ?: IndexUsageStats.empty
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (usage.totalItems <= 0) {
                Text(
                    "Nenhum item no índice ainda. Sincronize ou indexe a galeria local.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            UsageStatRow("Itens indexados", usage.totalItems.toString())
            IndexMediaTypeDistribution(usage)
            Text(
                "Tamanhos somam local ou nuvem por arquivo (sem contar duas vezes).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IndexMediaTypeDistribution(stats: IndexUsageStats) {
    val segments = listOf(
        IndexMediaTypeSegment("Imagens", stats.images, stats.imageBytes, IndexMediaTypeColors.images),
        IndexMediaTypeSegment("Vídeos", stats.videos, stats.videoBytes, IndexMediaTypeColors.videos),
        IndexMediaTypeSegment("Áudio", stats.audio, stats.audioBytes, IndexMediaTypeColors.audio),
        IndexMediaTypeSegment(
            "Documentos",
            stats.documents,
            stats.documentBytes,
            IndexMediaTypeColors.documents,
        ),
        IndexMediaTypeSegment("Outros", stats.otherTypes, stats.otherTypeBytes, IndexMediaTypeColors.other),
    )
    val sizeTotal = stats.totalBytes.coerceAtLeast(0L)
    val barBySize = sizeTotal > 0L
    val active = if (barBySize) {
        segments.filter { it.bytes > 0L }
    } else {
        segments.filter { it.count > 0 }
    }
    if (sizeTotal > 0L) {
        UsageStatRow("Total estimado", formatFileSize(sizeTotal), emphasized = true)
    } else {
        Text(
            "Tamanho ainda não indexado para todos os itens — barra por quantidade.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (active.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            active.forEach { segment ->
                val weight = if (barBySize) {
                    segment.bytes.toFloat().coerceAtLeast(1f)
                } else {
                    segment.count.toFloat().coerceAtLeast(1f)
                }
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(segment.color),
                )
            }
        }
    }
    segments.forEach { segment ->
        val pct = when {
            barBySize && sizeTotal > 0L -> segment.bytes * 100f / sizeTotal.toFloat()
            stats.totalItems > 0 -> segment.count * 100f / stats.totalItems.toFloat()
            else -> 0f
        }
        val sizeLabel = if (segment.bytes > 0L) formatFileSize(segment.bytes) else "—"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(segment.color),
                )
                Text(segment.label, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "$sizeLabel · ${segment.count} itens (${formatIndexTypePercent(pct)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class IndexMediaTypeSegment(
    val label: String,
    val count: Int,
    val bytes: Long,
    val color: Color,
)

private fun formatIndexTypePercent(pct: Float): String {
    return when {
        pct <= 0f -> "0%"
        pct < 1f -> "<1%"
        pct >= 99.95f -> "100%"
        else -> "${pct.toInt()}%"
    }
}

private object IndexMediaTypeColors {
    val images = Color(0xFF1A73E8)
    val videos = Color(0xFFD93025)
    val audio = Color(0xFF188038)
    val documents = Color(0xFFF9AB00)
    val other = Color(0xFF80868B)
}

@Composable
private fun UsageStatRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
