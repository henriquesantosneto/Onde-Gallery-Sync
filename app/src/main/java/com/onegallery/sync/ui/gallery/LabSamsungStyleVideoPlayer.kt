package com.onegallery.sync.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Player experimental (estilo Samsung) — não substitui [VideoPlayerView]. */
@Composable
fun LabSamsungStyleVideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    var muted by remember(uri) { mutableStateOf(true) }
    var userPlaying by remember(uri) { mutableStateOf(true) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var scrubFraction by remember(uri) { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            playWhenReady = false
            prepare()
        }
    }

    LaunchedEffect(isActive) {
        userPlaying = isActive
    }

    val shouldPlay = isActive && userPlaying

    DisposableEffect(muted) {
        player.volume = if (muted) 0f else 1f
        onDispose { }
    }

    DisposableEffect(shouldPlay) {
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
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.release()
        }
    }

    LaunchedEffect(shouldPlay, scrubbing) {
        while (isActive && shouldPlay && !scrubbing) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val dur = player.duration.coerceAtLeast(0L)
            if (dur > 0L) durationMs = dur
            delay(250L)
        }
    }

    val displayFraction = when {
        scrubbing -> scrubFraction
        durationMs > 0L -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    isClickable = false
                    isFocusable = false
                    setOnTouchListener { _, _ -> false }
                }
            },
            update = { view ->
                if (view.player != player) view.player = player
            },
        )

        if (isActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 108.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(28.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { userPlaying = !userPlaying }) {
                        Icon(
                            imageVector = if (userPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (userPlaying) "Pausar" else "Reproduzir",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = "${formatPlaybackClock(positionMs)} / ${formatPlaybackClock(durationMs)}",
                        color = Color.White,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Slider(
                value = displayFraction,
                onValueChange = { value ->
                    scrubbing = true
                    scrubFraction = value
                },
                onValueChangeFinished = {
                    val target = (durationMs * scrubFraction).toLong()
                    player.seekTo(target)
                    positionMs = target
                    scrubbing = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = 92.dp)
                    .fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                ),
            )

            IconButton(
                onClick = { muted = !muted },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 28.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
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

private fun formatPlaybackClock(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%02d:%02d".format(minutes, seconds)
}
