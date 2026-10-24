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
) {
    val transform = remember(item.canonicalPath) { ZoomTransformHolder() }
    val model = item.detailPreviewModel()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            model == null -> Text("Prévia indisponível.", color = Color.White.copy(alpha = 0.85f))
            else -> {
                val context = LocalContext.current
                val decodeMaxPx = remember(context) {
                    val m = context.resources.displayMetrics
                    (maxOf(m.widthPixels, m.heightPixels) * 2).coerceIn(720, 4096)
                }
                val request = remember(model, decodeMaxPx) {
                    ImageRequest.Builder(context)
                        .data(model)
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
        }
    }
}
