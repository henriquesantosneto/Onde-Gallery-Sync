package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun ZoomableImageContent(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
) {
    ZoomableMediaContainer(
        resetKey = model,
        modifier = modifier,
        interceptSingleTap = true,
        onZoomActiveChanged = onZoomActiveChanged,
        onSingleTap = onSingleTap,
    ) {
        if (model != null) {
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
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
