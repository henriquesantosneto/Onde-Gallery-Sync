package com.onegallery.sync.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class LabMediaPlaybackKind {
    Video,
    Audio,
}

/** Player experimental (estilo Samsung) — não substitui [VideoPlayerView]. */
@Composable
fun LabSamsungStyleVideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    kind: LabMediaPlaybackKind = LabMediaPlaybackKind.Video,
    displayName: String? = null,
    artworkItem: GalleryDisplayItem? = null,
    bottomChromeInset: Dp = 32.dp,
) {
    LabSamsungStyleMediaPlayer(
        uri = uri,
        modifier = modifier,
        isActive = isActive,
        kind = kind,
        displayName = displayName,
        artworkItem = artworkItem,
        bottomChromeInset = bottomChromeInset,
    )
}

@Composable
fun LabSamsungStyleMediaPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    kind: LabMediaPlaybackKind = LabMediaPlaybackKind.Video,
    displayName: String? = null,
    artworkItem: GalleryDisplayItem? = null,
    /** Espaço acima da faixa de miniaturas / barra de gestos. */
    bottomChromeInset: Dp = 32.dp,
) {
    val context = LocalContext.current
    val focusRequester = remember(uri) { FocusRequester() }
    var muted by remember(uri) { mutableStateOf(false) }
    var userPlaying by remember(uri) { mutableStateOf(true) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var scrubFraction by remember(uri) { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }

    val player = remember(uri) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(
                        if (kind == LabMediaPlaybackKind.Audio) {
                            C.AUDIO_CONTENT_TYPE_MUSIC
                        } else {
                            C.AUDIO_CONTENT_TYPE_MOVIE
                        },
                    )
                    .build(),
                true,
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 1f
                playWhenReady = false
                prepare()
            }
    }

    fun unmuteIfNeeded() {
        if (muted) muted = false
    }

    LaunchedEffect(isActive) {
        userPlaying = isActive
        if (isActive) {
            focusRequester.requestFocus()
        }
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

    val sliderBottom = bottomChromeInset + 6.dp
    val pillBottom = bottomChromeInset + 42.dp

    Box(
        modifier = modifier
            .background(Color.Black)
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
        if (kind == LabMediaPlaybackKind.Video) {
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
        } else {
            LabAudioArtworkPanel(
                displayName = displayName,
                artworkItem = artworkItem,
            )
        }

        if (isActive) {
            LabPlaybackControlPill(
                userPlaying = userPlaying,
                onTogglePlay = { userPlaying = !userPlaying },
                positionMs = positionMs,
                durationMs = durationMs,
                muted = muted,
                onToggleMute = { muted = !muted },
                showMuteInPill = kind == LabMediaPlaybackKind.Video,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = pillBottom)
                    .fillMaxWidth(),
            )

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
                    .padding(start = 20.dp, end = 20.dp, bottom = sliderBottom)
                    .fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                ),
            )

            if (kind == LabMediaPlaybackKind.Audio) {
                IconButton(
                    onClick = { muted = !muted },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = bottomChromeInset)
                        .background(Color.Black.copy(alpha = 0.62f), CircleShape),
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
}

@Composable
private fun LabAudioArtworkPanel(
    displayName: String?,
    artworkItem: GalleryDisplayItem?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkItem != null && artworkItem.detailPreviewModel() != null) {
                MediaThumbnailImage(
                    item = artworkItem,
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        if (!displayName.isNullOrBlank()) {
            Text(
                text = displayName,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun LabPlaybackControlPill(
    userPlaying: Boolean,
    onTogglePlay: () -> Unit,
    positionMs: Long,
    durationMs: Long,
    muted: Boolean,
    onToggleMute: () -> Unit,
    showMuteInPill: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(22.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (userPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (userPlaying) "Pausar" else "Reproduzir",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "${formatPlaybackClock(positionMs)} / ${formatPlaybackClock(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            if (showMuteInPill) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = if (muted) "Ativar som" else "Silenciar",
                        tint = if (muted) Color.White.copy(alpha = 0.65f) else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
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
