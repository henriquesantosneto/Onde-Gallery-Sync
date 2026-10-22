@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.onegallery.sync.sync

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaLocation
import com.onegallery.sync.data.local.MediaCaptureTimeResolver
import com.onegallery.sync.data.local.MediaLocationResolver
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.ui.gallery.AlbumCoverBucketKeys
import com.onegallery.sync.ui.gallery.AlbumCoverThumbnailPlanner
import com.onegallery.sync.ui.gallery.GalleryMapper
import com.onegallery.sync.data.cloud.CloudSyncBackendRegistry
import com.onegallery.sync.data.cloud.toRemoteCloudFile
import com.onegallery.sync.data.cloud.toRemoteDriveFile
import com.onegallery.sync.data.local.MediaDeleteRequestRouter
import com.onegallery.sync.data.local.MediaDeleteResult
import com.onegallery.sync.data.local.MediaStorageAccess
import com.onegallery.sync.data.local.db.SyncIndexStatsRow
import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.local.db.UserAlbumDao
import com.onegallery.sync.data.local.db.UserAlbumEntity
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.data.onedrive.RemoteDriveFile
import com.onegallery.sync.data.onedrive.LocalMirrorRepository
import com.onegallery.sync.data.onedrive.SaOneDriveRepository
import com.onegallery.sync.worker.BackgroundSyncScheduler
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    val progressPhase: SyncProgressPhase = SyncProgressPhase.IDLE,
    /** Linha principal do que está acontecendo agora (spinner). */
    val activityHeadline: String = "",
    /** Detalhes numéricos / sub-etapa (ex.: 1200/2943 itens). */
    val activityDetail: String = "",
    /** Arquivos em transferência agora (upload/download/miniatura), até 3 em paralelo. */
    val activeItems: List<ActiveSyncItem> = emptyList(),
    /** Fila prevista/em andamento nesta execução (uploads reais, não só PENDING_* no Room). */
    val runQueue: List<SyncQueueDisplayRow> = emptyList(),
    /** Contagem de miniaturas (atualizada após sync / prefetch e ao abrir Sync). */
    val thumbnailStats: ThumbnailCacheStats? = null,
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
    private val cloudSyncBackendRegistry: CloudSyncBackendRegistry,
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
    private val managedSyncJobs = ConcurrentHashMap.newKeySet<Job>()
    private val pendingIndexRebuild = AtomicBoolean(false)
    /** Cache da passagem atual de indexação (evita re-varrer MediaStore no backfill). */
    private var indexPassLocalItemsById: Map<Long, LocalMediaItem> = emptyMap()
    private val longTaskMutex = Mutex()
    private val onDemandOriginalMutexes = ConcurrentHashMap<String, Mutex>()
    private val thumbnailMutexes = ConcurrentHashMap<String, Mutex>()
    private val foregroundGuard = SyncForegroundGuard(appContext)
    private val workerForegroundSession = AtomicBoolean(false)
    private var albumCoverRevisionJob: Job? = null
    private var bulkGalleryDisplayRefreshJob: Job? = null
    private var backgroundThumbPrefetchJob: Job? = null
    private val backgroundThumbPrefetchDeferred = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Tela Sync aberta — prefetch de miniaturas pode rodar em 1º plano (fila visível na UI). */
    private val syncStatusScreenVisibleCount = AtomicInteger(0)
    private val pendingAlbumCoverPaths = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bulkThumbnailPrefetchDepth = AtomicInteger(0)
    private val thumbPrefetchAttemptsThisSession = ConcurrentHashMap<String, Int>()
    private val lastActiveItemProgressPostMs = ConcurrentHashMap<String, Long>()
    /** Caminhos enviados à nuvem nesta execução — só estes entram em “liberar após sync”. */
    private val pathsUploadedThisSync = mutableSetOf<String>()
    private val thumbnailStatsRefreshMutex = Mutex()
    private var lastThumbnailStatsRefreshMs = 0L
    private var thumbnailStatsRefreshScheduled = false

    init {
        mediaCacheRepository.onThumbnailCommitted = onThumbnailCommitted@{ canonicalPath ->
            if (appForegroundState.isInForeground() && !_state.value.isRunning) {
                scheduleAlbumCoverRevisionForPath(canonicalPath)
                scheduleThumbnailStatsRefresh(debounceMs = 60_000L)
                return@onThumbnailCommitted
            }
            if (bulkThumbnailPrefetchDepth.get() == 0) {
                notifyGalleryDisplayRefresh()
            } else {
                scheduleGalleryDisplayRefreshCoalesced()
            }
            scheduleAlbumCoverRevisionForPath(canonicalPath)
            scheduleThumbnailStatsRefresh(debounceMs = 4_000L)
        }
    }

    fun scheduleThumbnailStatsRefresh(debounceMs: Long = 0L) {
        val scope = backgroundScope ?: return
        if (thumbnailStatsRefreshScheduled) return
        thumbnailStatsRefreshScheduled = true
        scope.launch(Dispatchers.Default) {
            if (debounceMs > 0L) delay(debounceMs)
            thumbnailStatsRefreshScheduled = false
            refreshThumbnailCacheStats(force = false)
        }
    }

    suspend fun refreshThumbnailCacheStats(force: Boolean = true) {
        thumbnailStatsRefreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastThumbnailStatsRefreshMs < THUMBNAIL_STATS_MIN_REFRESH_MS) return
            lastThumbnailStatsRefreshMs = now
            val stats = computeThumbnailCacheStats()
            _state.update { it.copy(thumbnailStats = stats) }
        }
    }

    private suspend fun computeThumbnailCacheStats(): ThumbnailCacheStats = withContext(Dispatchers.Default) {
        val rows = syncMetadataDao.getAll()
        var eligible = 0
        var withThumb = 0
        for (row in rows) {
            if (!rowCanBuildThumbnail(row)) continue
            eligible++
            if (mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId) != null) {
                withThumb++
            }
        }
        ThumbnailCacheStats(
            indexTotal = rows.size,
            indexedEligible = eligible,
            indexedWithThumbnail = withThumb,
            indexedPending = (eligible - withThumb).coerceAtLeast(0),
            savedOnDisk = mediaCacheRepository.countThumbFilesOnDisk(),
        )
    }

    /** Durante prefetch em massa: atualiza grade/álbuns em lote (sem ANR). */
    private fun scheduleGalleryDisplayRefreshCoalesced() {
        val scope = backgroundScope
        if (scope == null) {
            notifyGalleryDisplayRefresh()
            return
        }
        bulkGalleryDisplayRefreshJob?.cancel()
        bulkGalleryDisplayRefreshJob = scope.launch {
            delay(GALLERY_DISPLAY_REFRESH_COALESCE_MS)
            notifyGalleryDisplayRefresh()
        }
    }

    private fun setSyncProgress(
        phase: SyncProgressPhase,
        headline: String,
        detail: String = "",
        alsoUpdateLastMessage: Boolean = true,
    ) {
        _state.value = _state.value.copy(
            progressPhase = phase,
            activityHeadline = headline,
            activityDetail = detail,
            lastMessage = if (alsoUpdateLastMessage && detail.isNotBlank()) {
                "$headline\n$detail"
            } else if (alsoUpdateLastMessage) {
                headline
            } else {
                _state.value.lastMessage
            },
        )
    }

    fun requestCancelSync() {
        cancelActiveSync(
            SyncCancelReason.USER,
            applyUserCooldown = true,
            inProgressMessage = "Parando sincronismo…",
        )
    }

    /** Jobs de sync/index lançados fora do mutex (fila, galeria, WorkManager). */
    fun registerManagedSyncJob(job: Job) {
        managedSyncJobs.add(job)
        job.invokeOnCompletion { managedSyncJobs.remove(job) }
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
        BackgroundSyncScheduler.cancelPendingSyncWork(appContext)
        _state.value = _state.value.copy(
            isRunning = false,
            progressPhase = SyncProgressPhase.IDLE,
            lastMessage = inProgressMessage,
            activityHeadline = inProgressMessage,
            activityDetail = "",
            runQueue = emptyList(),
            activeItems = emptyList(),
        )
        cancelAllManagedSyncJobs(inProgressMessage)
        bulkGalleryDisplayRefreshJob?.cancel()
        val job = activeLongTaskJob.get()
        if (job != null) {
            job.cancel(CancellationException(inProgressMessage))
        }
    }

    private fun cancelAllManagedSyncJobs(message: String) {
        val pending = managedSyncJobs.toList()
        managedSyncJobs.clear()
        val cancelError = CancellationException(message)
        pending.forEach { job ->
            if (job.isActive) {
                job.cancel(cancelError)
            }
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
            activeItems = emptyList(),
            runQueue = emptyList(),
            lastMessage = msg,
        )
        return Result.failure(IllegalStateException(msg))
    }

    private suspend fun bindActiveLongTaskJob() {
        val job = coroutineContext[Job] ?: return
        activeLongTaskJob.set(job)
        registerManagedSyncJob(job)
        job.invokeOnCompletion { activeLongTaskJob.compareAndSet(job, null) }
    }

    private fun prepareSyncSession(userInitiated: Boolean) {
        if (userInitiated) {
            resetSyncCancel()
        } else {
            ensureNotCancelled()
        }
        syncPreferences.extendMediaLibraryWatchSuppression(
            SyncPreferences.MEDIA_LIBRARY_SUPPRESS_DURING_SYNC_MS,
        )
        thumbPrefetchAttemptsThisSession.clear()
        pathsUploadedThisSync.clear()
    }

    suspend fun refreshPendingTransferCache() {
        val pending = syncMetadataDao.countPendingUploadOperations() +
            syncMetadataDao.countPendingDownloadOperations()
        syncPreferences.cachedPendingTransferCount = pending
    }

    /** Sync automático (WorkManager / rede / MediaStore) sem pendências urgentes. */
    suspend fun shouldDeferAutomaticBidirectionalSync(): Boolean {
        if (syncPreferences.isAutoSyncPaused()) return true
        if (_state.value.isRunning) return true
        refreshPendingTransferCache()
        return syncPreferences.millisUntilBackgroundSyncAllowed() > 0L
    }

    private fun isUserCancellation(): Boolean =
        syncCancelReason.get() == SyncCancelReason.USER

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
                try {
                    prepareSyncSession(userInitiated)
                } catch (cancelled: CancellationException) {
                    return@withContext Result.failure(cancelled)
                }
                runCatching {
                    bindActiveLongTaskJob()
                    _state.value = _state.value.copy(
                        isRunning = true,
                        lastMessage = "Verificando índice da galeria…",
                        runQueue = emptyList(),
                        activeItems = emptyList(),
                    )
                    val rebuilt = applyPendingIndexRebuildIfNeeded()
                    val items = localMediaRepository.loadAllMedia()
                    indexPassLocalItemsById = items.associateBy { it.id }
                    val seenLocalIds = items.map { it.id }.toSet()
                    val seenPaths = applyLocalIndexPass(items, lightMetadataOnly = true)
                    reconcileMissingLocalItems(seenPaths)
                    healAllStaleLocalCopyMetadata()
                    val remoteListingAvailable: Boolean
                    val remotePaths: Set<String> = when (syncPreferences.syncMode) {
                        SyncMode.LOCAL_ONLY -> {
                            remoteListingAvailable = true
                            emptySet()
                        }
                        else -> if (networkMonitor.isOnline()) {
                            remoteListingAvailable = true
                            listRemoteFiles().getOrThrow().files.map { it.canonicalPath }.toSet()
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
                        activeItems = emptyList(),
                        lastMessage = buildSanitizeMessage(rebuilt, sanitized),
                    )
                    sanitized.copy(fullRebuild = rebuilt || sanitized.fullRebuild)
                }.onFailure { error ->
                    indexPassLocalItemsById = emptyMap()
                    val cancelled = error is CancellationException || isComposeScopeCancellation(error)
                    val userCancelled = cancelled && isUserCancellation()
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItems = emptyList(),
                        lastMessage = when {
                            cancelled -> cancellationResultMessage()
                            else -> error.message ?: "Falha na manutenção do índice."
                        },
                    )
                    if (cancelled && !userCancelled) {
                        resetSyncCancel()
                    }
                }
            }
        }
    }

    /**
     * Apaga só o índice local (SQLite + cache de miniaturas/originais do app).
     * Não remove fotos/vídeos da galeria Samsung nem arquivos no OneDrive.
     * O próximo sync reconstrói a partir do aparelho e da pasta na nuvem.
     */
    suspend fun resetSyncIndexForFreshStart(userInitiated: Boolean = true): Result<Unit> {
        if (_state.value.isRunning) {
            return Result.failure(IllegalStateException("Aguarde o sync atual terminar."))
        }
        ensureAutoSyncAllowed(userInitiated)?.let { denied ->
            return Result.failure(denied.exceptionOrNull() ?: IllegalStateException("Sync pausado"))
        }
        return longTaskMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    pendingIndexRebuild.set(false)
                    rebuildSyncMetadataIndex(syncMetadataDao, mediaCacheRepository)
                    syncPreferences.resetIndexMaintenanceState()
                    if (syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
                        oneDriveRepository.resetRemoteListingState()
                    }
                    indexPassLocalItemsById = emptyMap()
                    notifyGalleryDisplayRefresh()
                    _state.value = _state.value.copy(
                        isRunning = false,
                        runQueue = emptyList(),
                        activeItems = emptyList(),
                        lastMessage = "Índice zerado. Sincronize para reconstruir a partir do aparelho e da nuvem.",
                    )
                }.onFailure { error ->
                    _state.value = _state.value.copy(
                        lastMessage = error.message ?: "Não foi possível zerar o índice.",
                    )
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

    private fun propagateCancellation(error: Throwable): Nothing? {
        if (error is CancellationException) throw error
        var cause = error.cause
        while (cause != null) {
            if (cause is CancellationException) throw cause
            cause = cause.cause
        }
        return null
    }

    private fun ensureNotCancelled() {
        if (syncCancelRequested.get()) {
            throw CancellationException("Sincronismo cancelado.")
        }
    }

    /** Só interrompe transferências do job de sync longo — recall/visualização não é afetado. */
    private fun shouldCancelActiveSyncTransfer(job: Job?): Boolean {
        if (job == null || !syncCancelRequested.get()) return false
        return activeLongTaskJob.get() == job
    }

    private fun ensureSyncMayContinue() {
        ensureNotCancelled()
        if (violatesWifiOnlyPolicy()) {
            syncCancelReason.compareAndSet(SyncCancelReason.NONE, SyncCancelReason.WIFI_POLICY)
            syncCancelRequested.set(true)
            throw CancellationException(WIFI_POLICY_MESSAGE)
        }
    }

    /** WorkManager já promoveu [androidx.work.CoroutineWorker.setForeground] — evita 2º serviço (falha em background). */
    fun setWorkerForegroundSession(active: Boolean) {
        workerForegroundSession.set(active)
    }

    private suspend inline fun <T> withSyncForeground(crossinline block: suspend () -> T): T {
        if (workerForegroundSession.get()) {
            return block()
        }
        return foregroundGuard.runWithForeground { block() }
    }

    private val galleryMetadataSource = syncMetadataDao.observeGalleryIndex()
    val galleryMetadata: Flow<List<SyncMetadataEntity>> = merge(
        galleryMetadataSource.take(1),
        galleryMetadataSource.drop(1).debounce(GALLERY_METADATA_DEBOUNCE_MS),
    )
    val syncIndexStats: Flow<SyncIndexStatsRow> = syncMetadataDao.observeIndexStats()
    val syncAttentionItems: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observeAttentionItems()
    val userAlbums: Flow<List<UserAlbumEntity>> = userAlbumDao.observeAll()

    private val _galleryDisplayRefreshEpoch = MutableStateFlow(0L)
    val galleryDisplayRefreshEpoch: StateFlow<Long> = _galleryDisplayRefreshEpoch.asStateFlow()

    private val _albumCoverRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val albumCoverRevisions: StateFlow<Map<String, Long>> = _albumCoverRevisions.asStateFlow()

    fun notifyGalleryDisplayRefresh() {
        _galleryDisplayRefreshEpoch.value = System.nanoTime()
    }

    fun setSyncStatusScreenVisible(visible: Boolean) {
        if (visible) {
            syncStatusScreenVisibleCount.incrementAndGet()
            kickThumbnailPrefetchIfIdle()
        } else {
            syncStatusScreenVisibleCount.updateAndGet { current ->
                (current - 1).coerceAtLeast(0)
            }
            if (appForegroundState.isInForeground() && !allowForegroundThumbnailPrefetch()) {
                backgroundThumbPrefetchJob?.cancel()
                backgroundThumbPrefetchJob = null
            }
        }
    }

    /** Retoma miniaturas pendentes (ex.: ao abrir a tela Sync com milhares no índice). */
    fun kickThumbnailPrefetchIfIdle() {
        if (_state.value.isRunning) return
        if (backgroundThumbPrefetchJob?.isActive == true) return
        val scope = backgroundScope ?: return
        scope.launch(Dispatchers.IO) {
            if (countIndexedMissingThumbnails() == 0) return@launch
            scheduleBackgroundThumbnailPrefetchIfNeeded()
        }
    }

    private fun allowForegroundThumbnailPrefetch(): Boolean =
        syncStatusScreenVisibleCount.get() > 0

    /**
     * Prefetch pós-sync de miniaturas compete com galeria/álbuns em 1º plano — adia até ir para 2º plano,
     * exceto enquanto a tela Sync estiver aberta.
     */
    fun onAppUiVisibilityChanged(inForeground: Boolean) {
        if (inForeground) {
            if (!allowForegroundThumbnailPrefetch()) {
                backgroundThumbPrefetchJob?.cancel()
                backgroundThumbPrefetchJob = null
                val scope = backgroundScope ?: return
                scope.launch(Dispatchers.IO) {
                    if (countIndexedMissingThumbnails() > 0) {
                        backgroundThumbPrefetchDeferred.set(true)
                    }
                }
            } else {
                kickThumbnailPrefetchIfIdle()
            }
        } else {
            resumeBackgroundThumbnailPrefetchIfNeeded()
        }
    }

    private fun resumeBackgroundThumbnailPrefetchIfNeeded() {
        if (appForegroundState.isInForeground() && !allowForegroundThumbnailPrefetch()) return
        if (_state.value.isRunning) return
        scheduleBackgroundThumbnailPrefetchIfNeeded()
    }

    /**
     * Preenche o Room a partir do MediaStore sem sync na nuvem — mesma base do início do sync,
     * para a galeria montar spill/sessão antes do usuário tocar em Sincronizar.
     */
    fun warmLocalIndexIfEmpty() {
        val scope = backgroundScope ?: return
        scope.launch(Dispatchers.IO) {
            if (!syncPreferences.hasCompletedSetup) return@launch
            if (_state.value.isRunning) return@launch
            if (syncMetadataDao.countAll() > 0) return@launch
            if (!longTaskMutex.tryLock()) return@launch
            try {
                indexLocalLibrary(manageRunningState = false, userInitiated = false)
            } finally {
                longTaskMutex.unlock()
            }
        }
    }

    /** Estilo galeria: só os álbuns afetados, após miniatura local gravada (lote curto). */
    private fun scheduleAlbumCoverRevisionForPath(canonicalPath: String) {
        pendingAlbumCoverPaths.add(canonicalPath)
        val scope = backgroundScope ?: return
        albumCoverRevisionJob?.cancel()
        albumCoverRevisionJob = scope.launch(Dispatchers.IO) {
            delay(ALBUM_COVER_REVISION_COALESCE_MS)
            val paths = pendingAlbumCoverPaths.toList()
            pendingAlbumCoverPaths.removeAll(paths.toSet())
            bumpAlbumCoverRevisionsForPaths(paths)
        }
    }

    /** Índice ganhou item novo ou ordem mudou — capas dos álbuns afetados. */
    fun bumpAlbumCoverRevisionsForBucketIds(bucketIds: Collection<String>) {
        if (bucketIds.isEmpty()) return
        val stamp = System.nanoTime()
        _albumCoverRevisions.update { current ->
            val next = current.toMutableMap()
            bucketIds.forEach { next[it] = stamp }
            next
        }
    }

    private suspend fun bumpAlbumCoverRevisionsForPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        val keys = linkedSetOf<String>()
        paths.forEach { path ->
            val row = syncMetadataDao.getByCanonicalPath(path) ?: return@forEach
            keys.addAll(AlbumCoverBucketKeys.forEntity(row))
        }
        if (keys.isEmpty()) return
        val stamp = System.nanoTime()
        _albumCoverRevisions.update { current ->
            val next = current.toMutableMap()
            keys.forEach { next[it] = stamp }
            next
        }
    }

    private suspend fun flushPendingAlbumCoverRevisions() {
        albumCoverRevisionJob?.cancel()
        albumCoverRevisionJob = null
        val paths = pendingAlbumCoverPaths.toList()
        pendingAlbumCoverPaths.clear()
        bumpAlbumCoverRevisionsForPaths(paths)
    }
    val pendingDeleteItems: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observePendingDeletes()
    val pendingOperationCount: Flow<Int> = syncMetadataDao.observePendingCount()
    val syncQueue: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observePendingQueue()

    private val activeSyncItemsLock = Any()
    private val activeSyncItemsBacking = ArrayList<ActiveSyncItem>()
    private val publishActiveItemsRunnable = Runnable {
        val snapshot = synchronized(activeSyncItemsLock) {
            activeSyncItemsBacking.toList()
        }
        _state.value = _state.value.copy(activeItems = snapshot)
    }

    private fun scheduleActiveItemsPublish() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            publishActiveItemsRunnable.run()
        } else {
            mainHandler.removeCallbacks(publishActiveItemsRunnable)
            mainHandler.post(publishActiveItemsRunnable)
        }
    }

    private fun upsertActiveSyncItem(item: ActiveSyncItem, force: Boolean = false) {
        synchronized(activeSyncItemsLock) {
            val index = activeSyncItemsBacking.indexOfFirst { it.canonicalPath == item.canonicalPath }
            if (index >= 0) {
                activeSyncItemsBacking[index] = item
            } else {
                activeSyncItemsBacking.add(item)
            }
            if (!force) {
                val now = System.currentTimeMillis()
                val last = lastActiveItemProgressPostMs[item.canonicalPath] ?: 0L
                if (item.bytesCompleted > 0L && now - last < ACTIVE_ITEM_PROGRESS_MIN_INTERVAL_MS) {
                    return
                }
                lastActiveItemProgressPostMs[item.canonicalPath] = now
            }
            scheduleActiveItemsPublish()
        }
    }

    private fun removeActiveSyncItem(canonicalPath: String) {
        lastActiveItemProgressPostMs.remove(canonicalPath)
        synchronized(activeSyncItemsLock) {
            val removed = activeSyncItemsBacking.removeAll { it.canonicalPath == canonicalPath }
            if (removed) {
                scheduleActiveItemsPublish()
            }
        }
    }

    private fun clearActiveSyncItems() {
        synchronized(activeSyncItemsLock) {
            if (activeSyncItemsBacking.isNotEmpty()) {
                activeSyncItemsBacking.clear()
                scheduleActiveItemsPublish()
            }
        }
    }

    private fun setRunQueueFromEntities(
        entities: List<SyncMetadataEntity>,
        thumbnailQueue: Boolean = false,
    ) {
        val rows = entities.map { entity ->
            if (thumbnailQueue) {
                SyncQueueDisplayRow(
                    canonicalPath = entity.canonicalPath,
                    displayName = entity.displayName,
                    statusLabel = thumbnailOperationLabel(entity),
                )
            } else {
                SyncQueueDisplayRow.fromEntity(entity)
            }
        }
        val apply = { _state.value = _state.value.copy(runQueue = rows) }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post(apply)
    }

    private fun removeFromRunQueue(canonicalPath: String) {
        val next = _state.value.runQueue.filter { it.canonicalPath != canonicalPath }
        if (next.size != _state.value.runQueue.size) {
            val apply = { _state.value = _state.value.copy(runQueue = next) }
            if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post(apply)
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
                        row.cloudState == CloudSyncState.PENDING_UPLOAD
                    )
        }
        return plannedUploads.sortedWith(syncQueueEntityComparator) +
            rest.sortedWith(syncQueueEntityComparator)
    }

    suspend fun getMetadata(canonicalPath: String): SyncMetadataEntity? {
        return syncMetadataDao.getByCanonicalPath(canonicalPath)
    }

    /** Remove linhas duplicadas no SQLite (mesmo id local, nuvem ou content URI). */
    suspend fun compactDuplicateIndexRows(): Int =
        removeDuplicateMetadataRows(syncMetadataDao, mediaCacheRepository)

    suspend fun indexLocalLibrary(
        manageRunningState: Boolean = true,
        userInitiated: Boolean = false,
    ): Result<Int> {
        if (manageRunningState) {
            ensureAutoSyncAllowed(userInitiated)?.let { return Result.failure(it.exceptionOrNull()!!) }
            return longTaskMutex.withLock {
                withSyncForeground {
                    try {
                        prepareSyncSession(userInitiated)
                    } catch (cancelled: CancellationException) {
                        return@withSyncForeground Result.failure(cancelled)
                    }
                    indexLocalLibraryBody(manageRunningState)
                }
            }
        }
        return indexLocalLibraryBody(manageRunningState)
    }

    private suspend fun indexLocalLibraryBody(manageRunningState: Boolean): Result<Int> {
        if (manageRunningState) {
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
            healAllStaleLocalCopyMetadata()
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
                activeItems = emptyList(),
                    lastMessage = "Indexação local: ${items.size} itens. GPS/data extra no sync ou em Sync → indexar GPS.",
                )
            } else {
                _state.value = _state.value.copy(lastIndexedLocalCount = items.size)
            }
            items.size
        }.onFailure { error ->
            val cancelled = error is CancellationException
            val userCancelled = cancelled && syncCancelReason.get() == SyncCancelReason.USER
            if (manageRunningState) {
                _state.value = _state.value.copy(
                    isRunning = false,
                activeItems = emptyList(),
                    lastMessage = if (cancelled) {
                        autoSyncPausedMessage("Indexação cancelada.")
                    } else {
                        error.message ?: "Falha ao indexar mídia local."
                    },
                )
                if (cancelled && !userCancelled) {
                    resetSyncCancel()
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
        if (existing.localSizeBytes != item.sizeBytes) return false
        if (existing.localContentUri != item.uri.toString()) return false
        if (existing.displayName != item.displayName) return false
        if (existing.pendingDeleteTarget != PendingDeleteTarget.NONE) return false
        if (item.kind == com.onegallery.sync.data.local.MediaKind.VIDEO &&
            existing.videoDurationMs != item.durationMs
        ) {
            return false
        }
        if (existing.latitude == null || existing.longitude == null) return false
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
            if (_state.value.isRunning && (index == 0 || index % 400 == 0)) {
                setSyncProgress(
                    SyncProgressPhase.INDEXING_LOCAL,
                    "Indexando biblioteca do aparelho…",
                    "${index.coerceAtLeast(1)}/${items.size} arquivos · " +
                        "${pendingUpserts.size} alteração(ões) pendentes no índice.",
                    alsoUpdateLastMessage = false,
                )
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
        if (!userInitiated && shouldDeferAutomaticBidirectionalSync()) {
            BackgroundSyncScheduler.enqueueDeferredAutoSync(appContext, syncPreferences)
            return Result.success(SyncSummary(0, 0, 0))
        }
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
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> {
                localMirrorRepository.ensureMirrorRootReady().getOrElse {
                    _state.value = _state.value.copy(
                        lastMessage = it.message ?: "Não foi possível criar Pictures/OneGallerySync.",
                    )
                    return Result.failure(it)
                }
            }
            SyncMode.ONEDRIVE_GRAPH -> {
                if (!oneDriveRepository.isSyncRootConfigured()) {
                    _state.value = _state.value.copy(
                        lastMessage = "Escolha uma pasta no OneDrive (explorador na nuvem) em Ajustes.",
                    )
                    return Result.failure(IllegalStateException("Pasta na nuvem não escolhida"))
                }
                ensureGraphSessionOnMain().getOrElse {
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
            withSyncForeground {
                try {
                    prepareSyncSession(userInitiated)
                } catch (cancelled: CancellationException) {
                    return@withSyncForeground Result.failure(cancelled)
                }
                bindActiveLongTaskJob()
                _state.value = _state.value.copy(
                    isRunning = true,
                    runQueue = emptyList(),
                    activeItems = emptyList(),
                    lastMessage = "Sincronizando com OneDrive…",
                    progressPhase = SyncProgressPhase.PREPARING,
                    activityHeadline = "Preparando sincronismo…",
                    activityDetail = "Conta Microsoft, pendências e índice local.",
                )
                runCatching {
                    ensureNotCancelled()
                    setSyncProgress(
                        SyncProgressPhase.PREPARING,
                        "Verificando pendências antigas…",
                        "Exclusões legadas e rebuild de índice, se necessário.",
                    )
                    flushLegacyPendingDeletes()
                    clearLegacyPendingDownloadStates()
                    applyPendingIndexRebuildIfNeeded()
                    setSyncProgress(
                        SyncProgressPhase.INDEXING_LOCAL,
                        "Indexando biblioteca do aparelho…",
                        "Lendo MediaStore (fotos, vídeos, pastas).",
                    )
                    val localCount = indexLocalLibrary(manageRunningState = false).getOrThrow()
                    setSyncProgress(
                        SyncProgressPhase.INDEXING_LOCAL,
                        "Indexação local concluída",
                        "$localCount item(ns) vistos no aparelho nesta passagem.",
                    )
                    ensureSyncMayContinue()
                    setSyncProgress(
                        SyncProgressPhase.LISTING_REMOTE,
                        "Listando pasta no OneDrive…",
                        oneDriveRepository.syncRootLabel()?.let { "Pasta: $it" }
                            ?: "Aguarde — pode demorar com bibliotecas grandes.",
                    )
                    ensureNotCancelled()
                    val remoteListing = listRemoteFiles().getOrThrow()
                    ensureNotCancelled()
                    val remoteFiles = remoteListing.files
                    val remoteByPath = remoteFiles.associateBy { it.canonicalPath }
                    val remoteByContentKey = buildRemoteContentIndex(remoteFiles)
                    setSyncProgress(
                        SyncProgressPhase.LISTING_REMOTE,
                        if (remoteListing.incremental) "Nuvem atualizada (delta)" else "Listagem na nuvem concluída",
                        buildString {
                            if (remoteListing.incremental) {
                                append("${remoteListing.changedCount} alteração(ões) · ")
                            }
                            append("${remoteFiles.size} arquivo(s) na pasta de sync.")
                            if (remoteListing.supplementalFromRecursive > 0) {
                                append(
                                    " (+${remoteListing.supplementalFromRecursive} via varredura completa " +
                                        "que o delta não listou)",
                                )
                            }
                            if (remoteListing.unresolvedDeltaSkips > 0) {
                                append(" · ${remoteListing.unresolvedDeltaSkips} ignorado(s) no delta (sem caminho)")
                            }
                        },
                    )

                    var uploaded = 0
                    var pendingDownloadPlaceholders = 0
                    var cloudOnly = 0

                    val allRows = syncMetadataDao.getAll()
                    val existingByPath = allRows.associateBy { it.canonicalPath }
                    val rows = syncMetadataDao.getRowsWithLocalCopy()
                    setSyncProgress(
                        SyncProgressPhase.RECONCILING_LOCAL,
                        "Montando fila de sincronismo…",
                        "${allRows.size} no índice · ${rows.size} com cópia local · " +
                            "${remoteFiles.size} na nuvem.",
                    )
                    val plannedUploads = planUploadsForSync(rows, remoteByPath)
                    val queueEntities = buildSyncRunQueueEntities(plannedUploads, allRows)
                    setRunQueueFromEntities(queueEntities)
                    val queuePathOrder = queueEntities.mapIndexed { index, entity ->
                        entity.canonicalPath to index
                    }.toMap()
                    val rowsProcessingOrder = rows.sortedWith(
                        compareBy(
                            { queuePathOrder[it.canonicalPath] ?: Int.MAX_VALUE },
                            { it.canonicalPath },
                        ),
                    )
                    setSyncProgress(
                        SyncProgressPhase.RECONCILING_LOCAL,
                        "Fila pronta",
                        "${queueEntities.size} operação(ões) nesta execução " +
                            "(${plannedUploads.size} envio(s) planejado(s)).",
                    )

                    val knownPaths = mutableSetOf<String>()
                    var localPassIndex = 0
                    val localPassTotal = rows.size
                    val uploadQueue = mutableListOf<SyncMetadataEntity>()
                    val upsertBatch = mutableListOf<SyncMetadataEntity>()
                    suspend fun flushReconcileUpserts() {
                        if (upsertBatch.isEmpty()) return
                        upsertBatch.chunked(ROOM_UPSERT_CHUNK).forEach { chunk ->
                            syncMetadataDao.upsertAll(chunk)
                        }
                        upsertBatch.clear()
                    }

                    for (row in rowsProcessingOrder) {
                        ensureSyncMayContinue()
                        localPassIndex++
                        if (localPassIndex == 1 || localPassIndex % 96 == 0 || localPassIndex == localPassTotal) {
                            setSyncProgress(
                                SyncProgressPhase.RECONCILING_LOCAL,
                                "Comparando itens locais com a nuvem…",
                                "Progresso $localPassIndex/$localPassTotal · " +
                                    "fila de envio ${uploadQueue.size} · " +
                                    "$pendingDownloadPlaceholders download(s) de miniatura.",
                                alsoUpdateLastMessage = false,
                            )
                        }
                        knownPaths.add(row.canonicalPath)
                        if (row.pendingDeleteTarget != PendingDeleteTarget.NONE) continue
                        var remote = remoteByPath[row.canonicalPath]
                            ?: resolveRemoteForUploadCandidate(row, remoteByPath)
                        when {
                            remote == null && row.oneDriveItemId != null -> {
                                upsertBatch.add(
                                    row.copy(
                                        oneDriveItemId = null,
                                        pendingDeleteTarget = PendingDeleteTarget.NONE,
                                        cloudState = CloudSyncState.LOCAL_ONLY,
                                        lastError = null,
                                    ),
                                )
                            }
                            remote == null -> {
                                var workRow = ensureLocalContentFingerprint(row)
                                val contentKey = workRow.localQuickXorHash?.let { hash ->
                                    workRow.localSizeBytes?.let { size ->
                                        ContentFingerprint.key(hash, size)
                                    }
                                }
                                val contentRemote = contentKey
                                    ?.let { remoteByContentKey[it] }
                                    ?.takeIf { it.canonicalPath == row.canonicalPath }
                                val syncedByHash = contentRemote?.let {
                                    markSyncedIfContentMatchesRemote(workRow, it)
                                }
                                if (syncedByHash == null) {
                                    uploadQueue.add(workRow)
                                }
                            }
                            else -> {
                                val workRow = ensureLocalContentFingerprint(row)
                                val merged = reconcileWithRemote(workRow, remote)
                                upsertBatch.add(merged)
                                when (merged.cloudState) {
                                    CloudSyncState.PENDING_UPLOAD -> {
                                        val skip = markSyncedIfContentMatchesRemote(merged, remote)
                                        if (skip == null) {
                                            uploadQueue.add(merged)
                                        }
                                    }
                                    CloudSyncState.PENDING_DOWNLOAD -> pendingDownloadPlaceholders++
                                    else -> Unit
                                }
                            }
                        }
                        if (upsertBatch.size >= ROOM_UPSERT_CHUNK) {
                            flushReconcileUpserts()
                        }
                    }
                    flushReconcileUpserts()
                    healStalePendingDownloads(remoteByPath)
                    healAllStaleLocalCopyMetadata()
                    pendingDownloadPlaceholders = syncMetadataDao.getAll().count {
                        it.cloudState == CloudSyncState.PENDING_DOWNLOAD
                    }

                    setSyncProgress(
                        SyncProgressPhase.RECONCILING_LOCAL,
                        "Enviando arquivos para a nuvem…",
                        "${uploadQueue.size} envio(s) · até $PARALLEL_UPLOADS em paralelo.",
                        alsoUpdateLastMessage = false,
                    )
                    uploaded += runParallelUploads(uploadQueue)
                    uploaded += applyPendingUploadRetries(remoteByPath)

                    val cloudOnlyCandidates = remoteByPath.keys.count { it !in knownPaths }
                    var cloudOnlyProcessed = 0
                    val cloudOnlyInserts = mutableListOf<SyncMetadataEntity>()
                    val cloudOnlyUpdates = mutableListOf<SyncMetadataEntity>()
                    remoteByPath.forEach { (path, remote) ->
                        ensureSyncMayContinue()
                        if (path in knownPaths) return@forEach
                        cloudOnlyProcessed++
                        if (cloudOnlyProcessed == 1 || cloudOnlyProcessed % 128 == 0) {
                            setSyncProgress(
                                SyncProgressPhase.REGISTERING_CLOUD_ONLY,
                                "Registrando fotos/vídeos só na nuvem…",
                                "$cloudOnlyProcessed / $cloudOnlyCandidates novos no índice · " +
                                    "$cloudOnly já gravados nesta passagem.",
                                alsoUpdateLastMessage = false,
                            )
                        }
                        if (path !in existingByPath) {
                            cloudOnlyInserts.add(
                                SyncMetadataEntity(
                                    canonicalPath = path,
                                    displayName = remote.name,
                                    albumName = AlbumGroupKeys.fromPaths(canonicalPath = path),
                                    localMediaId = null,
                                    localContentUri = null,
                                    oneDriveItemId = remote.itemId,
                                    localModifiedAt = null,
                                    remoteModifiedAt = remote.modifiedAtEpoch,
                                    remoteQuickXorHash = remote.quickXorHash,
                                    remoteSizeBytes = remote.sizeBytes,
                                    cloudState = CloudSyncState.CLOUD_ONLY,
                                    cachedFilePath = null,
                                    pendingDeleteTarget = PendingDeleteTarget.NONE,
                                    lastError = null,
                                ),
                            )
                            cloudOnly++
                        } else {
                            val existing = existingByPath[path]!!
                            if (existing.localMediaId == null &&
                                (
                                    existing.oneDriveItemId == null ||
                                        existing.cloudState == CloudSyncState.LOCAL_ONLY ||
                                        existing.cloudState == CloudSyncState.ERROR
                                    )
                            ) {
                                cloudOnlyUpdates.add(
                                    existing.copy(
                                        displayName = remote.name,
                                        oneDriveItemId = remote.itemId,
                                        remoteModifiedAt = remote.modifiedAtEpoch,
                                        remoteQuickXorHash = remote.quickXorHash,
                                        remoteSizeBytes = remote.sizeBytes,
                                        cloudState = CloudSyncState.CLOUD_ONLY,
                                        lastError = null,
                                    ),
                                )
                                cloudOnly++
                            }
                        }
                    }
                    (cloudOnlyInserts + cloudOnlyUpdates).chunked(ROOM_UPSERT_CHUNK).forEach { chunk ->
                        syncMetadataDao.upsertAll(chunk)
                    }

                    val pendingCount = syncMetadataDao.countPendingOperations()
                    var freedLocal = 0
                    val allowDialog = appForegroundState.isInForeground()
                    if (syncPreferences.autoFreeLocalSpaceWhenSynced) {
                        freedLocal += autoReleaseSyncedLocalCopies(
                            allowSystemDialog = allowDialog,
                            scope = AutoReleaseSyncedScope.UPLOADED_THIS_SESSION,
                        )
                    }
                    if (syncPreferences.autoFreeLocalSpaceWhenIdle) {
                        freedLocal += autoReleaseIdleLocalCopies(allowSystemDialog = allowDialog)
                    }
                    val freeMsg = if (freedLocal > 0) ", $freedLocal liberados (placeholder na nuvem)" else ""
                    val seenLocalIds = indexPassLocalItemsById.keys
                    val seenLocalPaths = indexPassLocalItemsById.values.map {
                        SyncPaths.canonicalFromLocal(it)
                    }.toSet()
                    indexPassLocalItemsById = emptyMap()
                    setSyncProgress(
                        SyncProgressPhase.SANITIZING_INDEX,
                        "Verificando consistência do índice…",
                        "Remove entradas obsoletas e alinha com aparelho/nuvem.",
                    )
                    healAllStaleLocalCopyMetadata()
                    val sanitized = runIndexSanitizePass(
                        seenLocalCanonicalPaths = seenLocalPaths,
                        seenLocalMediaIds = seenLocalIds,
                        remoteCanonicalPaths = remoteByPath.keys,
                        remoteListingAvailable = true,
                    )
                    setSyncProgress(
                        SyncProgressPhase.FINISHING,
                        "Finalizando sincronismo…",
                        "",
                        alsoUpdateLastMessage = false,
                    )
                    val coreSyncMessage = buildString {
                        append(
                            "Sync: $uploaded enviados, $pendingDownloadPlaceholders download(s) " +
                                "só miniatura (nuvem), $cloudOnly só na nuvem$freeMsg.",
                        )
                        if (sanitized.removedRows > 0) {
                            append("\nManutenção: ${sanitized.removedRows} entrada(s) obsoleta(s) removida(s).")
                        }
                        append(
                            "\nEm seguida: miniaturas, capas e metadados (mesma sessão de sync).",
                        )
                    }
                    _state.value = _state.value.copy(
                        isOffline = false,
                        pendingOperations = pendingCount,
                        lastMessage = coreSyncMessage,
                    )
                    sweepTransientErrorsAfterSuccessfulSync()
                    if (freedLocal > 0) {
                        syncPreferences.extendMediaLibraryWatchSuppression(
                            SyncPreferences.MEDIA_LIBRARY_SUPPRESS_AFTER_LOCAL_RELEASE_MS,
                        )
                    }
                    ensureNotCancelled()
                    // Mesmo mutex + foreground service até terminar miniaturas/GPS (não perder ao minimizar).
                    runPostSyncEnrichmentInternal(
                        coreSyncMessagePrefix = coreSyncMessage.substringBefore("\n"),
                    )
                    SyncSummary(uploaded, pendingDownloadPlaceholders, cloudOnly)
                }.onFailure { error ->
                    val cancelled = error is CancellationException
                    val userCancelled = cancelled && syncCancelReason.get() == SyncCancelReason.USER
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItems = emptyList(),
                        runQueue = emptyList(),
                        progressPhase = SyncProgressPhase.IDLE,
                        activityHeadline = "",
                        activityDetail = "",
                        lastMessage = when {
                            cancelled -> cancellationResultMessage()
                            else -> error.message ?: "Erro na sincronização."
                        },
                    )
                    if (cancelled && !userCancelled) {
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
            withSyncForeground {
                try {
                    prepareSyncSession(userInitiated)
                } catch (cancelled: CancellationException) {
                    return@withSyncForeground Result.failure(cancelled)
                }
                bindActiveLongTaskJob()
                _state.value = _state.value.copy(
                    isRunning = true,
                    lastMessage = "Indexando localização das fotos…",
                    runQueue = emptyList(),
                    activeItems = emptyList(),
                )
                val result = runCatching {
                    ensureNotCancelled()
                    indexLocalLibrary(manageRunningState = false, userInitiated = userInitiated).getOrThrow()
                    backfillMissingGpsLocations(
                        maxRounds = GPS_BACKFILL_MANUAL_ROUNDS,
                        resetCursor = true,
                    )
                    backfillMissingCaptureTimes()
                    indexPassLocalItemsById = emptyMap()
                    syncMetadataDao.getAll().count { it.latitude != null && it.longitude != null }
                }
                result.onSuccess { withGps ->
                    val stillMissing = countIndexedMissingGps()
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItems = emptyList(),
                        lastMessage = when {
                            withGps == 0 ->
                                "Nenhuma foto com GPS encontrada. Conceda “Localização das mídias” " +
                                    "e confira se a câmera grava localização."
                            stillMissing > 0 ->
                                "Mapa: $withGps com GPS · $stillMissing ainda sem coordenadas " +
                                    "(sem EXIF, só na nuvem ou sync interrompido — sincronize de novo)."
                            else -> "Mapa: $withGps mídia(s) com GPS no índice."
                        },
                    )
                }.onFailure { error ->
                    val cancelled = error is CancellationException
                    val userCancelled = cancelled && isUserCancellation()
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItems = emptyList(),
                        lastMessage = if (cancelled) {
                            cancellationResultMessage()
                        } else {
                            error.message ?: "Erro ao indexar GPS."
                        },
                    )
                    if (cancelled && !userCancelled) {
                        resetSyncCancel()
                    }
                }
                result
            }
        }
    }

    suspend fun ensureThumbnailCached(
        canonicalPath: String,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
    ): Result<File?> {
        val mutex = thumbnailMutexes.getOrPut(canonicalPath) { Mutex() }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureThumbnailCachedOnIo(canonicalPath, onProgress)
            }
        }
    }

    private fun activeSyncProgressCallback(
        row: SyncMetadataEntity,
        operationLabel: String,
    ): ((Long, Long?) -> Unit)? {
        if (!_state.value.isRunning) return null
        return { done, total ->
            upsertActiveSyncItem(
                ActiveSyncItem(
                    canonicalPath = row.canonicalPath,
                    displayName = row.displayName,
                    operationLabel = operationLabel,
                    bytesCompleted = done,
                    totalBytes = total,
                ),
                force = done == 0L || (total != null && done >= total),
            )
        }
    }

    private suspend fun ensureThumbnailCachedOnIo(
        canonicalPath: String,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
    ): Result<File?> {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath) ?: return Result.success(null)
        if (!GalleryMapper.supportsGeneratedThumbnail(row.displayName)) {
            persistMinimalPlaceholderThumbnail(row)?.let { return Result.success(it) }
            return Result.success(null)
        }
        mediaCacheRepository.thumbnailIfCached(canonicalPath, row.oneDriveItemId)
            ?.let {
                syncPreferences.clearThumbnailFailure(canonicalPath)
                return Result.success(it)
            }
        val staging = mediaCacheRepository.thumbStagingFile(canonicalPath)
        staging.delete()
        val isVideo = GalleryMapper.isVideoFileName(row.displayName)
        onProgress?.invoke(0L, null)

        fun commitIfReady(): File? =
            mediaCacheRepository.commitThumbnail(staging, canonicalPath, row.oneDriveItemId)

        localContentUri(row)?.let { uri ->
            val ok = if (isVideo) {
                VideoThumbnailHelper.captureToFile(appContext, uri, staging)
            } else {
                cacheImageThumbnailFromUri(uri, staging)
            }
            if (ok) {
                commitIfReady()?.let { file ->
                    syncPreferences.clearThumbnailFailure(canonicalPath)
                    onProgress?.invoke(1L, 1L)
                    return Result.success(file)
                }
            }
        }

        row.cachedFilePath?.let { path ->
            val cached = File(path)
            if (cached.isFile && cached.length() > 0L) {
                val ok = if (isVideo) {
                    VideoThumbnailHelper.captureToFile(cached, staging)
                } else {
                    cacheImageThumbnailFromUri(Uri.fromFile(cached), staging)
                }
                if (ok) {
                    commitIfReady()?.let { file ->
                        syncPreferences.clearThumbnailFailure(canonicalPath)
                        onProgress?.invoke(1L, 1L)
                        return Result.success(file)
                    }
                }
            }
        }

        val itemId = row.oneDriveItemId
        if (itemId != null && networkMonitor.isOnline() && syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
            var thumbReady = false
            oneDriveRepository.downloadThumbnailToFile(itemId, staging, onProgress).onSuccess {
                if (staging.length() > 0L) thumbReady = true
            }
            if (!thumbReady) {
                val partial = File(staging.absolutePath + ".partial")
                partial.delete()
                oneDriveRepository.downloadImagePrefixForThumbnail(itemId, partial, onProgress = onProgress).onSuccess {
                    if (partial.isFile && partial.length() > 0L) {
                        thumbReady = if (isVideo) {
                            VideoThumbnailHelper.captureToFile(partial, staging)
                        } else {
                            cacheImageThumbnailFromUri(Uri.fromFile(partial), staging)
                        }
                    }
                }
                partial.delete()
            }
            if (thumbReady) {
                commitIfReady()?.let {
                    syncPreferences.clearThumbnailFailure(canonicalPath)
                    onProgress?.invoke(it.length(), it.length())
                    return Result.success(it)
                }
            }
        }
        staging.delete()
        if (syncPreferences.incrementThumbnailFailure(canonicalPath) >= THUMB_MAX_FAILURE_ATTEMPTS) {
            persistMinimalPlaceholderThumbnail(row)?.let { placeholder ->
                syncPreferences.clearThumbnailFailure(canonicalPath)
                return Result.success(placeholder)
            }
        }
        return Result.success(null)
    }

    private suspend fun ensureGraphSessionOnMain(): Result<Unit> {
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return Result.success(Unit)
        return withContext(Dispatchers.Main.immediate) {
            oneDriveRepository.ensureGraphSession()
        }
    }

    suspend fun needsOriginalRecall(canonicalPath: String): Boolean {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath) ?: return false
        if (row.oneDriveItemId.isNullOrBlank()) return false
        resolveViewableOriginalFile(row)?.let { cached ->
            persistCachedOriginalPath(row, cached)
            return false
        }
        if (hasLocalOriginalOnDevice(row)) return false
        return true
    }

    /** Original completo já no aparelho (Galeria ou pasta de cache do app). */
    private fun resolveViewableOriginalFile(row: SyncMetadataEntity): File? {
        row.cachedFilePath?.let { path ->
            File(path).takeIf { it.isFile && it.length() > 0L }?.let { return it }
        }
        mediaCacheRepository.originalIfCached(row.canonicalPath)?.let { return it }
        return null
    }

    private suspend fun persistCachedOriginalPath(row: SyncMetadataEntity, file: File) {
        if (!file.isFile || file.length() <= 0L) return
        val captureFromFile = row.capturedAtSeconds?.takeIf { it > 0L }
            ?: mediaCaptureTimeResolver.resolveFromFile(file)?.takeIf { it > 0L }
        val nextCloudState = cloudStateAfterVerifiedLocal(row, file)
        val nextRow = row.copy(
            cachedFilePath = file.absolutePath,
            lastError = null,
            cloudState = nextCloudState,
            localReleasedForSpace = false,
            capturedAtSeconds = captureFromFile ?: row.capturedAtSeconds,
        )
        if (nextRow.cachedFilePath == row.cachedFilePath &&
            nextRow.cloudState == row.cloudState &&
            nextRow.capturedAtSeconds == row.capturedAtSeconds
        ) {
            notifyGalleryDisplayRefresh()
            return
        }
        syncMetadataDao.upsert(nextRow)
        notifyGalleryDisplayRefresh()
    }

    /** Baixa da nuvem (se preciso); importação na galeria roda em segundo plano. */
    suspend fun recallOriginalToDevice(canonicalPath: String): Result<File> {
        return withContext(Dispatchers.IO) {
            ensureGraphSessionOnMain().getOrElse { return@withContext Result.failure(it) }
            val mutex = onDemandOriginalMutexes.getOrPut(canonicalPath) { Mutex() }
            mutex.withLock {
                try {
                    downloadOriginalOnDemandLocked(canonicalPath)
                } catch (cancelled: CancellationException) {
                    Result.failure(IllegalStateException("Recuperação cancelada."))
                } catch (unexpected: Throwable) {
                    Result.failure(unexpected)
                }
            }
        }
    }

    suspend fun downloadOriginalOnDemand(canonicalPath: String): Result<File> =
        recallOriginalToDevice(canonicalPath)

    private suspend fun downloadOriginalOnDemandLocked(canonicalPath: String): Result<File> {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath)
            ?: return Result.failure(IllegalArgumentException("Item não encontrado"))

        if (hasLocalOriginalOnDevice(row)) {
            resolveViewableOriginalFile(row)?.let { return Result.success(it) }
            return Result.failure(IllegalStateException("Original já está no dispositivo"))
        }

        if (!networkMonitor.isOnline()) {
            val offlineRemote = remoteFingerprintFromIndexedRow(row)
            val cached = resolveViewableOriginalFile(row)
                ?: mediaCacheRepository.originalIfCached(canonicalPath)
            if (offlineRemote != null && cached != null &&
                localFileMatchesRemote(cached, row, offlineRemote)
            ) {
                return finalizeRecallReuse(row, cached, offlineRemote)
            }
            return Result.failure(IllegalStateException("Offline — conecte-se para baixar o original"))
        }

        val remoteFingerprint = fetchRemoteFingerprintForRecall(row)

        resolveViewableOriginalFile(row)?.let { existing ->
            if (remoteFingerprint != null && !localFileMatchesRemote(existing, row, remoteFingerprint)) {
                dropStaleRecallCache(row)
            } else {
                return finalizeRecallReuse(row, existing, remoteFingerprint)
            }
        }

        val itemId = row.oneDriveItemId
            ?: return Result.failure(IllegalStateException("Sem referência OneDrive"))

        val target = mediaCacheRepository.originalFile(canonicalPath)
        if (target.isFile && target.length() > 0L) {
            if (remoteFingerprint != null && localFileMatchesRemote(target, row, remoteFingerprint)) {
                return finalizeRecallReuse(row, target, remoteFingerprint)
            }
            target.delete()
        }

        downloadRemoteFile(
            remoteId = itemId,
            target = target,
            canonicalPath = row.canonicalPath,
            displayName = row.displayName,
            operationLabel = "Recuperando da nuvem…",
            honorSyncCancel = false,
            updateSyncUi = false,
            useSimpleStreamDownload = true,
        ).getOrElse { error ->
            return Result.failure(
                error.takeUnless { it is CancellationException }
                    ?: IllegalStateException("Download cancelado."),
            )
        }
        if (!target.isFile || target.length() <= 0L) {
            return Result.failure(IllegalStateException("Não foi possível obter o arquivo da nuvem."))
        }

        val verifyRemote = fetchRemoteFingerprintForRecall(row) ?: remoteFingerprint
        if (verifyRemote != null && !localFileMatchesRemote(target, row, verifyRemote)) {
            target.delete()
            return Result.failure(
                IllegalStateException("Arquivo baixado não confere com a nuvem (hash/tamanho)."),
            )
        }
        return finalizeRecallReuse(row, target, verifyRemote)
    }

    private suspend fun fetchRemoteFingerprintForRecall(row: SyncMetadataEntity): RemoteDriveFile? {
        if (row.oneDriveItemId.isNullOrBlank()) return null
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_GRAPH -> {
                oneDriveRepository.lookupRemoteFileAtCanonicalPath(row.canonicalPath).getOrNull()
                    ?: oneDriveRepository.getRemoteDriveFileByItemId(
                        row.oneDriveItemId!!,
                        row.canonicalPath,
                    ).getOrNull()
            }
            SyncMode.ONEDRIVE_FOLDER, SyncMode.ONEDRIVE_LOCAL_MIRROR ->
                remoteFingerprintFromIndexedRow(row)
            SyncMode.LOCAL_ONLY -> null
        }
    }

    private fun remoteFingerprintFromIndexedRow(row: SyncMetadataEntity): RemoteDriveFile? {
        val hash = row.remoteQuickXorHash?.takeIf { it.isNotBlank() } ?: return null
        val size = row.remoteSizeBytes ?: return null
        val itemId = row.oneDriveItemId ?: return null
        return RemoteDriveFile(
            itemId = itemId,
            name = row.displayName,
            canonicalPath = row.canonicalPath,
            modifiedAtEpoch = row.remoteModifiedAt,
            sizeBytes = size,
            quickXorHash = hash,
        )
    }

    private suspend fun localFileMatchesRemote(
        file: File,
        row: SyncMetadataEntity,
        remote: RemoteDriveFile,
    ): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val size = file.length()
        if (!ContentFingerprint.sizesEqual(size, remote.sizeBytes)) return false
        val hash = if (
            ContentFingerprint.hashesEqual(row.localQuickXorHash, remote.quickXorHash) &&
            row.localSizeBytes == size
        ) {
            row.localQuickXorHash!!
        } else {
            withContext(Dispatchers.IO) {
                ContentFingerprint.quickXorHashBase64(file)
            }
        }
        return ContentFingerprint.matchesRemote(hash, size, remote)
    }

    private suspend fun dropStaleRecallCache(row: SyncMetadataEntity) {
        mediaCacheRepository.deleteOriginalOnly(row.canonicalPath)
        if (!row.cachedFilePath.isNullOrBlank()) {
            syncMetadataDao.upsert(row.copy(cachedFilePath = null))
        }
    }

    private suspend fun finalizeRecallReuse(
        row: SyncMetadataEntity,
        file: File,
        remote: RemoteDriveFile?,
    ): Result<File> {
        val localHash = withContext(Dispatchers.IO) {
            ContentFingerprint.quickXorHashBase64(file)
        }
        val localSize = file.length()
        if (!file.isFile || file.length() <= 0L) {
            return Result.failure(IllegalStateException("Não foi possível obter o arquivo da nuvem."))
        }
        val synced = row.copy(
            cachedFilePath = file.absolutePath,
            localQuickXorHash = localHash,
            localSizeBytes = localSize,
            remoteQuickXorHash = remote?.quickXorHash ?: row.remoteQuickXorHash,
            remoteSizeBytes = remote?.sizeBytes ?: row.remoteSizeBytes,
            remoteModifiedAt = remote?.modifiedAtEpoch ?: row.remoteModifiedAt,
            oneDriveItemId = remote?.itemId ?: row.oneDriveItemId,
            cloudState = cloudStateAfterVerifiedLocal(row, file),
            localReleasedForSpace = false,
            lastError = null,
        )
        syncMetadataDao.upsert(synced)
        notifyGalleryDisplayRefresh()
        if (!hasLocalOriginalOnDevice(synced)) {
            scheduleRecalledGalleryImport(row.canonicalPath, file)
        }
        return Result.success(file)
    }

    private fun scheduleRecalledGalleryImport(canonicalPath: String, sourceFile: File) {
        val scope = backgroundScope ?: return
        scope.launch(Dispatchers.IO) {
            val mutex = onDemandOriginalMutexes.getOrPut(canonicalPath) { Mutex() }
            mutex.withLock {
                val row = syncMetadataDao.getByCanonicalPath(canonicalPath) ?: return@launch
                if (hasLocalOriginalOnDevice(row)) return@launch
                importRecalledOriginalToGallery(row, sourceFile)
            }
        }
    }

    private fun localOriginalPresent(row: SyncMetadataEntity): Boolean {
        if (row.localReleasedForSpace) return false
        row.localMediaId?.let { id ->
            if (localMediaRepository.contentUriForMediaId(id) != null) return true
        }
        row.localContentUri?.let { parsed ->
            val uri = Uri.parse(parsed)
            if (mediaDeleteRequestRouter.uriExists(appContext, uri)) return true
        }
        return false
    }

    private fun hasLocalOriginalOnDevice(row: SyncMetadataEntity): Boolean {
        if (row.cloudState == CloudSyncState.CLOUD_ONLY) return false
        return localOriginalPresent(row)
    }

    private suspend fun importRecalledOriginalToGallery(
        row: SyncMetadataEntity,
        sourceFile: File,
    ): Result<File> {
        val kind = if (GalleryMapper.isVideoFileName(row.displayName)) {
            com.onegallery.sync.data.local.MediaKind.VIDEO
        } else {
            com.onegallery.sync.data.local.MediaKind.IMAGE
        }
        var updated = row.copy(
            cachedFilePath = sourceFile.absolutePath,
            lastError = null,
        )
        if (!hasLocalOriginalOnDevice(updated)) {
            val captureSeconds = row.capturedAtSeconds?.takeIf { it > 0L }
                ?: mediaCaptureTimeResolver.resolveFromFile(sourceFile)
                ?: row.remoteModifiedAt?.takeIf { it > 0L }
            val imported = localMediaRepository.importIntoMediaStore(
                canonicalPath = row.canonicalPath,
                displayName = row.displayName,
                kind = kind,
                sourceFile = sourceFile,
                takenAtEpochSeconds = captureSeconds,
                storedGalleryFolder = row.localGalleryRelativePath,
            ).getOrElse { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "Não foi possível copiar para a galeria do aparelho."
                syncMetadataDao.upsert(
                    updated.copy(
                        lastError = message,
                        cloudState = if (resolveViewableOriginalFile(updated) != null) {
                            updated.cloudState
                        } else {
                            CloudSyncState.CLOUD_ONLY
                        },
                    ),
                )
                notifyGalleryDisplayRefresh()
                return Result.failure(
                    error.takeUnless { it is CancellationException }
                        ?: IllegalStateException("Importação cancelada."),
                )
            }
            val stableModified = captureSeconds ?: imported.dateModifiedSeconds
            updated = updated.copy(
                localMediaId = imported.id,
                localContentUri = imported.uri.toString(),
                localGalleryRelativePath = imported.relativePath ?: row.localGalleryRelativePath,
                localModifiedAt = stableModified,
                capturedAtSeconds = captureSeconds ?: row.capturedAtSeconds,
                localReleasedForSpace = false,
                cloudState = CloudSyncState.SYNCED,
            )
        } else {
            val captureFromFile = row.capturedAtSeconds?.takeIf { it > 0L }
                ?: mediaCaptureTimeResolver.resolveFromFile(sourceFile)?.takeIf { it > 0L }
            updated = updated.copy(
                capturedAtSeconds = captureFromFile ?: row.capturedAtSeconds,
                cloudState = when (updated.cloudState) {
                    CloudSyncState.CLOUD_ONLY, CloudSyncState.PENDING_DOWNLOAD -> CloudSyncState.SYNCED
                    else -> updated.cloudState
                },
            )
        }
        syncMetadataDao.upsert(updated)
        notifyGalleryDisplayRefresh()
        runCatching { ensureThumbnailCachedOnIo(row.canonicalPath) }
        return Result.success(sourceFile)
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
            if (!canRemoveLocalCopiesWithoutPrompt()) {
                error(localSpaceReleaseFailureMessage())
            }
            val rows = canonicalPaths.mapNotNull { syncMetadataDao.getByCanonicalPath(it) }
            val withoutCloud = rows.filter { it.oneDriveItemId == null }
            if (withoutCloud.isNotEmpty()) {
                error(
                    "Só é possível liberar espaço de itens já no OneDrive. " +
                        "Sincronize antes ou escolha outro item.",
                )
            }
            val healedRows = rows.map { healAndPersistStaleLocalCopyMetadata(it) }
            if (healedRows.none { rowHasReleasableLocalCopy(it) }) {
                error(localSpaceReleaseFailureMessage(noLocalCopyOnDevice = true))
            }
            val freed = releaseLocalSpaceForRows(
                healedRows,
                allowSystemDialog = allowSystemDeleteDialogForLocalRemoval(),
            )
            if (healedRows.isNotEmpty() && freed == 0) {
                error(localSpaceReleaseFailureMessage())
            }
            freed
        }
    }

    /** Chamado pelo [com.onegallery.sync.worker.SyncWorker] — não pelo ciclo de vida da UI. */
    suspend fun runAutoReleaseIfNeeded(): Int {
        if (syncPreferences.syncMode == SyncMode.LOCAL_ONLY) return 0
        if (!syncPreferences.autoFreeLocalSpaceWhenSynced &&
            !syncPreferences.autoFreeLocalSpaceWhenIdle
        ) {
            return 0
        }
        val allowDialog = appForegroundState.isInForeground() &&
            allowSystemDeleteDialogForLocalRemoval()
        var freed = 0
        if (syncPreferences.autoFreeLocalSpaceWhenSynced) {
            freed += autoReleaseSyncedLocalCopies(
                allowSystemDialog = allowDialog,
                scope = AutoReleaseSyncedScope.ALL_ELIGIBLE,
            )
        }
        if (syncPreferences.autoFreeLocalSpaceWhenIdle) {
            freed += autoReleaseIdleLocalCopies(allowSystemDialog = allowDialog)
        }
        if (freed > 0) {
            syncPreferences.extendMediaLibraryWatchSuppression(
                SyncPreferences.MEDIA_LIBRARY_SUPPRESS_AFTER_LOCAL_RELEASE_MS,
            )
        }
        return freed
    }

    /** Registra abertura da foto/vídeo no detalhe (base para dias sem acesso). */
    suspend fun recordLocalMediaAccess(canonicalPath: String) {
        val row = syncMetadataDao.getByCanonicalPath(canonicalPath) ?: return
        val now = System.currentTimeMillis()
        val previous = row.localLastAccessedAtMs
        if (previous != null && now - previous < LOCAL_ACCESS_WRITE_THROTTLE_MS) return
        syncMetadataDao.upsert(row.copy(localLastAccessedAtMs = now))
    }

    private enum class AutoReleaseSyncedScope {
        /** Fim do sync: reforço só nos enviados nesta execução (cada upload já tenta liberar na hora). */
        UPLOADED_THIS_SESSION,
        /** Abertura do app / manutenção: qualquer item já na nuvem com cópia local ainda no aparelho. */
        ALL_ELIGIBLE,
    }

    private suspend fun autoReleaseSyncedLocalCopies(
        allowSystemDialog: Boolean,
        scope: AutoReleaseSyncedScope,
    ): Int {
        if (syncPreferences.syncMode == SyncMode.LOCAL_ONLY) return 0
        val sessionPaths = when (scope) {
            AutoReleaseSyncedScope.UPLOADED_THIS_SESSION -> pathsUploadedThisSync.toSet()
            AutoReleaseSyncedScope.ALL_ELIGIBLE -> null
        }
        if (scope == AutoReleaseSyncedScope.UPLOADED_THIS_SESSION && sessionPaths.isNullOrEmpty()) {
            return 0
        }
        if (scope == AutoReleaseSyncedScope.UPLOADED_THIS_SESSION) {
            ensureSyncMayContinue()
        }
        val rows = syncMetadataDao.getAll().filter { row ->
            (sessionPaths == null || row.canonicalPath in sessionPaths) &&
                rowHasReleasableLocalCopy(row) &&
                isEligibleForSyncedAutoRelease(row) &&
                row.pendingDeleteTarget == PendingDeleteTarget.NONE &&
                !row.localReleasedForSpace
        }
        if (rows.isEmpty()) return 0
        return releaseLocalSpaceForRows(rows, allowSystemDialog = allowSystemDialog)
    }

    private fun isEligibleForSyncedAutoRelease(row: SyncMetadataEntity): Boolean {
        if (row.oneDriveItemId.isNullOrBlank()) return false
        return when (row.cloudState) {
            CloudSyncState.SYNCED,
            CloudSyncState.CLOUD_ONLY,
            -> true
            CloudSyncState.PENDING_UPLOAD,
            CloudSyncState.PENDING_DOWNLOAD,
            CloudSyncState.ERROR,
            CloudSyncState.CONFLICT,
            CloudSyncState.LOCAL_ONLY,
            -> false
        }
    }

    private suspend fun autoReleaseIdleLocalCopies(allowSystemDialog: Boolean): Int {
        ensureSyncMayContinue()
        if (syncPreferences.syncMode == SyncMode.LOCAL_ONLY) return 0
        val idleDays = syncPreferences.localCopyIdleReleaseDays
        val rows = syncMetadataDao.getAll().filter { row ->
            row.oneDriveItemId != null &&
                row.pendingDeleteTarget == PendingDeleteTarget.NONE &&
                !row.localReleasedForSpace &&
                rowHasReleasableLocalCopy(row) &&
                row.cloudState != CloudSyncState.PENDING_UPLOAD &&
                isLocalCopyIdleBeyondPolicy(row, idleDays)
        }
        if (rows.isEmpty()) return 0
        return releaseLocalSpaceForRows(rows, allowSystemDialog = allowSystemDialog)
    }

    private fun rowHasReleasableLocalCopy(row: SyncMetadataEntity): Boolean {
        if (row.localReleasedForSpace) return false
        if (localOriginalPresent(row)) return true
        return resolveViewableOriginalFile(row) != null
    }

    /**
     * Referência para “por inatividade”: abertura no app; senão captura/alteração local (como no MediaStore).
     * Assim, após reindexar sem [localLastAccessedAtMs], não libera antes do prazo desde a foto.
     */
    private fun baselineMsForIdleRelease(row: SyncMetadataEntity): Long? {
        row.localLastAccessedAtMs?.let { return it }
        row.capturedAtSeconds?.takeIf { it > 0L }?.let { return it * 1000L }
        row.localModifiedAt?.takeIf { it > 0L }?.let { return it * 1000L }
        row.remoteModifiedAt?.takeIf { it > 0L }?.let { return it * 1000L }
        return null
    }

    private fun isLocalCopyIdleBeyondPolicy(row: SyncMetadataEntity, idleDays: Int): Boolean {
        val baseline = baselineMsForIdleRelease(row) ?: return false
        val idleMs = idleDays.toLong() * 24L * 60L * 60L * 1000L
        return System.currentTimeMillis() - baseline >= idleMs
    }

    private fun canRemoveLocalCopiesWithoutPrompt(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            MediaStorageAccess.hasAllFilesAccess(appContext)
    }

    /** Liberar espaço não usa diálogo do Android — só acesso amplo ou remoção silenciosa. */
    private fun allowSystemDeleteDialogForLocalRemoval(): Boolean {
        return canRemoveLocalCopiesWithoutPrompt()
    }

    private fun localSpaceReleaseFailureMessage(noLocalCopyOnDevice: Boolean = false): String {
        if (noLocalCopyOnDevice) {
            return "Não há cópia local deste item — só miniatura ou nuvem. " +
                "Abra a foto para baixar o original, se precisar."
        }
        return if (!canRemoveLocalCopiesWithoutPrompt()) {
            "Para liberar espaço: Ajustes → Remover cópias no aparelho → Conceder " +
                "(acesso a todos os arquivos)."
        } else {
            "Não foi possível remover a cópia local. Tente de novo ou verifique se o arquivo " +
                "ainda está na galeria do aparelho."
        }
    }

    private suspend fun releaseLocalSpaceForRows(
        rows: List<SyncMetadataEntity>,
        allowSystemDialog: Boolean,
    ): Int {
        if (rows.isEmpty()) return 0
        val preparedRows = rows.map { healAndPersistStaleLocalCopyMetadata(it) }
        val priorReleasedFlag = preparedRows.associate { it.canonicalPath to it.localReleasedForSpace }
        preparedRows.forEach { row ->
            ensureThumbnailCached(row.canonicalPath).getOrNull()
            markLocalSpaceReleasePending(row)
        }

        val pairs = preparedRows.mapNotNull { row -> localContentUri(row)?.let { uri -> uri to row } }
        val uriToRow = pairs.associate { it.first to it.second }
        val uris = uriToRow.keys.toList()

        var deleted = mediaDeleteRequestRouter.deleteSilent(appContext, uris)
        var pending = uris.filter { it !in deleted }
        if (pending.isNotEmpty()) {
            deleted = deleted + mediaDeleteRequestRouter.deleteWithAllFilesAccessIfGranted(
                appContext,
                pending,
            )
            pending = uris.filter { it !in deleted }
        }
        if (pending.isNotEmpty() && allowSystemDialog) {
            when (val outcome = mediaDeleteRequestRouter.requestDelete(appContext, pending)) {
                is MediaDeleteResult.Success, MediaDeleteResult.NothingToDelete -> {
                    deleted = deleted + mediaDeleteRequestRouter.deleteSilent(appContext, pending)
                }
                MediaDeleteResult.UserDenied -> Unit
                is MediaDeleteResult.Failed -> Unit
            }
        }

        var freed = 0
        val freedPaths = mutableSetOf<String>()
        for ((uri, row) in uriToRow) {
            if (uri !in deleted) continue
            finalizeLocalSpaceReleased(row)
            freedPaths.add(row.canonicalPath)
            freed++
        }
        val handled = uriToRow.values.map { it.canonicalPath }.toSet()
        for (row in preparedRows) {
            if (row.canonicalPath in handled) continue
            if (row.localContentUri != null || row.localMediaId != null) continue
            if (resolveViewableOriginalFile(row) == null) continue
            finalizeLocalSpaceReleased(row)
            freedPaths.add(row.canonicalPath)
            freed++
        }
        for (path in preparedRows.map { it.canonicalPath }) {
            if (path in freedPaths) continue
            val current = syncMetadataDao.getByCanonicalPath(path) ?: continue
            syncMetadataDao.upsert(
                current.copy(localReleasedForSpace = priorReleasedFlag[path] ?: false),
            )
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
                        "Exclusão cancelada. Em Ajustes, ative “Excluir sem confirmar toda vez” " +
                            "ou confirme no diálogo do Android.",
                    )
                    is MediaDeleteResult.Failed -> {
                        if (target == PendingDeleteTarget.BOTH) {
                            localSkipped = true
                        } else {
                            error(
                                "Não foi possível excluir no aparelho. Em Ajustes, conceda " +
                                    "“Excluir sem confirmar toda vez” ou aceite o diálogo do Android.",
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
        if (MediaStorageAccess.hasAllFilesAccess(appContext)) {
            deleteLocalFilesWithAllFilesAccess(rows)
        }
        val uris = rows.mapNotNull { row -> localContentUri(row) }
        if (uris.isEmpty()) return MediaDeleteResult.NothingToDelete
        return mediaDeleteRequestRouter.requestDelete(appContext, uris)
    }

    private fun deleteLocalFilesWithAllFilesAccess(rows: List<SyncMetadataEntity>) {
        rows.forEach { row ->
            val item = row.localMediaId?.let { localMediaRepository.findById(it) } ?: return@forEach
            val rel = item.relativePath?.trim('/') ?: return@forEach
            val file = android.os.Environment.getExternalStorageDirectory()
                .resolve("$rel/${item.displayName}")
            runCatching {
                if (file.isFile) file.delete()
            }
        }
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
        upsertActiveSyncItem(
            ActiveSyncItem(
                canonicalPath = row.canonicalPath,
                displayName = row.displayName,
                operationLabel = "Enviando para OneDrive…",
            ),
        )
        val localId = row.localMediaId ?: return 0.also { removeActiveSyncItem(row.canonicalPath) }
        val item = indexPassLocalItemsById[localId]
            ?: localMediaRepository.findById(localId)
            ?: return 0.also { removeActiveSyncItem(row.canonicalPath) }
        val temp = localMediaRepository.materializeToTempFile(item) ?: run {
            syncMetadataDao.upsert(
                row.copy(
                    lastError = "Não foi possível ler o arquivo local.",
                    cloudState = CloudSyncState.ERROR,
                ),
            )
            removeActiveSyncItem(row.canonicalPath)
            return 0
        }
        return runCatching {
            val localHash = ContentFingerprint.quickXorHashBase64(temp)
            val localSize = temp.length()
            var remoteId = row.oneDriveItemId
            var remoteHash = row.remoteQuickXorHash
            var remoteSize = row.remoteSizeBytes
            var remoteModified = row.remoteModifiedAt
            var uploadedBytes = false
            if (syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
                val existingRemote = oneDriveRepository
                    .lookupRemoteFileAtCanonicalPath(row.canonicalPath)
                    .getOrNull()
                if (existingRemote != null &&
                    ContentFingerprint.matchesRemote(localHash, localSize, existingRemote)
                ) {
                    remoteId = existingRemote.itemId
                    remoteHash = existingRemote.quickXorHash ?: remoteHash
                    remoteSize = existingRemote.sizeBytes ?: remoteSize
                    remoteModified = existingRemote.modifiedAtEpoch ?: remoteModified
                } else {
                    remoteId = uploadLocalFile(
                        canonicalPath = row.canonicalPath,
                        sourceFile = temp,
                        mimeType = item.mimeType,
                        displayName = row.displayName,
                        operationLabel = "Enviando para OneDrive…",
                    ).getOrThrow()
                    uploadedBytes = true
                }
            } else {
                remoteId = uploadLocalFile(
                    canonicalPath = row.canonicalPath,
                    sourceFile = temp,
                    mimeType = item.mimeType,
                    displayName = row.displayName,
                    operationLabel = "Enviando para OneDrive…",
                ).getOrThrow()
                uploadedBytes = true
            }
            val synced = row.copy(
                oneDriveItemId = remoteId,
                localQuickXorHash = localHash,
                localSizeBytes = localSize,
                remoteQuickXorHash = remoteHash,
                remoteSizeBytes = remoteSize,
                remoteModifiedAt = if (uploadedBytes) {
                    System.currentTimeMillis() / 1000
                } else {
                    remoteModified
                },
                cloudState = CloudSyncState.SYNCED,
                lastError = null,
            )
            syncMetadataDao.upsert(synced)
            pathsUploadedThisSync.add(row.canonicalPath)
            if (syncPreferences.autoFreeLocalSpaceWhenSynced) {
                releaseLocalSpaceForRows(
                    listOf(synced),
                    allowSystemDialog = appForegroundState.isInForeground(),
                )
            }
            1
        }.getOrElse { error ->
            propagateCancellation(error)
            syncMetadataDao.upsert(row.copy(cloudState = CloudSyncState.ERROR, lastError = error.message))
            0
        }.also {
            temp.delete()
            removeActiveSyncItem(row.canonicalPath)
            removeFromRunQueue(row.canonicalPath)
        }
    }

    /** Exclusões pendentes na fila; miniaturas ficam para [runMissingThumbnailPrefetch] (3 em paralelo). */
    private suspend fun drainRemainingRunQueueBeforePrefetch() {
        val snapshot = _state.value.runQueue
        if (snapshot.isEmpty()) return
        val thumbRows = mutableListOf<SyncMetadataEntity>()
        for (displayRow in snapshot) {
            val path = displayRow.canonicalPath
            val row = syncMetadataDao.getByCanonicalPath(path)
            if (row == null) {
                removeFromRunQueue(path)
                continue
            }
            when {
                row.pendingDeleteTarget != PendingDeleteTarget.NONE -> {
                    deleteItemsImmediately(listOf(path), row.pendingDeleteTarget)
                    removeFromRunQueue(path)
                }
                row.oneDriveItemId != null &&
                    mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId) == null -> {
                    thumbRows.add(row)
                }
                else -> removeFromRunQueue(path)
            }
        }
        if (thumbRows.isEmpty()) return
        setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Miniaturas da fila…",
            "${thumbRows.size} item(ns) · até $PARALLEL_THUMBNAILS em paralelo.",
            alsoUpdateLastMessage = false,
        )
        thumbRows.chunked(THUMB_PREFETCH_BATCH).forEach { batch ->
            ensureNotCancelled()
            setRunQueueFromEntities(batch, thumbnailQueue = true)
            runMissingThumbnailPrefetch(batch, honorSyncCancel = true)
        }
    }

    private fun rowCanBuildThumbnail(row: SyncMetadataEntity): Boolean {
        if (!GalleryMapper.supportsGeneratedThumbnail(row.displayName)) return false
        if (row.oneDriveItemId != null) return true
        if (row.localMediaId != null) return true
        if (!row.localContentUri.isNullOrBlank()) return true
        if (!row.cachedFilePath.isNullOrBlank()) return true
        return false
    }

    /** Áudio (ex.: .opus), PDF etc. — placeholder fixo, sem fila de download Graph. */
    private suspend fun materializeNonVisualThumbnails() {
        val rows = syncMetadataDao.getAll().filter { row ->
            mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId) == null &&
                !GalleryMapper.supportsGeneratedThumbnail(row.displayName) &&
                (
                    row.oneDriveItemId != null || row.localMediaId != null ||
                        !row.localContentUri.isNullOrBlank() || !row.cachedFilePath.isNullOrBlank()
                    )
        }
        if (rows.isEmpty()) return
        rows.forEach { row ->
            persistMinimalPlaceholderThumbnail(row)
        }
    }

    private suspend fun buildMissingThumbnailEntities(maxPerRun: Int): List<SyncMetadataEntity> {
        val missing = syncMetadataDao.getAll()
            .asSequence()
            .filter { row ->
                mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId) == null &&
                    rowCanBuildThumbnail(row)
            }
            .sortedWith(missingThumbnailPrefetchComparator)
            .toList()
        if (missing.isEmpty()) return emptyList()
        val cursor = syncPreferences.thumbPrefetchCursor % missing.size
        val rotated = missing.drop(cursor) + missing.take(cursor)
        val batch = rotated.take(maxPerRun)
        syncPreferences.thumbPrefetchCursor = (cursor + batch.size) % missing.size
        return batch
    }

    private suspend fun countIndexedMissingThumbnails(): Int {
        return syncMetadataDao.getAll().count { row ->
            mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId) == null &&
                rowCanBuildThumbnail(row)
        }
    }

    private suspend fun countIndexedMissingGps(): Int {
        return syncMetadataDao.getAll().count { row ->
            row.latitude == null &&
                row.longitude == null &&
                (
                    row.localMediaId != null || row.localContentUri != null ||
                        row.cachedFilePath != null || row.oneDriveItemId != null
                    )
        }
    }

    private fun thumbnailOperationLabel(row: SyncMetadataEntity): String {
        val hasLocalSource = row.localMediaId != null ||
            !row.localContentUri.isNullOrBlank() ||
            !row.cachedFilePath.isNullOrBlank()
        return if (row.oneDriveItemId != null && !hasLocalSource) {
            "Baixando miniatura da nuvem…"
        } else {
            "Gerando miniatura…"
        }
    }

    /**
     * Corrige [CloudSyncState.PENDING_DOWNLOAD] fantasma (hash na listagem delta incompleto,
     * fingerprint local desatualizado, miniatura já em cache).
     */
    private suspend fun healStalePendingDownloads(remoteByPath: Map<String, RemoteDriveFile>?) {
        val pending = syncMetadataDao.getAll().filter { it.cloudState == CloudSyncState.PENDING_DOWNLOAD }
        if (pending.isEmpty()) return
        val map = remoteByPath ?: emptyMap()
        val healed = mutableListOf<SyncMetadataEntity>()
        for (row in pending) {
            val remote = map[row.canonicalPath] ?: remoteFingerprintFromIndexedRow(row)
            val work = ensureLocalContentFingerprint(row)
            if (remote != null) {
                val effective = ContentFingerprint.remoteWithIndexedFallback(work, remote)
                if (ContentFingerprint.matchesIndexedLocalWithFallback(work, effective)) {
                    healed.add(syncedRowFromRemote(work, effective, CloudSyncState.SYNCED))
                    continue
                }
                val liveHash = computeLiveLocalQuickXorHash(work)
                if (liveHash != null &&
                    ContentFingerprint.matchesRemote(liveHash.first, liveHash.second, effective)
                ) {
                    healed.add(
                        syncedRowFromRemote(
                            work.copy(
                                localQuickXorHash = liveHash.first,
                                localSizeBytes = liveHash.second,
                            ),
                            effective,
                            CloudSyncState.SYNCED,
                        ),
                    )
                    continue
                }
            }
            if (mediaCacheRepository.thumbnailIfCached(work.canonicalPath, work.oneDriveItemId) != null) {
                val state = if (localOriginalPresent(work)) {
                    CloudSyncState.SYNCED
                } else {
                    CloudSyncState.CLOUD_ONLY
                }
                if (remote != null) {
                    healed.add(syncedRowFromRemote(work, remote, state))
                } else {
                    healed.add(work.copy(cloudState = state, lastError = null))
                }
            }
        }
        if (healed.isEmpty()) return
        healed.chunked(ROOM_UPSERT_CHUNK).forEach { chunk ->
            syncMetadataDao.upsertAll(chunk)
        }
    }

    private fun syncedRowFromRemote(
        row: SyncMetadataEntity,
        remote: RemoteDriveFile,
        cloudState: CloudSyncState,
    ): SyncMetadataEntity {
        return row.copy(
            oneDriveItemId = remote.itemId,
            remoteModifiedAt = remote.modifiedAtEpoch ?: row.remoteModifiedAt,
            remoteQuickXorHash = remote.quickXorHash ?: row.remoteQuickXorHash,
            remoteSizeBytes = remote.sizeBytes ?: row.remoteSizeBytes,
            cloudState = cloudState,
            lastError = null,
        )
    }

    private suspend fun computeLiveLocalQuickXorHash(
        row: SyncMetadataEntity,
    ): Pair<String, Long>? {
        val localId = row.localMediaId ?: return null
        val item = indexPassLocalItemsById[localId]
            ?: localMediaRepository.findById(localId)
            ?: return null
        val size = item.sizeBytes
        val hash = withContext(Dispatchers.IO) {
            localMediaRepository.openMediaInputStream(item)?.use { input ->
                ContentFingerprint.quickXorHashBase64(input)
            }
        } ?: return null
        return hash to size
    }

    private fun reconcileWithRemote(row: SyncMetadataEntity, remote: RemoteDriveFile): SyncMetadataEntity {
        val localTs = row.localModifiedAt ?: 0L
        val remoteTs = remote.modifiedAtEpoch ?: 0L
        val base = row.copy(
            oneDriveItemId = remote.itemId,
            remoteModifiedAt = remote.modifiedAtEpoch,
            remoteQuickXorHash = remote.quickXorHash ?: row.remoteQuickXorHash,
            remoteSizeBytes = remote.sizeBytes ?: row.remoteSizeBytes,
            lastError = null,
        )
        if (row.localMediaId == null) {
            return base.copy(cloudState = CloudSyncState.CLOUD_ONLY)
        }
        if (ContentFingerprint.matchesIndexedLocalWithFallback(row, remote)) {
            return base.copy(cloudState = CloudSyncState.SYNCED)
        }
        val lastKnownRemote = row.remoteModifiedAt
        val remoteChangedSinceSync = lastKnownRemote != null &&
            remoteTs > lastKnownRemote + SYNC_TIMESTAMP_TOLERANCE_SEC
        val localNewerThanCloud = localTs > remoteTs + SYNC_TIMESTAMP_TOLERANCE_SEC
        val remoteNewerThanLocal = remoteTs > localTs + SYNC_TIMESTAMP_TOLERANCE_SEC
        return when {
            localNewerThanCloud -> {
                if (ContentFingerprint.matchesIndexedLocalWithFallback(row, remote)) {
                    base.copy(cloudState = CloudSyncState.SYNCED)
                } else {
                    base.copy(cloudState = CloudSyncState.PENDING_UPLOAD)
                }
            }
            remoteNewerThanLocal || (remoteChangedSinceSync && !localNewerThanCloud) -> {
                if (ContentFingerprint.matchesIndexedLocalWithFallback(row, remote)) {
                    base.copy(cloudState = CloudSyncState.SYNCED)
                } else {
                    // Original completo só sob demanda (abrir mídia) — não manter fila fantasma.
                    base.copy(cloudState = CloudSyncState.SYNCED)
                }
            }
            else -> base.copy(cloudState = CloudSyncState.SYNCED)
        }
    }

    private fun buildRemoteContentIndex(files: List<RemoteDriveFile>): Map<String, RemoteDriveFile> {
        return files.mapNotNull { remote ->
            val hash = remote.quickXorHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val size = remote.sizeBytes ?: return@mapNotNull null
            ContentFingerprint.key(hash, size) to remote
        }.toMap()
    }

    private suspend fun ensureLocalContentFingerprint(row: SyncMetadataEntity): SyncMetadataEntity {
        val sizeKnown = row.localSizeBytes
        if (row.localQuickXorHash != null && sizeKnown != null) return row
        val localId = row.localMediaId ?: return row
        val item = indexPassLocalItemsById[localId]
            ?: localMediaRepository.findById(localId)
            ?: return row
        val size = sizeKnown ?: item.sizeBytes
        val hash = withContext(Dispatchers.IO) {
            localMediaRepository.openMediaInputStream(item)?.use { input ->
                ContentFingerprint.quickXorHashBase64(input)
            }
        } ?: return row.copy(localSizeBytes = size)
        return row.copy(localQuickXorHash = hash, localSizeBytes = size)
    }

    private suspend fun markSyncedIfContentMatchesRemote(
        row: SyncMetadataEntity,
        remote: RemoteDriveFile,
    ): SyncMetadataEntity? {
        val enriched = ensureLocalContentFingerprint(row)
        val merged = reconcileWithRemote(enriched, remote)
        if (merged.cloudState != CloudSyncState.SYNCED) return null
        syncMetadataDao.upsert(merged)
        return merged
    }

    private suspend fun resolveRemoteForUploadCandidate(
        row: SyncMetadataEntity,
        remoteByPath: Map<String, RemoteDriveFile>,
    ): RemoteDriveFile? {
        remoteByPath[row.canonicalPath]?.let { return it }
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return null
        return oneDriveRepository.lookupRemoteFileAtCanonicalPath(row.canonicalPath).getOrNull()
    }

    /** Reaplica envios pendentes após o merge principal (ex.: falha de rede na mesma execução). */
    private suspend fun applyPendingUploadRetries(
        remoteByPath: Map<String, RemoteDriveFile>,
    ): Int {
        val toUpload = mutableListOf<SyncMetadataEntity>()
        for (row in syncMetadataDao.getPendingUploadRows()) {
            ensureSyncMayContinue()
            val remote = remoteByPath[row.canonicalPath]
                ?: resolveRemoteForUploadCandidate(row, remoteByPath)
            if (remote == null) {
                toUpload.add(row)
            } else {
                val merged = reconcileWithRemote(row, remote)
                syncMetadataDao.upsert(merged)
                when (merged.cloudState) {
                    CloudSyncState.PENDING_UPLOAD -> {
                        if (markSyncedIfContentMatchesRemote(merged, remote) == null) {
                            toUpload.add(merged)
                        }
                    }
                    else -> Unit
                }
            }
        }
        return runParallelUploads(toUpload)
    }

    private suspend fun runParallelUploads(rows: List<SyncMetadataEntity>): Int {
        if (rows.isEmpty()) return 0
        val semaphore = Semaphore(PARALLEL_UPLOADS)
        return supervisorScope {
            rows.map { row ->
                async {
                    semaphore.withPermit { uploadLocalRow(row) }
                }
            }.awaitAll().sum()
        }
    }

    private suspend fun reconcileMissingLocalItems(seenPaths: Set<String>) {
        syncMetadataDao.getAll().forEachIndexed { index, row ->
            if (index % 32 == 0) ensureNotCancelled()
            if (row.localMediaId == null) return@forEachIndexed
            if (row.canonicalPath in seenPaths) return@forEachIndexed
            row.localMediaId?.let { mediaId ->
                localMediaRepository.findById(mediaId)?.let { stillOnDevice ->
                    val canonical = SyncPaths.canonicalFromLocal(stillOnDevice)
                    syncMetadataDao.upsert(
                        mergeLocalIntoEntity(
                            item = stillOnDevice,
                            canonical = canonical,
                            existing = row,
                        ),
                    )
                    return@forEachIndexed
                }
            }
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

    /** Instalações antigas: PENDING_DOWNLOAD não dispara mais download automático. */
    private suspend fun clearLegacyPendingDownloadStates() {
        val legacy = syncMetadataDao.getAll().filter { it.cloudState == CloudSyncState.PENDING_DOWNLOAD }
        if (legacy.isEmpty()) return
        legacy.chunked(ROOM_UPSERT_CHUNK).forEach { chunk ->
            syncMetadataDao.upsertAll(
                chunk.map { row ->
                    val state = if (localOriginalPresent(row)) {
                        CloudSyncState.SYNCED
                    } else {
                        CloudSyncState.CLOUD_ONLY
                    }
                    row.copy(cloudState = state, lastError = null)
                },
            )
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

    /** Miniatura neutra para itens que a nuvem não consegue decodificar (evita loop infinito). */
    private fun persistMinimalPlaceholderThumbnail(row: SyncMetadataEntity): File? {
        val existing = mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId)
        if (existing != null) return existing
        val staging = mediaCacheRepository.thumbStagingFile(row.canonicalPath)
        staging.delete()
        return runCatching {
            val edge = MediaCacheRepository.MIN_DISPLAYABLE_THUMB_EDGE_PX
            val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.RGB_565)
            try {
                bitmap.eraseColor(MediaCacheRepository.SYNC_STUB_PLACEHOLDER_COLOR)
                staging.parentFile?.mkdirs()
                staging.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_CACHE_JPEG_QUALITY, out)
                }
            } finally {
                bitmap.recycle()
            }
            if (staging.length() <= 0L) return null
            mediaCacheRepository.commitThumbnail(staging, row.canonicalPath, row.oneDriveItemId)
        }.getOrNull()
    }

    private fun cachePlaceholderThumbnail(row: SyncMetadataEntity) {
        val existing = mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId)
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

    private fun cacheImageThumbnailFromUri(uri: Uri, target: File, maxSidePx: Int = THUMB_CACHE_MAX_SIDE_PX): Boolean {
        return runCatching {
            target.parentFile?.mkdirs()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { probe ->
                BitmapFactory.decodeStream(probe, null, bounds)
            } ?: return false
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val sample = thumbnailInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = appContext.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: return false
            try {
                target.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_CACHE_JPEG_QUALITY, out)
                }
                target.length() > 0L
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
    }

    private fun thumbnailInSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
        var sample = 1
        while (width / sample > maxSidePx || height / sample > maxSidePx) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
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
            albumName = SyncPaths.folderAlbumName(
                canonicalPath = canonical,
                relativePath = item.relativePath,
                bucketDisplayName = item.bucketDisplayName,
            ),
            userAlbumName = existing?.userAlbumName,
            localMediaId = item.id,
            localContentUri = item.uri.toString(),
            localGalleryRelativePath = item.relativePath?.takeIf { it.isNotBlank() }
                ?: existing?.localGalleryRelativePath,
            localReleasedForSpace = false,
            localLastAccessedAtMs = existing?.localLastAccessedAtMs,
            oneDriveItemId = existing?.oneDriveItemId,
            localModifiedAt = item.dateModifiedSeconds,
            localSizeBytes = item.sizeBytes,
            localQuickXorHash = existing?.localQuickXorHash,
            capturedAtSeconds = capturedAt,
            remoteModifiedAt = existing?.remoteModifiedAt,
            remoteQuickXorHash = existing?.remoteQuickXorHash,
            remoteSizeBytes = existing?.remoteSizeBytes,
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
        existing?.capturedAtSeconds?.takeIf { it > 0L }?.let { return it }
        return mediaCaptureTimeResolver.resolveFromLocalItem(item)
    }

    private fun resolveCaptureTimeForItemLight(
        item: LocalMediaItem,
        existing: SyncMetadataEntity?,
    ): Long? {
        existing?.capturedAtSeconds?.takeIf { it > 0L }?.let { return it }
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
            probeCloudExifLocation(itemId, fileNameHint = null)?.let { return it }
        }
        return null
    }

    private suspend fun resolveLocationForBackfill(row: SyncMetadataEntity): MediaLocation? {
        resolveLocationFromSources(
            uri = localContentUri(row),
            cachedFilePath = row.cachedFilePath,
            oneDriveItemId = null,
        )?.let { return it }
        val localId = row.localMediaId
        if (localId != null) {
            val item = indexPassLocalItemsById[localId]
                ?: localMediaRepository.findById(localId)
            if (item != null) {
                item.latitude?.let { lat ->
                    item.longitude?.let { lon ->
                        return MediaLocation(lat, lon, formatCoordLabel(lat, lon))
                    }
                }
                val temp = localMediaRepository.materializeToTempFile(item)
                if (temp != null) {
                    runCatching {
                        mediaLocationResolver.resolveFromFile(temp)
                    }.getOrNull()?.let { located ->
                        temp.delete()
                        return located
                    }
                    temp.delete()
                }
            }
        }
        return row.oneDriveItemId?.let { itemId ->
            probeCloudExifLocation(itemId, row.displayName)
        }
    }

    private suspend fun probeCloudExifLocation(
        oneDriveItemId: String,
        fileNameHint: String? = null,
    ): MediaLocation? {
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return null
        if (!networkMonitor.isOnline()) return null
        return oneDriveRepository.probeExifLocationFromItem(
            oneDriveItemId,
            mediaLocationResolver,
            fileNameHint,
        ).getOrNull()
    }

    private suspend fun seedResidualRunQueueAfterCoreSync() {
        val residual = syncMetadataDao.getPostSyncResidualCandidates().filter { row ->
            when {
                row.pendingDeleteTarget != PendingDeleteTarget.NONE -> true
                row.oneDriveItemId != null &&
                    mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId) == null &&
                    row.cloudState == CloudSyncState.CLOUD_ONLY -> true
                else -> false
            }
        }
        if (residual.isNotEmpty()) {
            setRunQueueFromEntities(residual.sortedWith(syncQueueEntityComparator))
        }
    }

    private suspend fun runPostSyncEnrichmentInternal(coreSyncMessagePrefix: String) {
        withSyncForeground {
            runCatching {
                ensureNotCancelled()
                bindActiveLongTaskJob()
                _state.value = _state.value.copy(
                    isRunning = true,
                    activeItems = emptyList(),
                    progressPhase = SyncProgressPhase.PREFETCH_THUMBNAILS,
                    activityHeadline = "Completando galeria em segundo plano…",
                    activityDetail = "Miniaturas, capas e metadados.",
                )
                seedResidualRunQueueAfterCoreSync()
                drainRemainingRunQueueBeforePrefetch()
                setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Capas dos álbuns…",
                    "Uma miniatura por álbum (pastas e locais), antes de indexar GPS.",
                    alsoUpdateLastMessage = false,
                )
                ensureNotCancelled()
                ensureAlbumCoverThumbnails()
                ensureNotCancelled()
                materializeNonVisualThumbnails()
                ensureNotCancelled()
                setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Miniaturas na galeria…",
                    "Grade, mapa e itens restantes — originais completos sob demanda.",
                    alsoUpdateLastMessage = false,
                )
                val thumbsStillMissing = runCompleteThumbnailPrefetchBeforeMetadata()
                healStalePendingDownloads(remoteByPath = null)
                healAllStaleLocalCopyMetadata()
                setSyncProgress(
                    SyncProgressPhase.BACKFILL_GPS,
                    "Indexando localização (GPS)…",
                    if (thumbsStillMissing > 0) {
                        "~$thumbsStillMissing miniatura(s) seguem em 2º plano · GPS não depende delas."
                    } else {
                        "EXIF local e trechos na nuvem."
                    },
                    alsoUpdateLastMessage = false,
                )
                backfillMissingGpsLocations()
                setSyncProgress(
                    SyncProgressPhase.BACKFILL_CAPTURE_DATE,
                    "Indexando data de captura…",
                    "Ordenação por data na galeria; nuvem limitada por sync.",
                    alsoUpdateLastMessage = false,
                )
                backfillMissingCaptureTimes()
                indexPassLocalItemsById = emptyMap()
                val finalMessage = buildString {
                    append(coreSyncMessagePrefix)
                    val gpsNote = _state.value.lastMessage.takeIf {
                        it.startsWith("Localização GPS:") || it.startsWith("Data de captura:")
                    }
                    if (gpsNote != null) {
                        append("\n")
                        append(gpsNote)
                    }
                    val missingThumb = countIndexedMissingThumbnails()
                    val missingGps = countIndexedMissingGps()
                    if (missingThumb > 0) {
                        append(
                            "\nMiniaturas pendentes: $missingThumb — continuam em 2º plano " +
                                "(tela Sync aberta ou app minimizado).",
                        )
                    }
                    if (missingGps > 0) {
                        append(
                            "\nSem GPS no índice: $missingGps — confira permissão " +
                                "“Localização das mídias” e sincronize de novo.",
                        )
                    }
                }
                val transferPending = syncMetadataDao.countPendingUploadOperations() +
                    syncMetadataDao.countPendingDownloadOperations()
                Triple(finalMessage, syncMetadataDao.countPendingOperations(), transferPending)
            }.onSuccess { (finalMessage, pendingCount, transferPending) ->
                syncPreferences.lastBidirectionalSyncCompletedAtMs = System.currentTimeMillis()
                syncPreferences.cachedPendingTransferCount = transferPending
                _state.value = _state.value.copy(
                    isRunning = false,
                    activeItems = emptyList(),
                    runQueue = emptyList(),
                    pendingOperations = pendingCount,
                    progressPhase = SyncProgressPhase.IDLE,
                    activityHeadline = "",
                    activityDetail = "",
                    lastMessage = finalMessage,
                )
                scheduleThumbnailStatsRefresh(
                    debounceMs = if (appForegroundState.isInForeground()) 30_000L else 0L,
                )
                if (countIndexedMissingThumbnails() > 0) {
                    scheduleBackgroundThumbnailPrefetchIfNeeded()
                }
                if (syncPreferences.cachedPendingTransferCount > 0) {
                    BackgroundSyncScheduler.enqueueDeferredAutoSync(appContext, syncPreferences)
                }
            }.onFailure { error ->
                syncPreferences.lastBidirectionalSyncCompletedAtMs = System.currentTimeMillis()
                if (error is CancellationException) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItems = emptyList(),
                        runQueue = emptyList(),
                        progressPhase = SyncProgressPhase.IDLE,
                        activityHeadline = "",
                        activityDetail = "",
                        lastMessage = cancellationResultMessage(),
                    )
                    scheduleBackgroundThumbnailPrefetchIfNeeded()
                } else {
                    val prefix = coreSyncMessagePrefix
                    _state.value = _state.value.copy(
                        isRunning = false,
                        activeItems = emptyList(),
                        runQueue = emptyList(),
                        progressPhase = SyncProgressPhase.IDLE,
                        activityHeadline = "",
                        activityDetail = "",
                        lastMessage = "$prefix\nPós-sync: ${error.message ?: "erro desconhecido"}.",
                    )
                }
            }
        }
    }

    /** Após cancelar sync/index, completa miniaturas pendentes sem refazer upload/download. */
    private fun scheduleThumbnailPrefetchAfterInterrupt() {
        if (appForegroundState.isInForeground() && !allowForegroundThumbnailPrefetch()) {
            backgroundThumbPrefetchDeferred.set(true)
            return
        }
        val scope = backgroundScope ?: return
        val job = scope.launch {
            if (appForegroundState.isInForeground() && !allowForegroundThumbnailPrefetch()) {
                backgroundThumbPrefetchDeferred.set(true)
                return@launch
            }
            if (syncCancelRequested.get()) return@launch
            if (!longTaskMutex.tryLock()) return@launch
            try {
                if (syncCancelRequested.get()) return@launch
                val rows = buildMissingThumbnailEntities(maxPerRun = THUMB_PREFETCH_BATCH)
                val cached = runMissingThumbnailPrefetch(rows, honorSyncCancel = false)
                if (cached > 0) {
                    val prefix = _state.value.lastMessage.substringBefore("\n")
                    _state.value = _state.value.copy(
                        lastMessage = "$prefix\nMiniaturas: +$cached após interrupção.",
                    )
                }
            } finally {
                longTaskMutex.unlock()
            }
            scheduleBackgroundThumbnailPrefetchIfNeeded()
        }
        registerManagedSyncJob(job)
    }

    /** Continua miniaturas após o sync principal (bibliotecas grandes, ex. dezenas de milhares na nuvem). */
    private fun scheduleBackgroundThumbnailPrefetchIfNeeded() {
        if (appForegroundState.isInForeground() && !allowForegroundThumbnailPrefetch()) {
            backgroundThumbPrefetchDeferred.set(true)
            return
        }
        if (backgroundThumbPrefetchJob?.isActive == true) return
        val scope = backgroundScope ?: return
        backgroundThumbPrefetchJob = scope.launch {
            backgroundThumbPrefetchDeferred.set(false)
            delay(BACKGROUND_THUMB_PREFETCH_INITIAL_DELAY_MS)
            var stalledPasses = 0
            try {
            repeat(THUMB_BACKGROUND_CONTINUE_PASSES) {
                if (appForegroundState.isInForeground() && !allowForegroundThumbnailPrefetch()) {
                    backgroundThumbPrefetchDeferred.set(true)
                    return@launch
                }
                if (_state.value.isRunning) return@launch
                if (countIndexedMissingThumbnails() == 0) return@launch
                if (!longTaskMutex.tryLock()) {
                    delay(BACKGROUND_THUMB_PREFETCH_RETRY_DELAY_MS)
                    return@repeat
                }
                try {
                    val before = countIndexedMissingThumbnails()
                    if (before == 0) return@launch
                    prefetchMissingThumbnails(
                        honorUserSyncCancel = false,
                        maxTotalPerRun = THUMB_BACKGROUND_MAX_TOTAL_PER_PASS,
                        completeAllPending = true,
                    )
                    val after = countIndexedMissingThumbnails()
                    if (after == 0) return@launch
                    if (after >= before) {
                        stalledPasses++
                        if (stalledPasses >= 3) return@launch
                    } else {
                        stalledPasses = 0
                    }
                } finally {
                    longTaskMutex.unlock()
                }
                delay(BACKGROUND_THUMB_PREFETCH_RETRY_DELAY_MS)
            }
            } finally {
                if (!_state.value.isRunning) {
                    _state.update {
                        it.copy(
                            progressPhase = SyncProgressPhase.IDLE,
                            activityHeadline = "",
                            activityDetail = "",
                        )
                    }
                }
            }
        }
    }

    /** Uma leva: no máximo uma miniatura por álbum ainda sem capa (UI usa qualquer thumb local). */
    private suspend fun ensureAlbumCoverThumbnails() {
        ensureNotCancelled()
        val paths = AlbumCoverThumbnailPlanner.coverPathsNeedingThumbnail(
            syncMetadataDao.getAll(),
            mediaCacheRepository,
        )
        if (paths.isEmpty()) return
        val rows = paths.mapNotNull { syncMetadataDao.getByCanonicalPath(it) }
        if (rows.isEmpty()) return
        setRunQueueFromEntities(rows, thumbnailQueue = true)
        setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Capas dos álbuns…",
            "${paths.size} álbum(s) sem capa · 1 miniatura prioritária cada.",
            alsoUpdateLastMessage = false,
        )
        runMissingThumbnailPrefetch(rows, honorSyncCancel = true)
    }

    /**
     * Pós-sync: esgota miniaturas antes de GPS/datas. Retorna quantas ainda faltam (0 = galeria liberada).
     */
    private suspend fun runCompleteThumbnailPrefetchBeforeMetadata(): Int {
        var stalledPasses = 0
        var pass = 0
        while (pass < THUMB_POST_SYNC_OUTER_PASSES) {
            ensureNotCancelled()
            val pending = countIndexedMissingThumbnails()
            if (pending == 0) return 0
            setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Miniaturas na galeria…",
                "~$pending pendente(s) · leva ${pass + 1} — GPS só depois de concluir.",
                alsoUpdateLastMessage = false,
            )
            val before = pending
            prefetchMissingThumbnails(
                completeAllPending = false,
                maxTotalPerRun = THUMB_POST_SYNC_MAX_TOTAL_PER_PASS,
            )
            forcePlaceholderForStubbornMissingThumbnails()
            val after = countIndexedMissingThumbnails()
            if (after == 0) return 0
            if (after >= before - THUMB_STALL_MIN_PROGRESS) {
                stalledPasses++
                if (stalledPasses >= THUMB_POST_SYNC_MAX_STALL_PASSES) return after
            } else {
                stalledPasses = 0
            }
            pass++
        }
        return countIndexedMissingThumbnails()
    }

    /** Miniaturas faltantes em levas; limite por sync para não travar GPS/resto do app. */
    private suspend fun prefetchMissingThumbnails(
        batchSize: Int = THUMB_PREFETCH_BATCH,
        honorUserSyncCancel: Boolean = true,
        maxTotalPerRun: Int = THUMB_PREFETCH_MAX_TOTAL_PER_SYNC,
        completeAllPending: Boolean = false,
    ) {
        val pendingAtStart = countIndexedMissingThumbnails()
        if (pendingAtStart == 0) return
        bulkThumbnailPrefetchDepth.incrementAndGet()
        var totalCached = 0
        var round = 0
        var consecutiveEmptyBatches = 0
        var remainingEstimate = pendingAtStart
        val roundsNeeded = (pendingAtStart + batchSize - 1) / batchSize
        val maxRounds = if (completeAllPending) {
            minOf(
                THUMB_POST_SYNC_COMPLETION_MAX_ROUNDS,
                roundsNeeded + THUMB_PREFETCH_EXTRA_ROUNDS,
            )
        } else {
            minOf(
                THUMB_PREFETCH_MAX_ROUNDS,
                roundsNeeded + THUMB_PREFETCH_EXTRA_ROUNDS,
            )
        }
        val totalCap = if (completeAllPending) Int.MAX_VALUE / 4 else maxTotalPerRun
        val emptyBatchGiveUp = if (completeAllPending) 8 else 5
        var stoppedByCap = false
        try {
            while (round < maxRounds) {
                if (honorUserSyncCancel) {
                    ensureNotCancelled()
                } else {
                    coroutineContext.ensureActive()
                }
                if (totalCached >= totalCap) {
                    stoppedByCap = true
                    break
                }
                if (round == 0 || round % 4 == 0) {
                    remainingEstimate = countIndexedMissingThumbnails()
                }
                if (remainingEstimate == 0) break
                val rows = buildMissingThumbnailEntities(batchSize)
                if (rows.isEmpty()) break
                setRunQueueFromEntities(rows, thumbnailQueue = true)
                if (_state.value.isRunning) {
                    setSyncProgress(
                        SyncProgressPhase.PREFETCH_THUMBNAILS,
                        "Miniaturas na galeria…",
                        "${totalCached.coerceAtLeast(0)} de ~$pendingAtStart · " +
                            "~$remainingEstimate pendente(s) · leva ${round + 1}/$maxRounds.",
                        alsoUpdateLastMessage = false,
                    )
                }
                val batchCached = runMissingThumbnailPrefetch(rows, honorSyncCancel = true)
                totalCached += batchCached
                remainingEstimate = (remainingEstimate - batchCached).coerceAtLeast(0)
                round++
                if (batchCached == 0) {
                    consecutiveEmptyBatches++
                    if (consecutiveEmptyBatches >= emptyBatchGiveUp) break
                } else {
                    consecutiveEmptyBatches = 0
                }
            }
            val remaining = countIndexedMissingThumbnails()
            if (_state.value.isRunning && (totalCached > 0 || remaining > 0 || stoppedByCap)) {
                setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Miniaturas",
                    when {
                        remaining == 0 -> "$totalCached gerada(s)/baixada(s); índice completo."
                        completeAllPending && remaining > 0 ->
                            "$totalCached nesta leva · ~$remaining ainda — continuando antes do GPS…"
                        stoppedByCap ->
                            "$totalCached nesta sync · ~$remaining ainda pendentes " +
                                "(limite por passagem — sincronize de novo para continuar)."
                        else -> "$totalCached nesta sync · $remaining ainda sem miniatura (falha ou cancelamento)."
                    },
                )
            }
            flushPendingAlbumCoverRevisions()
            bulkGalleryDisplayRefreshJob?.cancel()
            bulkGalleryDisplayRefreshJob = null
            if (totalCached > 0) {
                notifyGalleryDisplayRefresh()
            }
            forcePlaceholderForStubbornMissingThumbnails()
            refreshThumbnailCacheStats(force = true)
        } finally {
            bulkThumbnailPrefetchDepth.decrementAndGet()
        }
    }

    /** Itens que a nuvem não decodifica ficam com placeholder — encerra o pós-sync. */
    private suspend fun forcePlaceholderForStubbornMissingThumbnails() {
        val stubborn = buildMissingThumbnailEntities(maxPerRun = THUMB_STUBBORN_PLACEHOLDER_CAP)
        if (stubborn.isEmpty()) return
        stubborn.forEach { row ->
            persistMinimalPlaceholderThumbnail(row)
        }
        if (stubborn.isNotEmpty()) {
            notifyGalleryDisplayRefresh()
        }
    }

    private suspend fun runMissingThumbnailPrefetch(
        rows: List<SyncMetadataEntity>,
        honorSyncCancel: Boolean,
    ): Int {
        if (rows.isEmpty()) return 0
        val semaphore = Semaphore(PARALLEL_THUMBNAILS)
        val completed = AtomicInteger(0)
        val cachedCount = AtomicInteger(0)
        return supervisorScope {
            rows.map { row ->
                async {
                    semaphore.withPermit {
                        try {
                            if (honorSyncCancel) {
                                coroutineContext.ensureActive()
                                ensureNotCancelled()
                            }
                            upsertActiveSyncItem(
                                ActiveSyncItem(
                                    canonicalPath = row.canonicalPath,
                                    displayName = row.displayName,
                                    operationLabel = thumbnailOperationLabel(row),
                                ),
                                force = true,
                            )
                            ensureThumbnailCached(
                                row.canonicalPath,
                                activeSyncProgressCallback(row, thumbnailOperationLabel(row)),
                            )
                            var hit = mediaCacheRepository.thumbnailIfCached(
                                row.canonicalPath,
                                row.oneDriveItemId,
                            ) != null
                            if (!hit) {
                                val attempts = thumbPrefetchAttemptsThisSession.merge(
                                    row.canonicalPath,
                                    1,
                                ) { left, _ -> left + 1 } ?: 1
                                if (attempts >= 2) {
                                    hit = persistMinimalPlaceholderThumbnail(row) != null
                                }
                            } else {
                                thumbPrefetchAttemptsThisSession.remove(row.canonicalPath)
                            }
                            if (hit) cachedCount.incrementAndGet()
                            val done = completed.incrementAndGet()
                            if (_state.value.isRunning &&
                                (done == rows.size || done % 16 == 0)
                            ) {
                                setSyncProgress(
                                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                                    "Gerando e baixando miniaturas…",
                                    "$done/${rows.size} nesta leva · " +
                                        "${cachedCount.get()} salvas em cache · " +
                                        "até $PARALLEL_THUMBNAILS em paralelo.",
                                    alsoUpdateLastMessage = false,
                                )
                            }
                            if (hit) 1 else 0
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            0
                        } finally {
                            removeActiveSyncItem(row.canonicalPath)
                            removeFromRunQueue(row.canonicalPath)
                        }
                    }
                }
            }.awaitAll().sum()
        }
    }

    private suspend fun backfillMissingGpsLocations(
        maxRounds: Int = GPS_BACKFILL_ROUNDS,
        resetCursor: Boolean = false,
    ) {
        if (resetCursor) {
            syncPreferences.gpsBackfillCursor = 0
        }
        var totalIndexed = 0
        var hitLimit = false
        repeat(maxRounds) {
            if (countIndexedMissingGps() == 0) return@repeat
            val outcome = backfillMissingGpsLocationsOnce(
                maxLocalProbesPerRun = GPS_LOCAL_BATCH,
                maxCloudProbesPerRun = GPS_CLOUD_BATCH,
            )
            totalIndexed += outcome.indexed
            hitLimit = hitLimit || outcome.hitProbeLimit
            if (!outcome.hitProbeLimit && outcome.probesAttempted == 0) return@repeat
        }
        if (totalIndexed > 0) {
            val remaining = countIndexedMissingGps()
            val suffix = when {
                remaining > 0 && hitLimit ->
                    " · $remaining ainda sem GPS (sincronize de novo ou verifique permissão de mídia)."
                remaining > 0 ->
                    " · $remaining sem GPS nos arquivos ou na nuvem."
                else -> "."
            }
            setSyncProgress(
                SyncProgressPhase.BACKFILL_GPS,
                "Localização GPS",
                "$totalIndexed item(ns) indexado(s)$suffix",
            )
        }
    }

    private data class GpsBackfillBatchOutcome(
        val indexed: Int,
        val hitProbeLimit: Boolean,
        val probesAttempted: Int,
    )

    private data class GpsBackfillWorkItem(
        val row: SyncMetadataEntity,
        val probeKind: GpsBackfillProbeKind,
    )

    /** Avança o cursor só pelos itens visitados; enche as cotas local/nuvem sem pular faixas. */
    private fun planGpsBackfillWork(
        sorted: List<SyncMetadataEntity>,
        startCursor: Int,
        maxLocalProbesPerRun: Int,
        maxCloudProbesPerRun: Int,
    ): Pair<List<GpsBackfillWorkItem>, Int> {
        if (sorted.isEmpty()) return emptyList<GpsBackfillWorkItem>() to 0
        var cursor = startCursor % sorted.size
        var steps = 0
        var localSlots = 0
        var cloudSlots = 0
        val work = ArrayList<GpsBackfillWorkItem>(
            (maxLocalProbesPerRun + maxCloudProbesPerRun).coerceAtMost(sorted.size),
        )
        while (
            steps < sorted.size &&
            (localSlots < maxLocalProbesPerRun || cloudSlots < maxCloudProbesPerRun)
        ) {
            val row = sorted[cursor]
            cursor = (cursor + 1) % sorted.size
            steps++
            val hasLocal = row.localMediaId != null ||
                !row.localContentUri.isNullOrBlank() ||
                !row.cachedFilePath.isNullOrBlank()
            when {
                hasLocal && localSlots < maxLocalProbesPerRun -> {
                    localSlots++
                    work.add(GpsBackfillWorkItem(row, GpsBackfillProbeKind.LOCAL))
                }
                row.oneDriveItemId != null && cloudSlots < maxCloudProbesPerRun -> {
                    cloudSlots++
                    work.add(GpsBackfillWorkItem(row, GpsBackfillProbeKind.CLOUD))
                }
            }
            if (localSlots >= maxLocalProbesPerRun && cloudSlots >= maxCloudProbesPerRun) break
        }
        return work to cursor
    }

    private suspend fun backfillMissingGpsLocationsOnce(
        maxLocalProbesPerRun: Int,
        maxCloudProbesPerRun: Int,
    ): GpsBackfillBatchOutcome {
        val rows = syncMetadataDao.getAll().filter { row ->
            row.latitude == null && row.longitude == null &&
                (
                    row.localMediaId != null || row.localContentUri != null ||
                        row.cachedFilePath != null || row.oneDriveItemId != null
                    )
        }
        if (rows.isEmpty()) return GpsBackfillBatchOutcome(0, false, 0)
        val sorted = rows.sortedWith(gpsBackfillComparator)
        val startCursor = syncPreferences.gpsBackfillCursor % sorted.size
        val (work, nextCursor) = planGpsBackfillWork(
            sorted = sorted,
            startCursor = startCursor,
            maxLocalProbesPerRun = maxLocalProbesPerRun,
            maxCloudProbesPerRun = maxCloudProbesPerRun,
        )
        syncPreferences.gpsBackfillCursor = nextCursor
        if (work.isEmpty()) return GpsBackfillBatchOutcome(0, false, 0)
        val candidates = work.size
        val semaphore = Semaphore(PARALLEL_GPS_BACKFILL)
        val indexedCount = AtomicInteger(0)
        val analyzedCount = AtomicInteger(0)
        var localProbes = 0
        var cloudProbes = 0
        work.forEach { item ->
            when (item.probeKind) {
                GpsBackfillProbeKind.LOCAL -> localProbes++
                GpsBackfillProbeKind.CLOUD -> cloudProbes++
            }
        }
        supervisorScope {
            work.map { item ->
                async {
                    semaphore.withPermit {
                        try {
                            ensureNotCancelled()
                            coroutineContext.ensureActive()
                            val index = analyzedCount.incrementAndGet()
                            if (index % 16 == 0 && _state.value.isRunning) {
                                setSyncProgress(
                                    SyncProgressPhase.BACKFILL_GPS,
                                    "Indexando localização (GPS)…",
                                    "Analisados $index/$candidates · ${indexedCount.get()} com GPS · " +
                                        "local $localProbes/$maxLocalProbesPerRun · " +
                                        "nuvem $cloudProbes/$maxCloudProbesPerRun · " +
                                        "até $PARALLEL_GPS_BACKFILL em paralelo.",
                                    alsoUpdateLastMessage = false,
                                )
                            }
                            val resolved = when (item.probeKind) {
                                GpsBackfillProbeKind.LOCAL -> resolveLocationForBackfill(item.row)
                                GpsBackfillProbeKind.CLOUD -> probeCloudExifLocation(
                                    item.row.oneDriveItemId!!,
                                    item.row.displayName,
                                )
                            } ?: return@withPermit
                            syncMetadataDao.upsert(
                                item.row.copy(
                                    locationLabel = resolved.label,
                                    latitude = resolved.latitude,
                                    longitude = resolved.longitude,
                                ),
                            )
                            indexedCount.incrementAndGet()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                        }
                    }
                }
            }.awaitAll()
        }
        val indexed = indexedCount.get()
        val probesAttempted = work.size
        val hitLimit = localProbes >= maxLocalProbesPerRun || cloudProbes >= maxCloudProbesPerRun
        return GpsBackfillBatchOutcome(indexed, hitLimit, probesAttempted)
    }

    private enum class GpsBackfillProbeKind {
        LOCAL,
        CLOUD,
    }

    private suspend fun probeCloudExifCaptureTime(
        oneDriveItemId: String,
        fileNameHint: String? = null,
    ): Long? {
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return null
        if (!networkMonitor.isOnline()) return null
        return oneDriveRepository.probeExifCaptureTimeFromItem(
            oneDriveItemId,
            mediaCaptureTimeResolver,
            fileNameHint,
        ).getOrNull()
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
        val candidates = rows.size
        rows.sortedByDescending { it.remoteModifiedAt ?: it.localModifiedAt ?: 0L }
            .forEachIndexed { index, row ->
                if (index % 16 == 0) {
                    ensureNotCancelled()
                    coroutineContext.ensureActive()
                    if (_state.value.isRunning) {
                        setSyncProgress(
                            SyncProgressPhase.BACKFILL_CAPTURE_DATE,
                            "Indexando data de captura…",
                            "Analisados $index/$candidates · $indexed datas encontradas · " +
                                "nuvem $cloudProbes/${maxCloudProbesPerRun}.",
                            alsoUpdateLastMessage = false,
                        )
                    }
                }
                val hasLocal = row.localMediaId != null || row.localContentUri != null || row.cachedFilePath != null
                val resolved = when {
                    hasLocal -> resolveCaptureTimeForBackfill(row)
                    row.cachedFilePath != null -> resolveCaptureTimeForBackfill(row)
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
            setSyncProgress(
                SyncProgressPhase.BACKFILL_CAPTURE_DATE,
                "Data de captura",
                "$indexed item(ns) indexado(s)$suffix",
            )
        }
    }

    private fun scheduleIndexRebuild() {
        pendingIndexRebuild.set(true)
    }

    private suspend fun applyPendingIndexRebuildIfNeeded(): Boolean {
        if (!pendingIndexRebuild.compareAndSet(true, false)) return false
        rebuildSyncMetadataIndex(syncMetadataDao, mediaCacheRepository)
        if (syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
            oneDriveRepository.resetRemoteListingState()
        }
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
            row.oneDriveItemId != null && localOriginalPresent(row) -> CloudSyncState.SYNCED
            row.oneDriveItemId != null -> CloudSyncState.CLOUD_ONLY
            localOriginalPresent(row) -> CloudSyncState.LOCAL_ONLY
            else -> CloudSyncState.LOCAL_ONLY
        }
    }

    /** Nuvem + original abrível (Galeria ou cache verificado no disco). */
    private fun cloudStateAfterVerifiedLocal(row: SyncMetadataEntity, verifiedFile: File): CloudSyncState {
        if (row.oneDriveItemId == null) return row.cloudState
        if (!verifiedFile.isFile || verifiedFile.length() <= 0L) {
            return if (localOriginalPresent(row)) CloudSyncState.SYNCED else CloudSyncState.CLOUD_ONLY
        }
        return CloudSyncState.SYNCED
    }

    /**
     * Limpa [localMediaId], [localContentUri] e [cachedFilePath] que apontam para arquivos inexistentes
     * e corrige [cloudState] SYNCED fantasma (índice diz local/cache, aparelho não tem).
     */
    private suspend fun healAllStaleLocalCopyMetadata() {
        val rows = syncMetadataDao.getAll()
        if (rows.isEmpty()) return
        val updates = mutableListOf<SyncMetadataEntity>()
        for (row in rows) {
            val healed = healStaleLocalCopyMetadataRow(row)
            if (healed != row) updates.add(healed)
        }
        if (updates.isEmpty()) return
        updates.chunked(ROOM_UPSERT_CHUNK).forEach { chunk ->
            syncMetadataDao.upsertAll(chunk)
        }
        notifyGalleryDisplayRefresh()
    }

    private suspend fun healAndPersistStaleLocalCopyMetadata(row: SyncMetadataEntity): SyncMetadataEntity {
        val healed = healStaleLocalCopyMetadataRow(row)
        if (healed != row) {
            syncMetadataDao.upsert(healed)
        }
        return healed
    }

    private fun healStaleLocalCopyMetadataRow(row: SyncMetadataEntity): SyncMetadataEntity {
        var work = row
        var dirty = false

        if (work.localReleasedForSpace) {
            if (work.localMediaId != null || !work.localContentUri.isNullOrBlank()) {
                work = work.copy(localMediaId = null, localContentUri = null)
                dirty = true
            }
            if (!work.cachedFilePath.isNullOrBlank() && resolveViewableOriginalFile(work) == null) {
                work = work.copy(cachedFilePath = null)
                dirty = true
            }
            if (work.oneDriveItemId != null && work.cloudState != CloudSyncState.CLOUD_ONLY) {
                work = work.copy(cloudState = CloudSyncState.CLOUD_ONLY, lastError = null)
                dirty = true
            }
            return if (dirty) work else row
        }

        if (work.localMediaId != null && !localOriginalPresent(work)) {
            work = work.copy(localMediaId = null, localContentUri = null)
            dirty = true
        } else if (!work.localContentUri.isNullOrBlank()) {
            val uri = Uri.parse(work.localContentUri)
            if (!mediaDeleteRequestRouter.uriExists(appContext, uri)) {
                work = work.copy(localContentUri = null)
                dirty = true
            }
        }

        val viewableCache = resolveViewableOriginalFile(work)
        if (!work.cachedFilePath.isNullOrBlank() && viewableCache == null) {
            work = work.copy(cachedFilePath = null)
            dirty = true
        } else if (work.cachedFilePath.isNullOrBlank() && viewableCache != null) {
            work = work.copy(cachedFilePath = viewableCache.absolutePath)
            dirty = true
        }

        val hasLocal = localOriginalPresent(work)
        val hasCache = resolveViewableOriginalFile(work) != null
        if (work.oneDriveItemId != null &&
            work.cloudState == CloudSyncState.SYNCED &&
            !hasLocal &&
            !hasCache &&
            work.pendingDeleteTarget == PendingDeleteTarget.NONE
        ) {
            work = work.copy(cloudState = CloudSyncState.CLOUD_ONLY, lastError = null)
            dirty = true
        }

        return if (dirty) work else row
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

    private data class RemoteListingSnapshot(
        val files: List<RemoteDriveFile>,
        val incremental: Boolean,
        val changedCount: Int,
        val unresolvedDeltaSkips: Int = 0,
        val supplementalFromRecursive: Int = 0,
    )

    private fun remoteBaselineFromIndex(rows: List<SyncMetadataEntity>): List<RemoteDriveFile> {
        return rows.mapNotNull { row ->
            val remoteId = row.oneDriveItemId ?: return@mapNotNull null
            RemoteDriveFile(
                itemId = remoteId,
                name = row.displayName,
                canonicalPath = row.canonicalPath,
                modifiedAtEpoch = row.remoteModifiedAt,
                sizeBytes = row.remoteSizeBytes,
                quickXorHash = row.remoteQuickXorHash,
            )
        }
    }

    private suspend fun listRemoteFiles(): Result<RemoteListingSnapshot> {
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.listMirrorFiles().map { files ->
                RemoteListingSnapshot(files = files, incremental = false, changedCount = files.size)
            }
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.listMirrorFiles().map { files ->
                RemoteListingSnapshot(files = files, incremental = false, changedCount = files.size)
            }
            SyncMode.ONEDRIVE_GRAPH -> {
                val backend = cloudSyncBackendRegistry.activeApiBackend()
                    ?: return Result.failure(IllegalStateException("Backend de nuvem indisponível"))
                val baseline = remoteBaselineFromIndex(syncMetadataDao.getAll())
                    .map { it.toRemoteCloudFile() }
                backend.listRemoteFiles(baseline).map { listing ->
                    RemoteListingSnapshot(
                        files = listing.files.map { it.toRemoteDriveFile() },
                        incremental = listing.incremental,
                        changedCount = listing.deltaChangeCount,
                        unresolvedDeltaSkips = listing.unresolvedDeltaSkips,
                        supplementalFromRecursive = listing.supplementalFromRecursive,
                    )
                }
            }
            SyncMode.LOCAL_ONLY -> Result.success(
                RemoteListingSnapshot(files = emptyList(), incremental = false, changedCount = 0),
            )
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
                val job = coroutineContext[Job]
                { done, total ->
                    if (shouldCancelActiveSyncTransfer(job)) {
                        job?.cancel(CancellationException("Sincronismo cancelado."))
                    } else {
                        upsertActiveSyncItem(
                            ActiveSyncItem(
                                canonicalPath = canonicalPath,
                                displayName = displayName,
                                operationLabel = operationLabel,
                                bytesCompleted = done,
                                totalBytes = total,
                            ),
                        )
                    }
                }
            } else {
                null
            }
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.uploadLocalFile(canonicalPath, sourceFile, mimeType)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.uploadLocalFile(canonicalPath, sourceFile, mimeType)
            SyncMode.ONEDRIVE_GRAPH -> {
                val backend = cloudSyncBackendRegistry.activeApiBackend()
                    ?: return Result.failure(IllegalStateException("Backend de nuvem indisponível"))
                backend.uploadLocalFile(canonicalPath, sourceFile, mimeType, onProgress)
            }
            SyncMode.LOCAL_ONLY -> Result.failure(IllegalStateException("Sem nuvem"))
        }
    }

    private suspend fun downloadRemoteFile(
        remoteId: String,
        target: File,
        canonicalPath: String,
        displayName: String,
        operationLabel: String,
        honorSyncCancel: Boolean = true,
        updateSyncUi: Boolean = true,
        useSimpleStreamDownload: Boolean = false,
    ): Result<Unit> {
        val onProgress: ((Long, Long?) -> Unit)? =
            if (updateSyncUi && syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH) {
                val job = coroutineContext[Job]
                { done, total ->
                    if (honorSyncCancel && shouldCancelActiveSyncTransfer(job)) {
                        job?.cancel(CancellationException("Sincronismo cancelado."))
                    } else {
                        upsertActiveSyncItem(
                            ActiveSyncItem(
                                canonicalPath = canonicalPath,
                                displayName = displayName,
                                operationLabel = operationLabel,
                                bytesCompleted = done,
                                totalBytes = total,
                            ),
                        )
                    }
                }
            } else {
                null
            }
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.downloadToFile(remoteId, target)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.downloadToFile(remoteId, target)
            SyncMode.ONEDRIVE_GRAPH -> {
                val backend = cloudSyncBackendRegistry.activeApiBackend()
                    ?: return Result.failure(IllegalStateException("Backend de nuvem indisponível"))
                if (useSimpleStreamDownload) {
                    backend.downloadItemContentToFile(remoteId, target)
                } else {
                    backend.downloadToFile(remoteId, target, onProgress)
                }
            }
            SyncMode.LOCAL_ONLY -> Result.failure(IllegalStateException("Sem nuvem"))
        }
    }

    private suspend fun deleteRemoteFile(remoteId: String): Result<Unit> {
        return when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> localMirrorRepository.deleteRemote(remoteId)
            SyncMode.ONEDRIVE_FOLDER -> saOneDriveRepository.deleteRemote(remoteId)
            SyncMode.ONEDRIVE_GRAPH -> {
                val backend = cloudSyncBackendRegistry.activeApiBackend()
                    ?: return Result.failure(IllegalStateException("Backend de nuvem indisponível"))
                backend.deleteRemoteItem(remoteId)
            }
            SyncMode.LOCAL_ONLY -> Result.success(Unit)
        }
    }

    private companion object {
        const val GALLERY_METADATA_DEBOUNCE_MS = 450L
        const val THUMB_CACHE_MAX_SIDE_PX = 1280
        const val THUMB_CACHE_JPEG_QUALITY = 88
        /** Itens por leva no pós-sync (miniaturas locais + download na nuvem). */
        const val THUMB_PREFETCH_BATCH = 512
        const val THUMB_PREFETCH_EXTRA_ROUNDS = 12
        const val THUMB_PREFETCH_MAX_ROUNDS = 48
        const val THUMB_PREFETCH_MAX_TOTAL_PER_SYNC = 6_000
        const val THUMB_POST_SYNC_COMPLETION_MAX_ROUNDS = 96
        const val THUMB_POST_SYNC_OUTER_PASSES = 2
        const val THUMB_POST_SYNC_MAX_STALL_PASSES = 2
        /** Por passagem do pós-sync: encerra sync cedo; resto em 2º plano + próximo sync. */
        const val THUMB_POST_SYNC_MAX_TOTAL_PER_PASS = 3_000
        const val THUMB_STALL_MIN_PROGRESS = 32
        const val THUMB_BACKGROUND_MAX_TOTAL_PER_PASS = 4_000
        const val THUMB_BACKGROUND_CONTINUE_PASSES = 24
        const val BACKGROUND_THUMB_PREFETCH_INITIAL_DELAY_MS = 2_500L
        const val BACKGROUND_THUMB_PREFETCH_RETRY_DELAY_MS = 4_000L
        const val THUMB_MAX_FAILURE_ATTEMPTS = 2
        const val THUMB_STUBBORN_PLACEHOLDER_CAP = 64
        const val ACTIVE_ITEM_PROGRESS_MIN_INTERVAL_MS = 280L
        const val ALBUM_COVER_REVISION_COALESCE_MS = 280L
        const val GALLERY_DISPLAY_REFRESH_COALESCE_MS = 5_000L
        const val THUMBNAIL_STATS_MIN_REFRESH_MS = 8_000L
        const val GPS_BACKFILL_ROUNDS = 8
        /** Indexação manual (mapa): varre a biblioteca inteira em várias levas. */
        const val GPS_BACKFILL_MANUAL_ROUNDS = 48
        const val GPS_LOCAL_BATCH = 500
        const val GPS_CLOUD_BATCH = 500
        const val PARALLEL_UPLOADS = 3
        /** Downloads Graph / decode local em paralelo por leva. */
        const val PARALLEL_THUMBNAILS = 5
        const val PARALLEL_GPS_BACKFILL = 3
        const val ROOM_UPSERT_CHUNK = 64
        const val LOCAL_ACCESS_WRITE_THROTTLE_MS = 60_000L
    }
}

data class SyncSummary(
    val uploaded: Int,
    val pendingDownloadPlaceholders: Int,
    val cloudOnly: Int,
)
