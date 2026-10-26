package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.debug.DebugCrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val albumCoverResolveSlots = Semaphore(2)

/**
 * Capa na grade de álbuns. Reaproveita a imagem já mostrada e só busca outra
 * com a lista parada, para a rolagem não piscar nem travar.
 */
@Composable
fun AlbumGridCoverImage(
    album: MediaAlbum,
    isPlace: Boolean,
    fallbackCover: Any?,
    coverRevision: Long,
    resolveLiveCover: suspend (MediaAlbum) -> LiveAlbumCover,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaCacheRepository = remember(context) {
        (context.applicationContext as OneGalleryApplication).mediaCacheRepository
    }
    val cachedCover = AlbumCoverBitmapCache.get(album.bucketId)
    val safeFallback = fallbackCover?.takeUnless { it.isBlankSyncedCover(mediaCacheRepository) }
    var displayModel by remember(album.bucketId) {
        mutableStateOf(albumCoverDisplaySeed(cachedCover?.model, safeFallback))
    }
    var coverIsVideo by remember(album.bucketId) { mutableStateOf(cachedCover?.isVideo == true) }
    var coverCacheKey by remember(album.bucketId) {
        mutableStateOf(cachedCover?.cacheKey ?: album.bucketId)
    }

    LaunchedEffect(album.bucketId, coverRevision) {
        try {
        val hit = AlbumCoverBitmapCache.get(album.bucketId)
        if (hit != null &&
            !hit.empty &&
            hit.revision == coverRevision &&
            hit.model.isPaintableAlbumCoverModel()
        ) {
            if (displayModel != hit.model) {
                displayModel = hit.model
                coverIsVideo = hit.isVideo
                coverCacheKey = hit.cacheKey
            }
            return@LaunchedEffect
        }
        GalleryGridScrollGate.awaitIdleIfZeroDecodeEnabled(true, maxWaitMs = 2_500L)
        val outcome = albumCoverResolveSlots.withPermit {
            GalleryGridScrollGate.awaitIdleIfZeroDecodeEnabled(true, maxWaitMs = 2_500L)
            withContext(Dispatchers.IO) {
                val live = resolveLiveCover(album)
                val raw = live.sourceItem?.let { item ->
                    resolveGalleryCoverModel(item, mediaCacheRepository)
                } ?: live.displayModel
                val videoCover = raw.asDeviceVideoCover()
                val paintable = when {
                    raw is android.net.Uri -> GalleryThumbnailModels.loadDeviceImagePoster(
                        context,
                        raw,
                        live.sourceItem?.localMediaId,
                        480,
                    )
                    videoCover != null -> GalleryThumbnailModels.loadDeviceVideoPoster(
                        context,
                        videoCover.uri,
                        videoCover.mediaId ?: live.sourceItem?.localMediaId,
                        480,
                    )
                    else -> raw
                }
                ResolvedAlbumCover(
                    model = paintable?.takeIf { it.isPaintableAlbumCoverModel() },
                    isVideo = live.sourceItem?.kind == MediaKind.VIDEO || videoCover != null,
                    cacheKey = live.sourceItem?.canonicalPath ?: album.bucketId,
                )
            }
        }
        val resolved = outcome.model
        if (resolved.isPaintableAlbumCoverModel()) {
            val keepShown = outcome.cacheKey == coverCacheKey &&
                displayModel.isPaintableAlbumCoverModel()
            val shown = if (keepShown) displayModel!! else resolved!!
            AlbumCoverBitmapCache.put(
                album.bucketId,
                shown,
                outcome.isVideo,
                outcome.cacheKey,
                coverRevision,
            )
            if (!keepShown) {
                displayModel = resolved
                coverIsVideo = outcome.isVideo
                coverCacheKey = outcome.cacheKey
            }
            return@LaunchedEffect
        }
        if (displayModel.isPaintableAlbumCoverModel() &&
            !displayModel.isBlankSyncedCover(mediaCacheRepository)
        ) {
            AlbumCoverBitmapCache.put(
                album.bucketId,
                displayModel!!,
                coverIsVideo,
                coverCacheKey,
                coverRevision,
            )
            return@LaunchedEffect
        }
        val fallback = nextAlbumCoverModel(displayModel, resolved = null, fallback = safeFallback)
        if (fallback == null) {
            AlbumCoverBitmapCache.markUnresolved(album.bucketId, coverRevision)
            return@LaunchedEffect
        }
        displayModel = fallback
        coverIsVideo = false
        coverCacheKey = album.bucketId
        AlbumCoverBitmapCache.put(album.bucketId, fallback, false, album.bucketId, coverRevision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugCrashLog.mark("capa ${album.bucketId} ${error.javaClass.simpleName}")
        }
    }
    val coilModel = remember(displayModel, coverCacheKey) { coverCoilModel(displayModel) }
    val paintModel = coilModel

    val shownBitmap = (displayModel as? Bitmap)?.takeIf { !it.isRecycled }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            shownBitmap != null -> {
                Image(
                    bitmap = shownBitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .galleryGridThumbnailLayer(GalleryThumbnailStyle.Grid),
                    contentScale = ContentScale.Crop,
                )
            }
            paintModel != null -> {
                val coverSizePx = 480
                val request = remember(paintModel, coverCacheKey, coverRevision) {
                    ImageRequest.Builder(context)
                        .data(paintModel)
                        .size(coverSizePx)
                        .applyGalleryThumbnailLoading(
                            GalleryThumbnailStyle.Grid,
                            allowHardwareBitmap = false,
                        )
                        .memoryCacheKey(
                            galleryThumbnailMemoryCacheKey(
                                "album-cover:$coverCacheKey",
                                coverSizePx,
                                GalleryThumbnailStyle.Grid,
                                allowHardwareBitmap = false,
                            ),
                        )
                        .diskCacheKey("album-cover:$coverCacheKey")
                        .listener(
                            onSuccess = { _, result ->
                                // O bitmap do Coil continua na AsyncImage até a próxima
                                // composição. Reciclar aqui fecha o app ao trocar de aba.
                                try {
                                    val raw = (result.drawable as? BitmapDrawable)?.bitmap
                                        ?: return@listener
                                    val stored = raw.copyOwnedSoftwareBitmap() ?: return@listener
                                    if (stored === raw) return@listener
                                    val existing = AlbumCoverBitmapCache.get(album.bucketId)
                                    if (existing != null &&
                                        existing.revision > coverRevision &&
                                        existing.model.isPaintableAlbumCoverModel()
                                    ) {
                                        stored.recycle()
                                        return@listener
                                    }
                                    AlbumCoverBitmapCache.put(
                                        album.bucketId,
                                        stored,
                                        coverIsVideo,
                                        coverCacheKey,
                                        coverRevision,
                                    )
                                    displayModel = stored
                                } catch (error: Exception) {
                                    DebugCrashLog.mark(
                                        "capa-bitmap ${album.bucketId} ${error.javaClass.simpleName}",
                                    )
                                }
                            },
                        )
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .galleryGridThumbnailLayer(GalleryThumbnailStyle.Grid),
                    contentScale = ContentScale.Crop,
                )
            }
            else -> {
                Icon(
                    imageVector = if (isPlace) Icons.Outlined.Place else Icons.Outlined.PhotoAlbum,
                    contentDescription = null,
                    modifier = Modifier.padding(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (coverIsVideo && (shownBitmap != null || paintModel != null)) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(22.dp),
            )
        }
    }
}

private data class ResolvedAlbumCover(
    val model: Any?,
    val isVideo: Boolean,
    val cacheKey: String,
)

private fun coverCoilModel(model: Any?): Any? {
    if (model.asDeviceVideoCover() != null) return null
    return when (model) {
        is java.io.File -> {
            if (!model.isSafeGridImageFile()) return null
            val ext = model.extension.lowercase()
            if (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" || ext == "gif") {
                model
            } else {
                DiskThumb(model)
            }
        }
        else -> model
    }
}
