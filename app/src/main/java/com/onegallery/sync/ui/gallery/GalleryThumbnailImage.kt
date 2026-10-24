package com.onegallery.sync.ui.gallery

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.GalleryThumbnailCache
import com.onegallery.sync.ui.theme.OneDriveBlue
import kotlinx.coroutines.Dispatchers
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
    val gridScrolling = LocalGalleryGridScrolling.current
    val zeroDecodeOnScroll = LocalZeroDecodeGridScroll.current
    val scrollDecodeLocked = isGrid && gridScrolling && zeroDecodeOnScroll
    val gpuThumbStyle = isGrid || style == GalleryThumbnailStyle.Filmstrip
    var allowHardwareBitmap by remember(item.canonicalPath, sizePx, style) {
        mutableStateOf(gpuThumbStyle)
    }

    LaunchedEffect(displayData) {
        allowHardwareBitmap = gpuThumbStyle
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
        scrollDecodeLocked,
    ) {
        if (scrollDecodeLocked) return@LaunchedEffect
        if (displayData is java.io.File &&
            !GalleryThumbnailResolver.isStaleDisplayData(item, displayData, mediaCacheRepository)
        ) {
            resolvingThumb = false
            return@LaunchedEffect
        }
        if (displayData !is OneDriveCloudThumbnailKey) {
            resolvingThumb = GalleryThumbnailResolver.shouldShowResolvingSpinner(
                displayData,
                thumbCache,
                item,
            )
        }
        try {
            withContext(Dispatchers.IO) {
                GalleryThumbnailResolver.resolveForGrid(
                    context = context,
                    item = item,
                    thumbnailCache = thumbCache,
                    mediaCacheRepository = mediaCacheRepository,
                    sizePx = sizePx,
                    style = style,
                )
            }?.let { resolved ->
                displayData = resolved
            }
        } finally {
            resolvingThumb = GalleryThumbnailResolver.shouldShowResolvingSpinner(displayData, thumbCache, item)
        }
    }

    val filmstripVideoPending = style == GalleryThumbnailStyle.Filmstrip &&
        item.kind == MediaKind.VIDEO &&
        (displayData == null || displayData is android.net.Uri)
    val memoryCacheKey = galleryThumbnailMemoryCacheKey(
        item.canonicalPath,
        sizePx,
        style,
        allowHardwareBitmap,
    )
    val mayPaint = displayData != null && !filmstripVideoPending &&
        (
            !scrollDecodeLocked ||
                GalleryThumbnailDecodeGate.canPaintGridCellDuringScroll(
                    item = item,
                    displayData = displayData,
                    mediaCacheRepository = mediaCacheRepository,
                    imageLoader = context.imageLoader,
                    memoryCacheKey = memoryCacheKey,
                )
            )
    if (mayPaint) {
        val request = remember(displayData, sizePx, style, item.canonicalPath, allowHardwareBitmap) {
            ImageRequest.Builder(context)
                .data(displayData)
                .size(sizePx)
                .applyGalleryThumbnailLoading(style, allowHardwareBitmap)
                .memoryCacheKey(memoryCacheKey)
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
                    allowHardwareBitmap
                ) {
                    allowHardwareBitmap = false
                }
                if (style == GalleryThumbnailStyle.Filmstrip &&
                    item.kind == MediaKind.VIDEO &&
                    state is AsyncImagePainter.State.Error
                ) {
                    resolvingThumb = true
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
            showCloudIcon = !resolvingThumb && !scrollDecodeLocked &&
                (
                    cloudOnly ||
                        (
                            item.cloudState != CloudSyncState.CLOUD_ONLY &&
                                item.cloudState != CloudSyncState.PENDING_DOWNLOAD
                            )
                    ),
            showLoading = resolvingThumb && !scrollDecodeLocked,
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
