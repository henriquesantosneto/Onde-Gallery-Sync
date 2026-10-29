package com.onegallery.sync.sync

/**
 * Sync ocioso: delta vazio e biblioteca local estável não precisam comparar cada arquivo.
 * Uma listagem inicial (não incremental), qualquer alteração, ou fila de envio/exclusão
 * continua no caminho completo.
 */
internal data class IdleReconcileSignals(
    val localLibraryMutated: Boolean,
    val incrementalRemote: Boolean,
    val remoteChangedCount: Int,
    val supplementalFromRecursive: Int,
    val unresolvedDeltaSkips: Int,
    val pendingUploads: Int,
    val pendingDeletes: Int,
)

internal fun shouldSkipUnchangedLibraryReconcile(signals: IdleReconcileSignals): Boolean {
    if (signals.localLibraryMutated) return false
    if (!signals.incrementalRemote) return false
    if (signals.remoteChangedCount != 0) return false
    if (signals.supplementalFromRecursive != 0) return false
    if (signals.unresolvedDeltaSkips != 0) return false
    if (signals.pendingUploads != 0) return false
    if (signals.pendingDeletes != 0) return false
    return true
}
