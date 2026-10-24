package com.onegallery.sync.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visualizador experimental (estilo Samsung). Rota separada — o [MediaDetailScreen] padrão
 * permanece intacto até você decidir trocar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaDetailLabScreen(
    initialCanonicalPath: String,
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onBack: () -> Unit,
    onOpenStandardDetail: (canonicalPath: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveItems = state.libraryItems
    val context = LocalContext.current
    val galleryPreferences = remember(context) {
        (context.applicationContext as OneGalleryApplication).galleryPreferences
    }
    val pagerScope = rememberCoroutineScope()

    if (liveItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(600)
            if (viewModel.state.value.libraryItems.isEmpty()) onBack()
        }
        return
    }

    val itemsByPath = remember(liveItems) { liveItems.associateBy { it.canonicalPath } }
    val timelineGrouping = timelineGroupingForGridScale(galleryPreferences.galleryGridZoom)
    val contextItems = remember(
        liveItems,
        state.albumFilter,
        state.locationFilter,
        state.placeBrowseMatchKey,
        state.geoItems,
        initialCanonicalPath,
        state.sortOrder,
        timelineGrouping,
    ) {
        detailContextItems(
            allItems = liveItems,
            albumFilter = state.albumFilter,
            locationFilter = state.locationFilter,
            placeMatchKey = state.placeBrowseMatchKey,
            anchorPath = initialCanonicalPath,
            geoItems = state.geoItems,
            sortOrder = state.sortOrder,
            timelineGrouping = timelineGrouping,
        )
    }
    val pagerPaths = remember(contextItems) { contextItems.map { it.canonicalPath } }
    val initialPage = pagerPaths.indexOf(initialCanonicalPath).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (pagerPaths.size - 1).coerceAtLeast(0)),
    ) { pagerPaths.size.coerceAtLeast(1) }

    val currentPath = pagerPaths.getOrNull(pagerState.currentPage)
    val filmstripItems = remember(pagerPaths, itemsByPath, contextItems) {
        val pool = itemsByPath.ifEmpty { contextItems.associateBy { it.canonicalPath } }
        pagerPaths.mapNotNull { path -> pool[path] }
    }

    var activeVideoPath by remember(pagerPaths) {
        mutableStateOf(pagerPaths.getOrNull(pagerState.currentPage))
    }
    LaunchedEffect(pagerState.currentPage, pagerPaths) {
        activeVideoPath = pagerPaths.getOrNull(pagerState.currentPage)
    }

    BackHandler(onBack = onBack)

    val filmstripVisible = filmstripItems.size > 1
    val playerBottomChromeInset = if (filmstripVisible) 100.dp else 36.dp

    Scaffold(
        contentWindowInsets = ZeroScaffoldContentInsets,
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = buildString {
                            append("Player lab")
                            currentPath?.let { path ->
                                itemsByPath[path]?.displayName?.let { name ->
                                    append(" · ")
                                    append(name)
                                }
                            }
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> pagerPaths.getOrElse(page) { "empty-$page" } },
            ) { page ->
                val path = pagerPaths.getOrNull(page) ?: return@HorizontalPager
                val liveItem = itemsByPath[path]
                val pageActive = pagerState.currentPage == page
                LabMediaDetailPage(
                    canonicalPath = path,
                    liveItem = liveItem,
                    isActivePage = pageActive,
                    shouldPlayVideo = activeVideoPath == path,
                    bottomChromeInset = playerBottomChromeInset,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    onOpenStandardDetail = { onOpenStandardDetail(path) },
                )
            }

            if (filmstripVisible) {
                MediaDetailFilmstrip(
                    items = filmstripItems,
                    currentCanonicalPath = currentPath,
                    onSelectPath = { path ->
                        val index = pagerPaths.indexOf(path)
                        if (index >= 0) {
                            activeVideoPath = path
                            pagerScope.launch { pagerState.scrollToPage(index) }
                        }
                    },
                    syncCoordinator = syncCoordinator,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun LabMediaDetailPage(
    canonicalPath: String,
    liveItem: GalleryDisplayItem?,
    isActivePage: Boolean,
    shouldPlayVideo: Boolean,
    bottomChromeInset: Dp,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onOpenStandardDetail: () -> Unit,
) {
    var item by remember(canonicalPath, liveItem?.canonicalPath) { mutableStateOf(liveItem) }
    var loading by remember(canonicalPath) { mutableStateOf(liveItem == null) }

    LaunchedEffect(canonicalPath, isActivePage, liveItem?.cachedOriginal?.absolutePath) {
        if (!isActivePage && liveItem != null && item == null) {
            item = liveItem
            loading = false
            return@LaunchedEffect
        }
        if (!isActivePage) return@LaunchedEffect
        loading = item == null
        val loaded = withContext(Dispatchers.Default) {
            syncCoordinator.getMetadata(canonicalPath)?.let { entity ->
                GalleryMapper.toDisplayItem(entity, mediaCacheRepository)
            }
        }
        if (loaded != null) item = loaded
        loading = false
    }

    LaunchedEffect(canonicalPath, isActivePage) {
        if (isActivePage) {
            withContext(Dispatchers.IO) {
                syncCoordinator.recordLocalMediaAccess(canonicalPath)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading && item == null -> CircularProgressIndicator(color = Color.White)
            item == null -> Text("Item não encontrado.", color = Color.White)
            else -> {
                val display = item!!
                when {
                    GalleryMapper.isAudioFileName(display.displayName) -> {
                        val playback = display.playbackUri()
                        if (playback == null) {
                            LabUnsupportedMediaMessage(
                                message = "Áudio sem arquivo local. Abra no visualizador padrão ou sincronize.",
                                onOpenStandardDetail = onOpenStandardDetail,
                            )
                        } else {
                            LabSamsungStyleMediaPlayer(
                                uri = playback,
                                modifier = Modifier.fillMaxSize(),
                                isActive = shouldPlayVideo,
                                kind = LabMediaPlaybackKind.Audio,
                                displayName = display.displayName,
                                artworkItem = display,
                                bottomChromeInset = bottomChromeInset,
                            )
                        }
                    }
                    display.kind == MediaKind.VIDEO -> {
                        val playback = display.playbackUri()
                        if (playback == null) {
                            LabUnsupportedMediaMessage(
                                message = "Vídeo sem URI de reprodução. Use o visualizador padrão.",
                                onOpenStandardDetail = onOpenStandardDetail,
                            )
                        } else {
                            LabSamsungStyleMediaPlayer(
                                uri = playback,
                                modifier = Modifier.fillMaxSize(),
                                isActive = shouldPlayVideo,
                                kind = LabMediaPlaybackKind.Video,
                                displayName = display.displayName,
                                bottomChromeInset = bottomChromeInset,
                            )
                        }
                    }
                    display.detailPreviewModel() != null -> {
                        LabSamsungStylePhotoViewer(
                            item = display,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        LabUnsupportedMediaMessage(
                            message = "Prévia indisponível neste lab. Detalhes completos no visualizador padrão.",
                            onOpenStandardDetail = onOpenStandardDetail,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabUnsupportedMediaMessage(
    message: String,
    onOpenStandardDetail: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onOpenStandardDetail,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Abrir visualizador padrão")
            }
        }
    }
}
