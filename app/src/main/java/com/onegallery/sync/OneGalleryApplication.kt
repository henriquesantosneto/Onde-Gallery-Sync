package com.onegallery.sync

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.onegallery.sync.ui.gallery.OneDriveCloudThumbnailFetcher
import com.onegallery.sync.sync.GalleryThumbnailCache
import com.onegallery.sync.sync.MediaLibraryChangeWatcher
import com.onegallery.sync.worker.BackgroundSyncScheduler
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.auth.AuthPreferences
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.AppDatabase
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCaptureTimeResolver
import com.onegallery.sync.data.local.MediaLocationResolver
import org.maplibre.android.MapLibre
import com.onegallery.sync.data.local.MediaDeleteRequestRouter
import com.onegallery.sync.data.cloud.CloudSyncBackendRegistry
import com.onegallery.sync.data.cloud.DropboxCloudBackendStub
import com.onegallery.sync.data.cloud.OneDriveGraphCloudBackend
import com.onegallery.sync.data.onedrive.GraphFolderPreferences
import com.onegallery.sync.data.onedrive.OneDriveFolderPreferences
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.data.onedrive.LocalMirrorRepository
import com.onegallery.sync.data.onedrive.SaOneDriveRepository
import com.onegallery.sync.sync.AppForegroundState
import com.onegallery.sync.sync.NetworkMonitor
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncPreferences
import com.onegallery.sync.ui.AppUserMessages
import com.onegallery.sync.ui.gallery.GalleryPreferences
import com.onegallery.sync.ui.gallery.GalleryProcessAlbumListsCache
import com.onegallery.sync.ui.gallery.GalleryProcessCatalogCache
import com.onegallery.sync.ui.gallery.GallerySessionAlbumListsSpill
import com.onegallery.sync.sync.SyncTaskLauncher
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.onegallery.sync.ui.gallery.GallerySessionCatalogSpill

class OneGalleryApplication : Application() {
    lateinit var authManager: MsalAuthManager
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var localMediaRepository: LocalMediaRepository
        private set

    lateinit var oneDriveRepository: OneDriveRepository
        private set

    lateinit var syncCoordinator: SyncCoordinator
        private set

    /** Miniaturas (grade/detalhe/mapa) — mesma instância usada pelo [syncCoordinator]. */
    val galleryThumbnailCache: GalleryThumbnailCache
        get() = syncCoordinator.thumbnailCache

    lateinit var syncPreferences: SyncPreferences
        private set

    lateinit var oneDriveFolderPreferences: OneDriveFolderPreferences
        private set

    lateinit var graphFolderPreferences: GraphFolderPreferences
        private set

    lateinit var galleryPreferences: GalleryPreferences
        private set

    val mediaDeleteRequestRouter = MediaDeleteRequestRouter()

    val appForegroundState = AppForegroundState()

    private val applicationCoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e("OneGallerySync", "Erro em segundo plano (app continua)", error)
    }

    val applicationScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            applicationCoroutineExceptionHandler,
    )

    lateinit var syncTaskLauncher: SyncTaskLauncher
        private set

    lateinit var mediaCacheRepository: MediaCacheRepository
        private set

    val galleryProcessCatalogCache = GalleryProcessCatalogCache()

    val galleryProcessAlbumListsCache = GalleryProcessAlbumListsCache()

    private var sessionSpillWriteJob: kotlinx.coroutines.Job? = null

    lateinit var mediaLibraryChangeWatcher: MediaLibraryChangeWatcher
        private set

    val userMessages = AppUserMessages()

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    add(VideoFrameDecoder.Factory())
                    add(OneDriveCloudThumbnailFetcher.Factory(this@OneGalleryApplication))
                }
                .memoryCache {
                    coil.memory.MemoryCache.Builder(this)
                        .maxSizePercent(0.25)
                        .build()
                }
                .crossfade(false)
                .build(),
        )
        authManager = MsalAuthManager(this, AuthPreferences(this))
        database = AppDatabase.create(this)
        localMediaRepository = LocalMediaRepository(this)
        val mediaLocationResolver = MediaLocationResolver(this)
        val mediaCaptureTimeResolver = MediaCaptureTimeResolver(this)
        graphFolderPreferences = GraphFolderPreferences(this)
        oneDriveRepository = OneDriveRepository(this, authManager, graphFolderPreferences)
        syncPreferences = SyncPreferences(this).also {
            it.migrateForAppVersion(BuildConfig.VERSION_CODE)
        }
        oneDriveFolderPreferences = OneDriveFolderPreferences(this)
        syncPreferences.repairPersistedConnection(
            graphFolderConfigured = graphFolderPreferences.isConfigured(),
            localOneDriveFolderConnected = oneDriveFolderPreferences.treeUri != null,
        )
        galleryPreferences = GalleryPreferences(this).also { prefs ->
            com.onegallery.sync.util.GalleryPerfLogger.enabled = prefs.logPerformanceTraces
        }
        runBlocking {
            withContext(Dispatchers.IO) {
                GallerySessionCatalogSpill.hydrateProcessCache(
                    this@OneGalleryApplication,
                    galleryProcessCatalogCache,
                )
                GallerySessionAlbumListsSpill.hydrateProcessCache(
                    this@OneGalleryApplication,
                    galleryProcessAlbumListsCache,
                )
            }
        }
        mediaCacheRepository = MediaCacheRepository(this)
        applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            mediaCacheRepository.warmThumbnailExistsCache()
            runCatching { localMediaRepository.loadLocalGallerySnapshot() }
        }
        val saOneDriveRepository = SaOneDriveRepository(this, oneDriveFolderPreferences)
        val localMirrorRepository = LocalMirrorRepository(this)
        val cloudSyncBackendRegistry = CloudSyncBackendRegistry(
            oneDriveGraph = OneDriveGraphCloudBackend(oneDriveRepository),
            dropboxStub = DropboxCloudBackendStub(),
            syncPreferences = syncPreferences,
        )
        syncCoordinator = SyncCoordinator(
            appContext = applicationContext,
            localMediaRepository = localMediaRepository,
            oneDriveRepository = oneDriveRepository,
            saOneDriveRepository = saOneDriveRepository,
            localMirrorRepository = localMirrorRepository,
            syncMetadataDao = database.syncMetadataDao(),
            userAlbumDao = database.userAlbumDao(),
            mediaCacheRepository = mediaCacheRepository,
            mediaLocationResolver = mediaLocationResolver,
            mediaCaptureTimeResolver = mediaCaptureTimeResolver,
            networkMonitor = NetworkMonitor(this),
            syncPreferences = syncPreferences,
            cloudSyncBackendRegistry = cloudSyncBackendRegistry,
            mediaDeleteRequestRouter = mediaDeleteRequestRouter,
            appForegroundState = appForegroundState,
            backgroundScope = applicationScope,
        )
        syncTaskLauncher = SyncTaskLauncher(this, syncCoordinator)
        mediaLibraryChangeWatcher = MediaLibraryChangeWatcher(
            context = applicationContext,
            syncPreferences = syncPreferences,
            onLibraryChanged = { localMediaRepository.invalidateLocalGallerySnapshot() },
        )
        registerWifiSyncNetworkGuard()
        rescheduleBackgroundSync()
        syncCoordinator.warmLocalIndexIfEmpty()
    }

    private fun registerWifiSyncNetworkGuard() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        /** Ignora o primeiro `onAvailable` ao subir o processo (abrir o app ≠ pedir sync). */
        var skipInitialNetworkAvailable = true
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (skipInitialNetworkAvailable) {
                        skipInitialNetworkAvailable = false
                        return
                    }
                    BackgroundSyncScheduler.enqueueSyncSoon(
                        context = this@OneGalleryApplication,
                        prefs = syncPreferences,
                        delaySeconds = 25,
                    )
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    enforceWifiOnlyDuringSync()
                }

                override fun onLost(network: Network) {
                    enforceWifiOnlyDuringSync()
                    skipInitialNetworkAvailable = false
                }
            },
        )
    }

    private fun enforceWifiOnlyDuringSync() {
        if (!syncPreferences.wifiOnly) return
        if (!syncCoordinator.state.value.isRunning) return
        if (syncCoordinator.violatesWifiOnlyPolicy()) {
            syncCoordinator.cancelSyncForWifiPolicy()
        }
    }

    fun rescheduleBackgroundSync() {
        BackgroundSyncScheduler.applyAlwaysOnPolicy(this)
    }

    /** Grava spill alguns segundos após montar catálogo (processo morto sem onStop). */
    fun scheduleSessionSpillPersistDebounced() {
        sessionSpillWriteJob?.cancel()
        sessionSpillWriteJob = applicationScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2_500L)
            persistSessionCatalogForBackground(blocking = false)
        }
    }

    /** Grava catálogo da sessão ao ir para 2º plano (processo morto nos recentes ainda reabre rápido). */
    fun persistSessionCatalogForBackground(blocking: Boolean = false) {
        suspend fun writeSpills() {
            galleryProcessCatalogCache.peek()?.toSnapshotBundle()?.let { bundle ->
                GallerySessionCatalogSpill.save(this@OneGalleryApplication, bundle)
            }
            galleryProcessAlbumListsCache.peek()?.let { albums ->
                GallerySessionAlbumListsSpill.save(this@OneGalleryApplication, albums)
            }
        }
        if (blocking) {
            runBlocking { withContext(Dispatchers.IO) { writeSpills() } }
        } else {
            applicationScope.launch(Dispatchers.IO) { writeSpills() }
        }
    }
}
