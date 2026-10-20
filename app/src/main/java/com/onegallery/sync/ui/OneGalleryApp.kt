package com.onegallery.sync.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.onegallery.sync.ui.theme.OneGalleryEdgeToEdgeEffect
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.onegallery.sync.R
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.onedrive.OneDriveFolderPreferences
import com.onegallery.sync.data.onedrive.OneDriveRepository
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences
import com.onegallery.sync.sync.SyncTaskLauncher
import com.onegallery.sync.ui.albums.AlbumsScreen
import com.onegallery.sync.ui.places.PlacesMapScreen
import com.onegallery.sync.ui.auth.SignInGate
import com.onegallery.sync.ui.gallery.GalleryScreen
import com.onegallery.sync.ui.gallery.GalleryPreferences
import com.onegallery.sync.ui.gallery.GalleryViewModel
import com.onegallery.sync.ui.gallery.GalleryViewModelFactory
import com.onegallery.sync.ui.gallery.MediaDetailScreen
import com.onegallery.sync.ui.onedrive.OneDriveCloudBrowserScreen
import com.onegallery.sync.ui.settings.SettingsScreen
import com.onegallery.sync.ui.sync.SyncStatusScreen
import com.onegallery.sync.ui.welcome.WelcomeScreen
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

private const val PLACES_MAP_ROUTE = "places_map"
/** Conteúdo filtrado de um álbum/local; mantém a aba Álbuns selecionada na barra inferior. */
private const val ALBUMS_BROWSE_ROUTE = "albums/browse"

private fun albumsTabSelected(route: String?): Boolean =
    route == MainTab.Albums.route || route == ALBUMS_BROWSE_ROUTE

private fun showsMainBottomBar(route: String?): Boolean =
    route in MainTab.entries.map { it.route } || route == ALBUMS_BROWSE_ROUTE

private fun NavHostController.openGalleryTab(clearBrowseFilters: () -> Unit) {
    clearBrowseFilters()
    if (currentBackStackEntry?.destination?.route == ALBUMS_BROWSE_ROUTE) {
        popBackStack()
    }
    navigate(MainTab.Gallery.route) {
        popUpTo(MainTab.Gallery.route) {
            inclusive = false
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.ensureAtAlbumsRoot() {
    repeat(8) {
        if (currentBackStackEntry?.destination?.route == MainTab.Albums.route) return
        if (!popBackStack()) return
    }
    if (currentBackStackEntry?.destination?.route != MainTab.Albums.route) {
        navigate(MainTab.Albums.route) {
            launchSingleTop = true
        }
    }
}

private fun NavHostController.openAlbumsTab(clearBrowseFilters: () -> Unit) {
    clearBrowseFilters()
    navigate(MainTab.Albums.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
    // restoreState pode reabrir albums/browse (grade) ou mapa por cima da lista de álbuns
    ensureAtAlbumsRoot()
    runCatching {
        getBackStackEntry(MainTab.Albums.route).savedStateHandle["resetAlbumsSubTab"] = true
    }
}

private fun NavHostController.openMainTab(tab: MainTab, clearBrowseFilters: () -> Unit) {
    when (tab) {
        MainTab.Gallery -> openGalleryTab(clearBrowseFilters)
        MainTab.Albums -> openAlbumsTab(clearBrowseFilters)
        MainTab.Sync, MainTab.Settings -> {
            clearBrowseFilters()
            navigate(tab.route) {
                popUpTo(graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}

private enum class MainTab(val route: String) {
    Gallery("gallery"),
    Albums("albums"),
    Sync("sync"),
    Settings("settings"),
}

private enum class CloudSetupStep {
    Hidden,
    SignIn,
    Browse,
}

@Composable
fun OneGalleryApp(
    authManager: MsalAuthManager,
    localMediaRepository: LocalMediaRepository,
    syncCoordinator: SyncCoordinator,
    syncPreferences: SyncPreferences,
    oneDriveFolderPreferences: OneDriveFolderPreferences,
    oneDriveRepository: OneDriveRepository,
    galleryPreferences: GalleryPreferences,
    mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
) {
    OneGalleryEdgeToEdgeEffect()
    var setupDone by rememberSaveable { mutableStateOf(syncPreferences.hasCompletedSetup) }
    var cloudSetupStepOrdinal by rememberSaveable { mutableStateOf(CloudSetupStep.Hidden.ordinal) }
    var postSignInStepOrdinal by rememberSaveable {
        mutableStateOf(CloudSetupStep.Browse.ordinal)
    }
    val cloudSetupStep = CloudSetupStep.entries[
        cloudSetupStepOrdinal.coerceIn(CloudSetupStep.entries.indices)
    ]
    fun setCloudSetupStep(step: CloudSetupStep) {
        cloudSetupStepOrdinal = step.ordinal
    }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as OneGalleryApplication
    val syncTasks = remember { SyncTaskLauncher.from(app) }

    val authState by authManager.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val mediaOk = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                results[Manifest.permission.READ_MEDIA_IMAGES] == true
            else -> results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        val locationOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            results[Manifest.permission.ACCESS_MEDIA_LOCATION] == true
        if (mediaOk && locationOk) {
            syncTasks.runBidirectionalSync(userInitiated = false)
        }
    }

    LaunchedEffect(Unit) {
        authManager.initialize()
        setupDone = syncPreferences.hasCompletedSetup
    }

    LaunchedEffect(setupDone, syncPreferences.syncMode) {
        if (!setupDone || syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return@LaunchedEffect
        authManager.ensureGraphSession()
    }

    LaunchedEffect(Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(setupDone, syncPreferences.syncMode, authState.isSignedIn) {
        if (!setupDone) return@LaunchedEffect
        when (syncPreferences.syncMode) {
            SyncMode.ONEDRIVE_FOLDER -> if (oneDriveFolderPreferences.treeUri != null) {
                syncTasks.runBidirectionalSync()
            }
            SyncMode.ONEDRIVE_LOCAL_MIRROR -> syncTasks.runBidirectionalSync()
            SyncMode.ONEDRIVE_GRAPH -> if (authState.isSignedIn) {
                syncTasks.runBidirectionalSync()
            }
            SyncMode.LOCAL_ONLY -> Unit
        }
    }

    LaunchedEffect(authState.isSignedIn, cloudSetupStep, setupDone) {
        if (cloudSetupStep != CloudSetupStep.SignIn || !authState.isSignedIn) return@LaunchedEffect
        val next = CloudSetupStep.entries[
            postSignInStepOrdinal.coerceIn(CloudSetupStep.entries.indices)
        ]
        setCloudSetupStep(next)
        if (next == CloudSetupStep.Hidden && setupDone) {
            syncTasks.runBidirectionalSync(userInitiated = true)
        }
    }

    fun openMicrosoftSignIn() {
        postSignInStepOrdinal = CloudSetupStep.Hidden.ordinal
        setCloudSetupStep(CloudSetupStep.SignIn)
    }

    fun openCloudFolderPicker() {
        postSignInStepOrdinal = CloudSetupStep.Browse.ordinal
        setCloudSetupStep(CloudSetupStep.SignIn)
    }

    LaunchedEffect(Unit) {
        authManager.interactionRequired.collect {
            if (cloudSetupStepOrdinal == CloudSetupStep.Hidden.ordinal) {
                postSignInStepOrdinal = CloudSetupStep.Hidden.ordinal
                setCloudSetupStep(CloudSetupStep.SignIn)
            }
        }
    }

    if (cloudSetupStep == CloudSetupStep.Browse) {
        OneDriveCloudBrowserScreen(
            oneDriveRepository = oneDriveRepository,
            onFolderSelected = { itemId, path, label ->
                setCloudSetupStep(CloudSetupStep.Hidden)
                setupDone = true
                syncTasks.connectGraphSyncFolderAndSync(itemId, path, label, userInitiated = true)
            },
            onBack = { setCloudSetupStep(CloudSetupStep.Hidden) },
        )
        return
    }

    if (cloudSetupStep == CloudSetupStep.SignIn) {
        SignInGate(
            authManager = authManager,
            msalConfigurationError = null,
            onClientConfigured = {
                scope.launch {
                    authManager.initialize().onSuccess {
                        if (authState.isSignedIn) {
                            setCloudSetupStep(CloudSetupStep.Browse)
                        }
                    }
                }
            },
            onBack = { setCloudSetupStep(CloudSetupStep.Hidden) },
            onSignedIn = { setCloudSetupStep(CloudSetupStep.Browse) },
        )
        return
    }

    if (!setupDone) {
        WelcomeScreen(
            syncCoordinator = syncCoordinator,
            onFinished = { setupDone = true },
            onConnectOneDriveCloud = { openCloudFolderPicker() },
        )
        return
    }

    val navController = rememberNavController()
    val galleryViewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModelFactory(
            localMediaRepository,
            syncCoordinator,
            galleryPreferences,
            mediaCacheRepository,
        ),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = showsMainBottomBar(currentRoute)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = ZeroScaffoldContentInsets,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = when (tab) {
                                MainTab.Albums -> albumsTabSelected(currentRoute)
                                else -> currentRoute == tab.route
                            },
                            onClick = {
                                navController.openMainTab(tab) {
                                    galleryViewModel.clearBrowseFilters()
                                }
                            },
                            icon = {
                                Icon(
                                    when (tab) {
                                        MainTab.Gallery -> Icons.Outlined.Photo
                                        MainTab.Albums -> Icons.Outlined.PhotoAlbum
                                        MainTab.Sync -> Icons.Outlined.CloudSync
                                        MainTab.Settings -> Icons.Outlined.Settings
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = {
                                Text(
                                    when (tab) {
                                        MainTab.Gallery -> stringResource(R.string.tab_gallery)
                                        MainTab.Albums -> stringResource(R.string.tab_albums)
                                        MainTab.Sync -> stringResource(R.string.tab_sync)
                                        MainTab.Settings -> stringResource(R.string.tab_settings)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainTab.Gallery.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable(MainTab.Gallery.route) {
                GalleryScreen(
                    viewModel = galleryViewModel,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    galleryPreferences = galleryPreferences,
                    onOpenItem = { encoded ->
                        navController.navigate("media/$encoded")
                    },
                    onExitAlbumFilter = {
                        navController.navigate(MainTab.Albums.route) {
                            popUpTo(MainTab.Gallery.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(MainTab.Albums.route) { entry ->
                AlbumsScreen(
                    viewModel = galleryViewModel,
                    galleryPreferences = galleryPreferences,
                    albumsBackStackEntry = entry,
                    onOpenAlbum = {
                        navController.navigate(ALBUMS_BROWSE_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onOpenMap = {
                        navController.navigate(PLACES_MAP_ROUTE)
                    },
                )
            }
            composable(PLACES_MAP_ROUTE) {
                PlacesMapScreen(
                    viewModel = galleryViewModel,
                    syncCoordinator = syncCoordinator,
                    syncTasks = syncTasks,
                    onBack = { navController.popBackStack() },
                    onOpenItem = { encoded ->
                        navController.navigate("media/$encoded")
                    },
                )
            }
            composable(ALBUMS_BROWSE_ROUTE) {
                GalleryScreen(
                    viewModel = galleryViewModel,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    galleryPreferences = galleryPreferences,
                    requiresActiveBrowseFilter = true,
                    onOpenItem = { encoded ->
                        navController.navigate("media/$encoded")
                    },
                    onExitAlbumFilter = {
                        galleryViewModel.clearBrowseFilters()
                        navController.popBackStack(MainTab.Albums.route, inclusive = false)
                    },
                )
            }
            composable(MainTab.Sync.route) {
                SyncStatusScreen(
                    syncCoordinator = syncCoordinator,
                    syncPreferences = syncPreferences,
                )
            }
            composable(MainTab.Settings.route) {
                SettingsScreen(
                    authManager = authManager,
                    syncPreferences = syncPreferences,
                    onOpenCloudFolderPicker = { openCloudFolderPicker() },
                    onMicrosoftSignIn = { openMicrosoftSignIn() },
                )
            }
            composable(
                route = "media/{encodedPath}",
                arguments = listOf(navArgument("encodedPath") { type = NavType.StringType }),
            ) { entry ->
                val encoded = entry.arguments?.getString("encodedPath").orEmpty()
                val path = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                MediaDetailScreen(
                    initialCanonicalPath = path,
                    viewModel = galleryViewModel,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    // Registrado por último: prioridade sobre NavHost (gesto Voltar / borda esquerda no S22).
    val galleryUi by galleryViewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = galleryUi.selectionMode) {
        galleryViewModel.clearSelection()
    }
    BackHandler(enabled = galleryUi.albumSelectionMode) {
        galleryViewModel.clearAlbumSelection()
    }
}
