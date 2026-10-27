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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.onegallery.sync.debug.DebugCrashLog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val mediaCacheRepository = remember(context) {
        (context.applicationContext as OneGalleryApplication).mediaCacheRepository
    }
    val coverDisk = remember(context) {
        AlbumCoverDiskStore(File(context.applicationContext.filesDir, "album-covers"))
    }
    val cachedCover = AlbumCoverBitmapCache.get(album.bucketId)
    val safeFallback = fallbackCover?.takeUnless { it.isBlankSyncedCover(mediaCacheRepository) }
    var displayModel by remember(album.bucketId, safeFallback) {
        mutableStateOf(albumCoverDisplaySeed(cachedCover?.model, safeFallback))
    }
    var coverIsVideo by remember(album.bucketId) { mutableStateOf(cachedCover?.isVideo == true) }
    var coverCacheKey by remember(album.bucketId) {
        mutableStateOf(cachedCover?.cacheKey ?: album.bucketId)
    }

    LaunchedEffect(album.bucketId, coverRevision, safeFallback) {
        try {
        val hit = AlbumCoverBitmapCache.get(album.bucketId)
        val cachedBitmap = hit?.model as? Bitmap
        if (hit != null &&
            !hit.empty &&
            hit.revision == coverRevision &&
            cachedBitmap != null &&
            !cachedBitmap.isRecycled
        ) {
            if (displayModel != hit.model) {
                displayModel = hit.model
                coverIsVideo = hit.isVideo
                coverCacheKey = hit.cacheKey
            }
            withContext(Dispatchers.IO) {
                if (coverDisk.read(album.bucketId, coverRevision) == null) {
                    coverDisk.write(
                        album.bucketId,
                        cachedBitmap,
                        AlbumCoverDiskStamp(coverRevision, hit.isVideo, hit.cacheKey),
                    )
                }
            }
            return@LaunchedEffect
        }
        GalleryGridScrollGate.awaitIdleIfZeroDecodeEnabled(true, maxWaitMs = 2_500L)
        val outcome = albumCoverResolveSlots.withPermit {
            GalleryGridScrollGate.awaitIdleIfZeroDecodeEnabled(true, maxWaitMs = 2_500L)
            withContext(Dispatchers.IO) {
                val saved = coverDisk.read(album.bucketId, coverRevision)
                if (saved != null) {
                    val decoded = GalleryThumbnailModels.decodeAlbumCoverBitmap(context, saved.file, 480)
                    if (decoded != null && !decoded.isRecycled) {
                        return@withContext PreparedAlbumCover(
                            model = decoded,
                            isVideo = saved.stamp.isVideo,
                            cacheKey = saved.stamp.cacheKey.ifBlank { album.bucketId },
                        )
                    }
                }
                val live = resolveLiveCover(album)
                val prepared = decodePreparedAlbumCover(context, album, mediaCacheRepository, live)
                val bitmap = prepared.model as? Bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    coverDisk.write(
                        album.bucketId,
                        bitmap,
                        AlbumCoverDiskStamp(coverRevision, prepared.isVideo, prepared.cacheKey),
                    )
                }
                prepared
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
                                    scope.launch(Dispatchers.IO) {
                                        coverDisk.write(
                                            album.bucketId,
                                            stored,
                                            AlbumCoverDiskStamp(coverRevision, coverIsVideo, coverCacheKey),
                                        )
                                    }
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

private fun coverCoilModel(model: Any?): Any? = preparedCoverCoilModel(model)
