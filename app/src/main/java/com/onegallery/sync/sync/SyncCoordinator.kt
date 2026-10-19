package com.onegallery.sync.sync

import android.content.Context
import android.net.Uri
import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaLocation
import com.onegallery.sync.data.local.MediaCaptureTimeResolver
import com.onegallery.sync.data.local.MediaLocationResolver
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private enum class SyncCancelReason {
    NONE,
    USER,
    WIFI_POLICY,
}

/** Margem para diferença de relógio entre aparelho, MediaStore e Graph. */
private const val SYNC_TIMESTAMP_TOLERANCE_SEC = 2L

private const val WIFI_POLICY_MESSAGE =
    "Sync pausado: apenas Wi‑Fi. Conecte-se ao Wi‑Fi ou desative essa opção nos Ajustes."

data class SyncUiState(
    val lastIndexedLocalCount: Int = 0,
    val pendingOperations: Int = 0,
    val lastMessage: String = "Pronto para sincronizar.",
    val isRunning: Boolean = false,
    val isOffline: Boolean = false,
    /** Arquivo em transferência agora (upload/download). */
    val activeItem: ActiveSyncItem? = null,
    /** Fila prevista/em andamento nesta execução (uploads reais, não só PENDING_* no Room). */
    val runQueue: List<SyncQueueDisplayRow> = emptyList(),
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
    private val mediaLocationResolver: MediaLocationResolver,
    private val mediaCaptureTimeResolver: MediaCaptureTimeResolver,
    private val networkMonitor: NetworkMonitor,
    private val syncPreferences: SyncPreferences,
    private val mediaDeleteRequestRouter: MediaDeleteRequestRouter,
    private val appForegroundState: AppForegroundState,
    private val backgroundScope: CoroutineScope? = null,
) {
    suspend fun connectOneDriveFolder(treeUri: Uri): Result<Unit> {
        val previousTree = saOneDriveRepository.persistedTreeUriString()
        return saOneDriveRepository.connectFolder(treeUri).onSuccess {
            val currentTree = saOneDriveRepository.persistedTreeUriString()
            if (currentTree != null && previousTree != currentTree) {
                scheduleIndexRebuild()
                applyPendingIndexRebuildIfNeeded()
            }
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

    suspend fun connectGraphSyncFolder(itemId: String, driveRelativePath: String, label: String) {
        if (oneDriveRepository.isDifferentSyncRoot(itemId, driveRelativePath)) {
            scheduleIndexRebuild()
            applyPendingIndexRebuildIfNeeded()
        }
        oneDriveRepository.setSyncRoot(itemId, driveRelativePath, label)
        useGraphMode()
    }

    fun graphSyncRootLabel(): String? = oneDriveRepository.syncRootLabel()

    fun mapLabelForCoordinates(latitude: Double, longitude: Double): String {
        return mediaLocationResolver.labelForCoordinates(latitude, longitude)
    }

    fun useOneDriveLocalMirrorMode() {
        syncPreferences.syncMode = SyncMode.ONEDRIVE_LOCAL_MIRROR
        syncPreferences.hasCompletedSetup = true
    }

    fun localMirrorPathLabel(): String = localMirrorRepository.rootPathLabel()
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val syncCancelRequested = AtomicBoolean(false)
    private val syncCancelReason = AtomicReference(SyncCancelReason.NONE)
    private val activeLongTaskJob = AtomicReference<Job?>(null)
    private val pendingIndexRebuild = AtomicBoolean(false)
    /** Cache da passagem atual de indexação (evita re-varrer MediaStore no backfill). */
    private var indexPassLocalItemsById: Map<Long, LocalMediaItem> = emptyMap()
    private val longTaskMutex = Mutex()
    private val foregroundGuard = SyncForegroundGuard(appContext)

    fun requestCancelSync() {
        cancelActiveSync(SyncCancelReason.USER, applyUserCooldown = true, inProgressMessage = "Cancelando…")
    }

    /** Interrompe sync em andamento quando a política “apenas Wi‑Fi” não permite a rede atual. */
    fun cancelSyncForWifiPolicy() {
        cancelActiveSync(
            SyncCancelReason.WIFI_POLICY,
            applyUserCooldown = false,
            inProgressMessage = WIFI_POLICY_MESSAGE,
        )
    }

    /**
     * Chamado ao alterar o toggle “Sync apenas em Wi‑Fi” nos Ajustes.
     * Se ligar sem Wi‑Fi, para sync ativo; se desligar, o caller deve disparar sync.
     */
    fun onWifiOnlyPreferenceChanged(enabled: Boolean) {
        if (enabled && syncPreferences.wifiOnly && violatesWifiOnlyPolicy()) {
            cancelSyncForWifiPolicy()
        }
    }

    fun violatesWifiOnlyPolicy(): Boolean {
        return syncPreferences.wifiOnly && networkMonitor.isOnline() && !networkMonitor.isUnmetered()
    }

    private fun cancelActiveSync(
        reason: SyncCancelReason,
        applyUserCooldown: Boolean,
        inProgressMessage: String,
    ) {
        syncCancelReason.set(reason)
        syncCancelRequested.set(true)
        if (applyUserCooldown) {
            syncPreferences.pauseAutoSyncAfterUserCancel()
        }
        _state.value = _state.value.copy(lastMessage = inProgressMessage)
        val job = activeLongTaskJob.get()
        if (job != null) {
            job.cancel(CancellationException(inProgressMessage))
        } else if (_state.value.isRunning) {
            _state.value = _state.value.copy(
                isRunning = false,
                activeItem = null,
                runQueue = emptyList(),
                lastMessage = completedMessageForCancelReason(reason),
            )
            resetSyncCancel()
        }
    }

    private fun completedMessageForCancelReason(reason: SyncCancelReason): String {
        return when (reason) {
            SyncCancelReason.WIFI_POLICY -> WIFI_POLICY_MESSAGE
            SyncCancelReason.USER -> autoSyncPausedMessage("Operação interrompida.")
            SyncCancelReason.NONE -> "Operação interrompida."
        }
    }

    private fun cancellationResultMessage(): String {
        return when (syncCancelReason.getAndSet(SyncCancelReason.NONE)) {
            SyncCancelReason.WIFI_POLICY -> WIFI_POLICY_MESSAGE
            SyncCancelReason.USER -> autoSyncPausedMessage("Sincronismo cancelado.")
            SyncCancelReason.NONE -> autoSyncPausedMessage("Sincronismo cancelado.")
        }
    }

    fun autoSyncPausedRemainingMs(): Long = syncPreferences.autoSyncPausedRemainingMs()

    private fun autoSyncPausedMessage(prefix: String): String {
        val minutes = (syncPreferences.autoSyncPausedRemainingMs() / 60_000L).coerceAtLeast(1L)
        return "$prefix Sync automático pausado ~${minutes} min (use Sincronizar agora quando quiser)."
    }

    private fun ensureAutoSyncAllowed(userInitiated: Boolean): Result<Unit>? {
        if (userInitiated || !syncPreferences.isAutoSyncPaused()) return null
        val msg = autoSyncPausedMessage("Sync automático em pausa após cancelamento.")
        _state.value = _state.value.copy(
            isRunning = false,
            activeItem = null,
            runQueue = emptyList(),
            lastMessage = msg,
        )
        return Result.failure(IllegalStateException(msg))
    }

    private suspend fun bindActiveLongTaskJob() {
        val job = coroutineContext[Job] ?: return
        activeLongTaskJob.set(job)
        job.invokeOnCompletion { activeLongTaskJob.compareAndSet(job, null) }
    }

    /**
     * Verifica o índice contra aparelho + pasta na nuvem e remove entradas órfãs.
     * Também aplica recriação pendente após troca de pasta.
     */
    suspend fun runIndexSanitation(userInitiated: Boolean = true): Result<IndexSanitizeResult> {
        ensureAutoSyncAllowed(userInitiated)?.let { denied ->
            return Result.failure(denied.exceptionOrNull() ?: IllegalStateException("Sync pausado"))
        }
        return longTaskMutex.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    resetSyncCancel()
                    bindActiveLongTaskJob()
                    _state.value = _state.value.copy(
                        isRunning = true,
                        lastMessage = "Verificando índice da galeria…",
                        runQueue = emptyList(),
                        activeItem = null,
                    )
                    val rebuilt = applyPendingIndexRebuildIfNeeded()
                    val items = localMediaRepository.loadAllMedia()
                    indexPassLocalItemsById = items.associateBy { it.id }
                    val seenLocalIds = items.map { it.id }.toSet()
                    val seenPaths = applyLocalIndexPass(items, lightMetadataOnly = true)
                    reconcileMissingLocalItems(seenPaths)
                    val remoteListingAvailable: Boolean
                    val remotePaths: Set<String> = when (syncPreferences.syncMode) {
                        SyncMode.LOCAL_ONLY -> {
                            remoteListingAvailable = true
                            emptySet()
                        }
                        else -> if (networkMonitor.isOnline()) {
                            remoteListingAvailable = true
                            listRemoteFiles().getOrThrow().associateBy { it.canonicalPath }.keys
                        } else {
                            remoteListingAvailable = false
                            emptySet()
                        }
                    }
                    val sanitized = runIndexSanitizePass(
                        seenLocalCanonicalPaths = seenPaths,
                        seenLocalMediaIds = seenLocalIds,
                        remoteCanonicalPaths = remotePaths,
                        remoteListingAvailable = remoteListingAvailable,
                    )
                    indexPassLocalItemsById = emptyMap()
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItem = null,
                        lastMessage = buildSanitizeMessage(rebuilt, sanitized),
                    )
                    sanitized.copy(fullRebuild = rebuilt || sanitized.fullRebuild)
                }.onFailure { error ->
                    indexPassLocalItemsById = emptyMap()
                    val cancelled = error is CancellationException || isComposeScopeCancellation(error)
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItem = null,
                        lastMessage = when {
                            cancelled -> cancellationResultMessage()
                            else -> error.message ?: "Falha na manutenção do índice."
                        },
                    )
                    if (cancelled) resetSyncCancel()
                }
            }
        }
    }

    /** Remove mensagens de erro gravadas por item (lista Atenção). Não apaga fotos nem fila de sync. */
    suspend fun clearStoredItemErrors(): Int {
        var cleared = 0
        syncMetadataDao.getAll().forEach { row ->
            if (!shouldShowInAttentionList(row)) return@forEach
            syncMetadataDao.upsert(
                row.copy(
                    cloudState = restoredCloudState(row),
                    lastError = null,
                ),
            )
            cleared++
        }
        _state.value = _state.value.copy(
            lastMessage = if (cleared > 0) {
                "Avisos limpos em $cleared item(ns). Sincronize de novo se ainda faltar envio."
            } else {
                "Nenhum aviso para limpar."
            },
        )
        return cleared
    }

    fun shouldShowAttentionItem(row: SyncMetadataEntity): Boolean = shouldShowInAttentionList(row)

    private fun shouldShowInAttentionList(row: SyncMetadataEntity): Boolean {
        return !row.lastError.isNullOrBlank() ||
            row.cloudState == CloudSyncState.ERROR ||
            row.cloudState == CloudSyncState.CONFLICT
    }

    private fun resetSyncCancel() {
        syncCancelRequested.set(false)
        syncCancelReason.set(SyncCancelReason.NONE)
    }

    private fun ensureNotCancelled() {
        if (syncCancelRequested.get()) {
            throw CancellationException("Sincronismo cancelado.")
        }
    }

    private fun ensureSyncMayContinue() {
        ensureNotCancelled()
        if (violatesWifiOnlyPolicy()) {
            syncCancelReason.compareAndSet(SyncCancelReason.NONE, SyncCancelReason.WIFI_POLICY)
            syncCancelRequested.set(true)
            throw CancellationException(WIFI_POLICY_MESSAGE)
        }
    }

    val galleryMetadata: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observeAll()
    val userAlbums: Flow<List<UserAlbumEntity>> = userAlbumDao.observeAll()
    val pendingDeleteItems: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observePendingDeletes()
    val pendingOperationCount: Flow<Int> = syncMetadataDao.observePendingCount()
    val syncQueue: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observePendingQueue()

    private fun setActiveSyncItem(item: ActiveSyncItem?) {
        _state.value = _state.value.copy(activeItem = item)
    }

    private fun setRunQueueFromEntities(entities: List<SyncMetadataEntity>) {
        _state.value = _state.value.copy(
            runQueue = entities.map { SyncQueueDisplayRow.fromEntity(it) },
        )
    }

    private fun removeFromRunQueue(canonicalPath: String) {
        val next = _state.value.runQueue.filter { it.canonicalPath != canonicalPath }
        if (next.size != _state.value.runQueue.size) {
            _state.value = _state.value.copy(runQueue = next)
        }
    }

    private fun planUploadsForSync(
        localRows: List<SyncMetadataEntity>,
        remoteByPath: Map<String, RemoteDriveFile>,
    ): List<SyncMetadataEntity> {
        val planned = mutableListOf<SyncMetadataEntity>()
        for (row in localRows) {
            if (row.pendingDeleteTarget != PendingDeleteTarget.NONE) continue
            val remote = remoteByPath[row.canonicalPath]
            when {
                remote == null && row.oneDriveItemId != null -> Unit
                remote == null -> planned.add(row)
                else -> {
                    val merged = reconcileWithRemote(row, remote)
                    if (merged.cloudState == CloudSyncState.PENDING_UPLOAD) {
                        planned.add(merged)
                    }
                }
            }
        }
        return planned
    }

    private fun buildSyncRunQueueEntities(
        plannedUploads: List<SyncMetadataEntity>,
        allRows: List<SyncMetadataEntity>,
    ): List<SyncMetadataEntity> {
        val uploadPaths = plannedUploads.map { it.canonicalPath }.toSet()
        val rest = allRows.filter { row ->
            row.canonicalPath !in uploadPaths &&
                (
                    row.pendingDeleteTarget != PendingDeleteTarget.NONE ||
                        row.cloudState == CloudSyncState.PENDING_DOWNLOAD ||
                        row.cloudState == CloudSyncState.PENDING_UPLOAD
                    )
        }
        return plannedUploads + rest
    }

    suspend fun getMetadata(canonicalPath: String): SyncMetadataEntity? {
        return syncMetadataDao.getByCanonicalPath(canonicalPath)
    }

    suspend fun indexLocalLibrary(
        manageRunningState: Boolean = true,
        userInitiated: Boolean = false,
    ): Result<Int> {
        if (manageRunningState) {
            ensureAutoSyncAllowed(userInitiated)?.let { return Result.failure(it.exceptionOrNull()!!) }
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
            bindActiveLongTaskJob()
            _state.value = _state.value.copy(
                isRunning = true,
                lastMessage = "Indexando biblioteca local…",
            )
        }
        return withContext(Dispatchers.Default) {
            runCatching {
            ensureNotCancelled()
            coroutineContext.ensureActive()
            val items = localMediaRepository.loadAllMedia()
            indexPassLocalItemsById = items.associateBy { it.id }
            val seenLocalIds = items.map { it.id }.toSet()
            ensureNotCancelled()
            val seenPaths = applyLocalIndexPass(items, lightMetadataOnly = false)
            ensureNotCancelled()
            reconcileMissingLocalItems(seenPaths)
            if (syncPreferences.syncMode == SyncMode.LOCAL_ONLY) {
                val sanitized = runIndexSanitizePass(
                    seenLocalCanonicalPaths = seenPaths,
                    seenLocalMediaIds = seenLocalIds,
                    remoteCanonicalPaths = emptySet(),
                    remoteListingAvailable = true,
                )
                if (sanitized.removedRows > 0 && manageRunningState) {
                    _state.value = _state.value.copy(
                        lastMessage = "Indexação local: ${items.size} itens. " +
                            "Manutenção: ${sanitized.removedRows} entrada(s) obsoleta(s) removida(s).",
                    )
                }
            }
            if (manageRunningState) {
                _state.value = _state.value.copy(
                    lastIndexedLocalCount = items.size,
                    isRunning = false,
                activeItem = null,
                    lastMessage = "Indexação local: ${items.size} itens. GPS/data extra no sync ou em Sync → indexar GPS.",
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
                activeItem = null,
                    lastMessage = if (cancelled) {
                        autoSyncPausedMessage("Indexação cancelada.")
                    } else {
                        error.message ?: "Falha ao indexar mídia local."
                    },
                )
                if (cancelled) {
                    scheduleThumbnailPrefetchAfterInterrupt()
                }
            } else if (cancelled) {
                throw error
            }
        }.also {
            if (manageRunningState) {
                indexPassLocalItemsById = emptyMap()
            }
        }
        }
    }

    /** Item já refletido no índice — evita upsert em todo sync quando nada mudou no aparelho. */
    private fun isLocalIndexRowUnchanged(
        existing: SyncMetadataEntity?,
        item: LocalMediaItem,
        canonical: String,
    ): Boolean {
        if (existing == null) return false
        if (existing.localMediaId != item.id) return false
        if (existing.canonicalPath != canonical) return false
        if (existing.localModifiedAt != item.dateModifiedSeconds) return false
        if (existing.localContentUri != item.uri.toString()) return false
        if (existing.displayName != item.displayName) return false
        if (existing.pendingDeleteTarget != PendingDeleteTarget.NONE) return false
        if (item.kind == com.onegallery.sync.data.local.MediaKind.VIDEO &&
            existing.videoDurationMs != item.durationMs
        ) {
            return false
        }
        return true
    }

    /** Atualiza SQLite a partir do MediaStore; retorna caminhos canônicos vistos no aparelho. */
    private suspend fun applyLocalIndexPass(
        items: List<LocalMediaItem>,
        lightMetadataOnly: Boolean,
    ): Set<String> {
        val existingRows = syncMetadataDao.getAll()
        val existingByCanonical = existingRows.associateBy { it.canonicalPath }
        val existingByLocalId = existingRows.mapNotNull { row ->
            row.localMediaId?.let { id -> id to row }
        }.toMap()
        val seenPaths = mutableSetOf<String>()
        val pendingUpserts = ArrayList<SyncMetadataEntity>(items.size)
        val pathsToDelete = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            if (index % 64 == 0) {
                ensureNotCancelled()
                coroutineContext.ensureActive()
                yield()
            }
            val canonical = SyncPaths.canonicalFromLocal(item)
            seenPaths.add(canonical)
            val existingByPath = existingByCanonical[canonical]
            val existingByLocal = existingByLocalId[item.id]
            val existing = existingByPath ?: existingByLocal
            if (existingByLocal != null && existingByLocal.canonicalPath != canonical) {
                seenPaths.add(existingByLocal.canonicalPath)
                pathsToDelete.add(existingByLocal.canonicalPath)
            } else if (isLocalIndexRowUnchanged(existing, item, canonical)) {
                return@forEachIndexed
            }
            pendingUpserts.add(
                mergeLocalIntoEntity(
                    item = item,
                    canonical = canonical,
                    existing = existing,
                    lightMetadataOnly = lightMetadataOnly,
                ),
            )
        }
        pendingUpserts.chunked(256).forEach { batch ->
            ensureNotCancelled()
            syncMetadataDao.upsertAll(batch)
        }
        pathsToDelete.forEach { syncMetadataDao.deleteByCanonicalPath(it) }
        return seenPaths
    }

    suspend fun runBidirectionalSync(userInitiated: Boolean = false): Result<SyncSummary> {
        ensureAutoSyncAllowed(userInitiated)?.let { return Result.failure(it.exceptionOrNull()!!) }
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
                oneDriveRepository.ensureGraphSession().getOrElse {
                    _state.value = _state.value.copy(
                        lastMessage = if (com.onegallery.sync.auth.MsalAuthInteraction
                                .needsInteractiveSignIn(it)
                        ) {
                            "Login Microsoft necessário — abrindo tela de entrada… " +
                                "Se não abrir, use Ajustes → Entrar com Microsoft."
                        } else {
                            it.message ?: "Não foi possível renovar a sessão Microsoft."
                        },
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
        if (violatesWifiOnlyPolicy()) {
            _state.value = _state.value.copy(lastMessage = WIFI_POLICY_MESSAGE)
            return Result.failure(IllegalStateException(WIFI_POLICY_MESSAGE))
        }

        return longTaskMutex.withLock {
            foregroundGuard.runWithForeground {
                resetSyncCancel()
                bindActiveLongTaskJob()
                _state.value = _state.value.copy(
                    isRunning = true,
                    runQueue = emptyList(),
                    activeItem = null,
                    lastMessage = "Sincronizando com OneDrive…",
                )
                runCatching {
                    ensureNotCancelled()
                    flushLegacyPendingDeletes()
                    applyPendingIndexRebuildIfNeeded()
                    _state.value = _state.value.copy(
                        lastMessage = "Indexando biblioteca local…",
                    )
                    indexLocalLibrary(manageRunningState = false).getOrThrow()
                    _state.value = _state.value.copy(lastMessage = "Sincronizando com OneDrive…")
                    ensureSyncMayContinue()
                    val remoteFiles = listRemoteFiles().getOrThrow()
                    val remoteByPath = remoteFiles.associateBy { it.canonicalPath }

                    var uploaded = 0
                    var pendingDownloadPlaceholders = 0
                    var cloudOnly = 0

                    val allRows = syncMetadataDao.getAll()
                    val rows = allRows.filter { it.localMediaId != null }
                    val plannedUploads = planUploadsForSync(rows, remoteByPath)
                    setRunQueueFromEntities(buildSyncRunQueueEntities(plannedUploads, allRows))

                    val knownPaths = mutableSetOf<String>()

                    for (row in rows) {
                        ensureSyncMayContinue()
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
                                    CloudSyncState.PENDING_DOWNLOAD ->
                                        pendingDownloadPlaceholders += registerCloudDownloadPlaceholder(merged)
                                    else -> Unit
                                }
                            }
                        }
                    }

                    uploaded += applyPendingUploadRetries(remoteByPath)

                    remoteByPath.forEach { (path, remote) ->
                        ensureSyncMayContinue()
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
                        lastMessage = "Indexando localização GPS (incl. nuvem)…",
                    )
                    backfillMissingGpsLocations()
                    backfillMissingCaptureTimes()
                    indexPassLocalItemsById = emptyMap()
                    prefetchCloudThumbnails()
                    val seenLocalIds = indexPassLocalItemsById.keys
                    val seenLocalPaths = indexPassLocalItemsById.values.map {
                        SyncPaths.canonicalFromLocal(it)
                    }.toSet()
                    val sanitized = runIndexSanitizePass(
                        seenLocalCanonicalPaths = seenLocalPaths,
                        seenLocalMediaIds = seenLocalIds,
                        remoteCanonicalPaths = remoteByPath.keys,
                        remoteListingAvailable = true,
                    )
                    val gpsNote = _state.value.lastMessage.takeIf {
                        it.startsWith("Localização GPS:") || it.startsWith("Data de captura:")
                    }
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItem = null,
                        runQueue = emptyList(),
                        isOffline = false,
                        pendingOperations = pendingCount,
                        lastMessage = buildString {
                            append(
                                "Sync: $uploaded enviados, $pendingDownloadPlaceholders download(s) " +
                                    "só miniatura (nuvem), $cloudOnly só na nuvem$freeMsg.",
                            )
                            if (sanitized.removedRows > 0) {
                                append("\nManutenção: ${sanitized.removedRows} entrada(s) obsoleta(s) removida(s).")
                            }
                            if (gpsNote != null) {
                                append("\n")
                                append(gpsNote)
                            }
                        },
                    )
                    sweepTransientErrorsAfterSuccessfulSync()
                    SyncSummary(uploaded, pendingDownloadPlaceholders, cloudOnly)
                }.onFailure { error ->
                    val cancelled = error is CancellationException
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItem = null,
                        runQueue = emptyList(),
                        lastMessage = when {
                            cancelled -> cancellationResultMessage()
                            else -> error.message ?: "Erro na sincronização."
                        },
                    )
                    if (cancelled) {
                        resetSyncCancel()
                        scheduleThumbnailPrefetchAfterInterrupt()
                    }
                }
            }
        }
    }

    /** Reindexa EXIF/GPS local + trechos na nuvem (mapa de locais). */
    suspend fun refreshPhotoLocations(userInitiated: Boolean = true): Result<Int> {
        ensureAutoSyncAllowed(userInitiated)?.let { denied ->
            return Result.failure(denied.exceptionOrNull() ?: IllegalStateException("Sync pausado"))
        }
        return longTaskMutex.withLock {
            foregroundGuard.runWithForeground {
                resetSyncCancel()
                bindActiveLongTaskJob()
                _state.value = _state.value.copy(
                    isRunning = true,
                    lastMessage = "Indexando localização das fotos…",
                    runQueue = emptyList(),
                    activeItem = null,
                )
                val result = runCatching {
                    ensureNotCancelled()
                    indexLocalLibrary(manageRunningState = false, userInitiated = userInitiated).getOrThrow()
                    backfillMissingGpsLocations()
                    backfillMissingCaptureTimes()
                    indexPassLocalItemsById = emptyMap()
                    syncMetadataDao.getAll().count { it.latitude != null && it.longitude != null }
                }
                result.onSuccess { withGps ->
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItem = null,
                        lastMessage = if (withGps > 0) {
                            "Mapa: $withGps foto(s) com GPS no índice."
                        } else {
                            "Nenhuma foto com GPS encontrada. Conceda “Localização das mídias” " +
                                "e confira se a câmera grava localização."
                        },
                    )
                }.onFailure { error ->
                    val cancelled = error is CancellationException
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItem = null,
                        lastMessage = if (cancelled) {
                            cancellationResultMessage()
                        } else {
                            error.message ?: "Erro ao indexar GPS."
                        },
                    )
                    if (cancelled) resetSyncCancel()
                }
                result
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

        val cloudCopyPending = row.cloudState == CloudSyncState.PENDING_DOWNLOAD
        row.localContentUri?.let {
            if (!cloudCopyPending) {
                return Result.failure(IllegalStateException("Original já está no dispositivo"))
            }
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
        setActiveSyncItem(
            ActiveSyncItem(
                canonicalPath = row.canonicalPath,
                displayName = row.displayName,
                operationLabel = "Baixando da nuvem…",
            ),
        )
        return try {
            downloadRemoteFile(
                remoteId = itemId,
                target = target,
                canonicalPath = row.canonicalPath,
                displayName = row.displayName,
                operationLabel = "Baixando da nuvem…",
            ).map {
                val location = mediaLocationResolver.resolveFromFile(target)
                syncMetadataDao.upsert(
                    row.copy(
                        cachedFilePath = target.absolutePath,
                        cloudState = if (row.localMediaId != null) {
                            CloudSyncState.PENDING_DOWNLOAD
                        } else {
                            CloudSyncState.CLOUD_ONLY
                        },
                        locationLabel = location?.label ?: row.locationLabel,
                        latitude = location?.latitude ?: row.latitude,
                        longitude = location?.longitude ?: row.longitude,
                    ),
                )
                target
            }
        } finally {
            setActiveSyncItem(null)
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

    suspend fun renameUserAlbum(oldTitle: String, newTitle: String): Result<Unit> {
        val from = oldTitle.trim()
        val to = newTitle.trim()
        if (from.isBlank() || to.isBlank()) {
            return Result.failure(IllegalArgumentException("Informe um nome válido."))
        }
        if (from.equals(to, ignoreCase = true)) return Result.success(Unit)
        val entity = userAlbumDao.getByTitle(from)
            ?: return Result.failure(IllegalArgumentException("Só álbuns personalizados podem ser renomeados."))
        if (userAlbumDao.getByTitle(to) != null) {
            return Result.failure(IllegalArgumentException("Já existe um álbum com esse nome."))
        }
        syncMetadataDao.getAll()
            .filter { it.userAlbumName == from }
            .forEach { row -> syncMetadataDao.updateUserAlbum(row.canonicalPath, to) }
        userAlbumDao.upsert(entity.copy(title = to))
        return Result.success(Unit)
    }

    /** Remove álbuns personalizados; as fotos permanecem (voltam à pasta original na galeria). */
    suspend fun deleteUserAlbums(titles: List<String>): Result<Int> {
        if (titles.isEmpty()) return Result.success(0)
        var removed = 0
        titles.distinct().forEach { rawTitle ->
            val title = rawTitle.trim()
            if (title.isBlank()) return@forEach
            val entity = userAlbumDao.getByTitle(title) ?: return@forEach
            syncMetadataDao.getAll()
                .filter { it.userAlbumName == title }
                .forEach { row -> syncMetadataDao.updateUserAlbum(row.canonicalPath, null) }
            userAlbumDao.delete(entity.id)
            removed++
        }
        return Result.success(removed)
    }

    suspend fun releaseLocalSpace(canonicalPaths: List<String>): Result<Int> {
        if (canonicalPaths.isEmpty()) return Result.success(0)
        return runCatching {
            val rows = canonicalPaths.mapNotNull { syncMetadataDao.getByCanonicalPath(it) }
            val withoutCloud = rows.filter { it.oneDriveItemId == null }
            if (withoutCloud.isNotEmpty()) {
                error(
                    "Só é possível liberar espaço de itens já no OneDrive. " +
                        "Sincronize antes ou escolha outro item.",
                )
            }
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
        rows.forEach { row ->
            ensureThumbnailCached(row.canonicalPath).getOrNull()
            markLocalSpaceReleasePending(row)
        }

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
            finalizeLocalSpaceReleased(row)
            freed++
        }
        val handled = uriToRow.values.map { it.canonicalPath }.toSet()
        for (row in rows) {
            if (row.canonicalPath in handled) continue
            if (row.localContentUri != null || row.localMediaId != null) continue
            if (row.cachedFilePath == null) continue
            finalizeLocalSpaceReleased(row)
            freed++
        }
        return freed
    }

    /** Antes de apagar no MediaStore: evita que o sync interprete como exclusão na nuvem. */
    private suspend fun markLocalSpaceReleasePending(row: SyncMetadataEntity) {
        syncMetadataDao.upsert(row.copy(localReleasedForSpace = true))
    }

    private suspend fun finalizeLocalSpaceReleased(row: SyncMetadataEntity) {
        val path = row.canonicalPath
        mediaCacheRepository.deleteOriginalOnly(path)
        ensureThumbnailCached(path).getOrNull()
        syncMetadataDao.upsert(
            row.copy(
                localMediaId = null,
                localContentUri = null,
                cachedFilePath = null,
                pendingDeleteTarget = PendingDeleteTarget.NONE,
                cloudState = CloudSyncState.CLOUD_ONLY,
                localReleasedForSpace = true,
                lastError = null,
            ),
        )
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
        ensureSyncMayContinue()
        setActiveSyncItem(
            ActiveSyncItem(
                canonicalPath = row.canonicalPath,
                displayName = row.displayName,
                operationLabel = "Enviando para OneDrive…",
            ),
        )
        val localId = row.localMediaId ?: return 0.also { setActiveSyncItem(null) }
        val item = localMediaRepository.findById(localId) ?: return 0.also { setActiveSyncItem(null) }
        val temp = localMediaRepository.materializeToTempFile(item) ?: run {
            syncMetadataDao.upsert(
                row.copy(
                    lastError = "Não foi possível ler o arquivo local.",
                    cloudState = CloudSyncState.ERROR,
                ),
            )
            setActiveSyncItem(null)
            return 0
        }
        return runCatching {
            val remoteId = uploadLocalFile(
                canonicalPath = row.canonicalPath,
                sourceFile = temp,
                mimeType = item.mimeType,
                displayName = row.displayName,
                operationLabel = "Enviando para OneDrive…",
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
        }.also {
            temp.delete()
            setActiveSyncItem(null)
            removeFromRunQueue(row.canonicalPath)
        }
    }

    /** Nuvem mais nova: só miniatura no cache — original completo sob demanda na visualização. */
    private suspend fun registerCloudDownloadPlaceholder(row: SyncMetadataEntity): Int {
        ensureThumbnailCached(row.canonicalPath).getOrNull()
        removeFromRunQueue(row.canonicalPath)
        return 1
    }

    private fun reconcileWithRemote(row: SyncMetadataEntity, remote: RemoteDriveFile): SyncMetadataEntity {
        val localTs = row.localModifiedAt ?: 0L
        val remoteTs = remote.modifiedAtEpoch ?: 0L
        val base = row.copy(
            oneDriveItemId = remote.itemId,
            remoteModifiedAt = remote.modifiedAtEpoch,
            lastError = null,
        )
        if (row.localMediaId == null) {
            return base.copy(cloudState = CloudSyncState.CLOUD_ONLY)
        }
        val lastKnownRemote = row.remoteModifiedAt
        val remoteChangedSinceSync = lastKnownRemote != null &&
            remoteTs > lastKnownRemote + SYNC_TIMESTAMP_TOLERANCE_SEC
        val localNewerThanCloud = localTs > remoteTs + SYNC_TIMESTAMP_TOLERANCE_SEC
        val remoteNewerThanLocal = remoteTs > localTs + SYNC_TIMESTAMP_TOLERANCE_SEC
        return when {
            localNewerThanCloud -> base.copy(cloudState = CloudSyncState.PENDING_UPLOAD)
            remoteNewerThanLocal || (remoteChangedSinceSync && !localNewerThanCloud) ->
                base.copy(cloudState = CloudSyncState.PENDING_DOWNLOAD)
            else -> base.copy(cloudState = CloudSyncState.SYNCED)
        }
    }

    /** Reaplica envios pendentes após o merge principal (ex.: falha de rede na mesma execução). */
    private suspend fun applyPendingUploadRetries(
        remoteByPath: Map<String, RemoteDriveFile>,
    ): Int {
        var uploaded = 0
        val pending = syncMetadataDao.getAll().filter { row ->
            row.localMediaId != null &&
                row.pendingDeleteTarget == PendingDeleteTarget.NONE &&
                row.cloudState == CloudSyncState.PENDING_UPLOAD
        }
        for (row in pending) {
            ensureSyncMayContinue()
            val remote = remoteByPath[row.canonicalPath]
            uploaded += if (remote == null) {
                uploadLocalRow(row)
            } else {
                val merged = reconcileWithRemote(row, remote)
                syncMetadataDao.upsert(merged)
                if (merged.cloudState == CloudSyncState.PENDING_UPLOAD) {
                    uploadLocalRow(merged)
                } else {
                    0
                }
            }
        }
        return uploaded
    }

    private suspend fun reconcileMissingLocalItems(seenPaths: Set<String>) {
        syncMetadataDao.getAll().forEachIndexed { index, row ->
            if (index % 32 == 0) ensureNotCancelled()
            if (row.localMediaId == null) return@forEachIndexed
            if (row.canonicalPath in seenPaths) return@forEachIndexed
            if (row.localReleasedForSpace) {
                finalizeLocalSpaceReleased(row)
                return@forEachIndexed
            }
            if (row.oneDriveItemId != null && syncPreferences.autoDeleteRemoteWhenLocalRemoved) {
                row.oneDriveItemId?.let { deleteRemoteFile(it).getOrNull() }
                mediaCacheRepository.deleteCached(row.canonicalPath)
                syncMetadataDao.deleteByCanonicalPath(row.canonicalPath)
            } else if (row.oneDriveItemId != null) {
                ensureThumbnailCached(row.canonicalPath).getOrNull()
                syncMetadataDao.upsert(
                    row.copy(
                        localMediaId = null,
                        localContentUri = null,
                        cachedFilePath = null,
                        pendingDeleteTarget = PendingDeleteTarget.NONE,
                        cloudState = CloudSyncState.CLOUD_ONLY,
                        localReleasedForSpace = true,
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
            ensureSyncMayContinue()
            runCatching {
                deleteItemsImmediately(listOf(row.canonicalPath), row.pendingDeleteTarget)
            }
        }
    }

    private fun cachePlaceholderThumbnail(row: SyncMetadataEntity) {
        val existing = mediaCacheRepository.thumbnailIfCached(row.canonicalPath)
        if (existing != null) return
        val target = mediaCacheRepository.thumbFile(row.canonicalPath)
        localContentUri(row)?.let { uri ->
            val ok = if (GalleryMapper.isVideoFileName(row.displayName)) {
                VideoThumbnailHelper.captureToFile(appContext, uri, target)
            } else {
                cacheImageThumbnailFromUri(uri, target)
            }
            if (ok) return
        }
        row.cachedFilePath?.let { path ->
            val cached = File(path)
            if (!cached.exists()) return@let
            if (GalleryMapper.isVideoFileName(row.displayName)) {
                VideoThumbnailHelper.captureToFile(cached, target)
            } else {
                cacheImageThumbnailFromUri(Uri.fromFile(cached), target)
            }
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
        lightMetadataOnly: Boolean = false,
    ): SyncMetadataEntity {
        val location = if (lightMetadataOnly) {
            resolveLocationForItemLight(item, existing)
        } else {
            resolveLocationForItem(item, existing)
        }
        val capturedAt = if (lightMetadataOnly) {
            resolveCaptureTimeForItemLight(item, existing)
        } else {
            resolveCaptureTimeForItem(item, existing)
        }
        return SyncMetadataEntity(
            canonicalPath = canonical,
            displayName = item.displayName,
            albumName = item.bucketDisplayName.ifBlank { SyncPaths.albumFromCanonical(canonical) },
            userAlbumName = existing?.userAlbumName,
            localMediaId = item.id,
            localContentUri = item.uri.toString(),
            localReleasedForSpace = false,
            oneDriveItemId = existing?.oneDriveItemId,
            localModifiedAt = item.dateModifiedSeconds,
            capturedAtSeconds = capturedAt,
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
            locationLabel = location.first,
            latitude = location.second,
            longitude = location.third,
            videoDurationMs = if (item.kind == com.onegallery.sync.data.local.MediaKind.VIDEO) {
                item.durationMs ?: existing?.videoDurationMs
            } else {
                null
            },
        )
    }

    private fun resolveCaptureTimeForItem(
        item: LocalMediaItem,
        existing: SyncMetadataEntity?,
    ): Long? {
        if (existing?.localMediaId == item.id &&
            existing.capturedAtSeconds != null &&
            existing.capturedAtSeconds > 0L
        ) {
            return existing.capturedAtSeconds
        }
        return mediaCaptureTimeResolver.resolveFromLocalItem(item)
    }

    private fun resolveCaptureTimeForItemLight(
        item: LocalMediaItem,
        existing: SyncMetadataEntity?,
    ): Long? {
        if (existing?.localMediaId == item.id &&
            existing.capturedAtSeconds != null &&
            existing.capturedAtSeconds > 0L
        ) {
            return existing.capturedAtSeconds
        }
        return item.dateTakenSeconds?.takeIf { it > 0L }
    }

    private fun resolveLocationForItem(
        item: LocalMediaItem,
        existing: SyncMetadataEntity?,
    ): Triple<String?, Double?, Double?> {
        if (existing?.localMediaId == item.id &&
            existing.latitude != null &&
            existing.longitude != null
        ) {
            return Triple(
                existing.locationLabel ?: formatCoordLabel(existing.latitude, existing.longitude),
                existing.latitude,
                existing.longitude,
            )
        }
        item.latitude?.let { lat ->
            item.longitude?.let { lon ->
                return Triple(formatCoordLabel(lat, lon), lat, lon)
            }
        }
        val resolved = resolveLocationLocalOnly(
            uri = item.uri,
            cachedFilePath = existing?.cachedFilePath,
        )
        if (resolved != null) {
            return Triple(resolved.label, resolved.latitude, resolved.longitude)
        }
        return Triple(null, null, null)
    }

    /** Manutenção de índice: só MediaStore / índice existente — sem EXIF nem Geocoder. */
    private fun resolveLocationForItemLight(
        item: LocalMediaItem,
        existing: SyncMetadataEntity?,
    ): Triple<String?, Double?, Double?> {
        if (existing?.localMediaId == item.id &&
            existing.latitude != null &&
            existing.longitude != null
        ) {
            return Triple(
                existing.locationLabel ?: formatCoordLabel(existing.latitude, existing.longitude),
                existing.latitude,
                existing.longitude,
            )
        }
        item.latitude?.let { lat ->
            item.longitude?.let { lon ->
                return Triple(formatCoordLabel(lat, lon), lat, lon)
            }
        }
        return Triple(null, null, null)
    }

    private fun resolveLocationLocalOnly(uri: Uri?, cachedFilePath: String?): MediaLocation? {
        uri?.let { mediaLocationResolver.resolveFromUri(it) }?.let { return it }
        cachedFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                mediaLocationResolver.resolveFromFile(file)?.let { return it }
            }
        }
        return null
    }

    private suspend fun resolveLocationFromSources(
        uri: Uri?,
        cachedFilePath: String?,
        oneDriveItemId: String?,
    ): MediaLocation? {
        resolveLocationLocalOnly(uri, cachedFilePath)?.let { return it }
        oneDriveItemId?.let { itemId ->
            probeCloudExifLocation(itemId)?.let { return it }
        }
        return null
    }

    private suspend fun resolveLocationForBackfill(row: SyncMetadataEntity): MediaLocation? {
        resolveLocationFromSources(
            uri = localContentUri(row),
            cachedFilePath = row.cachedFilePath,
            oneDriveItemId = null,
        )?.let { return it }
        val localId = row.localMediaId ?: return null
        val item = indexPassLocalItemsById[localId]
            ?: localMediaRepository.findById(localId)
            ?: return null
        item.latitude?.let { lat ->
            item.longitude?.let { lon ->
                return MediaLocation(lat, lon, formatCoordLabel(lat, lon))
            }
        }
        val temp = localMediaRepository.materializeToTempFile(item) ?: return null
        return runCatching {
            mediaLocationResolver.resolveFromFile(temp)
        }.getOrNull().also { temp.delete() }
    }

    private suspend fun probeCloudExifLocation(oneDriveItemId: String): MediaLocation? {
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return null
        if (!networkMonitor.isOnline()) return null
        return oneDriveRepository.probeExifLocationFromItem(oneDriveItemId, mediaLocationResolver)
            .getOrNull()
    }

    /** Após cancelar sync/index, completa miniaturas pendentes sem refazer upload/download. */
    private fun scheduleThumbnailPrefetchAfterInterrupt() {
        val scope = backgroundScope ?: return
        scope.launch {
            if (!longTaskMutex.tryLock()) return@launch
            try {
                val cached = runCloudThumbnailPrefetch(maxPerRun = 96, honorSyncCancel = false)
                if (cached > 0) {
                    val prefix = _state.value.lastMessage.substringBefore("\n")
                    _state.value = _state.value.copy(
                        lastMessage = "$prefix\nMiniaturas: +$cached após interrupção.",
                    )
                }
            } finally {
                longTaskMutex.unlock()
            }
        }
    }

    /** Miniaturas da nuvem para grade/mapa/álbuns — sem baixar originais. */
    private suspend fun prefetchCloudThumbnails(maxPerRun: Int = 200) {
        runCloudThumbnailPrefetch(maxPerRun = maxPerRun, honorSyncCancel = true)
    }

    private suspend fun runCloudThumbnailPrefetch(
        maxPerRun: Int,
        honorSyncCancel: Boolean,
    ): Int {
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return 0
        if (!networkMonitor.isOnline()) return 0
        val rows = syncMetadataDao.getAll().filter { row ->
            row.oneDriveItemId != null &&
                mediaCacheRepository.thumbnailIfCached(row.canonicalPath) == null
        }.take(maxPerRun)
        if (rows.isEmpty()) return 0
        var cached = 0
        rows.forEachIndexed { index, row ->
            if (honorSyncCancel && index % 12 == 0) {
                ensureNotCancelled()
            }
            coroutineContext.ensureActive()
            ensureThumbnailCached(row.canonicalPath).getOrNull()?.let { cached++ }
        }
        if (cached > 0 && honorSyncCancel) {
            _state.value = _state.value.copy(
                lastMessage = "Miniaturas na nuvem: $cached de ${rows.size} nesta passagem.",
            )
        }
        return cached
    }

    private suspend fun backfillMissingGpsLocations() {
        val rows = syncMetadataDao.getAll().filter { row ->
            row.latitude == null && row.longitude == null &&
                (
                    row.localMediaId != null || row.localContentUri != null ||
                        row.cachedFilePath != null || row.oneDriveItemId != null
                    )
        }
        if (rows.isEmpty()) return
        var cloudProbes = 0
        var localProbes = 0
        val maxCloudProbesPerRun = 200
        val maxLocalProbesPerRun = 400
        var indexed = 0
        rows.sortedByDescending { it.remoteModifiedAt ?: it.localModifiedAt ?: 0L }
            .forEachIndexed { index, row ->
                if (index % 16 == 0) {
                    ensureNotCancelled()
                    coroutineContext.ensureActive()
                }
                val hasLocal = row.localMediaId != null || row.localContentUri != null || row.cachedFilePath != null
                val resolved = when {
                    hasLocal && localProbes < maxLocalProbesPerRun -> {
                        localProbes++
                        resolveLocationForBackfill(row)
                    }
                    hasLocal -> null
                    row.oneDriveItemId != null && cloudProbes < maxCloudProbesPerRun -> {
                        cloudProbes++
                        resolveLocationFromSources(
                            uri = null,
                            cachedFilePath = null,
                            oneDriveItemId = row.oneDriveItemId,
                        )
                    }
                    else -> null
                } ?: return@forEachIndexed
                syncMetadataDao.upsert(
                    row.copy(
                        locationLabel = resolved.label,
                        latitude = resolved.latitude,
                        longitude = resolved.longitude,
                    ),
                )
                indexed++
            }
        if (indexed > 0) {
            val suffix = buildString {
                if (localProbes >= maxLocalProbesPerRun || cloudProbes >= maxCloudProbesPerRun) {
                    append(" (limite nesta passagem; sincronize de novo para o restante)")
                }
            }
            _state.value = _state.value.copy(
                lastMessage = "Localização GPS: $indexed item(ns) indexado(s)$suffix",
            )
        }
    }

    private suspend fun probeCloudExifCaptureTime(oneDriveItemId: String): Long? {
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return null
        if (!networkMonitor.isOnline()) return null
        return oneDriveRepository.probeExifCaptureTimeFromItem(oneDriveItemId, mediaCaptureTimeResolver)
            .getOrNull()
    }

    private suspend fun resolveCaptureTimeFromSources(
        uri: Uri?,
        cachedFilePath: String?,
        oneDriveItemId: String?,
    ): Long? {
        uri?.let { mediaCaptureTimeResolver.resolveFromUri(it) }?.let { return it }
        cachedFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                mediaCaptureTimeResolver.resolveFromFile(file)?.let { return it }
            }
        }
        oneDriveItemId?.let { probeCloudExifCaptureTime(it) }?.let { return it }
        return null
    }

    private suspend fun resolveCaptureTimeForBackfill(row: SyncMetadataEntity): Long? {
        resolveCaptureTimeFromSources(
            uri = localContentUri(row),
            cachedFilePath = row.cachedFilePath,
            oneDriveItemId = null,
        )?.let { return it }
        val localId = row.localMediaId ?: return null
        val item = indexPassLocalItemsById[localId]
            ?: localMediaRepository.findById(localId)
            ?: return null
        return mediaCaptureTimeResolver.resolveFromLocalItem(item)
            ?: run {
                val temp = localMediaRepository.materializeToTempFile(item) ?: return null
                mediaCaptureTimeResolver.resolveFromFile(temp).also { temp.delete() }
            }
    }

    private suspend fun backfillMissingCaptureTimes() {
        val rows = syncMetadataDao.getAll().filter { row ->
            row.capturedAtSeconds == null || row.capturedAtSeconds <= 0L
        }
        if (rows.isEmpty()) return
        var cloudProbes = 0
        val maxCloudProbesPerRun = 200
        var indexed = 0
        rows.sortedByDescending { it.remoteModifiedAt ?: it.localModifiedAt ?: 0L }
            .forEachIndexed { index, row ->
                if (index % 16 == 0) {
                    ensureNotCancelled()
                    coroutineContext.ensureActive()
                }
                val hasLocal = row.localMediaId != null || row.localContentUri != null || row.cachedFilePath != null
                val resolved = when {
                    hasLocal -> resolveCaptureTimeForBackfill(row)
                    row.oneDriveItemId != null && cloudProbes < maxCloudProbesPerRun -> {
                        cloudProbes++
                        resolveCaptureTimeFromSources(
                            uri = null,
                            cachedFilePath = null,
                            oneDriveItemId = row.oneDriveItemId,
                        )
                    }
                    else -> null
                } ?: return@forEachIndexed
                syncMetadataDao.upsert(
                    row.copy(capturedAtSeconds = resolved),
                )
                indexed++
            }
        if (indexed > 0) {
            val suffix = if (cloudProbes >= maxCloudProbesPerRun) {
                " (limite de nuvem nesta passagem; sincronize de novo para o restante)"
            } else {
                ""
            }
            _state.value = _state.value.copy(
                lastMessage = "Data de captura: $indexed item(ns) indexado(s)$suffix",
            )
        }
    }

    private fun scheduleIndexRebuild() {
        pendingIndexRebuild.set(true)
    }

    private suspend fun applyPendingIndexRebuildIfNeeded(): Boolean {
        if (!pendingIndexRebuild.compareAndSet(true, false)) return false
        rebuildSyncMetadataIndex(syncMetadataDao, mediaCacheRepository)
        _state.value = _state.value.copy(
            lastMessage = "Pasta na nuvem alterada — índice recriado. Reindexando…",
        )
        return true
    }

    private suspend fun runIndexSanitizePass(
        seenLocalCanonicalPaths: Set<String>,
        seenLocalMediaIds: Set<Long>,
        remoteCanonicalPaths: Set<String>,
        remoteListingAvailable: Boolean,
    ): IndexSanitizeResult {
        return sanitizeSyncMetadataIndex(
            syncMode = syncPreferences.syncMode,
            dao = syncMetadataDao,
            mediaCacheRepository = mediaCacheRepository,
            seenLocalCanonicalPaths = seenLocalCanonicalPaths,
            seenLocalMediaIds = seenLocalMediaIds,
            remoteCanonicalPaths = remoteCanonicalPaths,
            remoteListingAvailable = remoteListingAvailable,
        )
    }

    private fun buildSanitizeMessage(rebuilt: Boolean, sanitized: IndexSanitizeResult): String {
        return when {
            rebuilt && sanitized.removedRows > 0 ->
                "Índice recriado após troca de pasta. Removidas ${sanitized.removedRows} entrada(s) obsoleta(s)."
            rebuilt -> "Índice recriado após troca de pasta. Sincronize para repopular a galeria."
            sanitized.removedRows > 0 ->
                "Manutenção: ${sanitized.removedRows} entrada(s) obsoleta(s) removida(s) do índice."
            else -> "Índice verificado — nada a limpar."
        }
    }

    private fun formatCoordLabel(lat: Double, lon: Double): String {
        return String.format(Locale.US, "%.2f°", lat) + ", " + String.format(Locale.US, "%.2f°", lon)
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

    private fun isComposeScopeCancellation(error: Throwable): Boolean {
        val msg = error.message?.lowercase().orEmpty()
        return msg.contains("left the composition") || msg.contains("scope left")
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
        displayName: String,
        operationLabel: String,
    ): Result<String> {
        val onProgress: ((Long, Long?) -> Unit)? =
            if (syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
                { done, total ->
                    setActiveSyncItem(
                        ActiveSyncItem(
                            canonicalPath = canonicalPath,
                            displayName = displayName,
                            operationLabel = operationLabel,
                            bytesCompleted = done,
                            totalBytes = total,
                        ),
                    )
                }
            } else {
                null
            }
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.uploadLocalFile(canonicalPath, sourceFile, mimeType)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.uploadLocalFile(canonicalPath, sourceFile, mimeType)
            SyncMode.ONEDRIVE_GRAPH -> oneDriveRepository.uploadLocalFile(
                canonicalPath,
                sourceFile,
                mimeType,
                onProgress,
            ).map { it.id }
            SyncMode.LOCAL_ONLY -> Result.failure(IllegalStateException("Sem nuvem"))
        }
    }

    private suspend fun downloadRemoteFile(
        remoteId: String,
        target: File,
        canonicalPath: String,
        displayName: String,
        operationLabel: String,
    ): Result<Unit> {
        val onProgress: ((Long, Long?) -> Unit)? =
            if (syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
                { done, total ->
                    setActiveSyncItem(
                        ActiveSyncItem(
                            canonicalPath = canonicalPath,
                            displayName = displayName,
                            operationLabel = operationLabel,
                            bytesCompleted = done,
                            totalBytes = total,
                        ),
                    )
                }
            } else {
                null
            }
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.downloadToFile(remoteId, target)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.downloadToFile(remoteId, target)
            SyncMode.ONEDRIVE_GRAPH -> oneDriveRepository.downloadToFile(remoteId, target, onProgress)
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
    val pendingDownloadPlaceholders: Int,
    val cloudOnly: Int,
)
