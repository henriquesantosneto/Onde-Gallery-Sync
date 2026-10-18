package com.onegallery.sync.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.onegallery.sync.R
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.albums.AlbumsScreen
import com.onegallery.sync.ui.auth.SignInGate
import com.onegallery.sync.ui.gallery.GalleryScreen
import com.onegallery.sync.ui.gallery.GalleryViewModel
import com.onegallery.sync.ui.gallery.GalleryViewModelFactory
import com.onegallery.sync.ui.settings.SettingsScreen
import com.onegallery.sync.ui.sync.SyncStatusScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings

private enum class MainTab(val route: String) {
    Gallery("gallery"),
    Albums("albums"),
    Sync("sync"),
    Settings("settings"),
}

@Composable
fun OneGalleryApp(
    authManager: MsalAuthManager,
    localMediaRepository: LocalMediaRepository,
    syncCoordinator: SyncCoordinator,
) {
    var msalReady by remember { mutableStateOf(false) }
    var msalError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authManager) {
        authManager.initialize()
            .onSuccess { msalReady = true }
            .onFailure { msalError = it.message }
    }

    val authState by authManager.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    if (!msalReady && msalError == null) {
        Text("Inicializando…")
        return
    }

    if (!authState.isSignedIn) {
        SignInGate(
            authManager = authManager,
            msalConfigurationError = msalError,
        )
        return
    }

    val navController = rememberNavController()
    val galleryViewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModelFactory(localMediaRepository, syncCoordinator),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = navController.currentDestination?.route == MainTab.Gallery.route,
                    onClick = { navController.navigate(MainTab.Gallery.route) },
                    icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_gallery)) },
                )
                NavigationBarItem(
                    selected = navController.currentDestination?.route == MainTab.Albums.route,
                    onClick = { navController.navigate(MainTab.Albums.route) },
                    icon = { Icon(Icons.Outlined.Collections, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_albums)) },
                )
                NavigationBarItem(
                    selected = navController.currentDestination?.route == MainTab.Sync.route,
                    onClick = { navController.navigate(MainTab.Sync.route) },
                    icon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_sync)) },
                )
                NavigationBarItem(
                    selected = navController.currentDestination?.route == MainTab.Settings.route,
                    onClick = { navController.navigate(MainTab.Settings.route) },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainTab.Gallery.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(MainTab.Gallery.route) {
                GalleryScreen(viewModel = galleryViewModel)
            }
            composable(MainTab.Albums.route) {
                AlbumsScreen(viewModel = galleryViewModel)
            }
            composable(MainTab.Sync.route) {
                SyncStatusScreen(syncCoordinator = syncCoordinator)
            }
            composable(MainTab.Settings.route) {
                SettingsScreen(authManager = authManager)
            }
        }
    }
}
