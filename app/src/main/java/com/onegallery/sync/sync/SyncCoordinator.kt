package com.onegallery.sync.sync

import android.content.Context
import android.net.Uri
import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.ui.gallery.GalleryMapper
import com.onegallery.sync.data.local.MediaDeleteRequestRouter
import com.onegallery.sync.data.local.MediaDeleteResult
import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.local.db.UserAlbumDao
import com.onegallery.sync.data.local.db.UserAlbumEntity
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.data.onedrive.RemoteDriveFile
import com.onegallery.sync.data.onedrive.LocalMirrorRepository
import com.onegallery.sync.data.onedrive.SaOneDriveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class SyncUiState(
    val lastIndexedLocalCount: Int = 0,
    val pendingOperations: Int = 0,
    val lastMessage: String = "Pronto para sincronizar.",
    val isRunning: Boolean = false,
    val isOffline: Boolean = false,
)

class SyncCoordinator(
    private val appContext: Context,
    private val localMediaRepository: LocalMediaRepository,
    private val oneDriveRepository: OneDriveRepository,
    private val saOneDriveRepository: SaOneDriveRepository,
    private val localMirrorRepository: LocalMirrorRepository,
    private val syncMetadataDao: SyncMetadataDao,
    private val userAlbumDao: UserAlbumDao,
    private val mediaCacheRepository: MediaCacheRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncPreferences: SyncPreferences,
    private val mediaDeleteRequestRouter: MediaDeleteRequestRouter,
    private val appForegroundState: AppForegroundState,
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

    private val syncCancelRequested = AtomicBoolean(false)
    private val longTaskMutex = Mutex()
    private val foregroundGuard = SyncForegroundGuard(appContext)

    fun requestCancelSync() {
        syncCancelRequested.set(true)
    }

    /** Remove mensagens de erro gravadas por item (lista Atenção). Não apaga fotos nem fila de sync. */
    suspend fun clearStoredItemErrors(): Int {
        var cleared = 0
        syncMetadataDao.getAll().forEach { row ->
            val flagged = row.cloudState == CloudSyncState.ERROR ||
                row.cloudState == CloudSyncState.CONFLICT ||
                !row.lastError.isNullOrBlank()
            if (!flagged) return@forEach
            val state = when (row.cloudState) {
                CloudSyncState.ERROR, CloudSyncState.CONFLICT ->
                    if (row.pendingDeleteTarget != PendingDeleteTarget.NONE) {
                        row.cloudState
                    } else {
                        restoredCloudState(row)
                    }
                else -> row.cloudState
            }
            syncMetadataDao.upsert(row.copy(cloudState = state, lastError = null))
            cleared++
        }
        if (cleared > 0) {
            _state.value = _state.value.copy(
                lastMessage = "Avisos limpos em $cleared item(ns). Sincronize de novo se ainda faltar envio.",
            )
        }
        return cleared
    }

    private fun resetSyncCancel() {
        syncCancelRequested.set(false)
    }

    private fun ensureNotCancelled() {
        if (syncCancelRequested.get()) {
            throw CancellationException("Sincronismo cancelado.")
        }
    }

    val galleryMetadata: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observeAll()
    val userAlbums: Flow<List<UserAlbumEntity>> = userAlbumDao.observeAll()
    val pendingDeleteItems: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observePendingDeletes()
    val pendingOperationCount: Flow<Int> = syncMetadataDao.observePendingCount()

    suspend fun getMetadata(canonicalPath: String): SyncMetadataEntity? {
        return syncMetadataDao.getByCanonicalPath(canonicalPath)
    }

    suspend fun indexLocalLibrary(manageRunningState: Boolean = true): Result<Int> {
        if (manageRunningState) {
            return longTaskMutex.withLock {
                foregroundGuard.runWithForeground {
                    indexLocalLibraryBody(manageRunningState)
                }
            }
        }
        return indexLocalLibraryBody(manageRunningState)
    }

    private suspend fun indexLocalLibraryBody(manageRunningState: Boolean): Result<Int> {
        if (manageRunningState) {
            resetSyncCancel()
            _state.value = _state.value.copy(isRunning = true, lastMessage = "Indexando mídia local…")
        }
        return runCatching {
            val items = localMediaRepository.loadAllMedia()
            val seenPaths = mutableSetOf<String>()
            items.forEach { item ->
                ensureNotCancelled()
                val canonical = SyncPaths.canonicalFromLocal(item)
                seenPaths.add(canonical)
                val existing = syncMetadataDao.getByCanonicalPath(canonical)
                syncMetadataDao.upsert(mergeLocalIntoEntity(item, canonical, existing))
            }
            reconcileMissingLocalItems(seenPaths)
            if (manageRunningState) {
                _state.value = _state.value.copy(
                    lastIndexedLocalCount = items.size,
                    isRunning = false,
                    lastMessage = "Indexação local: ${items.size} itens (todas as pastas visíveis, ex. WhatsApp).",
                )
            } else {
                _state.value = _state.value.copy(lastIndexedLocalCount = items.size)
            }
            items.size
        }.onFailure { error ->
            val cancelled = error is CancellationException
            if (manageRunningState) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    lastMessage = if (cancelled) {
                        "Indexação cancelada."
                    } else {
                        error.message ?: "Falha ao indexar mídia local."
                    },
                )
            } else if (cancelled) {
                throw error
            }
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

        return longTaskMutex.withLock {
            foregroundGuard.runWithForeground {
                resetSyncCancel()
                _state.value = _state.value.copy(isRunning = true, lastMessage = "Sincronizando com OneDrive…")
                runCatching {
                    flushLegacyPendingDeletes()
                    indexLocalLibrary(manageRunningState = false).getOrThrow()
                    ensureNotCancelled()
                    val remoteFiles = listRemoteFiles().getOrThrow()
                    val remoteByPath = remoteFiles.associateBy { it.canonicalPath }

                    var uploaded = 0
                    var markedPendingDownload = 0
                    var cloudOnly = 0

                    val rows = syncMetadataDao.getAll().filter { it.localMediaId != null }
                    val knownPaths = mutableSetOf<String>()

                    for (row in rows) {
                        ensureNotCancelled()
                        knownPaths.add(row.canonicalPath)
                        if (row.pendingDeleteTarget != PendingDeleteTarget.NONE) continue
                        val remote = remoteByPath[row.canonicalPath]
                        when {
                            remote == null && row.oneDriveItemId != null -> {
                                syncMetadataDao.upsert(
                                    row.copy(
                                        oneDriveItemId = null,
                                        pendingDeleteTarget = PendingDeleteTarget.NONE,
                                        cloudState = CloudSyncState.LOCAL_ONLY,
                                        lastError = null,
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
                        ensureNotCancelled()
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
                    var freedLocal = 0
                    if (syncPreferences.autoFreeLocalSpaceWhenSynced) {
                        freedLocal = autoReleaseSyncedLocalCopies(
                            allowSystemDialog = appForegroundState.isInForeground(),
                        )
                    }
                    val freeMsg = if (freedLocal > 0) ", $freedLocal liberados (placeholder na nuvem)" else ""
                    _state.value = _state.value.copy(
                        isRunning = false,
                        isOffline = false,
                        pendingOperations = pendingCount,
                        lastMessage = "Sync: $uploaded enviados, $markedPendingDownload aguardando download, " +
                            "$cloudOnly só na nuvem$freeMsg.",
                    )
                    sweepTransientErrorsAfterSuccessfulSync()
                    SyncSummary(uploaded, markedPendingDownload, cloudOnly)
                }.onFailure { error ->
                    val cancelled = error is CancellationException
                    _state.value = _state.value.copy(
                        isRunning = false,
                        lastMessage = when {
                            cancelled -> "Sincronismo cancelado."
                            else -> error.message ?: "Erro na sincronização."
                        },
                    )
                }
            }
        }
    }

    suspend fun ensureThumbnailCached(canonicalPath: String): Result<File?> {
        mediaCacheRepository.thumbnailIfCached(canonicalPath)?.let { return Result.success(it) }
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath) ?: return Result.success(null)
        val thumbDest = mediaCacheRepository.thumbFile(canonicalPath)
        val isVideo = GalleryMapper.isVideoFileName(row.displayName)

        localContentUri(row)?.let { uri ->
            val ok = if (isVideo) {
                VideoThumbnailHelper.captureToFile(appContext, uri, thumbDest)
            } else {
                cacheImageThumbnailFromUri(uri, thumbDest)
            }
            if (ok) return Result.success(mediaCacheRepository.thumbnailIfCached(canonicalPath))
        }

        row.cachedFilePath?.let { path ->
            val cached = File(path)
            if (cached.exists() && isVideo && VideoThumbnailHelper.captureToFile(cached, thumbDest)) {
                return Result.success(mediaCacheRepository.thumbnailIfCached(canonicalPath))
            }
        }

        val itemId = row.oneDriveItemId
        if (itemId != null && networkMonitor.isOnline() && syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
            oneDriveRepository.downloadThumbnailToFile(itemId, thumbDest).onSuccess {
                if (thumbDest.length() > 0L) {
                    return Result.success(thumbDest)
                }
            }
        }
        return Result.success(null)
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

    suspend fun resolvePendingDelete(
        canonicalPath: String,
        confirmed: Boolean,
        skipLocalMediaStoreDelete: Boolean = false,
    ): Result<Unit> {
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
            executeDeleteTarget(row, row.pendingDeleteTarget, skipLocalMediaStoreDelete)
        }
    }

    private suspend fun executeDeleteTarget(
        row: SyncMetadataEntity,
        target: PendingDeleteTarget,
        skipLocalMediaStoreDelete: Boolean,
    ) {
        val canonicalPath = row.canonicalPath
        when (target) {
            PendingDeleteTarget.LOCAL -> {
                if (!skipLocalMediaStoreDelete) {
                    deleteLocalMediaStoreCopy(row).getOrThrow()
                }
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
                if (!skipLocalMediaStoreDelete) {
                    deleteLocalMediaStoreCopy(row).getOrThrow()
                }
                mediaCacheRepository.deleteCached(canonicalPath)
                syncMetadataDao.deleteByCanonicalPath(canonicalPath)
            }
            PendingDeleteTarget.NONE -> Unit
        }
    }

    suspend fun requestDelete(canonicalPath: String, target: PendingDeleteTarget): Result<Unit> {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath)
            ?: return Result.failure(IllegalArgumentException("Item não encontrado"))
        syncMetadataDao.upsert(row.copy(pendingDeleteTarget = target))
        return Result.success(Unit)
    }

    suspend fun createUserAlbum(title: String): Result<UserAlbumEntity> {
        val trimmed = title.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Informe um nome para o álbum."))
        }
        val entity = UserAlbumEntity(
            id = java.util.UUID.randomUUID().toString(),
            title = trimmed,
            createdAtEpoch = System.currentTimeMillis() / 1000,
        )
        userAlbumDao.upsert(entity)
        return Result.success(entity)
    }

    suspend fun moveItemsToUserAlbum(canonicalPaths: List<String>, albumTitle: String?): Result<Int> {
        if (canonicalPaths.isEmpty()) return Result.success(0)
        val normalized = albumTitle?.trim()?.takeIf { it.isNotBlank() }
        canonicalPaths.forEach { path ->
            syncMetadataDao.updateUserAlbum(path, normalized)
        }
        return Result.success(canonicalPaths.size)
    }

    suspend fun releaseLocalSpace(canonicalPaths: List<String>): Result<Int> {
        if (canonicalPaths.isEmpty()) return Result.success(0)
        return runCatching {
            val rows = canonicalPaths.mapNotNull { syncMetadataDao.getByCanonicalPath(it) }
            val freed = releaseLocalSpaceForRows(rows, allowSystemDialog = true)
            if (rows.isNotEmpty() && freed == 0) {
                error(
                    "Não foi possível remover a cópia local. " +
                        "Abra o app e confirme no diálogo do Android, se aparecer.",
                )
            }
            freed
        }
    }

    suspend fun runAutoReleaseIfNeeded() {
        if (!syncPreferences.autoFreeLocalSpaceWhenSynced) return
        if (syncPreferences.syncMode == SyncMode.LOCAL_ONLY) return
        autoReleaseSyncedLocalCopies(allowSystemDialog = appForegroundState.isInForeground())
    }

    private suspend fun autoReleaseSyncedLocalCopies(allowSystemDialog: Boolean): Int {
        if (syncPreferences.syncMode == SyncMode.LOCAL_ONLY) return 0
        val rows = syncMetadataDao.getAll().filter { row ->
            row.localMediaId != null &&
                row.oneDriveItemId != null &&
                row.cloudState == CloudSyncState.SYNCED &&
                row.pendingDeleteTarget == PendingDeleteTarget.NONE
        }
        if (rows.isEmpty()) return 0
        return releaseLocalSpaceForRows(rows, allowSystemDialog = allowSystemDialog)
    }

    private suspend fun releaseLocalSpaceForRows(
        rows: List<SyncMetadataEntity>,
        allowSystemDialog: Boolean,
    ): Int {
        if (rows.isEmpty()) return 0
        rows.forEach { cachePlaceholderThumbnail(it) }

        val pairs = rows.mapNotNull { row -> localContentUri(row)?.let { uri -> uri to row } }
        val uriToRow = pairs.associate { it.first to it.second }
        val uris = uriToRow.keys.toList()

        var deleted = mediaDeleteRequestRouter.deleteSilent(appContext, uris)
        var pending = uris.filter { it !in deleted }
        if (pending.isNotEmpty() && allowSystemDialog) {
            when (val outcome = mediaDeleteRequestRouter.requestDelete(appContext, pending)) {
                is MediaDeleteResult.Success, MediaDeleteResult.NothingToDelete -> {
                    deleted = deleted + mediaDeleteRequestRouter.deleteSilent(appContext, pending)
                }
                else -> Unit
            }
        }

        var freed = 0
        for ((uri, row) in uriToRow) {
            if (uri !in deleted) continue
            applyLocalReleasedPlaceholder(row)
            freed++
        }
        return freed
    }

    private suspend fun applyLocalReleasedPlaceholder(row: SyncMetadataEntity) {
        val path = row.canonicalPath
        mediaCacheRepository.deleteOriginalOnly(path)
        if (row.oneDriveItemId != null) {
            syncMetadataDao.upsert(
                row.copy(
                    localMediaId = null,
                    localContentUri = null,
                    cachedFilePath = null,
                    pendingDeleteTarget = PendingDeleteTarget.NONE,
                    cloudState = CloudSyncState.CLOUD_ONLY,
                    lastError = null,
                ),
            )
        } else {
            mediaCacheRepository.deleteCached(path)
            syncMetadataDao.deleteByCanonicalPath(path)
        }
    }

    suspend fun deleteItemsImmediately(
        canonicalPaths: List<String>,
        target: PendingDeleteTarget = PendingDeleteTarget.BOTH,
    ): Result<Int> {
        if (canonicalPaths.isEmpty()) return Result.success(0)
        return runCatching {
            val rows = canonicalPaths.mapNotNull { syncMetadataDao.getByCanonicalPath(it) }
            val needsLocalDelete = target == PendingDeleteTarget.LOCAL || target == PendingDeleteTarget.BOTH

            var localSkipped = false
            if (needsLocalDelete) {
                when (val outcome = deleteLocalMediaForRows(rows)) {
                    MediaDeleteResult.Success, MediaDeleteResult.NothingToDelete -> Unit
                    MediaDeleteResult.UserDenied -> error(
                        "Exclusão cancelada. Toque em Excluir de novo e confirme no diálogo do Android.",
                    )
                    is MediaDeleteResult.Failed -> {
                        if (target == PendingDeleteTarget.BOTH) {
                            localSkipped = true
                        } else {
                            error(
                                "Não foi possível excluir no aparelho. Tente de novo com o app em primeiro plano " +
                                    "e aceite o pedido do Android.",
                            )
                        }
                    }
                }
            }

            var deleted = 0
            for (path in canonicalPaths) {
                val row = syncMetadataDao.getByCanonicalPath(path) ?: continue
                executeDeleteTarget(
                    row,
                    target,
                    skipLocalMediaStoreDelete = needsLocalDelete,
                )
                deleted++
            }
            if (localSkipped) {
                error(
                    "Removido do OneDrive, mas a cópia local ainda está no aparelho. " +
                        "Confirme a exclusão no diálogo do Android ou apague na galeria Samsung.",
                )
            }
            deleted
        }
    }

    private suspend fun deleteLocalMediaForRows(rows: List<SyncMetadataEntity>): MediaDeleteResult {
        val uris = rows.mapNotNull { row -> localContentUri(row) }
        if (uris.isEmpty()) return MediaDeleteResult.NothingToDelete
        return mediaDeleteRequestRouter.requestDelete(appContext, uris)
    }

    private suspend fun deleteLocalMediaStoreCopy(row: SyncMetadataEntity): Result<Unit> {
        return when (deleteLocalMediaForRows(listOf(row))) {
            MediaDeleteResult.Success, MediaDeleteResult.NothingToDelete -> Result.success(Unit)
            MediaDeleteResult.UserDenied -> Result.failure(
                SecurityException("Confirme a exclusão no diálogo do Android."),
            )
            is MediaDeleteResult.Failed -> Result.failure(
                SecurityException("Não foi possível excluir esta mídia no aparelho."),
            )
        }
    }

    private fun localContentUri(row: SyncMetadataEntity): Uri? {
        row.localMediaId?.let { id ->
            localMediaRepository.contentUriForMediaId(id)?.let { return it }
        }
        row.localContentUri?.let { parsed ->
            val uri = Uri.parse(parsed)
            if (mediaDeleteRequestRouter.uriExists(appContext, uri)) return uri
        }
        return null
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
            val synced = row.copy(
                oneDriveItemId = remoteId,
                remoteModifiedAt = System.currentTimeMillis() / 1000,
                cloudState = CloudSyncState.SYNCED,
                lastError = null,
            )
            syncMetadataDao.upsert(synced)
            if (syncPreferences.autoFreeLocalSpaceWhenSynced) {
                releaseLocalSpaceForRows(
                    listOf(synced),
                    allowSystemDialog = appForegroundState.isInForeground(),
                )
            }
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
            if (row.oneDriveItemId != null && syncPreferences.autoDeleteRemoteWhenLocalRemoved) {
                row.oneDriveItemId?.let { deleteRemoteFile(it).getOrNull() }
                mediaCacheRepository.deleteCached(row.canonicalPath)
                syncMetadataDao.deleteByCanonicalPath(row.canonicalPath)
            } else if (row.oneDriveItemId != null) {
                cachePlaceholderThumbnail(row)
                syncMetadataDao.upsert(
                    row.copy(
                        localMediaId = null,
                        localContentUri = null,
                        cachedFilePath = null,
                        pendingDeleteTarget = PendingDeleteTarget.NONE,
                        cloudState = CloudSyncState.CLOUD_ONLY,
                        lastError = null,
                    ),
                )
            } else {
                syncMetadataDao.deleteByCanonicalPath(row.canonicalPath)
            }
        }
    }

    private suspend fun flushLegacyPendingDeletes() {
        val pending = syncMetadataDao.getAll().filter { it.pendingDeleteTarget != PendingDeleteTarget.NONE }
        if (pending.isEmpty()) return
        pending.forEach { row ->
            runCatching {
                deleteItemsImmediately(listOf(row.canonicalPath), row.pendingDeleteTarget)
            }
        }
    }

    private fun cachePlaceholderThumbnail(row: SyncMetadataEntity) {
        val existing = mediaCacheRepository.thumbnailIfCached(row.canonicalPath)
        if (existing != null) return
        val uri = localContentUri(row) ?: return
        val target = mediaCacheRepository.thumbFile(row.canonicalPath)
        if (GalleryMapper.isVideoFileName(row.displayName)) {
            VideoThumbnailHelper.captureToFile(appContext, uri, target)
        } else {
            cacheImageThumbnailFromUri(uri, target)
        }
    }

    private fun cacheImageThumbnailFromUri(uri: Uri, target: File): Boolean {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.length() > 0L
        }.getOrDefault(false)
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
            userAlbumName = existing?.userAlbumName,
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
            lastError = existing?.lastError?.takeUnless { isTransientNetworkMessage(it) },
        )
    }

    private fun restoredCloudState(row: SyncMetadataEntity): CloudSyncState {
        return when {
            row.localMediaId != null && row.oneDriveItemId != null -> CloudSyncState.SYNCED
            row.oneDriveItemId != null -> CloudSyncState.CLOUD_ONLY
            else -> CloudSyncState.LOCAL_ONLY
        }
    }

    private suspend fun sweepTransientErrorsAfterSuccessfulSync() {
        syncMetadataDao.getAll().forEach { row ->
            val msg = row.lastError ?: return@forEach
            if (!isTransientNetworkMessage(msg)) return@forEach
            syncMetadataDao.upsert(
                row.copy(
                    lastError = null,
                    cloudState = if (row.cloudState == CloudSyncState.ERROR) {
                        restoredCloudState(row)
                    } else {
                        row.cloudState
                    },
                ),
            )
        }
    }

    private fun isTransientNetworkMessage(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("unable to resolve host") ||
            lower.contains("graph.microsoft.com") ||
            lower.contains("failed to connect") ||
            lower.contains("network is unreachable") ||
            lower.contains("timeout") ||
            lower.contains("offline") ||
            lower.contains("sem conexão") ||
            lower.contains("wi‑fi") ||
            lower.contains("wifi")
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
