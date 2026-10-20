package com.onegallery.sync.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.VolumeOff
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun VideoPlayerView(
    uri: Uri,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    /** false = toques passam ao pager (detalhe com swipe horizontal). */
    usePlaybackControls: Boolean = true,
) {
    val context = LocalContext.current
    var muted by remember(uri) { mutableStateOf(true) }
    val mutedState = rememberUpdatedState(muted)
    val focusRequester = remember(uri) { FocusRequester() }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            this.playWhenReady = playWhenReady
            prepare()
        }
    }

    fun unmuteIfNeeded() {
        if (mutedState.value) {
            muted = false
        }
    }

    DisposableEffect(muted) {
        player.volume = if (muted) 0f else 1f
        onDispose { }
    }
    DisposableEffect(uri) {
        focusRequester.requestFocus()
        onDispose { player.release() }
    }
    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.VolumeUp || event.key == Key.VolumeDown)
                ) {
                    unmuteIfNeeded()
                    false
                } else {
                    false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = usePlaybackControls
                    controllerShowTimeoutMs = 5_000
                    isMotionEventSplittingEnabled = false
                    if (!usePlaybackControls) {
                        isClickable = false
                        isFocusable = false
                        setOnTouchListener { _, _ -> false }
                    }
                }
            },
            update = { view ->
                if (view.player != player) {
                    view.player = player
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
                imageVector = if (muted) Icons.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = if (muted) "Ativar som" else "Silenciar",
                tint = Color.White,
            )
        }
    }
}
