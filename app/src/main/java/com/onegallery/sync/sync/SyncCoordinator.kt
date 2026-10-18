package com.onegallery.sync.sync

import android.net.Uri
import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.data.onedrive.RemoteDriveFile
import com.onegallery.sync.data.onedrive.LocalMirrorRepository
import com.onegallery.sync.data.onedrive.SaOneDriveRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class SyncUiState(
    val lastIndexedLocalCount: Int = 0,
    val pendingOperations: Int = 0,
    val lastMessage: String = "Pronto para sincronizar.",
    val isRunning: Boolean = false,
    val isOffline: Boolean = false,
)

class SyncCoordinator(
    private val localMediaRepository: LocalMediaRepository,
    private val oneDriveRepository: OneDriveRepository,
    private val saOneDriveRepository: SaOneDriveRepository,
    private val localMirrorRepository: LocalMirrorRepository,
    private val syncMetadataDao: SyncMetadataDao,
    private val mediaCacheRepository: MediaCacheRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncPreferences: SyncPreferences,
) {
    fun connectOneDriveFolder(treeUri: Uri): Result<Unit> {
        return saOneDriveRepository.connectFolder(treeUri).onSuccess {
            syncPreferences.syncMode = SyncMode.ONEDRIVE_FOLDER
            syncPreferences.hasCompletedSetup = true
        }
    }

    fun useLocalOnlyMode() {
        syncPreferences.syncMode = SyncMode.LOCAL_ONLY
        syncPreferences.hasCompletedSetup = true
    }

    fun useGraphMode() {
        syncPreferences.syncMode = SyncMode.ONEDRIVE_GRAPH
        syncPreferences.hasCompletedSetup = true
    }

    fun connectGraphSyncFolder(itemId: String, driveRelativePath: String, label: String) {
        oneDriveRepository.setSyncRoot(itemId, driveRelativePath, label)
        useGraphMode()
    }

    fun graphSyncRootLabel(): String? = oneDriveRepository.syncRootLabel()

    fun useOneDriveLocalMirrorMode() {
        syncPreferences.syncMode = SyncMode.ONEDRIVE_LOCAL_MIRROR
        syncPreferences.hasCompletedSetup = true
    }

    fun localMirrorPathLabel(): String = localMirrorRepository.rootPathLabel()
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    val galleryMetadata: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observeAll()
    val pendingDeleteItems: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observePendingDeletes()
    val pendingOperationCount: Flow<Int> = syncMetadataDao.observePendingCount()

    suspend fun getMetadata(canonicalPath: String): SyncMetadataEntity? {
        return syncMetadataDao.getByCanonicalPath(canonicalPath)
    }

    suspend fun indexLocalLibrary(): Result<Int> {
        _state.value = _state.value.copy(isRunning = true, lastMessage = "Indexando mídia local…")
        return runCatching {
            val items = localMediaRepository.loadAllMedia()
            val seenPaths = mutableSetOf<String>()
            items.forEach { item ->
                val canonical = SyncPaths.canonicalFromLocal(item)
                seenPaths.add(canonical)
                val existing = syncMetadataDao.getByCanonicalPath(canonical)
                syncMetadataDao.upsert(mergeLocalIntoEntity(item, canonical, existing))
            }
            reconcileMissingLocalItems(seenPaths)
            _state.value = _state.value.copy(
                lastIndexedLocalCount = items.size,
                isRunning = false,
                lastMessage = "Indexação local: ${items.size} itens (todas as pastas visíveis, ex. WhatsApp).",
            )
            items.size
        }.onFailure {
            _state.value = _state.value.copy(
                isRunning = false,
                lastMessage = it.message ?: "Falha ao indexar mídia local.",
            )
        }
    }

    suspend fun runBidirectionalSync(): Result<SyncSummary> {
        if (!SyncCapability.bidirectionalSyncEnabled) {
            return Result.failure(UnsupportedOperationException(SyncCapability.NOT_ENABLED_MESSAGE))
        }
        when (syncPreferences.syncMode) {
            SyncMode.LOCAL_ONLY -> {
                indexLocalLibrary()
                _state.value = _state.value.copy(
                    lastMessage = "Modo só galeria local. Conecte OneDrive em Ajustes.",
                )
                return Result.failure(IllegalStateException("Sync na nuvem desativada"))
            }
            SyncMode.ONEDRIVE_FOLDER -> if (!saOneDriveRepository.isConnected()) {
                _state.value = _state.value.copy(
                    lastMessage = "Escolha a pasta local que o OneDrive sincroniza (Ajustes).",
                )
                return Result.failure(IllegalStateException("Pasta de sync não conectada"))
            }
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> Unit
            SyncMode.ONEDRIVE_GRAPH -> {
                if (!oneDriveRepository.isSyncRootConfigured()) {
                    _state.value = _state.value.copy(
                        lastMessage = "Escolha uma pasta no OneDrive (explorador na nuvem) em Ajustes.",
                    )
                    return Result.failure(IllegalStateException("Pasta na nuvem não escolhida"))
                }
                oneDriveRepository.accessToken().getOrElse {
                    _state.value = _state.value.copy(
                        lastMessage = "Faça login Microsoft para sync na nuvem.",
                    )
                    return Result.failure(it)
                }
            }
        }
        if (!networkMonitor.isOnline()) {
            _state.value = _state.value.copy(
                isOffline = true,
                lastMessage = "Sem conexão. Miniaturas locais e cache offline continuam visíveis.",
            )
            return Result.failure(IllegalStateException("Offline"))
        }
        if (syncPreferences.wifiOnly && !networkMonitor.isUnmetered()) {
            _state.value = _state.value.copy(
                lastMessage = "Sync pausado: ative dados móveis nas configurações ou use Wi‑Fi.",
            )
            return Result.failure(IllegalStateException("Wi‑Fi necessário"))
        }

        _state.value = _state.value.copy(isRunning = true, lastMessage = "Sincronizando com OneDrive…")
        return runCatching {
            indexLocalLibrary().getOrThrow()
            val remoteFiles = listRemoteFiles().getOrThrow()
            val remoteByPath = remoteFiles.associateBy { it.canonicalPath }

            var uploaded = 0
            var markedPendingDownload = 0
            var cloudOnly = 0

            val rows = syncMetadataDao.getAll().filter { it.localMediaId != null }
            val knownPaths = mutableSetOf<String>()

            for (row in rows) {
                knownPaths.add(row.canonicalPath)
                if (row.pendingDeleteTarget != PendingDeleteTarget.NONE) continue
                val remote = remoteByPath[row.canonicalPath]
                when {
                    remote == null && row.oneDriveItemId != null -> {
                        syncMetadataDao.upsert(
                            row.copy(
                                pendingDeleteTarget = PendingDeleteTarget.LOCAL,
                                cloudState = CloudSyncState.CONFLICT,
                                lastError = "Removido do OneDrive — confirme exclusão local.",
                            ),
                        )
                    }
                    remote == null -> uploaded += uploadLocalRow(row)
                    else -> {
                        val merged = reconcileWithRemote(row, remote)
                        syncMetadataDao.upsert(merged)
                        when (merged.cloudState) {
                            CloudSyncState.PENDING_UPLOAD -> uploaded += uploadLocalRow(merged)
                            CloudSyncState.PENDING_DOWNLOAD -> markedPendingDownload++
                            else -> Unit
                        }
                    }
                }
            }

            remoteByPath.forEach { (path, remote) ->
                if (path in knownPaths) return@forEach
                val existing = syncMetadataDao.getByCanonicalPath(path)
                if (existing == null) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            canonicalPath = path,
                            displayName = remote.name,
                            albumName = SyncPaths.albumFromCanonical(path),
                            localMediaId = null,
                            localContentUri = null,
                            oneDriveItemId = remote.itemId,
                            localModifiedAt = null,
                            remoteModifiedAt = remote.modifiedAtEpoch,
                            cloudState = CloudSyncState.CLOUD_ONLY,
                            cachedFilePath = null,
                            pendingDeleteTarget = PendingDeleteTarget.NONE,
                            lastError = null,
                        ),
                    )
                    cloudOnly++
                }
            }

            val pendingCount = syncMetadataDao.getAll().count {
                it.cloudState == CloudSyncState.PENDING_UPLOAD ||
                    it.cloudState == CloudSyncState.PENDING_DOWNLOAD ||
                    it.pendingDeleteTarget != PendingDeleteTarget.NONE
            }
            _state.value = _state.value.copy(
                isRunning = false,
                isOffline = false,
                pendingOperations = pendingCount,
                lastMessage = "Sync: $uploaded enviados, $markedPendingDownload aguardando download, $cloudOnly só na nuvem.",
            )
            SyncSummary(uploaded, markedPendingDownload, cloudOnly)
        }.onFailure {
            _state.value = _state.value.copy(
                isRunning = false,
                lastMessage = it.message ?: "Erro na sincronização.",
            )
        }
    }

    suspend fun downloadOriginalOnDemand(canonicalPath: String): Result<File> {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath)
            ?: return Result.failure(IllegalArgumentException("Item não encontrado"))

        row.localContentUri?.let {
            return Result.failure(IllegalStateException("Original já está no dispositivo"))
        }
        row.cachedFilePath?.let { path ->
            val cached = File(path)
            if (cached.exists()) return Result.success(cached)
        }

        val itemId = row.oneDriveItemId
            ?: return Result.failure(IllegalStateException("Sem referência OneDrive"))
        if (!networkMonitor.isOnline()) {
            return Result.failure(IllegalStateException("Offline — conecte-se para baixar o original"))
        }

        val target = mediaCacheRepository.originalFile(canonicalPath)
        return downloadRemoteFile(itemId, target).map {
            syncMetadataDao.upsert(
                row.copy(
                    cachedFilePath = target.absolutePath,
                    cloudState = CloudSyncState.CLOUD_ONLY,
                ),
            )
            target
        }
    }

    suspend fun resolvePendingDelete(canonicalPath: String, confirmed: Boolean): Result<Unit> {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath)
            ?: return Result.failure(IllegalArgumentException("Item não encontrado"))
        if (row.pendingDeleteTarget == PendingDeleteTarget.NONE) return Result.success(Unit)

        if (!confirmed) {
            syncMetadataDao.upsert(
                row.copy(
                    pendingDeleteTarget = PendingDeleteTarget.NONE,
                    cloudState = restoredCloudState(row),
                    lastError = null,
                ),
            )
            return Result.success(Unit)
        }

        return runCatching {
            when (row.pendingDeleteTarget) {
                PendingDeleteTarget.LOCAL -> {
                    row.localMediaId?.let { localMediaRepository.deleteMediaItem(it) }
                    mediaCacheRepository.deleteCached(canonicalPath)
                    if (row.oneDriveItemId != null) {
                        syncMetadataDao.upsert(
                            row.copy(
                                localMediaId = null,
                                localContentUri = null,
                                pendingDeleteTarget = PendingDeleteTarget.NONE,
                                cloudState = CloudSyncState.CLOUD_ONLY,
                            ),
                        )
                    } else {
                        syncMetadataDao.deleteByCanonicalPath(canonicalPath)
                    }
                }
                PendingDeleteTarget.REMOTE -> {
                    row.oneDriveItemId?.let { deleteRemoteFile(it).getOrThrow() }
                    if (row.localMediaId != null) {
                        syncMetadataDao.upsert(
                            row.copy(
                                oneDriveItemId = null,
                                pendingDeleteTarget = PendingDeleteTarget.NONE,
                                cloudState = CloudSyncState.LOCAL_ONLY,
                            ),
                        )
                    } else {
                        syncMetadataDao.deleteByCanonicalPath(canonicalPath)
                    }
                }
                PendingDeleteTarget.BOTH -> {
                    row.oneDriveItemId?.let { deleteRemoteFile(it).getOrThrow() }
                    row.localMediaId?.let { localMediaRepository.deleteMediaItem(it) }
                    mediaCacheRepository.deleteCached(canonicalPath)
                    syncMetadataDao.deleteByCanonicalPath(canonicalPath)
                }
                PendingDeleteTarget.NONE -> Unit
            }
        }
    }

    suspend fun requestDelete(canonicalPath: String, target: PendingDeleteTarget): Result<Unit> {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath)
            ?: return Result.failure(IllegalArgumentException("Item não encontrado"))
        syncMetadataDao.upsert(row.copy(pendingDeleteTarget = target))
        return Result.success(Unit)
    }

    private suspend fun uploadLocalRow(row: SyncMetadataEntity): Int {
        val localId = row.localMediaId ?: return 0
        val item = localMediaRepository.findById(localId) ?: return 0
        val temp = localMediaRepository.materializeToTempFile(item) ?: run {
            syncMetadataDao.upsert(
                row.copy(
                    lastError = "Não foi possível ler o arquivo local.",
                    cloudState = CloudSyncState.ERROR,
                ),
            )
            return 0
        }
        return runCatching {
            val remoteId = uploadLocalFile(
                canonicalPath = row.canonicalPath,
                sourceFile = temp,
                mimeType = item.mimeType,
            ).getOrThrow()
            syncMetadataDao.upsert(
                row.copy(
                    oneDriveItemId = remoteId,
                    remoteModifiedAt = System.currentTimeMillis() / 1000,
                    cloudState = CloudSyncState.SYNCED,
                    lastError = null,
                ),
            )
            1
        }.getOrElse {
            syncMetadataDao.upsert(row.copy(cloudState = CloudSyncState.ERROR, lastError = it.message))
            0
        }.also { temp.delete() }
    }

    private fun reconcileWithRemote(row: SyncMetadataEntity, remote: RemoteDriveFile): SyncMetadataEntity {
        val localTs = row.localModifiedAt ?: 0L
        val remoteTs = remote.modifiedAtEpoch ?: 0L
        val base = row.copy(
            oneDriveItemId = remote.itemId,
            remoteModifiedAt = remote.modifiedAtEpoch,
            lastError = null,
        )
        return when {
            localTs > remoteTs -> base.copy(cloudState = CloudSyncState.PENDING_UPLOAD)
            remoteTs > localTs -> base.copy(cloudState = CloudSyncState.PENDING_DOWNLOAD)
            else -> base.copy(cloudState = CloudSyncState.SYNCED)
        }
    }

    private suspend fun reconcileMissingLocalItems(seenPaths: Set<String>) {
        syncMetadataDao.getAll().forEach { row ->
            if (row.localMediaId == null) return@forEach
            if (row.canonicalPath in seenPaths) return@forEach
            if (row.oneDriveItemId != null) {
                syncMetadataDao.upsert(
                    row.copy(
                        localMediaId = null,
                        localContentUri = null,
                        pendingDeleteTarget = PendingDeleteTarget.LOCAL,
                        cloudState = CloudSyncState.CLOUD_ONLY,
                        lastError = "Removido do aparelho — confirme exclusão.",
                    ),
                )
            } else {
                syncMetadataDao.deleteByCanonicalPath(row.canonicalPath)
            }
        }
    }

    private fun mergeLocalIntoEntity(
        item: LocalMediaItem,
        canonical: String,
        existing: SyncMetadataEntity?,
    ): SyncMetadataEntity {
        return SyncMetadataEntity(
            canonicalPath = canonical,
            displayName = item.displayName,
            albumName = item.bucketDisplayName.ifBlank { SyncPaths.albumFromCanonical(canonical) },
            localMediaId = item.id,
            localContentUri = item.uri.toString(),
            oneDriveItemId = existing?.oneDriveItemId,
            localModifiedAt = item.dateAddedSeconds,
            remoteModifiedAt = existing?.remoteModifiedAt,
            cloudState = when {
                existing?.oneDriveItemId != null && existing.cloudState != CloudSyncState.LOCAL_ONLY ->
                    existing.cloudState
                existing?.oneDriveItemId != null -> CloudSyncState.SYNCED
                else -> CloudSyncState.LOCAL_ONLY
            },
            cachedFilePath = existing?.cachedFilePath,
            pendingDeleteTarget = existing?.pendingDeleteTarget ?: PendingDeleteTarget.NONE,
            lastError = existing?.lastError,
        )
    }

    private fun restoredCloudState(row: SyncMetadataEntity): CloudSyncState {
        return when {
            row.localMediaId != null && row.oneDriveItemId != null -> CloudSyncState.SYNCED
            row.oneDriveItemId != null -> CloudSyncState.CLOUD_ONLY
            else -> CloudSyncState.LOCAL_ONLY
        }
    }

    private fun parseGraphTime(value: String): Long? {
        return runCatching { java.time.Instant.parse(value).epochSecond }.getOrNull()
    }

    private suspend fun listRemoteFiles(): Result<List<RemoteDriveFile>> {
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.listMirrorFiles()
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.listMirrorFiles()
            SyncMode.ONEDRIVE_GRAPH -> oneDriveRepository.listMirrorFiles()
            SyncMode.LOCAL_ONLY -> Result.success(emptyList())
        }
    }

    private suspend fun uploadLocalFile(
        canonicalPath: String,
        sourceFile: File,
        mimeType: String?,
    ): Result<String> {
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.uploadLocalFile(canonicalPath, sourceFile, mimeType)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.uploadLocalFile(canonicalPath, sourceFile, mimeType)
            SyncMode.ONEDRIVE_GRAPH -> oneDriveRepository.uploadLocalFile(canonicalPath, sourceFile, mimeType)
                .map { it.id }
            SyncMode.LOCAL_ONLY -> Result.failure(IllegalStateException("Sem nuvem"))
        }
    }

    private suspend fun downloadRemoteFile(remoteId: String, target: File): Result<Unit> {
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.downloadToFile(remoteId, target)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.downloadToFile(remoteId, target)
            SyncMode.ONEDRIVE_GRAPH -> oneDriveRepository.downloadToFile(remoteId, target)
            SyncMode.LOCAL_ONLY -> Result.failure(IllegalStateException("Sem nuvem"))
        }
    }

    private suspend fun deleteRemoteFile(remoteId: String): Result<Unit> {
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.deleteRemote(remoteId)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.deleteRemote(remoteId)
            SyncMode.ONEDRIVE_GRAPH -> oneDriveRepository.deleteRemoteItem(remoteId)
            SyncMode.LOCAL_ONLY -> Result.success(Unit)
        }
    }
}

data class SyncSummary(
    val uploaded: Int,
    val pendingDownload: Int,
    val cloudOnly: Int,
)
