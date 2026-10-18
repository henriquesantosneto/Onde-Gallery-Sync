package com.onegallery.sync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.onegallery.sync.ui.OneGalleryApp
import com.onegallery.sync.ui.theme.OneGalleryTheme

class MainActivity : ComponentActivity() {
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
        (application as OneGalleryApplication).mediaDeleteRequestRouter.attachIntentSenderLauncher {
            mediaDeleteLauncher.launch(it)
        }
        bindUi()
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
