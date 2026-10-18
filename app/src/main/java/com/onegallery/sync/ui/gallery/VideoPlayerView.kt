package com.onegallery.sync.ui.gallery

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

@Composable
fun VideoPlayerView(
    uri: Uri,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    onZoomActiveChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val zoomCallback = rememberUpdatedState(onZoomActiveChanged)
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            this.playWhenReady = playWhenReady
            prepare()
        }
    }
    DisposableEffect(uri) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ZoomablePlayerLayout(ctx).also { layout ->
                layout.playerView.player = player
                layout.playerView.useController = true
                layout.onZoomActiveChanged = { active -> zoomCallback.value(active) }
            }
        },
        update = { layout ->
            layout.onZoomActiveChanged = { active -> zoomCallback.value(active) }
            if (layout.playerView.player != player) {
                layout.playerView.player = player
            }
        },
    )
}
