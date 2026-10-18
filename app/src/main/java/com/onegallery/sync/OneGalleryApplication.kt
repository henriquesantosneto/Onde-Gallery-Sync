package com.onegallery.sync

import android.app.Application
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.data.local.db.AppDatabase
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.sync.SyncCoordinator

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

    override fun onCreate() {
        super.onCreate()
        authManager = MsalAuthManager(this)
        database = AppDatabase.create(this)
        localMediaRepository = LocalMediaRepository(this)
        oneDriveRepository = OneDriveRepository(authManager)
        syncCoordinator = SyncCoordinator(
            localMediaRepository = localMediaRepository,
            oneDriveRepository = oneDriveRepository,
            syncMetadataDao = database.syncMetadataDao(),
        )
    }
}
