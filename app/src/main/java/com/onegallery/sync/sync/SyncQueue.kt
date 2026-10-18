package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataEntity

enum class SyncQueueOperation(val queueLabel: String) {
    UPLOAD_PENDING("Aguardando envio"),
    DOWNLOAD_PENDING("Aguardando download"),
    DELETE_PENDING("Exclusão pendente"),
}

data class ActiveSyncItem(
    val displayName: String,
    val operationLabel: String,
)

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
