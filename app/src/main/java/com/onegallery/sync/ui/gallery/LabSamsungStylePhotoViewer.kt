package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Foto no visualizador lab — pinça sem roubar swipe horizontal do pager. */
@Composable
fun LabSamsungStylePhotoViewer(
    item: GalleryDisplayItem,
    modifier: Modifier = Modifier,
    onToggleImmersive: (() -> Unit)? = null,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    previewContext: Context? = null,
    openingPreview: Bitmap? = null,
) {
    val transform = remember(item.canonicalPath) { ZoomTransformHolder() }
    val immersiveModifier = if (onToggleImmersive != null) {
        Modifier.detailImmersiveTap(transform, onToggleImmersive)
    } else {
        Modifier
    }
    val fullModel = remember(
        item.canonicalPath,
        item.localUri,
        item.cachedOriginal,
        previewContext,
    ) {
        previewContext?.let { item.detailFullImageModel(it) }
            ?: item.detailFullImageModel()
    }
    val useThumbnailPipeline = remember(fullModel) {
        item.detailImageUsesThumbnailPipeline(fullModel)
    }
    val matchGridCrop = remember(fullModel, openingPreview) {
        openingPreview != null || fullModel is Uri || fullModel is File
    }
    Box(
        modifier = modifier.then(immersiveModifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            fullModel == null -> Text("Prévia indisponível.", color = Color.White.copy(alpha = 0.85f))
            !useThumbnailPipeline -> {
                val context = LocalContext.current
                val decodeMaxPx = remember(context) {
                    val m = context.resources.displayMetrics
                    (maxOf(m.widthPixels, m.heightPixels) * 2).coerceIn(720, 4096)
                }
                val request = remember(fullModel, decodeMaxPx, openingPreview, matchGridCrop) {
                    ImageRequest.Builder(context)
                        .data(fullModel)
                        .size(decodeMaxPx)
                        .crossfade(if (openingPreview != null) 0 else 160)
                        .allowHardware(false)
                        .apply {
                            val preview = openingPreview
                            if (preview != null && !preview.isRecycled) {
                                placeholder(BitmapDrawable(context.resources, preview))
                            }
                        }
                        .build()
                }
                ZoomableMediaContainer(
                    resetKey = item.canonicalPath,
                    transform = transform,
                    modifier = Modifier
                        .fillMaxSize()
                        .notifyPagerWhenMultiTouch(onZoomActiveChanged),
                    interceptSingleTap = false,
                    onZoomActiveChanged = onZoomActiveChanged,
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = item.displayName,
                        contentScale = if (matchGridCrop) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> {
                val context = LocalContext.current
                val detailSizePx = remember(context) {
                    val m = context.resources.displayMetrics
                    (maxOf(m.widthPixels, m.heightPixels) * 2).coerceIn(720, 4096)
                }
                ZoomableMediaContainer(
                    resetKey = item.canonicalPath,
                    transform = transform,
                    modifier = Modifier
                        .fillMaxSize()
                        .notifyPagerWhenMultiTouch(onZoomActiveChanged),
                    interceptSingleTap = false,
                    onZoomActiveChanged = onZoomActiveChanged,
                ) {
                    GalleryThumbnailImage(
                        item = item,
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        style = GalleryThumbnailStyle.Detail,
                        sizePx = detailSizePx,
                    )
                }
            }
        }
    }
}
