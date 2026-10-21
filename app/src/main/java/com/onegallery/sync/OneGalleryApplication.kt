package com.onegallery.sync

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
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
import com.onegallery.sync.sync.SyncTaskLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var syncTaskLauncher: SyncTaskLauncher
        private set

    lateinit var mediaCacheRepository: MediaCacheRepository
        private set

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
        galleryPreferences = GalleryPreferences(this)
        mediaCacheRepository = MediaCacheRepository(this)
        val saOneDriveRepository = SaOneDriveRepository(this, oneDriveFolderPreferences)
        val localMirrorRepository = LocalMirrorRepository(this)
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
            mediaDeleteRequestRouter = mediaDeleteRequestRouter,
            appForegroundState = appForegroundState,
            backgroundScope = applicationScope,
        )
        syncTaskLauncher = SyncTaskLauncher(applicationScope, syncCoordinator)
        mediaLibraryChangeWatcher = MediaLibraryChangeWatcher(applicationContext, syncPreferences)
        registerWifiSyncNetworkGuard()
        rescheduleBackgroundSync()
    }

    private fun registerWifiSyncNetworkGuard() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (syncPreferences.shouldDeferBackgroundSyncByTime()) return
                    BackgroundSyncScheduler.enqueueSyncSoon(
                        context = this@OneGalleryApplication,
                        prefs = syncPreferences,
                        delaySeconds = 25,
                    )
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    enforceWifiOnlyDuringSync()
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                        !syncPreferences.shouldDeferBackgroundSyncByTime()
                    ) {
                        BackgroundSyncScheduler.enqueueSyncSoon(
                            context = this@OneGalleryApplication,
                            prefs = syncPreferences,
                            delaySeconds = 25,
                        )
                    }
                }

                override fun onLost(network: Network) {
                    enforceWifiOnlyDuringSync()
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
}
