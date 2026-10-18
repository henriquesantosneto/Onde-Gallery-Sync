package com.onegallery.sync

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.auth.AuthPreferences
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.AppDatabase
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaLocationResolver
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
import com.onegallery.sync.ui.gallery.GalleryPreferences
import com.onegallery.sync.sync.SyncTaskLauncher
import com.onegallery.sync.worker.SyncWorker
import java.util.concurrent.TimeUnit
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

    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    add(VideoFrameDecoder.Factory())
                }
                .crossfade(true)
                .build(),
        )
        authManager = MsalAuthManager(this, AuthPreferences(this))
        database = AppDatabase.create(this)
        localMediaRepository = LocalMediaRepository(this)
        val mediaLocationResolver = MediaLocationResolver(this)
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
            networkMonitor = NetworkMonitor(this),
            syncPreferences = syncPreferences,
            mediaDeleteRequestRouter = mediaDeleteRequestRouter,
            appForegroundState = appForegroundState,
        )
        syncTaskLauncher = SyncTaskLauncher(applicationScope, syncCoordinator)
        registerWifiSyncNetworkGuard()
        rescheduleBackgroundSync()
    }

    private fun registerWifiSyncNetworkGuard() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    enforceWifiOnlyDuringSync()
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
        val networkType = if (syncPreferences.wifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
