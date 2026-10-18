package com.onegallery.sync.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    var muted by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 1f
            this.playWhenReady = playWhenReady
            prepare()
        }
    }
    DisposableEffect(muted) {
        player.volume = if (muted) 0f else 1f
        onDispose { }
    }
    DisposableEffect(uri) {
        onDispose { player.release() }
    }
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .matchParentSize()
                .observeMultiTouchPinch { active -> zoomCallback.value(active) },
            factory = { ctx ->
                ZoomablePlayerLayout(ctx).also { layout ->
                    layout.playerView.player = player
                    layout.playerView.useController = true
                    layout.playerView.controllerShowTimeoutMs = 5_000
                    layout.playerView.showController()
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
        IconButton(
            onClick = { muted = !muted },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(
                imageVector = if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                contentDescription = if (muted) "Ativar som" else "Silenciar",
                tint = Color.White,
            )
        }
    }
}
