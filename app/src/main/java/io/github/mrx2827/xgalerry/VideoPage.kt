// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
internal fun VideoPage(
    item: GalleryItem,
    thumbnails: Thumbnails,
    thumbSize: Int,
    active: Boolean,
    closing: Boolean,
    controls: Boolean,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentTap by rememberUpdatedState(onTap)
    val thumbnail = rememberThumbnail(item, thumbnails, thumbSize)
    val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
    var player by remember(item.key) { mutableStateOf<ExoPlayer?>(null) }
    var rendered by remember(item.key) { mutableStateOf(false) }
    var failed by remember(item.key) { mutableStateOf(false) }
    var playing by remember(item.key) { mutableStateOf(false) }
    var buffering by remember(item.key) { mutableStateOf(false) }
    var ended by remember(item.key) { mutableStateOf(false) }
    var position by rememberSaveable(item.key) { mutableLongStateOf(0L) }
    var total by remember(item.key) { mutableLongStateOf(item.duration) }
    var seek by remember(item.key) { mutableFloatStateOf(-1f) }

    DisposableEffect(active, item.key, lifecycle) {
        if (!active) return@DisposableEffect onDispose {}
        rendered = false
        failed = false
        ended = false
        val playback = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                ended = playbackState == Player.STATE_ENDED
                if (playback.getDuration() > 0) total = playback.getDuration()
                position = playback.getCurrentPosition()
            }

            override fun onRenderedFirstFrame() {
                rendered = true
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                buffering = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) playback.pause()
        }
        playback.addListener(listener)
        lifecycle.addObserver(observer)
        playback.setMediaItem(MediaItem.fromUri(item.uri), position)
        playback.setPlayWhenReady(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        player = playback
        playback.prepare()
        onDispose {
            lifecycle.removeObserver(observer)
            position = playback.getCurrentPosition()
            playback.removeListener(listener)
            player = null
            rendered = false
            playing = false
            buffering = false
            playback.release()
        }
    }
    LaunchedEffect(player, closing) {
        if (closing) player?.pause()
    }
    LaunchedEffect(player, controls, playing) {
        val current = player ?: return@LaunchedEffect
        position = current.getCurrentPosition()
        while (controls && playing) {
            delay(125)
            position = current.getCurrentPosition()
        }
    }

    val toggle = stringResource(R.string.show_controls)
    Box(
        Modifier.fillMaxSize().pointerInput(item.key) { detectTapGestures { currentTap() } }
            .semantics {
                contentDescription = item.name
                onClick(label = toggle) { currentTap(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        val current = player
        if (current != null) {
            AndroidView(
                factory = { LayoutInflater.from(it).inflate(R.layout.video_surface, null, false) as PlayerView },
                modifier = Modifier.fillMaxSize(),
                update = {
                    if (it.getPlayer() !== current) it.setPlayer(current)
                    it.setKeepScreenOn(playing)
                },
                onRelease = { it.setPlayer(null) }
            )
        }
        if (image != null && !rendered) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (failed && active) {
            Surface(color = ViewerControl, shape = RoundedCornerShape(21.dp)) {
                Text(stringResource(R.string.video_unavailable), Modifier.padding(16.dp), color = Color.White)
            }
        } else if (buffering && active && !rendered) {
            CircularProgressIndicator(Modifier.size(34.dp), color = ViewerAccent)
        }
        AnimatedVisibility(
            visible = controls && current != null && !failed,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(), exit = fadeOut()
        ) {
            val range = total.coerceAtLeast(1L).toFloat()
            PlaybackControls(
                playing = playing, ended = ended, total = total,
                position = if (seek >= 0f) seek.toLong() else position,
                onPlayPause = {
                    when {
                        current == null -> Unit
                        ended -> { current.seekTo(0); current.play() }
                        playing -> current.pause()
                        else -> current.play()
                    }
                },
                onSeek = { seek = it * range },
                onSeekFinished = {
                    if (seek >= 0f) {
                        position = seek.toLong()
                        current?.seekTo(position)
                        seek = -1f
                    }
                }
            )
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackControls(
    playing: Boolean,
    ended: Boolean,
    total: Long,
    position: Long,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit
) {
    val seekLabel = stringResource(R.string.seek)
    val sliderState = rememberSliderState()
    sliderState.value = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    Surface(
        Modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 70.dp)
            .widthIn(max = 500.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp), color = ViewerControl
    ) {
        Row(
            Modifier.padding(start = 7.dp, end = 13.dp, top = 4.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ViewerButton(
                icon = when {
                    ended -> Icons.Rounded.Replay
                    playing -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                },
                label = when {
                    ended -> R.string.replay
                    playing -> R.string.pause
                    else -> R.string.play
                },
                onClick = onPlayPause, selected = true
            )
            Column(Modifier.weight(1f)) {
                Slider(
                    state = sliderState,
                    enabled = total > 0,
                    onValueChange = onSeek,
                    onValueChangeFinished = onSeekFinished,
                    modifier = Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = seekLabel },
                    colors = SliderDefaults.colors(
                        thumbColor = ViewerAccent, activeTrackColor = ViewerAccent, inactiveTrackColor = Color(0xFF535F50)
                    )
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 3.dp, end = 3.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val style = MaterialTheme.typography.labelSmall
                    Text(durationText(position), color = Color.White, style = style, fontWeight = FontWeight.SemiBold)
                    Text(durationText(total), color = Color(0xFFCCD5C9), style = style)
                }
            }
        }
    }
}
