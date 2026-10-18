package com.onegallery.sync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.onegallery.sync.ui.OneGalleryApp
import com.onegallery.sync.ui.theme.OneGalleryTheme

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val mediaDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val app = application as OneGalleryApplication
        app.mediaDeleteRequestRouter.onIntentSenderResult(
            app.mediaDeleteRequestRouter.mapActivityResult(result.resultCode),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OneGalleryApplication
        app.mediaDeleteRequestRouter.attachIntentSenderLauncher {
            mediaDeleteLauncher.launch(it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        bindUi()
    }

    override fun onStart() {
        super.onStart()
        val app = application as OneGalleryApplication
        app.appForegroundState.setForeground(true)
        lifecycleScope.launch {
            app.syncCoordinator.runAutoReleaseIfNeeded()
        }
    }

    override fun onStop() {
        (application as OneGalleryApplication).appForegroundState.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun bindUi() {
        val app = application as OneGalleryApplication
        setContent {
            OneGalleryTheme {
                OneGalleryApp(
                    authManager = app.authManager,
                    localMediaRepository = app.localMediaRepository,
                    syncCoordinator = app.syncCoordinator,
                    syncPreferences = app.syncPreferences,
                    oneDriveFolderPreferences = app.oneDriveFolderPreferences,
                    oneDriveRepository = app.oneDriveRepository,
                    galleryPreferences = app.galleryPreferences,
                    mediaCacheRepository = app.mediaCacheRepository,
                )
            }
        }
    }
}
