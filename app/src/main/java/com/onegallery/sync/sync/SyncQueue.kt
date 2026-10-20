package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataEntity

enum class SyncQueueOperation(val queueLabel: String) {
    UPLOAD_PENDING("Aguardando envio"),
    DOWNLOAD_PENDING("Aguardando download"),
    DELETE_PENDING("Exclusão pendente"),
}

/** Item mostrado na fila durante ou após o sync (UI). */
data class SyncQueueDisplayRow(
    val canonicalPath: String,
    val displayName: String,
    val statusLabel: String,
) {
    companion object {
        fun fromEntity(entity: SyncMetadataEntity): SyncQueueDisplayRow {
            return SyncQueueDisplayRow(
                canonicalPath = entity.canonicalPath,
                displayName = entity.displayName,
                statusLabel = entity.syncQueueStatusLabel(),
            )
        }
    }
}

data class ActiveSyncItem(
    val canonicalPath: String,
    val displayName: String,
    val operationLabel: String,
    val bytesCompleted: Long = 0L,
    val totalBytes: Long? = null,
) {
    val progressFraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return (bytesCompleted.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

fun SyncMetadataEntity.queueOperation(): SyncQueueOperation? {
    if (pendingDeleteTarget != PendingDeleteTarget.NONE) {
        return SyncQueueOperation.DELETE_PENDING
    }
    return when (cloudState) {
        CloudSyncState.PENDING_UPLOAD -> SyncQueueOperation.UPLOAD_PENDING
        CloudSyncState.PENDING_DOWNLOAD -> SyncQueueOperation.DOWNLOAD_PENDING
        else -> null
    }
}

/** Rótulo na fila de sync (inclui LOCAL_ONLY = aguardando primeiro envio). */
fun SyncMetadataEntity.syncQueueStatusLabel(): String {
    queueOperation()?.let { return it.queueLabel }
    return when (cloudState) {
        CloudSyncState.LOCAL_ONLY -> SyncQueueOperation.UPLOAD_PENDING.queueLabel
        CloudSyncState.SYNCED -> "Sincronizado"
        CloudSyncState.CLOUD_ONLY -> SyncQueueOperation.DOWNLOAD_PENDING.queueLabel
        else -> queueDetailLabel()
    }
}

/** Mesma prioridade usada na fila da UI e na ordem de processamento do sync. */
fun SyncMetadataEntity.syncQueueSortOrder(): Int = when {
    pendingDeleteTarget != PendingDeleteTarget.NONE -> 2
    cloudState == CloudSyncState.PENDING_UPLOAD -> 0
    cloudState == CloudSyncState.LOCAL_ONLY -> 0
    cloudState == CloudSyncState.PENDING_DOWNLOAD -> 1
    else -> 3
}

val syncQueueEntityComparator: Comparator<SyncMetadataEntity> =
    compareBy<SyncMetadataEntity>(
        { it.syncQueueSortOrder() },
        { -(it.localModifiedAt ?: it.remoteModifiedAt ?: 0L) },
        { it.canonicalPath },
    )

fun SyncMetadataEntity.queueDetailLabel(): String {
    if (pendingDeleteTarget != PendingDeleteTarget.NONE) {
        return when (pendingDeleteTarget) {
            PendingDeleteTarget.LOCAL -> "Excluir do aparelho"
            PendingDeleteTarget.REMOTE -> "Excluir na nuvem"
            PendingDeleteTarget.BOTH -> "Excluir no aparelho e na nuvem"
            PendingDeleteTarget.NONE -> ""
        }
    }
    return queueOperation()?.queueLabel ?: cloudState.name
}
