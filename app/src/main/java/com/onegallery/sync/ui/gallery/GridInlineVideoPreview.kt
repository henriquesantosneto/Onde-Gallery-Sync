package com.onegallery.sync.ui.gallery

import android.net.Uri
import android.view.TextureView
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/** A grade precisa ficar parada esse tempo antes do primeiro vídeo. */
internal const val GRID_INLINE_VIDEO_IDLE_MS = 2_000L

/** Cada vídeo local visível toca esse trecho, um de cada vez. */
internal const val GRID_INLINE_VIDEO_PLAY_MS = 5_000L

internal data class GridVideoPreviewTarget(
    val canonicalPath: String,
    val uri: Uri,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun localVideosInPreviewOrder(items: List<GalleryDisplayItem>): List<String> {
    return items.mapNotNull { item ->
        if (item.playsInlineLocalVideo()) item.canonicalPath else null
    }
}

internal suspend fun holdGridVideoPreview(durationMs: Long): Boolean {
    var waited = 0L
    while (waited < durationMs) {
        val step = minOf(100L, durationMs - waited)
        delay(step)
        waited += step
        if (!GalleryGridScrollGate.inlineVideoPreviewEnabled) return false
    }
    return GalleryGridScrollGate.inlineVideoPreviewEnabled
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GridInlineVideoPreview(target: GridVideoPreviewTarget) {
    val context = LocalContext.current
    val player = remember(target.canonicalPath, target.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(target.uri))
            volume = 0f
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(target.left, target.top) }
            .size(
                with(density) { target.width.toDp() },
                with(density) { target.height.toDp() },
            )
            .pointerInteropFilter { false },
    ) {
        AndroidView(
            factory = { viewContext -> TextureView(viewContext) },
            update = { texture -> player.setVideoTextureView(texture) },
            modifier = Modifier
                .matchParentSize()
                .pointerInteropFilter { false },
        )
    }
}
