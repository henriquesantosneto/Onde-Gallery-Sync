package com.onegallery.sync.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.view.LayoutInflater
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.onegallery.sync.R

@Composable
fun VideoPlayerView(
    uri: Uri,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    /** false = toques passam ao pager (detalhe com swipe horizontal). */
    usePlaybackControls: Boolean = true,
    /** Pausa imediatamente ao trocar de página no carrossel. */
    isActive: Boolean = true,
    /** Toque na área do vídeo (ex.: alternar tela cheia no detalhe). */
    onVideoAreaTap: (() -> Unit)? = null,
    /** Espaço inferior para não sobrepor o carrossel de miniaturas. */
    controlsBottomPadding: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val context = LocalContext.current
    var muted by remember(uri) { mutableStateOf(true) }
    var userPlaying by remember(uri) { mutableStateOf(true) }
    val mutedState = rememberUpdatedState(muted)
    val focusRequester = remember(uri) { FocusRequester() }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            this.playWhenReady = false
            prepare()
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            userPlaying = true
        } else {
            userPlaying = false
        }
    }

    LaunchedEffect(uri, isActive) {
        if (isActive) {
            muted = true
            player.volume = 0f
        }
    }

    val shouldPlay = isActive && playWhenReady && userPlaying

    fun unmuteIfNeeded() {
        if (mutedState.value) {
            muted = false
        }
    }

    DisposableEffect(muted) {
        player.volume = if (muted) 0f else 1f
        onDispose { }
    }
    DisposableEffect(shouldPlay, muted) {
        player.volume = if (muted) 0f else 1f
        if (shouldPlay) {
            player.playWhenReady = true
            player.play()
        } else {
            player.playWhenReady = false
            player.pause()
        }
        onDispose { }
    }
    DisposableEffect(uri) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    userPlaying = false
                    player.playWhenReady = false
                    player.pause()
                    player.seekTo(0)
                }
            }
        }
        player.addListener(listener)
        if (isActive) {
            focusRequester.requestFocus()
        }
        onDispose {
            player.removeListener(listener)
            runCatching {
                player.playWhenReady = false
                player.pause()
                player.stop()
                player.clearMediaItems()
                player.release()
            }
        }
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
                (LayoutInflater.from(ctx).inflate(
                    R.layout.gallery_detail_player_view,
                    null,
                    false,
                ) as PlayerView).apply {
                    this.player = player
                    useController = usePlaybackControls
                    controllerShowTimeoutMs = 5_000
                    isMotionEventSplittingEnabled = false
                    if (!usePlaybackControls) {
                        isClickable = false
                        isFocusable = false
                        isFocusableInTouchMode = false
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
        if (isActive && !usePlaybackControls && onVideoAreaTap != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(1f)
                    .pointerInput(uri, onVideoAreaTap) {
                        detectImmersiveToggleTapMainPass { onVideoAreaTap.invoke() }
                    },
            )
        }
        if (isActive && !usePlaybackControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f)
                    .padding(bottom = controlsBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (!userPlaying &&
                            player.playbackState == Player.STATE_ENDED
                        ) {
                            player.seekTo(0)
                        }
                        userPlaying = !userPlaying
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                ) {
                    Icon(
                        imageVector = if (userPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (userPlaying) "Pausar" else "Reproduzir",
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = { muted = !muted },
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = if (muted) "Ativar som" else "Silenciar",
                        tint = Color.White,
                    )
                }
            }
        } else if (isActive && usePlaybackControls) {
            IconButton(
                onClick = { muted = !muted },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(
                    imageVector = if (muted) Icons.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (muted) "Ativar som" else "Silenciar",
                    tint = Color.White,
                )
            }
        }
    }
}
