package com.onegallery.sync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.onegallery.sync.debug.DebugCrashLog
import com.onegallery.sync.ui.OneGalleryApp
import com.onegallery.sync.ui.debug.DebugCrashScreen
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val app = application as OneGalleryApplication
        app.mediaDeleteRequestRouter.attachIntentSenderLauncher {
            mediaDeleteLauncher.launch(it)
        }
        bindUi()
    }

    override fun onStart() {
        super.onStart()
        val app = application as OneGalleryApplication
        app.appForegroundState.setForeground(true)
        app.syncCoordinator.onAppUiVisibilityChanged(inForeground = true)
    }

    override fun onStop() {
        val app = application as OneGalleryApplication
        app.appForegroundState.setForeground(false)
        // Promove antes de voltar ao looper. Gravar o catálogo aqui travava a thread
        // principal e o serviço de sync não chamava startForeground a tempo.
        app.syncCoordinator.holdForegroundIfBackgrounded()
        app.syncCoordinator.onAppUiVisibilityChanged(inForeground = false)
        app.persistSessionCatalogForBackground(blocking = false)
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
                var crashReport by remember { mutableStateOf(DebugCrashLog.read(app)) }
                if (crashReport != null) {
                    DebugCrashScreen(
                        report = crashReport.orEmpty(),
                        onContinue = {
                            DebugCrashLog.clear(app)
                            crashReport = null
                        },
                    )
                    return@OneGalleryTheme
                }
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
