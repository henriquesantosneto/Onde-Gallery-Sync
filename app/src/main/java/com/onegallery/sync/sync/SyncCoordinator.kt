package com.onegallery.sync.sync

import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.onedrive.OneDriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncUiState(
    val lastIndexedLocalCount: Int = 0,
    val pendingOperations: Int = 0,
    val lastMessage: String = SyncCapability.NOT_ENABLED_MESSAGE,
    val isRunning: Boolean = false,
)

class SyncCoordinator(
    private val localMediaRepository: LocalMediaRepository,
    private val oneDriveRepository: OneDriveRepository,
    private val syncMetadataDao: SyncMetadataDao,
) {
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /**
     * Indexa mídia local e grava metadados iniciais (estado LOCAL_ONLY).
     * Não envia arquivos ao OneDrive nesta versão.
     */
    suspend fun indexLocalLibrary(): Result<Int> {
        _state.value = _state.value.copy(isRunning = true, lastMessage = "Indexando mídia local…")
        return runCatching {
            val items = localMediaRepository.loadAllMedia()
            items.forEach { item ->
                val existing = syncMetadataDao.getByLocalId(item.id)
                if (existing == null) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localMediaId = item.id,
                            localContentUri = item.uri.toString(),
                            relativePath = item.relativePath,
                            oneDriveItemId = null,
                            oneDrivePath = null,
                            localModifiedAt = item.dateAddedSeconds,
                            remoteModifiedAt = null,
                            cloudState = CloudSyncState.LOCAL_ONLY,
                            lastError = null,
                        ),
                    )
                }
            }
            _state.value = _state.value.copy(
                lastIndexedLocalCount = items.size,
                pendingOperations = 0,
                isRunning = false,
                lastMessage = "Indexação local concluída. Sync com nuvem: desativado (alpha).",
            )
            items.size
        }.onFailure {
            _state.value = _state.value.copy(
                isRunning = false,
                lastMessage = it.message ?: "Falha ao indexar mídia local.",
            )
        }
    }

    suspend fun runCloudProbe(): Result<String> {
        if (!SyncCapability.bidirectionalSyncEnabled) {
            _state.value = _state.value.copy(lastMessage = SyncCapability.NOT_ENABLED_MESSAGE)
            return Result.failure(UnsupportedOperationException(SyncCapability.NOT_ENABLED_MESSAGE))
        }
        _state.value = _state.value.copy(isRunning = true, lastMessage = "Consultando OneDrive…")
        return oneDriveRepository.probeRootListing()
            .map { count ->
                "OneDrive respondeu ($count itens na raiz)."
            }
            .also { result ->
                _state.value = _state.value.copy(
                    isRunning = false,
                    lastMessage = result.getOrElse { it.message ?: "Erro" },
                )
            }
    }
}
