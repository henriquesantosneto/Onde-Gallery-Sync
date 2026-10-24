package com.onegallery.sync.ui.gallery

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File

fun shouldUseSubsamplingPhotoDetail(model: Any?): Boolean {
    return when (model) {
        is File -> model.isFile && model.length() >= GALLERY_DETAIL_SUBSAMPLING_MIN_BYTES
        else -> false
    }
}

@Composable
fun SubsamplingPhotoContent(
    model: Any?,
    modifier: Modifier = Modifier,
    onSingleTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val source = remember(model) {
        when (model) {
            is File -> ImageSource.uri(Uri.fromFile(model))
            is Uri -> ImageSource.uri(model)
            is String -> ImageSource.uri(model)
            else -> null
        }
    }
    if (source == null) return
    AndroidView(
        modifier = modifier,
        factory = {
            SubsamplingScaleImageView(context).apply {
                setMinimumDpi(48)
                setDoubleTapZoomScale(2.5f)
                setOnClickListener { onSingleTap() }
                setImage(source)
            }
        },
        update = { view ->
            view.setOnClickListener { onSingleTap() }
        },
    )
}
