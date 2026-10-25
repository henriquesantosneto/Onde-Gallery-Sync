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
import com.onegallery.sync.ui.gallery.GalleryPreferences
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
import com.onegallery.sync.data.onedrive.CloudThumbnailStaging
import com.onegallery.sync.data.onedrive.CloudVideoThumbnailLoader
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.data.onedrive.RemoteDriveFile
import com.onegallery.sync.data.onedrive.LocalMirrorRepository
import com.onegallery.sync.data.onedrive.SaOneDriveRepository
import com.onegallery.sync.R
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    /** Total no índice (miniaturas/GPS); a lista pode estar limitada a 1500 linhas. */
    val runQueueGlobalPending: Int? = null,
    /** Contagem de miniaturas (atualizada após sync / prefetch e ao abrir Sync). */
    val thumbnailStats: ThumbnailCacheStats? = null,
    /** Prefetch de miniaturas (sync ou 2º plano com tela Sync aberta). */
    val prefetchingThumbnails: Boolean = false,
    /** Jobs gerenciados / prefetch em 2º plano ainda sem flag de UI. */
    val extraCancellableWork: Boolean = false,
) {
    /** Qualquer operação longa do sync — bloqueia “Sincronizar agora” e mostra Parar. */
    val isSyncBusy: Boolean
        get() = isRunning || prefetchingThumbnails || extraCancellableWork

    /** Linha principal na aba Ajustes → Sync (evita “Pronto…” durante prefetch). */
    fun settingsSyncStatusHeadline(): String {
        if (!isSyncBusy) {
            return lastMessage.lineSequence().firstOrNull()?.trim().orEmpty()
        }
        return activityHeadline.ifBlank {
            when {
                prefetchingThumbnails && progressPhase == SyncProgressPhase.IDLE ->
                    "Completando miniaturas no cache…"
                else -> progressPhase.runningHeadlineFallback()
            }
        }
    }
}

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
    private val userStopHooks = CopyOnWriteArrayList<() -> Unit>()
    private val pendingIndexRebuild = AtomicBoolean(false)
    /** Cache da passagem atual de indexação (evita re-varrer MediaStore no backfill). */
    private var indexPassLocalItemsById: Map<Long, LocalMediaItem> = emptyMap()
    private val longTaskMutex = Mutex()
    private val onDemandOriginalMutexes = ConcurrentHashMap<String, Mutex>()
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
    private val galleryThumbnailService = GalleryThumbnailService(
        appContext = appContext,
        syncMetadataDao = syncMetadataDao,
        mediaCacheRepository = mediaCacheRepository,
        oneDriveRepository = oneDriveRepository,
        localMediaRepository = localMediaRepository,
        syncPreferences = syncPreferences,
        networkMonitor = networkMonitor,
        mediaDeleteRequestRouter = mediaDeleteRequestRouter,
        localIndexPassItems = { indexPassLocalItemsById },
    )
    private val galleryThumbnailIndex = GalleryThumbnailIndex(
        syncMetadataDao = syncMetadataDao,
        syncPreferences = syncPreferences,
        thumbnails = galleryThumbnailService,
        thumbPrefetchAttemptsThisSession = thumbPrefetchAttemptsThisSession,
    )
    private val thumbnailSyncHost = SyncCoordinatorThumbnailSyncHost()
    private val thumbnailSyncPipeline = ThumbnailSyncPipeline(
        syncMetadataDao = syncMetadataDao,
        mediaCacheRepository = mediaCacheRepository,
        thumbnails = galleryThumbnailService,
        host = thumbnailSyncHost,
    )
    private val thumbnailPrefetchRunner = ThumbnailPrefetchRunner(thumbnailSyncHost)
    private val thumbnailPostSyncEngine = ThumbnailPostSyncEngine(thumbnailSyncHost, thumbnailPrefetchRunner)

    val thumbnailCache: GalleryThumbnailCache
        get() = galleryThumbnailService
    /** Retriever/loadThumbnail bloqueiam — no máximo 2 threads dedicadas na fila. */
    private val thumbnailPrefetchBlocking = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
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
        backgroundScope?.launch(Dispatchers.IO) {
            syncMetadataDao.observeIndexStats(indexStatsSqlQuery).collect { row ->
                _syncIndexStats.value = row
                syncPreferences.saveCachedIndexStats(row)
            }
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

    /** Interrompe sync, miniaturas, indexação e outros jobs gerenciados. */
    fun requestStop() {
        cancelActiveSync(
            SyncCancelReason.USER,
            applyUserCooldown = true,
            inProgressMessage = "Parando…",
        )
    }

    fun requestCancelSync() = requestStop()

    fun registerUserStopHook(hook: () -> Unit) {
        userStopHooks.add(hook)
    }

    fun unregisterUserStopHook(hook: () -> Unit) {
        userStopHooks.remove(hook)
    }

    private fun fireUserStopHooks() {
        userStopHooks.forEach { hook -> runCatching(hook) }
    }

    private fun refreshExtraCancellableWork() {
        val extra =
            (backgroundThumbPrefetchJob?.isActive == true) ||
                managedSyncJobs.any { it.isActive } ||
                (
                    activeLongTaskJob.get()?.isActive == true &&
                        !_state.value.isRunning &&
                        !_state.value.prefetchingThumbnails
                    )
        _state.update { state ->
            if (state.extraCancellableWork == extra) state
            else state.copy(extraCancellableWork = extra)
        }
    }

    /** Jobs de sync/index lançados fora do mutex (fila, galeria, WorkManager). */
    fun registerManagedSyncJob(job: Job) {
        managedSyncJobs.add(job)
        refreshExtraCancellableWork()
        job.invokeOnCompletion {
            managedSyncJobs.remove(job)
            refreshExtraCancellableWork()
        }
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
        fireUserStopHooks()
        syncCancelReason.set(reason)
        syncCancelRequested.set(true)
        if (applyUserCooldown) {
            syncPreferences.pauseAutoSyncAfterUserCancel()
        }
        BackgroundSyncScheduler.cancelPendingSyncWork(appContext)
        stopThumbnailPrefetchForCancel(inProgressMessage)
        _state.update {
            it.copy(
                isRunning = false,
                progressPhase = SyncProgressPhase.IDLE,
                activityHeadline = inProgressMessage,
                activityDetail = "",
                runQueue = emptyList(),
                activeItems = emptyList(),
            )
        }
        cancelAllManagedSyncJobs(inProgressMessage)
        bulkGalleryDisplayRefreshJob?.cancel()
        val job = activeLongTaskJob.get()
        if (job != null) {
            job.cancel(CancellationException(inProgressMessage))
        }
        refreshExtraCancellableWork()
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

    /** Para prefetch de miniaturas ao cancelar — evita isSyncBusy preso e fila fantasma. */
    private fun stopThumbnailPrefetchForCancel(inProgressMessage: String) {
        backgroundThumbPrefetchJob?.cancel()
        backgroundThumbPrefetchJob = null
        while (bulkThumbnailPrefetchDepth.get() > 0) {
            bulkThumbnailPrefetchDepth.decrementAndGet()
        }
        clearRunQueueAndActiveTransfers()
        _state.update {
            it.copy(
                prefetchingThumbnails = false,
                progressPhase = SyncProgressPhase.IDLE,
                activityHeadline = inProgressMessage,
                activityDetail = "",
                lastMessage = if (syncCancelReason.get() == SyncCancelReason.USER) {
                    autoSyncPausedMessage("Operação interrompida.")
                } else {
                    "Operação interrompida."
                },
            )
        }
        refreshExtraCancellableWork()
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
            SyncCancelReason.USER -> autoSyncPausedMessage("Operação cancelada.")
            SyncCancelReason.NONE -> autoSyncPausedMessage("Operação cancelada.")
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
        job.invokeOnCompletion {
            activeLongTaskJob.compareAndSet(job, null)
            refreshExtraCancellableWork()
        }
        refreshExtraCancellableWork()
    }

    private fun prepareSyncSession(userInitiated: Boolean) {
        if (userInitiated) {
            resetSyncCancel()
            syncPreferences.clearAllThumbnailFailureCounts()
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
                            listRemoteFiles(userInitiated = userInitiated).getOrThrow().files.map { it.canonicalPath }.toSet()
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
    private val indexStatsSqlQuery =
        androidx.sqlite.db.SimpleSQLiteQuery(GalleryMapper.buildSyncIndexStatsSql())
    private val _syncIndexStats = MutableStateFlow<SyncIndexStatsRow?>(
        syncPreferences.cachedIndexStats(),
    )
    val syncIndexStats: StateFlow<SyncIndexStatsRow?> = _syncIndexStats.asStateFlow()
    val syncAttentionItems: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observeAttentionItems()
    val userAlbums: Flow<List<UserAlbumEntity>> = userAlbumDao.observeAll()

    private val _galleryDisplayRefreshEpoch = MutableStateFlow(0L)
    val galleryDisplayRefreshEpoch: StateFlow<Long> = _galleryDisplayRefreshEpoch.asStateFlow()

    private val _albumCoverRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val albumCoverRevisions: StateFlow<Map<String, Long>> = _albumCoverRevisions.asStateFlow()

    @Volatile
    private var pendingGalleryDisplayRefresh: Boolean = false

    fun notifyGalleryDisplayRefresh() {
        if (com.onegallery.sync.ui.gallery.GalleryGridScrollGate.shouldDeferCatalogThumbnailRefresh()) {
            pendingGalleryDisplayRefresh = true
            return
        }
        pendingGalleryDisplayRefresh = false
        _galleryDisplayRefreshEpoch.value = System.nanoTime()
    }

    fun flushPendingGalleryDisplayRefreshIfAny() {
        if (!pendingGalleryDisplayRefresh) return
        pendingGalleryDisplayRefresh = false
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

    private fun galleryPreferences(): GalleryPreferences = GalleryPreferences(appContext)

    /** Modo agressivo: prefetch forte em 1º plano (grade só com miniatura ou opção explícita). */
    private fun allowForegroundThumbnailPrefetch(): Boolean {
        if (syncStatusScreenVisibleCount.get() > 0) return true
        if (syncPreferences.aggressiveThumbnailPrefetch) return true
        return galleryPreferences().browseOnlyItemsWithThumbnail
    }

    /**
     * Prefetch pós-sync: em modo agressivo continua com app aberto; senão só tela Sync ou 2º plano.
     */
    fun onAppUiVisibilityChanged(inForeground: Boolean) {
        if (inForeground) {
            kickThumbnailPrefetchIfIdle()
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

    private fun shouldPublishSyncActivityUi(): Boolean =
        _state.value.isRunning || _state.value.prefetchingThumbnails

    private fun refreshThumbnailPrefetchUiFlag() {
        val active = bulkThumbnailPrefetchDepth.get() > 0
        val current = _state.value
        if (current.prefetchingThumbnails == active &&
            (!active || current.isRunning || current.progressPhase != SyncProgressPhase.IDLE ||
                current.activityHeadline.isNotBlank())
        ) {
            return
        }
        _state.update { state ->
            if (!active) {
                state.copy(
                    prefetchingThumbnails = false,
                    progressPhase = if (state.isRunning) state.progressPhase else SyncProgressPhase.IDLE,
                    activityHeadline = if (state.isRunning) state.activityHeadline else "",
                    activityDetail = if (state.isRunning) state.activityDetail else "",
                )
            } else {
                state.copy(
                    prefetchingThumbnails = true,
                    progressPhase = if (state.isRunning) {
                        state.progressPhase
                    } else {
                        SyncProgressPhase.PREFETCH_THUMBNAILS
                    },
                    activityHeadline = state.activityHeadline.ifBlank {
                        if (state.isRunning) "" else "Completando miniaturas no cache…"
                    },
                )
            }
        }
    }

    private suspend inline fun <T> withThumbnailPrefetchUi(block: suspend () -> T): T {
        bulkThumbnailPrefetchDepth.incrementAndGet()
        refreshThumbnailPrefetchUiFlag()
        try {
            return block()
        } finally {
            bulkThumbnailPrefetchDepth.decrementAndGet()
            refreshThumbnailPrefetchUiFlag()
        }
    }

    private fun setRunQueueFromEntities(
        entities: List<SyncMetadataEntity>,
        thumbnailQueue: Boolean = false,
        gpsQueue: Boolean = false,
        runQueueGlobalPending: Int? = null,
    ) {
        if (!shouldPublishSyncActivityUi()) return
        val rows = entities.map { entity ->
            when {
                thumbnailQueue -> SyncQueueDisplayRow(
                    canonicalPath = entity.canonicalPath,
                    displayName = entity.displayName,
                    statusLabel = GalleryThumbnailLabels.operationLabel(entity),
                )
                gpsQueue -> SyncQueueDisplayRow(
                    canonicalPath = entity.canonicalPath,
                    displayName = entity.displayName,
                    statusLabel = gpsQueueStatusLabel(entity),
                )
                else -> SyncQueueDisplayRow.fromEntity(entity)
            }
        }
        val apply = {
            _state.update { state ->
                state.copy(
                    runQueue = rows,
                    runQueueGlobalPending = runQueueGlobalPending ?: state.runQueueGlobalPending,
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post(apply)
    }

    private fun removeFromRunQueue(canonicalPath: String) {
        val prev = _state.value.runQueue
        val next = prev.filter { it.canonicalPath != canonicalPath }
        if (next.size != prev.size) {
            val apply = {
                _state.update { state ->
                    val global = state.runQueueGlobalPending?.let { pending ->
                        (pending - 1).coerceAtLeast(0)
                    }
                    state.copy(runQueue = next, runQueueGlobalPending = global)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post(apply)
        }
    }

    private fun clearRunQueueOnly() {
        val apply = {
            _state.update { it.copy(runQueue = emptyList(), runQueueGlobalPending = null) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post(apply)
    }

    /** Evita UI presa com 1–2 miniaturas após timeout ou ao seguir para GPS. */
    private fun clearRunQueueAndActiveTransfers() {
        clearActiveSyncItems()
        clearRunQueueOnly()
    }

    private fun markSyncProcessingItem(
        row: SyncMetadataEntity,
        operationLabel: String,
        showTransferProgress: Boolean = false,
    ) {
        if (!shouldPublishSyncActivityUi()) return
        upsertActiveSyncItem(
            ActiveSyncItem(
                canonicalPath = row.canonicalPath,
                displayName = row.displayName,
                operationLabel = operationLabel,
                showTransferProgress = showTransferProgress,
            ),
            force = true,
        )
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
                    kickLocalMediaThumbnailPrefetchDuringSync()
                    ensureSyncMayContinue()
                    setSyncProgress(
                        SyncProgressPhase.LISTING_REMOTE,
                        "Listando pasta no OneDrive…",
                        oneDriveRepository.syncRootLabel()?.let { "Pasta: $it" }
                            ?: "Aguarde — pode demorar com bibliotecas grandes.",
                    )
                    ensureNotCancelled()
                    val indexBeforeRemote = syncMetadataDao.countAll()
                    val remoteListing = listRemoteFiles(userInitiated = userInitiated).getOrThrow()
                    ensureNotCancelled()
                    val remoteFiles = remoteListing.files
                    if (syncPreferences.forceRemoteFullListingOnce &&
                        (
                            remoteFiles.size > indexBeforeRemote + 32 ||
                                remoteListing.supplementalFromRecursive > 0
                            )
                    ) {
                        syncPreferences.forceRemoteFullListingOnce = false
                    }
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
                        var keepInRunQueue = false
                        markSyncProcessingItem(row, "Comparando com a nuvem…")
                        try {
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
                                    keepInRunQueue = true
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
                                            keepInRunQueue = true
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
                        } finally {
                            removeActiveSyncItem(row.canonicalPath)
                            if (!keepInRunQueue) {
                                removeFromRunQueue(row.canonicalPath)
                            }
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
                    val uploadBatchSize = uploadQueue.size
                    if (uploadBatchSize > 0) {
                        setRunQueueFromEntities(
                            uploadQueue.sortedWith(syncQueueEntityComparator),
                            runQueueGlobalPending = uploadBatchSize,
                        )
                    } else {
                        clearRunQueueOnly()
                    }
                    uploaded += runParallelUploads(uploadQueue)
                    uploaded += applyPendingUploadRetries(remoteByPath)
                    healStalePendingUploads(remoteByPath)
                    clearRunQueueAndActiveTransfers()
                    val uploadStillPending = syncMetadataDao.countPendingUploadOperations()
                    setSyncProgress(
                        SyncProgressPhase.RECONCILING_LOCAL,
                        if (uploadStillPending == 0) "Envio concluído" else "Envio desta leva concluído",
                        buildString {
                            append("$uploaded de $uploadBatchSize enviado(s) nesta execução")
                            if (uploadStillPending > 0) {
                                append(" · $uploadStillPending ainda aguardando (próximo sync ou rede)")
                            }
                            append(" · registrando itens só na nuvem…")
                        },
                        alsoUpdateLastMessage = false,
                    )

                    val cloudOnlyCandidates = remoteByPath.keys.count { it !in knownPaths }
                    var cloudOnlyProcessed = 0
                    val cloudOnlyInserts = mutableListOf<SyncMetadataEntity>()
                    val cloudOnlyUpdates = mutableListOf<SyncMetadataEntity>()
                    remoteByPath.forEach { (path, remote) ->
                        ensureSyncMayContinue()
                        if (path in knownPaths) return@forEach
                        cloudOnlyProcessed++
                        if (_state.value.isRunning) {
                            upsertActiveSyncItem(
                                ActiveSyncItem(
                                    canonicalPath = path,
                                    displayName = remote.name,
                                    operationLabel = "Registrando só na nuvem…",
                                ),
                                force = true,
                            )
                        }
                        try {
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
                        } finally {
                            removeActiveSyncItem(path)
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
                    progressPhase = SyncProgressPhase.BACKFILL_GPS,
                    activityHeadline = "Indexando localização (GPS)…",
                    activityDetail = "Preparando fila…",
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
                        runQueue = emptyList(),
                        progressPhase = SyncProgressPhase.IDLE,
                        activityHeadline = "",
                        activityDetail = "",
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
        preferCloudFirst: Boolean = false,
        lightweightPrefetch: Boolean = false,
        localSourcesOnly: Boolean = false,
        cloudSourcesOnly: Boolean = false,
        /** Grade / detalhe: amostras maiores do MP4 na nuvem. */
        cloudVideoAggressive: Boolean = false,
    ): Result<File?> = galleryThumbnailService.ensureThumbnailCached(
        canonicalPath,
        onProgress,
        preferCloudFirst,
        lightweightPrefetch,
        localSourcesOnly,
        cloudSourcesOnly,
        cloudVideoAggressive,
    )

    private fun activeSyncProgressCallback(
        row: SyncMetadataEntity,
        operationLabel: String,
    ): ((Long, Long?) -> Unit)? {
        if (!shouldPublishSyncActivityUi()) return null
        return { done, total ->
            upsertActiveSyncItem(
                ActiveSyncItem(
                    canonicalPath = row.canonicalPath,
                    displayName = row.displayName,
                    operationLabel = operationLabel,
                    bytesCompleted = done,
                    totalBytes = total,
                    showTransferProgress = false,
                ),
                force = done == 0L || (total != null && done >= total),
            )
        }
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
        fun acceptOriginal(file: File): File? {
            if (!file.isFile || file.length() <= 0L) return null
            val remoteBytes = row.remoteSizeBytes
            if (remoteBytes != null && remoteBytes > 48_000L &&
                file.length() < (remoteBytes * 0.85).toLong()
            ) {
                return null
            }
            return file
        }
        row.cachedFilePath?.let { path ->
            acceptOriginal(File(path))?.let { return it }
        }
        mediaCacheRepository.originalIfCached(row.canonicalPath)?.let { acceptOriginal(it)?.let { return it } }
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
            val kind = if (GalleryMapper.isVideoFileName(row.displayName)) {
                com.onegallery.sync.data.local.MediaKind.VIDEO
            } else {
                com.onegallery.sync.data.local.MediaKind.IMAGE
            }
            if (localMediaRepository.contentUriForMediaId(id, kind) != null) return true
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
        runCatching { galleryThumbnailService.ensureThumbnailCached(row.canonicalPath) }
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

    private fun localContentUri(row: SyncMetadataEntity): Uri? =
        galleryThumbnailService.localContentUri(row)

    private suspend fun uploadLocalRow(row: SyncMetadataEntity): Int {
        var temp: File? = null
        try {
            ensureSyncMayContinue()
            upsertActiveSyncItem(
                ActiveSyncItem(
                    canonicalPath = row.canonicalPath,
                    displayName = row.displayName,
                    operationLabel = "Enviando para OneDrive…",
                    showTransferProgress = true,
                ),
                force = true,
            )
            val localId = row.localMediaId ?: run {
                syncMetadataDao.upsert(
                    row.copy(
                        cloudState = CloudSyncState.ERROR,
                        lastError = "Sem referência local para envio.",
                    ),
                )
                return 0
            }
            val item = indexPassLocalItemsById[localId]
                ?: localMediaRepository.findById(localId)
                ?: run {
                    syncMetadataDao.upsert(
                        row.copy(
                            cloudState = CloudSyncState.ERROR,
                            lastError = "Arquivo local não encontrado no aparelho.",
                        ),
                    )
                    return 0
                }
            temp = localMediaRepository.materializeToTempFile(item) ?: run {
                syncMetadataDao.upsert(
                    row.copy(
                        lastError = "Não foi possível ler o arquivo local.",
                        cloudState = CloudSyncState.ERROR,
                    ),
                )
                return 0
            }
            return withTimeout(UPLOAD_SINGLE_FILE_TIMEOUT_MS) {
                runCatching {
                    val localHash = ContentFingerprint.quickXorHashBase64(temp!!)
                    val localSize = temp!!.length()
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
                                sourceFile = temp!!,
                                mimeType = item.mimeType,
                                displayName = row.displayName,
                                operationLabel = "Enviando para OneDrive…",
                            ).getOrThrow()
                            uploadedBytes = true
                        }
                    } else {
                        remoteId = uploadLocalFile(
                            canonicalPath = row.canonicalPath,
                            sourceFile = temp!!,
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
                    schedulePostUploadThumbnailRefresh(synced.canonicalPath)
                    if (syncPreferences.autoFreeLocalSpaceWhenSynced) {
                        releaseLocalSpaceForRows(
                            listOf(synced),
                            allowSystemDialog = appForegroundState.isInForeground(),
                        )
                    }
                    1
                }.getOrElse { error ->
                    when (error) {
                        is TimeoutCancellationException ->
                            syncMetadataDao.upsert(
                                row.copy(
                                    cloudState = CloudSyncState.ERROR,
                                    lastError = "Envio expirou (tempo máximo por arquivo).",
                                ),
                            )
                        else -> {
                            propagateCancellation(error)
                            syncMetadataDao.upsert(
                                row.copy(cloudState = CloudSyncState.ERROR, lastError = error.message),
                            )
                        }
                    }
                    0
                }
            }
        } finally {
            temp?.delete()
            removeActiveSyncItem(row.canonicalPath)
            removeFromRunQueue(row.canonicalPath)
        }
    }

    /** Miniatura da nuvem após upload — fora da fila de envio (3 paralelos). */
    private fun schedulePostUploadThumbnailRefresh(canonicalPath: String) {
        val scope = backgroundScope ?: return
        scope.launch(Dispatchers.IO) {
            val row = syncMetadataDao.getByCanonicalPath(canonicalPath)
            val isVideo = row != null && GalleryMapper.isVideoFileName(row.displayName)
            val attempts = if (isVideo) POST_UPLOAD_VIDEO_THUMB_ATTEMPTS else 1
            repeat(attempts) { attempt ->
                if (attempt > 0) delay(POST_UPLOAD_VIDEO_THUMB_RETRY_DELAY_MS * attempt)
                val hit = runCatching {
                    withTimeout(POST_UPLOAD_THUMB_REFRESH_TIMEOUT_MS) {
                        ensureThumbnailCached(
                            canonicalPath,
                            preferCloudFirst = true,
                        ).getOrNull()
                    }
                }.getOrNull()
                if (hit != null && mediaCacheRepository.isDisplayableGalleryThumbFile(hit)) {
                    notifyGalleryDisplayRefresh()
                    return@launch
                }
            }
        }
    }

    /** Exclusões pendentes na fila; miniaturas ficam para [runMissingThumbnailPrefetch]. */
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
                    rowNeedsThumbnailPrefetch(row) -> {
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
        thumbRows.chunked(ThumbnailPostSyncEngine.THUMB_PREFETCH_BATCH).forEach { batch ->
            ensureNotCancelled()
            withThumbnailPrefetchUi {
                runMissingThumbnailPrefetch(batch, honorSyncCancel = true)
            }
        }
    }

    private fun rowCanBuildThumbnail(row: SyncMetadataEntity): Boolean =
        galleryThumbnailService.canBuildThumbnail(row)

    private fun rowHasGalleryThumbnail(row: SyncMetadataEntity): Boolean =
        galleryThumbnailService.rowHasGalleryThumbnail(row)

    private fun rowNeedsThumbnailPrefetch(row: SyncMetadataEntity): Boolean =
        galleryThumbnailService.needsThumbnailPrefetch(row)

    private suspend fun materializeNonVisualThumbnails() {
        thumbnailSyncPipeline.materializeNonVisualThumbnails()
    }

    private suspend fun warmLocalItemsForThumbnailPass() {
        if (indexPassLocalItemsById.isNotEmpty()) return
        indexPassLocalItemsById = localMediaRepository.loadLocalGallerySnapshot().associateBy { it.id }
    }

    /** Garante vídeos/fotos do MediaStore no Room — senão não entram na fila de miniaturas. */
    private suspend fun ensureAllLocalMediaPresentInIndex() {
        warmLocalItemsForThumbnailPass()
        val items = indexPassLocalItemsById.values.toList()
        if (items.isEmpty()) return
        applyLocalIndexPass(items, lightMetadataOnly = true)
    }

    private suspend fun allPendingThumbnailEntitiesForUi(): List<SyncMetadataEntity> =
        galleryThumbnailIndex.allPendingEntitiesForUi()

    /** Reconstrói a fila a partir do índice (início de leva / recuperação). */
    private suspend fun publishFullPendingThumbnailQueueToUi(): Int {
        return refreshThumbnailRunQueueUi(ThumbnailRunQueueUiMode.FULL)
    }

    /** Remove da lista só o que já tem miniatura — sem repor itens no final. */
    private suspend fun trimThumbnailRunQueueUi(): Int {
        return refreshThumbnailRunQueueUi(ThumbnailRunQueueUiMode.TRIM)
    }

    /** Coloca a leva atual no topo da fila (abaixo dos downloads ativos na UI). */
    private suspend fun publishThumbnailBatchAtFront(batch: List<SyncMetadataEntity>): Int {
        return refreshThumbnailRunQueueUi(ThumbnailRunQueueUiMode.BATCH_AT_FRONT, batch)
    }

    private enum class ThumbnailRunQueueUiMode { FULL, TRIM, BATCH_AT_FRONT }

    private suspend fun refreshThumbnailRunQueueUi(
        mode: ThumbnailRunQueueUiMode,
        batch: List<SyncMetadataEntity> = emptyList(),
    ): Int {
        if (!shouldPublishSyncActivityUi()) return galleryThumbnailIndex.countMissing()
        val total = galleryThumbnailIndex.countMissing()
        val cap = GalleryThumbnailIndex.THUMB_RUN_QUEUE_UI_CAP
        val allPending = allPendingThumbnailEntitiesForUi()
        val pendingPathSet = allPending.map { it.canonicalPath }.toHashSet()
        val entities = when (mode) {
            ThumbnailRunQueueUiMode.FULL -> allPending.take(cap)
            ThumbnailRunQueueUiMode.TRIM -> {
                val fromQueue = _state.value.runQueue.mapNotNull { row ->
                    syncMetadataDao.getByCanonicalPath(row.canonicalPath)
                }.filter { it.canonicalPath in pendingPathSet }
                if (fromQueue.isNotEmpty()) {
                    fromQueue
                } else {
                    allPending.take(cap)
                }
            }
            ThumbnailRunQueueUiMode.BATCH_AT_FRONT -> batch.take(cap)
        }
        setRunQueueFromEntities(entities, thumbnailQueue = true)
        val applyGlobal = {
            _state.update { it.copy(runQueueGlobalPending = total) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) applyGlobal() else mainHandler.post(applyGlobal)
        return total
    }

    private suspend fun missingThumbnailCountForProgress(): Int {
        return countIndexedMissingThumbnails()
    }

    private suspend fun buildMissingThumbnailEntities(maxPerRun: Int): List<SyncMetadataEntity> {
        val queueOrder = if (shouldPublishSyncActivityUi() && _state.value.runQueue.isNotEmpty()) {
            _state.value.runQueue.map { it.canonicalPath }
        } else {
            null
        }
        return galleryThumbnailIndex.buildMissingBatch(maxPerRun, queueOrder)
    }

    private suspend fun countIndexedMissingThumbnails(): Int =
        galleryThumbnailIndex.countMissing()

    private fun rowNeedsGpsBackfill(row: SyncMetadataEntity): Boolean {
        if (row.latitude != null && row.longitude != null) return false
        return row.localMediaId != null ||
            !row.localContentUri.isNullOrBlank() ||
            !row.cachedFilePath.isNullOrBlank() ||
            !row.oneDriveItemId.isNullOrBlank()
    }

    private suspend fun allPendingGpsEntitiesForUi(): List<SyncMetadataEntity> {
        return syncMetadataDao.getAll()
            .asSequence()
            .filter { rowNeedsGpsBackfill(it) }
            .sortedWith(gpsBackfillComparator)
            .toList()
    }

    /** Fila total na tela Sync durante leitura de GPS (como miniaturas). */
    private suspend fun publishFullPendingGpsQueueToUi(): Int {
        if (!shouldPublishSyncActivityUi()) return 0
        val rows = allPendingGpsEntitiesForUi()
        val total = rows.size
        val capped = if (rows.size > GPS_RUN_QUEUE_UI_CAP) {
            rows.take(GPS_RUN_QUEUE_UI_CAP)
        } else {
            rows
        }
        setRunQueueFromEntities(capped, gpsQueue = true)
        return total
    }

    private fun gpsQueueStatusLabel(row: SyncMetadataEntity): String {
        val hasLocal = row.localMediaId != null ||
            !row.localContentUri.isNullOrBlank() ||
            !row.cachedFilePath.isNullOrBlank()
        return when {
            hasLocal && !row.oneDriveItemId.isNullOrBlank() ->
                "Aguardando GPS (aparelho ou nuvem)…"
            hasLocal -> "Aguardando GPS no aparelho…"
            !row.oneDriveItemId.isNullOrBlank() -> "Aguardando GPS na nuvem…"
            else -> "Aguardando leitura de GPS…"
        }
    }

    private suspend fun countIndexedMissingGps(): Int {
        return syncMetadataDao.getAll().count { row -> rowNeedsGpsBackfill(row) }
    }

    /**
     * Corrige [CloudSyncState.PENDING_DOWNLOAD] fantasma (hash na listagem delta incompleto,
     * fingerprint local desatualizado, miniatura já em cache).
     */
    /**
     * Limpa [CloudSyncState.PENDING_UPLOAD] fantasma (já na nuvem, sem arquivo local, etc.)
     * para a UI não ficar presa em “aguardando envio”.
     */
    private suspend fun healStalePendingUploads(remoteByPath: Map<String, RemoteDriveFile>) {
        val pending = syncMetadataDao.getPendingUploadRows()
        if (pending.isEmpty()) return
        val healed = mutableListOf<SyncMetadataEntity>()
        for (row in pending) {
            ensureNotCancelled()
            val remote = remoteByPath[row.canonicalPath]
                ?: resolveRemoteForUploadCandidate(row, remoteByPath)
            if (remote != null) {
                if (markSyncedIfContentMatchesRemote(row, remote) != null) {
                    continue
                }
                val work = ensureLocalContentFingerprint(row)
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
            val localId = row.localMediaId
            if (localId == null) {
                healed.add(
                    row.copy(
                        cloudState = CloudSyncState.ERROR,
                        lastError = "Sem mídia local para enviar.",
                    ),
                )
                continue
            }
            val item = indexPassLocalItemsById[localId]
                ?: localMediaRepository.findById(localId)
            if (item == null) {
                healed.add(
                    row.copy(
                        cloudState = CloudSyncState.ERROR,
                        lastError = "Arquivo local não encontrado no aparelho.",
                    ),
                )
            }
        }
        if (healed.isEmpty()) return
        healed.chunked(ROOM_UPSERT_CHUNK).forEach { chunk ->
            syncMetadataDao.upsertAll(chunk)
        }
    }

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
                rowNeedsThumbnailPrefetch(row) &&
                    (
                        hasLocalThumbnailSource(row) ||
                            (
                                row.oneDriveItemId != null &&
                                    row.cloudState == CloudSyncState.CLOUD_ONLY
                                )
                        ) -> true
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
                    activityHeadline = "Envio/recebimento concluído",
                    activityDetail = "Miniaturas locais primeiro (funciona offline), " +
                        "depois cache do OneDrive — não reenvia arquivos.",
                )
                seedResidualRunQueueAfterCoreSync()
                drainRemainingRunQueueBeforePrefetch()
                ensureNotCancelled()
                ensureAllLocalMediaPresentInIndex()
                ensureLocalMediaThumbnailsEarly()
                setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Capas dos álbuns…",
                    "Uma miniatura por álbum — fila local já terminou.",
                    alsoUpdateLastMessage = false,
                )
                ensureNotCancelled()
                ensureAlbumCoverThumbnails()
                ensureNotCancelled()
                ensureCloudThumbnailsPrefetch()
                ensureNotCancelled()
                materializeNonVisualThumbnails()
                ensureNotCancelled()
                publishFullPendingThumbnailQueueToUi()
                healStalePendingDownloads(remoteByPath = null)
                healAllStaleLocalCopyMetadata()
                setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Miniaturas na galeria…",
                    "Grade e itens restantes — originais completos sob demanda.",
                    alsoUpdateLastMessage = false,
                )
                val thumbsStillMissing = runCompleteThumbnailPrefetchBeforeMetadata()
                setSyncProgress(
                    SyncProgressPhase.BACKFILL_GPS,
                    "Indexando localização (GPS)…",
                    if (thumbsStillMissing == 0) {
                        "Miniaturas concluídas — mapa e Locais."
                    } else {
                        "~$thumbsStillMissing ainda sem JPEG no cache — mapa e Locais."
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
        val scope = backgroundScope ?: return
        val job = scope.launch {
            if (syncCancelRequested.get()) return@launch
            if (!longTaskMutex.tryLock()) return@launch
            try {
                if (syncCancelRequested.get()) return@launch
                val rows = buildMissingThumbnailEntities(
                    maxPerRun = ThumbnailPostSyncEngine.THUMB_PREFETCH_BATCH,
                )
                val cached = withThumbnailPrefetchUi {
                    runMissingThumbnailPrefetch(rows, honorSyncCancel = false)
                }
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

    fun hasActiveBackgroundThumbnailPrefetchJob(): Boolean =
        backgroundThumbPrefetchJob?.isActive == true

    /** Espera prefetch de miniaturas em 2º plano (ex.: WorkManager mantém notificação até terminar). */
    suspend fun joinBackgroundThumbnailPrefetchIfRunning() {
        backgroundThumbPrefetchJob?.join()
    }

    /** Continua miniaturas após o sync principal (bibliotecas grandes, ex. dezenas de milhares na nuvem). */
    private fun scheduleBackgroundThumbnailPrefetchIfNeeded() {
        if (backgroundThumbPrefetchJob?.isActive == true) return
        val scope = backgroundScope ?: return
        val thumbMessage = appContext.getString(R.string.sync_notification_thumbnails_message)
        val handoffForeground = foregroundGuard.holdsForeground()
        if (handoffForeground) {
            foregroundGuard.enter(thumbMessage)
        }
        backgroundThumbPrefetchJob = scope.launch {
            refreshExtraCancellableWork()
            if (!handoffForeground) {
                foregroundGuard.enter(thumbMessage)
            }
            try {
            withThumbnailPrefetchUi {
            backgroundThumbPrefetchDeferred.set(false)
            delay(BACKGROUND_THUMB_PREFETCH_INITIAL_DELAY_MS)
            var stalledPasses = 0
            try {
            repeat(THUMB_BACKGROUND_CONTINUE_PASSES) {
                if (syncCancelRequested.get()) return@launch
                if (_state.value.isRunning) return@launch
                if (com.onegallery.sync.ui.gallery.GalleryGridScrollGate.shouldDeferBulkThumbnailPrefetch()) {
                    delay(180)
                    return@repeat
                }
                if (countIndexedMissingThumbnails() == 0) return@launch
                val idleForeground = appForegroundState.isInForeground() &&
                    !allowForegroundThumbnailPrefetch()
                if (!longTaskMutex.tryLock()) {
                    delay(
                        if (idleForeground) {
                            THUMB_FOREGROUND_IDLE_RETRY_DELAY_MS
                        } else {
                            BACKGROUND_THUMB_PREFETCH_RETRY_DELAY_MS
                        },
                    )
                    return@repeat
                }
                try {
                    val before = countIndexedMissingThumbnails()
                    if (before == 0) return@launch
                    prefetchMissingThumbnails(
                        honorUserSyncCancel = false,
                        maxTotalPerRun = if (idleForeground) {
                            THUMB_FOREGROUND_IDLE_MAX_PER_PASS
                        } else {
                            THUMB_BACKGROUND_MAX_TOTAL_PER_PASS
                        },
                        completeAllPending = !idleForeground,
                        parallelSlots = if (idleForeground) {
                            PARALLEL_THUMBNAILS_FOREGROUND_IDLE
                        } else {
                            PARALLEL_THUMBNAILS
                        },
                    )
                    val after = countIndexedMissingThumbnails()
                    if (after == 0) return@launch
                    if (after >= before) {
                        stalledPasses++
                        if (stalledPasses >= 6) return@launch
                    } else {
                        stalledPasses = 0
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (error.message?.contains("Wi‑Fi", ignoreCase = true) == true) {
                        return@launch
                    }
                } finally {
                    longTaskMutex.unlock()
                }
                delay(
                    if (idleForeground) {
                        THUMB_FOREGROUND_IDLE_RETRY_DELAY_MS
                    } else {
                        BACKGROUND_THUMB_PREFETCH_RETRY_DELAY_MS
                    },
                )
            }
            } finally {
                clearRunQueueAndActiveTransfers()
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
            } finally {
                foregroundGuard.leave()
                backgroundThumbPrefetchJob = null
                refreshExtraCancellableWork()
            }
        }
        refreshExtraCancellableWork()
    }

    /** Uma leva: no máximo uma miniatura por álbum ainda sem capa (UI usa qualquer thumb local). */
    private fun kickLocalMediaThumbnailPrefetchDuringSync() {
        val scope = backgroundScope ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                ensureLocalMediaThumbnailsEarly(
                    maxPerRun = ThumbnailSyncPipeline.LOCAL_THUMB_EARLY_DURING_SYNC_MAX,
                    showProgress = false,
                )
            }
        }
    }

    private suspend fun ensureLocalMediaThumbnailsEarly(
        maxPerRun: Int = ThumbnailSyncPipeline.LOCAL_THUMB_EARLY_POST_SYNC_MAX,
        showProgress: Boolean = true,
    ) {
        thumbnailSyncPipeline.ensureLocalMediaThumbnailsEarly(maxPerRun, showProgress)
    }

    private suspend fun ensureCloudThumbnailsPrefetch(
        maxPerRun: Int = ThumbnailSyncPipeline.CLOUD_THUMB_PREFETCH_MAX_PER_PASS,
    ) {
        thumbnailSyncPipeline.ensureCloudThumbnailsPrefetch(maxPerRun)
    }

    private suspend fun ensureAlbumCoverThumbnails() {
        thumbnailSyncPipeline.ensureAlbumCoverThumbnails(PARALLEL_THUMBNAILS)
    }

    private fun hasLocalThumbnailSource(row: SyncMetadataEntity): Boolean =
        galleryThumbnailService.hasLocalThumbnailSource(row)

    private suspend fun runCompleteThumbnailPrefetchBeforeMetadata(): Int =
        thumbnailPostSyncEngine.runCompleteBeforeMetadata()

    private suspend fun prefetchMissingThumbnails(
        batchSize: Int = ThumbnailPostSyncEngine.THUMB_PREFETCH_BATCH,
        honorUserSyncCancel: Boolean = true,
        maxTotalPerRun: Int = ThumbnailPostSyncEngine.THUMB_PREFETCH_MAX_TOTAL_PER_SYNC,
        completeAllPending: Boolean = false,
        parallelSlots: Int = ThumbnailPostSyncEngine.PARALLEL_THUMBNAILS,
        progressHeadline: String = "Completando cache da galeria…",
    ) {
        thumbnailPostSyncEngine.prefetchMissingThumbnails(
            batchSize = batchSize,
            honorUserSyncCancel = honorUserSyncCancel,
            maxTotalPerRun = maxTotalPerRun,
            completeAllPending = completeAllPending,
            parallelSlots = parallelSlots,
            progressHeadline = progressHeadline,
        )
    }

    private suspend fun giveUpStubbornThumbnailsBatch(maxItems: Int): Int =
        thumbnailSyncPipeline.giveUpStubbornThumbnailsBatch(maxItems) { row ->
            syncPreferences.clearThumbnailFailure(row.canonicalPath)
            thumbPrefetchAttemptsThisSession.remove(row.canonicalPath)
        }

    private suspend fun runMissingThumbnailPrefetch(
        rows: List<SyncMetadataEntity>,
        honorSyncCancel: Boolean,
        parallelSlots: Int = PARALLEL_THUMBNAILS,
        localSourcesOnly: Boolean = false,
        cloudSourcesOnly: Boolean = false,
        progressHeadline: String = "Completando cache da galeria…",
        publishUi: Boolean = true,
    ): Int = thumbnailPrefetchRunner.runMissingThumbnailPrefetch(
        rows = rows,
        honorSyncCancel = honorSyncCancel,
        parallelSlots = parallelSlots,
        localSourcesOnly = localSourcesOnly,
        cloudSourcesOnly = cloudSourcesOnly,
        progressHeadline = progressHeadline,
        publishUi = publishUi,
    )

    private suspend fun backfillMissingGpsLocations(
        maxRounds: Int = GPS_BACKFILL_ROUNDS,
        resetCursor: Boolean = false,
    ) {
        if (resetCursor) {
            syncPreferences.gpsBackfillCursor = 0
        }
        val globalTotalAtStart = publishFullPendingGpsQueueToUi()
        if (globalTotalAtStart == 0) {
            clearRunQueueAndActiveTransfers()
            return
        }
        if (shouldPublishSyncActivityUi()) {
            setSyncProgress(
                SyncProgressPhase.BACKFILL_GPS,
                "Indexando localização (GPS)…",
                "0/$globalTotalAtStart · ~$globalTotalAtStart na fila · " +
                    "até $PARALLEL_GPS_BACKFILL em paralelo.",
                alsoUpdateLastMessage = false,
            )
        }
        var totalIndexed = 0
        var totalAnalyzed = 0
        var hitLimit = false
        try {
            repeat(maxRounds) {
                if (countIndexedMissingGps() == 0) return@repeat
                if (_state.value.runQueue.isEmpty()) {
                    publishFullPendingGpsQueueToUi()
                }
                val outcome = backfillMissingGpsLocationsOnce(
                    maxLocalProbesPerRun = GPS_LOCAL_BATCH,
                    maxCloudProbesPerRun = GPS_CLOUD_BATCH,
                    globalTotalAtStart = globalTotalAtStart,
                    analyzedBeforeBatch = totalAnalyzed,
                )
                totalIndexed += outcome.indexed
                totalAnalyzed += outcome.probesAttempted
                hitLimit = hitLimit || outcome.hitProbeLimit
                if (!outcome.hitProbeLimit && outcome.probesAttempted == 0) return@repeat
            }
            if (totalIndexed > 0 || totalAnalyzed > 0) {
                val remaining = countIndexedMissingGps()
                val queueLeft = _state.value.runQueue.size
                val suffix = when {
                    remaining > 0 && hitLimit ->
                        " · $remaining ainda sem GPS no índice (sincronize de novo ou permissão de mídia)."
                    remaining > 0 ->
                        " · $remaining sem GPS nos arquivos ou na nuvem."
                    else -> "."
                }
                setSyncProgress(
                    SyncProgressPhase.BACKFILL_GPS,
                    "Localização GPS",
                    "$totalAnalyzed/$globalTotalAtStart analisados · " +
                        "$totalIndexed com GPS · " +
                        (if (queueLeft > 0) "~$queueLeft ainda na fila · " else "") +
                        suffix.trim(),
                )
                notifyGalleryDisplayRefresh()
            }
        } finally {
            clearRunQueueAndActiveTransfers()
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
        globalTotalAtStart: Int = 0,
        analyzedBeforeBatch: Int = 0,
    ): GpsBackfillBatchOutcome {
        val rows = allPendingGpsEntitiesForUi()
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
                            markSyncProcessingItem(
                                item.row,
                                when (item.probeKind) {
                                    GpsBackfillProbeKind.LOCAL -> "Lendo GPS no aparelho…"
                                    GpsBackfillProbeKind.CLOUD -> "Lendo GPS na nuvem…"
                                },
                            )
                            val index = analyzedCount.incrementAndGet()
                            val analyzedOverall = analyzedBeforeBatch + index
                            val queueLeft = _state.value.runQueue.size
                            if ((index % 8 == 0 || index == candidates) &&
                                (_state.value.isRunning || shouldPublishSyncActivityUi())
                            ) {
                                val totalLabel = globalTotalAtStart.takeIf { it > 0 } ?: rows.size
                                setSyncProgress(
                                    SyncProgressPhase.BACKFILL_GPS,
                                    "Indexando localização (GPS)…",
                                    "$analyzedOverall/$totalLabel analisados · " +
                                        "${indexedCount.get()} com GPS · " +
                                        "~$queueLeft na fila · " +
                                        "leva $index/$candidates · " +
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
                        } finally {
                            removeActiveSyncItem(item.row.canonicalPath)
                            removeFromRunQueue(item.row.canonicalPath)
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
                markSyncProcessingItem(row, "Lendo data de captura…")
                try {
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
                } finally {
                    removeActiveSyncItem(row.canonicalPath)
                }
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

    private suspend fun listRemoteFiles(userInitiated: Boolean = false): Result<RemoteListingSnapshot> {
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
                val forceRecursive = syncPreferences.forceRemoteFullListingOnce ||
                    oneDriveRepository.shouldForceRecursiveRemoteListing(
                        indexRowCount = syncMetadataDao.countAll(),
                        userInitiated = userInitiated,
                    )
                if (forceRecursive && syncPreferences.forceRemoteFullListingOnce) {
                    oneDriveRepository.resetRemoteListingState()
                }
                backend.listRemoteFiles(baseline, forceRecursive).map { listing ->
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
                                showTransferProgress = true,
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
                                showTransferProgress = true,
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

    private inner class SyncCoordinatorThumbnailSyncHost :
        ThumbnailPrefetchHost,
        ThumbnailPostSyncHost,
        ThumbnailSyncPipelineHost {
        override val thumbnailPrefetchBlocking get() = this@SyncCoordinator.thumbnailPrefetchBlocking

        override fun isSyncCancelRequested(): Boolean = syncCancelRequested.get()

        override suspend fun ensureNotCancelled() = this@SyncCoordinator.ensureNotCancelled()

        override suspend fun ensureSyncMayContinue() = this@SyncCoordinator.ensureSyncMayContinue()

        override fun shouldPublishSyncActivityUi(): Boolean =
            this@SyncCoordinator.shouldPublishSyncActivityUi()

        override suspend fun publishFullPendingThumbnailQueueToUi(): Int =
            this@SyncCoordinator.publishFullPendingThumbnailQueueToUi()

        override suspend fun trimThumbnailRunQueueUi(): Int =
            this@SyncCoordinator.trimThumbnailRunQueueUi()

        override suspend fun publishThumbnailBatchAtFront(batch: List<SyncMetadataEntity>): Int =
            this@SyncCoordinator.publishThumbnailBatchAtFront(batch)

        override fun upsertActiveSyncItem(item: ActiveSyncItem, force: Boolean) {
            this@SyncCoordinator.upsertActiveSyncItem(item, force)
        }

        override fun removeActiveSyncItem(canonicalPath: String) {
            this@SyncCoordinator.removeActiveSyncItem(canonicalPath)
        }

        override fun removeFromRunQueue(canonicalPath: String) {
            this@SyncCoordinator.removeFromRunQueue(canonicalPath)
        }

        override suspend fun setSyncProgress(
            phase: SyncProgressPhase,
            headline: String,
            detail: String,
            alsoUpdateLastMessage: Boolean,
        ) {
            this@SyncCoordinator.setSyncProgress(phase, headline, detail, alsoUpdateLastMessage)
        }

        override suspend fun missingThumbnailCountForProgress(): Int =
            galleryThumbnailIndex.countMissing()

        override fun thumbnailOperationLabel(row: SyncMetadataEntity): String =
            GalleryThumbnailLabels.operationLabel(row)

        override fun activeSyncProgressCallback(
            row: SyncMetadataEntity,
            operationLabel: String,
        ): ((Long, Long?) -> Unit)? =
            this@SyncCoordinator.activeSyncProgressCallback(row, operationLabel)

        override fun thumbnailPrefetchProgressDetailSuffix(
            localSourcesOnly: Boolean,
            cloudSourcesOnly: Boolean,
        ): String = GalleryThumbnailLabels.prefetchProgressDetailSuffix(
            localSourcesOnly,
            cloudSourcesOnly,
        )

        override suspend fun ensureThumbnailCached(
            canonicalPath: String,
            onProgress: ((Long, Long?) -> Unit)?,
            lightweightPrefetch: Boolean,
            localSourcesOnly: Boolean,
            cloudSourcesOnly: Boolean,
            cloudVideoAggressive: Boolean,
        ): Result<File?> = galleryThumbnailService.ensureThumbnailCached(
            canonicalPath,
            onProgress,
            lightweightPrefetch = lightweightPrefetch,
            localSourcesOnly = localSourcesOnly,
            cloudSourcesOnly = cloudSourcesOnly,
            cloudVideoAggressive = cloudVideoAggressive,
        )

        override fun rowHasGalleryThumbnail(row: SyncMetadataEntity): Boolean =
            galleryThumbnailService.rowHasGalleryThumbnail(row)

        override fun incrementThumbnailFailure(canonicalPath: String) {
            syncPreferences.incrementThumbnailFailure(canonicalPath)
        }

        override fun recordThumbPrefetchAttempt(canonicalPath: String) {
            thumbPrefetchAttemptsThisSession.merge(canonicalPath, 1) { left, _ -> left + 1 }
        }

        override fun clearThumbPrefetchAttempt(canonicalPath: String) {
            thumbPrefetchAttemptsThisSession.remove(canonicalPath)
        }

        override suspend fun countIndexedMissingThumbnails(): Int =
            galleryThumbnailIndex.countMissing()

        override suspend fun buildMissingThumbnailBatch(batchSize: Int): List<SyncMetadataEntity> =
            galleryThumbnailIndex.buildMissingBatch(batchSize)

        override suspend fun warmLocalItemsForThumbnailPass() =
            this@SyncCoordinator.warmLocalItemsForThumbnailPass()

        override fun onBulkThumbnailPrefetchDepthChanged(delta: Int) {
            bulkThumbnailPrefetchDepth.addAndGet(delta)
        }

        override fun refreshThumbnailPrefetchUiFlag() =
            this@SyncCoordinator.refreshThumbnailPrefetchUiFlag()

        override fun isRunQueueEmpty(): Boolean = _state.value.runQueue.isEmpty()

        override suspend fun flushPendingAlbumCoverRevisions() =
            this@SyncCoordinator.flushPendingAlbumCoverRevisions()

        override fun cancelBulkGalleryDisplayRefreshJob() {
            bulkGalleryDisplayRefreshJob?.cancel()
            bulkGalleryDisplayRefreshJob = null
        }

        override suspend fun notifyGalleryDisplayRefresh() =
            this@SyncCoordinator.notifyGalleryDisplayRefresh()

        override suspend fun refreshThumbnailCacheStats(force: Boolean) =
            this@SyncCoordinator.refreshThumbnailCacheStats(force)

        override suspend fun clearRunQueueAndActiveTransfers() =
            this@SyncCoordinator.clearRunQueueAndActiveTransfers()

        override suspend fun giveUpStubbornThumbnailsBatch(maxItems: Int): Int =
            this@SyncCoordinator.giveUpStubbornThumbnailsBatch(maxItems)

        override fun clearActiveSyncItems() = this@SyncCoordinator.clearActiveSyncItems()

        override suspend fun ensureAllLocalMediaPresentInIndex() =
            this@SyncCoordinator.ensureAllLocalMediaPresentInIndex()

        override suspend fun <T> withThumbnailPrefetchUi(block: suspend () -> T): T =
            this@SyncCoordinator.withThumbnailPrefetchUi(block)

        override suspend fun runMissingThumbnailPrefetch(
            rows: List<SyncMetadataEntity>,
            honorSyncCancel: Boolean,
            parallelSlots: Int,
            localSourcesOnly: Boolean,
            cloudSourcesOnly: Boolean,
            progressHeadline: String,
            publishUi: Boolean,
        ): Int = this@SyncCoordinator.runMissingThumbnailPrefetch(
            rows,
            honorSyncCancel,
            parallelSlots,
            localSourcesOnly,
            cloudSourcesOnly,
            progressHeadline,
            publishUi,
        )

        override fun isOnline(): Boolean = networkMonitor.isOnline()

        override fun isOneDriveGraphMode(): Boolean =
            syncPreferences.syncMode == SyncMode.ONEDRIVE_GRAPH
    }

    private companion object {
        const val GALLERY_METADATA_DEBOUNCE_MS = 450L
        const val THUMB_BACKGROUND_MAX_TOTAL_PER_PASS = 10_000
        const val THUMB_BACKGROUND_CONTINUE_PASSES = 64
        const val BACKGROUND_THUMB_PREFETCH_INITIAL_DELAY_MS = 600L
        const val BACKGROUND_THUMB_PREFETCH_RETRY_DELAY_MS = 1_200L
        const val THUMB_MAX_FAILURE_ATTEMPTS = 5
        const val THUMB_MAX_FAILURE_ATTEMPTS_VIDEO = 12
        const val LOCAL_THUMB_EARLY_DURING_SYNC_MAX = 4_000
        const val LOCAL_THUMB_EARLY_POST_SYNC_MAX = 6_000
        /** Por item na fila de miniaturas (fotos locais / JPEG rápido). */
        const val THUMB_PREFETCH_ITEM_TIMEOUT_MS = 30_000L
        /** MediaStore / retriever em MP4 local durante prefetch. */
        const val THUMB_PREFETCH_LOCAL_VIDEO_TIMEOUT_MS = 75_000L
        /** Leva mista: aparelho + Graph/amostra parcial no mesmo item. */
        const val THUMB_PREFETCH_VIDEO_TIMEOUT_MS = 120_000L
        /** Evita milhares de coroutines e fila “congelada” após os primeiros itens. */
        const val THUMB_PREFETCH_RUN_CHUNK = 40
        /** Depois disso o item sai da leva nesta sessão (grade tenta de novo sob demanda). */
        const val THUMB_PREFETCH_MAX_SESSION_ATTEMPTS = 8
        /** Lista na tela Sync — evita OOM com dezenas de milhares pendentes. */
        const val THUMB_RUN_QUEUE_UI_CAP = 1_500
        const val GPS_RUN_QUEUE_UI_CAP = 1_500
        const val PARALLEL_THUMB_PREFETCH = 8
        const val CLOUD_THUMB_FETCH_TIMEOUT_MS = 25_000L
        /** Graph + amostras parciais para frame (cauda/início). */
        const val CLOUD_VIDEO_THUMB_FETCH_TIMEOUT_MS = 90_000L
        const val CLOUD_VIDEO_ON_DEMAND_FETCH_TIMEOUT_MS = 180_000L
        const val CLOUD_VIDEO_THUMB_TAIL_MAX_BYTES = 48L * 1024L * 1024L
        const val CLOUD_VIDEO_ON_DEMAND_TAIL_MAX_BYTES = 96L * 1024L * 1024L
        const val CLOUD_VIDEO_THUMB_HEAD_MAX_BYTES = 24L * 1024L * 1024L
        /** Vídeos pequenos na nuvem: baixa inteiro e extrai frame (mais confiável que trecho). */
        const val CLOUD_VIDEO_FULL_FETCH_MAX_BYTES = 25L * 1024L * 1024L
        const val CLOUD_VIDEO_ON_DEMAND_FULL_FETCH_MAX_BYTES = 120L * 1024L * 1024L
        const val POST_UPLOAD_VIDEO_THUMB_ATTEMPTS = 5
        const val POST_UPLOAD_VIDEO_THUMB_RETRY_DELAY_MS = 6_000L
        const val POST_UPLOAD_THUMB_REFRESH_TIMEOUT_MS = 45_000L
        const val CLOUD_THUMB_PREFETCH_MAX_PER_PASS = 2_000
        const val ACTIVE_ITEM_PROGRESS_MIN_INTERVAL_MS = 280L
        const val ALBUM_COVER_REVISION_COALESCE_MS = 280L
        const val GALLERY_DISPLAY_REFRESH_COALESCE_MS = 2_000L
        const val THUMBNAIL_STATS_MIN_REFRESH_MS = 8_000L
        const val GPS_BACKFILL_ROUNDS = 8
        /** Indexação manual (mapa): varre a biblioteca inteira em várias levas. */
        const val GPS_BACKFILL_MANUAL_ROUNDS = 48
        const val GPS_LOCAL_BATCH = 500
        const val GPS_CLOUD_BATCH = 500
        const val PARALLEL_UPLOADS = 5
        /** Evita envio infinito na UI se a rede/Graph travar. */
        const val UPLOAD_SINGLE_FILE_TIMEOUT_MS = 45L * 60L * 1000L
        /** Downloads Graph / decode local em paralelo por leva. */
        const val PARALLEL_THUMBNAILS = 8
        /** Fallback se [allowForegroundThumbnailPrefetch] for falso (modo econômico). */
        const val PARALLEL_THUMBNAILS_FOREGROUND_IDLE = 6
        const val THUMB_FOREGROUND_IDLE_MAX_PER_PASS = 2_000
        const val THUMB_FOREGROUND_IDLE_RETRY_DELAY_MS = 2_500L
        const val PARALLEL_GPS_BACKFILL = 5
        const val ROOM_UPSERT_CHUNK = 64
        const val LOCAL_ACCESS_WRITE_THROTTLE_MS = 60_000L
    }
}

data class SyncSummary(
    val uploaded: Int,
    val pendingDownloadPlaceholders: Int,
    val cloudOnly: Int,
)
