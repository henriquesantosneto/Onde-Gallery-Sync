package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.GalleryThumbnailCache
import com.onegallery.sync.ui.theme.OneDriveBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

enum class GalleryThumbnailStyle {
    /** Grade: sem crossfade, decode no tamanho da célula. */
    Grid,
    /** Detalhe / zoom: crossfade suave. */
    Detail,
    /** Carrossel do detalhe: JPEG/cache antes de decodificar vídeo inteiro. */
    Filmstrip,
}

@Composable
fun GalleryThumbnailImage(
    item: GalleryDisplayItem,
    contentDescription: String?,
    thumbnailCache: GalleryThumbnailCache? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    style: GalleryThumbnailStyle = GalleryThumbnailStyle.Grid,
    sizePx: Int,
    onContentAspectRatio: ((Float) -> Unit)? = null,
    /** Faixa do detalhe: incrementar após prefetch para recarregar JPEG de vídeo. */
    refreshNonce: Int = 0,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as OneGalleryApplication }
    val mediaCacheRepository = remember(app) { app.mediaCacheRepository }
    val thumbCache = thumbnailCache ?: app.galleryThumbnailCache
    var displayData by remember(item.canonicalPath, sizePx, style) {
        mutableStateOf<Any?>(
            GalleryThumbnailResolver.initialDisplayData(item, mediaCacheRepository, style),
        )
    }
    var resolvingThumb by remember(item.canonicalPath, sizePx) {
        mutableStateOf(
            GalleryThumbnailResolver.shouldShowResolvingSpinner(displayData, thumbCache, item),
        )
    }
    val isGrid = style == GalleryThumbnailStyle.Grid
    val zeroDecodeOnScroll = LocalZeroDecodeGridScroll.current
    val gpuThumbStyle = isGrid || style == GalleryThumbnailStyle.Filmstrip
    var allowHardwareBitmap by remember(item.canonicalPath, sizePx, style) {
        mutableStateOf(gpuThumbStyle)
    }
    var filmstripVideoRetry by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var gridVideoRetry by remember(item.canonicalPath) { mutableIntStateOf(0) }
    var lastStableGridPaint by remember(item.canonicalPath, sizePx) {
        mutableStateOf<Any?>(null)
    }

    LaunchedEffect(item.canonicalPath, sizePx, style) {
        lastStableGridPaint = null
    }

    suspend fun resolveDisplayData(forceFullDecode: Boolean = false) {
        val diskOnly = isGrid &&
            zeroDecodeOnScroll &&
            !forceFullDecode &&
            GalleryGridScrollGate.isUserScrollingGrid
        withContext(Dispatchers.IO) {
            GalleryThumbnailResolver.resolveForGrid(
                context = context,
                item = item,
                thumbnailCache = thumbCache,
                mediaCacheRepository = mediaCacheRepository,
                sizePx = sizePx,
                style = style,
                diskCacheOnly = diskOnly,
            )
        }?.let { resolved ->
            if (resolved != displayData) {
                displayData = resolved
            }
            if (isGrid) {
                lastStableGridPaint = resolved
            }
        }
    }

    LaunchedEffect(
        item.canonicalPath,
        item.localUri,
        item.thumbnailModel,
        item.oneDriveItemId,
        item.cachedPlaceholderThumb?.absolutePath,
        sizePx,
        style,
        refreshNonce,
        zeroDecodeOnScroll,
        gridVideoRetry,
    ) {
        val filmstripVideo = style == GalleryThumbnailStyle.Filmstrip && item.kind == MediaKind.VIDEO
        if (filmstripVideo && displayData is Uri) {
            displayData = null
        }
        val cachedFile = displayData as? java.io.File
        if (cachedFile != null) {
            val stale = GalleryThumbnailResolver.isStaleDisplayData(
                item,
                cachedFile,
                mediaCacheRepository,
            )
            val okVideoThumb = !filmstripVideo ||
                VideoThumbnailHelper.isGalleryVideoThumbnailFile(cachedFile)
            if (!stale && okVideoThumb) {
                resolvingThumb = false
                return@LaunchedEffect
            }
        }
        if (displayData !is OneDriveCloudThumbnailKey) {
            resolvingThumb = GalleryThumbnailResolver.shouldShowResolvingSpinner(
                displayData,
                thumbCache,
                item,
            )
        }
        try {
            resolveDisplayData(forceFullDecode = gridVideoRetry > 0)
        } finally {
            resolvingThumb = GalleryThumbnailResolver.shouldShowResolvingSpinner(displayData, thumbCache, item)
        }
    }

    LaunchedEffect(filmstripVideoRetry, item.canonicalPath, sizePx, style, refreshNonce) {
        if (filmstripVideoRetry == 0) return@LaunchedEffect
        if (style != GalleryThumbnailStyle.Filmstrip || item.kind != MediaKind.VIDEO) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                thumbCache.ensureThumbnailCached(
                    item.canonicalPath,
                    preferCloudFirst = item.localUri == null && item.oneDriveItemId != null,
                    cloudVideoAggressive = true,
                    localSourcesOnly = item.localUri != null,
                    lightweightPrefetch = false,
                )
                GalleryThumbnailResolver.resolveForGrid(
                    context = context,
                    item = item,
                    thumbnailCache = thumbCache,
                    mediaCacheRepository = mediaCacheRepository,
                    sizePx = sizePx,
                    style = style,
                )
            }?.let { resolved ->
                if (resolved !is Uri) {
                    displayData = resolved
                }
            }
        } finally {
            resolvingThumb = GalleryThumbnailResolver.shouldShowResolvingSpinner(displayData, thumbCache, item)
        }
    }

    if (isGrid && zeroDecodeOnScroll) {
        LaunchedEffect(item.canonicalPath, zeroDecodeOnScroll) {
            snapshotFlow { GalleryGridScrollGate.isUserScrollingGrid }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (scrolling) return@collect
                    val currentFile = displayData as? java.io.File
                    if (currentFile != null &&
                        !GalleryThumbnailResolver.isStaleDisplayData(
                            item,
                            currentFile,
                            mediaCacheRepository,
                        )
                    ) {
                        return@collect
                    }
                    if (displayData != null && displayData !is Uri) {
                        return@collect
                    }
                    resolveDisplayData(forceFullDecode = true)
                    resolvingThumb = GalleryThumbnailResolver.shouldShowResolvingSpinner(
                        displayData,
                        thumbCache,
                        item,
                    )
                }
        }
    }

    val paintModel = if (isGrid) {
        val candidate = displayData
            ?: mediaCacheRepository.displayableThumbnailIfCached(
                item.canonicalPath,
                item.oneDriveItemId,
            )
        when {
            candidate != null -> {
                lastStableGridPaint = candidate
                candidate
            }
            lastStableGridPaint != null -> lastStableGridPaint
            else -> null
        }
    } else {
        val raw = displayData
        if (style == GalleryThumbnailStyle.Filmstrip &&
            item.kind == MediaKind.VIDEO &&
            raw is Uri
        ) {
            null
        } else {
            raw
        }
    }

    val filmstripVideoPending = style == GalleryThumbnailStyle.Filmstrip &&
        item.kind == MediaKind.VIDEO &&
        paintModel == null &&
        resolvingThumb
    if (paintModel != null && !filmstripVideoPending) {
        val gridVideoDecode = isGrid &&
            item.kind == MediaKind.VIDEO &&
            (paintModel is Uri || paintModel is Bitmap)
        val requestHardware = allowHardwareBitmap && !gridVideoDecode
        val request = remember(paintModel, sizePx, style, item.canonicalPath, requestHardware, gridVideoDecode) {
            ImageRequest.Builder(context)
                .data(paintModel)
                .size(sizePx)
                .applyGalleryThumbnailLoading(
                    style = style,
                    allowHardwareBitmap = requestHardware,
                    videoDecode = gridVideoDecode,
                )
                .memoryCacheKey(
                    galleryThumbnailMemoryCacheKey(
                        item.canonicalPath,
                        sizePx,
                        style,
                        requestHardware,
                    ),
                )
                .diskCacheKey("${item.canonicalPath}:$sizePx")
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier.galleryGridThumbnailLayer(style),
            contentScale = contentScale,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error &&
                    gpuThumbStyle &&
                    allowHardwareBitmap &&
                    !GalleryGridScrollGate.isUserScrollingGrid
                ) {
                    allowHardwareBitmap = false
                }
                if (isGrid &&
                    item.kind == MediaKind.VIDEO &&
                    state is AsyncImagePainter.State.Error &&
                    !GalleryGridScrollGate.isUserScrollingGrid
                ) {
                    gridVideoRetry++
                }
                if (style == GalleryThumbnailStyle.Filmstrip &&
                    item.kind == MediaKind.VIDEO &&
                    state is AsyncImagePainter.State.Error
                ) {
                    if (displayData is Uri) {
                        displayData = null
                    }
                    resolvingThumb = true
                    filmstripVideoRetry++
                }
                if (onContentAspectRatio != null && state is AsyncImagePainter.State.Success) {
                    val size = state.painter.intrinsicSize
                    if (size.width.isFinite() && size.height.isFinite() && size.height > 0f) {
                        onContentAspectRatio(size.width / size.height)
                    }
                }
            },
        )
    } else {
        val cloudOnly = item.cloudState == CloudSyncState.CLOUD_ONLY ||
            item.cloudState == CloudSyncState.PENDING_DOWNLOAD
        MediaThumbnailPlaceholder(
            modifier = modifier,
            showCloudIcon = !resolvingThumb &&
                (
                    cloudOnly ||
                        (
                            item.cloudState != CloudSyncState.CLOUD_ONLY &&
                                item.cloudState != CloudSyncState.PENDING_DOWNLOAD
                            )
                    ),
            showLoading = resolvingThumb,
        )
    }
}

@Composable
fun MediaThumbnailPlaceholder(
    modifier: Modifier = Modifier,
    showCloudIcon: Boolean = true,
    showLoading: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            showLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                )
            }
            showCloudIcon -> {
                Icon(
                    Icons.Outlined.Cloud,
                    contentDescription = null,
                    tint = OneDriveBlue,
                )
            }
        }
    }
}
