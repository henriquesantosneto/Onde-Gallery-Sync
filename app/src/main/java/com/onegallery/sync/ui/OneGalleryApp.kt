package com.onegallery.sync.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.onegallery.sync.ui.gallery.GalleryGridScrollGate
import com.onegallery.sync.ui.gallery.routeKeepsSyncQuiet
import com.onegallery.sync.ui.theme.OneGalleryEdgeToEdgeEffect
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.onegallery.sync.ui.gallery.GridCellExpandOverlay
import com.onegallery.sync.ui.gallery.GridExpandOrigin
import com.onegallery.sync.ui.gallery.MediaDetailScreen
import com.onegallery.sync.ui.onedrive.OneDriveCloudBrowserScreen
import com.onegallery.sync.ui.settings.SettingsScreen
import com.onegallery.sync.ui.sync.SyncStatusScreen
import com.onegallery.sync.system.AppStartupPermissions
import com.onegallery.sync.ui.welcome.WelcomeScreen
import com.onegallery.sync.ui.navigation.decodeBrowseNavKey
import com.onegallery.sync.ui.navigation.encodeBrowseNavKey
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

private const val PLACES_MAP_ROUTE = "places_map"
/** Conteúdo filtrado de um álbum/local; mantém a aba Álbuns selecionada na barra inferior. */
private const val ALBUM_BROWSE_FOLDER_ROUTE = "albums/browse/folder/{folderKey}"
private const val ALBUM_BROWSE_PLACE_ROUTE = "albums/browse/place/{placeKey}/{placeTitle}"

private fun isAlbumBrowseRoute(route: String?): Boolean =
    route?.startsWith("albums/browse/") == true
/** Detalhe de sync (antes era aba própria). */
private const val SYNC_STATUS_ROUTE = "settings/sync"
private fun albumsTabSelected(route: String?): Boolean =
    route == MainTab.Albums.route || isAlbumBrowseRoute(route)

private fun showsMainBottomBar(route: String?): Boolean =
    route in MainTab.entries.map { it.route } || isAlbumBrowseRoute(route)

private fun NavHostController.openGalleryTab(clearBrowseFilters: () -> Unit) {
    clearBrowseFilters()
    if (isAlbumBrowseRoute(currentBackStackEntry?.destination?.route)) {
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
        MainTab.Settings -> {
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
    ) { _ ->
        app.localMediaRepository.invalidateLocalGallerySnapshot()
        // Sync fica só em segundo plano (WorkManager / MediaStore); abrir o app não dispara sync.
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
        val missing = AppStartupPermissions.missingAtStartup(app)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
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
        OneGalleryEdgeToEdgeEffect(currentRoute = null)
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
        OneGalleryEdgeToEdgeEffect(currentRoute = null)
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
        OneGalleryEdgeToEdgeEffect(currentRoute = null)
        val appContext = LocalContext.current.applicationContext as OneGalleryApplication
        WelcomeScreen(
            syncCoordinator = syncCoordinator,
            onFinished = {
                setupDone = true
                appContext.rescheduleBackgroundSync()
            },
            onConnectOneDriveCloud = { openCloudFolderPicker() },
        )
        return
    }

    val appContext = LocalContext.current.applicationContext as OneGalleryApplication
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        appContext.userMessages.events.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    val navController = rememberNavController()
    var openMediaPath by rememberSaveable { mutableStateOf<String?>(null) }
    var gridExpand by remember { mutableStateOf<GridExpandOrigin?>(null) }
    var gridExpandVisible by remember { mutableStateOf(false) }
    val galleryViewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModelFactory(
            localMediaRepository,
            syncCoordinator,
            appContext.galleryThumbnailCache,
            galleryPreferences,
            mediaCacheRepository,
            appContext.userMessages,
            appContext,
        ),
    )
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        galleryViewModel.onReturningToForeground()
        galleryViewModel.resumePendingLocalRemovalIfAccessGranted()
    }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = showsMainBottomBar(currentRoute)
    SideEffect {
        GalleryGridScrollGate.galleryInUse = routeKeepsSyncQuiet(currentRoute)
    }
    DisposableEffect(Unit) {
        onDispose { GalleryGridScrollGate.galleryInUse = false }
    }

    fun openMedia(encoded: String) {
        val path = runCatching {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        }.getOrDefault(encoded)
        val staged = galleryViewModel.takeGridExpand(path)
        val photoExpand = staged?.takeIf { origin ->
            origin.kind == com.onegallery.sync.data.local.MediaKind.IMAGE &&
                origin.bitmap != null &&
                origin.bitmap.isRecycled.not() &&
                origin.width > 0 &&
                origin.height > 0
        }
        if (staged != null && staged !== photoExpand) {
            staged.bitmap?.recycle()
        }
        gridExpand = photoExpand
        gridExpandVisible = photoExpand != null
        openMediaPath = path
    }

    val chromeRoute = if (openMediaPath != null) "media/viewer" else currentRoute
    OneGalleryEdgeToEdgeEffect(currentRoute = chromeRoute)

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = ZeroScaffoldContentInsets,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                when (tab) {
                                    MainTab.Gallery -> {
                                        if (currentRoute == MainTab.Gallery.route) {
                                            galleryViewModel.requestGalleryScrollToTop()
                                        }
                                    }
                                    MainTab.Albums -> {
                                        if (albumsTabSelected(currentRoute)) {
                                            galleryViewModel.requestAlbumsScrollToTop()
                                        }
                                    }
                                    MainTab.Settings -> Unit
                                }
                                navController.openMainTab(tab) {
                                    galleryViewModel.clearBrowseFilters()
                                }
                            },
                            icon = {
                                Icon(
                                    when (tab) {
                                        MainTab.Gallery -> Icons.Outlined.Photo
                                        MainTab.Albums -> Icons.Outlined.PhotoAlbum
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
                    onOpenItem = { encoded -> openMedia(encoded) },
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
                    onOpenFolderAlbum = { filterKey ->
                        galleryViewModel.setAlbumFilter(filterKey)
                        navController.navigate(
                            "albums/browse/folder/${encodeBrowseNavKey(filterKey)}",
                        ) {
                            launchSingleTop = false
                        }
                    },
                    onOpenPlaceAlbum = { title, matchKey ->
                        galleryViewModel.setPlaceFilter(title, matchKey)
                        navController.navigate(
                            "albums/browse/place/${encodeBrowseNavKey(matchKey)}/${encodeBrowseNavKey(title)}",
                        ) {
                            launchSingleTop = false
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
                    onOpenItem = { encoded -> openMedia(encoded) },
                )
            }
            composable(
                route = ALBUM_BROWSE_FOLDER_ROUTE,
                arguments = listOf(
                    navArgument("folderKey") { type = NavType.StringType },
                ),
            ) { entry ->
                val folderKey = entry.arguments?.getString("folderKey")
                    ?.let { runCatching { decodeBrowseNavKey(it) }.getOrNull() }
                    .orEmpty()
                androidx.compose.runtime.key("browse-folder-$folderKey") {
                GalleryScreen(
                    viewModel = galleryViewModel,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    galleryPreferences = galleryPreferences,
                    requiresActiveBrowseFilter = true,
                    routeAlbumFolderKey = folderKey.takeIf { it.isNotBlank() },
                    onOpenItem = { encoded -> openMedia(encoded) },
                    onExitAlbumFilter = {
                        galleryViewModel.clearBrowseFilters()
                        navController.popBackStack(MainTab.Albums.route, inclusive = false)
                    },
                )
                }
            }
            composable(
                route = ALBUM_BROWSE_PLACE_ROUTE,
                arguments = listOf(
                    navArgument("placeKey") { type = NavType.StringType },
                    navArgument("placeTitle") { type = NavType.StringType },
                ),
            ) { entry ->
                val placeKey = entry.arguments?.getString("placeKey")
                    ?.let { runCatching { decodeBrowseNavKey(it) }.getOrNull() }
                    .orEmpty()
                val placeTitle = entry.arguments?.getString("placeTitle")
                    ?.let { runCatching { decodeBrowseNavKey(it) }.getOrNull() }
                    .orEmpty()
                androidx.compose.runtime.key("browse-place-$placeKey") {
                GalleryScreen(
                    viewModel = galleryViewModel,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    galleryPreferences = galleryPreferences,
                    requiresActiveBrowseFilter = true,
                    routePlaceMatchKey = placeKey.takeIf { it.isNotBlank() },
                    routePlaceTitle = placeTitle.takeIf { it.isNotBlank() },
                    onOpenItem = { encoded -> openMedia(encoded) },
                    onExitAlbumFilter = {
                        galleryViewModel.clearBrowseFilters()
                        navController.popBackStack(MainTab.Albums.route, inclusive = false)
                    },
                )
                }
            }
            composable(MainTab.Settings.route) {
                SettingsScreen(
                    authManager = authManager,
                    syncPreferences = syncPreferences,
                    galleryPreferences = galleryPreferences,
                    onOpenCloudFolderPicker = { openCloudFolderPicker() },
                    onMicrosoftSignIn = { openMicrosoftSignIn() },
                    onOpenSyncStatus = {
                        navController.navigate(SYNC_STATUS_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onGalleryBrowseFilterChanged = {
                        galleryViewModel.refreshBrowseVisibilityFromPreferences()
                    },
                )
            }
            composable(SYNC_STATUS_ROUTE) {
                SyncStatusScreen(
                    syncCoordinator = syncCoordinator,
                    syncPreferences = syncPreferences,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
    val viewerPath = openMediaPath
    if (viewerPath != null) {
        val expand = gridExpand?.takeIf { it.canonicalPath == viewerPath }
        var showDetail by remember(viewerPath) { mutableStateOf(expand == null) }
        LaunchedEffect(viewerPath, expand?.canonicalPath) {
            if (expand == null) {
                showDetail = true
                return@LaunchedEffect
            }
            androidx.compose.runtime.withFrameNanos { }
            showDetail = true
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            if (showDetail) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    androidx.compose.runtime.key(viewerPath) {
                        MediaDetailScreen(
                            initialCanonicalPath = viewerPath,
                            viewModel = galleryViewModel,
                            syncCoordinator = syncCoordinator,
                            mediaCacheRepository = mediaCacheRepository,
                            openingPreview = expand?.bitmap,
                            onBack = {
                                gridExpandVisible = false
                                gridExpand = null
                                openMediaPath = null
                            },
                            onOpenPlacesMap = { lat, lng ->
                                gridExpandVisible = false
                                gridExpand = null
                                openMediaPath = null
                                galleryViewModel.requestPlacesMapFocus(lat, lng)
                                navController.navigate(PLACES_MAP_ROUTE)
                            },
                        )
                    }
                }
            }
            if (expand != null && gridExpandVisible) {
                GridCellExpandOverlay(
                    origin = expand,
                    revealContent = showDetail,
                    onFinished = { gridExpandVisible = false },
                )
            }
        }
        DisposableEffect(expand?.bitmap) {
            val bitmap = expand?.bitmap
            onDispose {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }
    }

    // Na foto, o MediaDetailScreen trata voltar (seleção/detalhes/sair). Aqui só grade e álbuns.
    val galleryUi by galleryViewModel.state.collectAsStateWithLifecycle()
    val photoSelectionMode by galleryViewModel.photoSelectionMode.collectAsStateWithLifecycle()
    val onMediaDetail = openMediaPath != null
    val interceptBackForSelection = !onMediaDetail &&
        (photoSelectionMode || galleryUi.albumSelectionMode)
    BackHandler(enabled = interceptBackForSelection) {
        when {
            photoSelectionMode -> galleryViewModel.clearSelection()
            galleryUi.albumSelectionMode -> galleryViewModel.clearAlbumSelection()
        }
    }
}
