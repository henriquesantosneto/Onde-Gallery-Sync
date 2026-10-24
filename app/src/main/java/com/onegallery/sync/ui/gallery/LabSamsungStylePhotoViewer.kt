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
) {
    val transform = remember(item.canonicalPath) { ZoomTransformHolder() }
    val immersiveModifier = if (onToggleImmersive != null) {
        Modifier.detailImmersiveTap(transform, onToggleImmersive)
    } else {
        Modifier
    }
    val fullModel = remember(item.canonicalPath, item.localUri, item.cachedOriginal) {
        item.detailFullImageModel()
    }
    val useThumbnailPipeline = remember(fullModel) {
        item.detailImageUsesThumbnailPipeline(fullModel)
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
                val request = remember(fullModel, decodeMaxPx) {
                    ImageRequest.Builder(context)
                        .data(fullModel)
                        .size(decodeMaxPx)
                        .crossfade(true)
                        .allowHardware(false)
                        .build()
                }
                ZoomableMediaContainer(
                    resetKey = item.canonicalPath,
                    transform = transform,
                    modifier = Modifier.fillMaxSize(),
                    interceptSingleTap = false,
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
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
                    modifier = Modifier.fillMaxSize(),
                    interceptSingleTap = false,
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
