package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

@Composable
fun ZoomableImageContent(
    model: Any?,
    contentDescription: String?,
    transform: ZoomTransformHolder,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    onSingleTap: () -> Unit = {},
    onContentAspectRatio: ((Float) -> Unit)? = null,
) {
    if (model != null && shouldUseSubsamplingPhotoDetail(model)) {
        SubsamplingPhotoContent(
            model = model,
            modifier = modifier,
            onSingleTap = onSingleTap,
        )
        return
    }
    ZoomableMediaContainer(
        resetKey = model,
        transform = transform,
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
                onState = { state ->
                    if (onContentAspectRatio != null && state is AsyncImagePainter.State.Success) {
                        val size = state.painter.intrinsicSize
                        if (size.width.isFinite() && size.height.isFinite() && size.height > 0f) {
                            onContentAspectRatio(size.width / size.height)
                        }
                    }
                },
            )
        }
    }
}
