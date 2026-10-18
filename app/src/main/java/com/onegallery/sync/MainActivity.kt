package com.onegallery.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.onegallery.sync.ui.OneGalleryApp
import com.onegallery.sync.ui.theme.OneGalleryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                )
            }
        }
    }
}
