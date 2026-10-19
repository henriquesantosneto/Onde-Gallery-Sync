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
            val request = remember(model) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(true)
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
