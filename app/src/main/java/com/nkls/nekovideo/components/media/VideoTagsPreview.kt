package com.nkls.nekovideo.components

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.HybridDataSourceFactory
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.roundToLong

private const val PREVIEW_MUTED_KEY = "video_tags_preview_muted"

fun buildVideoPreviewUri(videoPath: String, isSecureMode: Boolean): Uri? {
    if (videoPath.isBlank()) return null

    return when {
        LockedPlaybackSession.getXorKeyForFile(videoPath) != null -> Uri.parse("locked://$videoPath")
        isSecureMode -> Uri.fromFile(File(videoPath))
        else -> Uri.fromFile(File(videoPath))
    }
}

@Composable
@OptIn(UnstableApi::class)
internal fun VideoTagsPreview(
    videoUri: Uri,
    modifier: Modifier = Modifier,
    maxCardWidth: Dp = 236.dp,
    maxVideoHeight: Dp = 260.dp,
    onPreviewError: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val previewPreferences = remember(context) {
        context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
    }
    var isMuted by remember(previewPreferences) {
        mutableStateOf(previewPreferences.getBoolean(PREVIEW_MUTED_KEY, false))
    }
    val latestOnPreviewError by rememberUpdatedState(onPreviewError)
    var aspectRatio by remember(videoUri) { mutableStateOf(16f / 9f) }
    var isLoadingPreview by remember(videoUri) { mutableStateOf(true) }
    var videoDurationMs by remember(videoUri) { mutableStateOf(0L) }
    var currentPositionMs by remember(videoUri) { mutableStateOf(0L) }
    var canSeek by remember(videoUri) { mutableStateOf(false) }

    val cardPadding = 8.dp
    val availableVideoWidth = (maxCardWidth - cardPadding * 2).coerceAtLeast(0.dp)
    val previewWidth = minOf(availableVideoWidth, maxVideoHeight * aspectRatio)
    val previewHeight = previewWidth / aspectRatio

    val player = remember(videoUri, lifecycle) {
        val dataSourceFactory = HybridDataSourceFactory(context)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
                volume = if (isMuted) 0f else 1f
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio = (videoSize.width.toFloat() / videoSize.height.toFloat()).coerceIn(0.4f, 2.5f)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isLoadingPreview = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                latestOnPreviewError()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    isLoadingPreview = false
                }
            }
        }
        player.addListener(listener)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.play()
                Lifecycle.Event.ON_PAUSE -> player.pause()
                else -> Unit
            }
        }
        player.setMediaItem(ExoMediaItem.fromUri(videoUri))
        player.prepare()
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            videoDurationMs = player.duration.coerceAtLeast(0L)
            currentPositionMs = player.currentPosition
            canSeek = videoDurationMs > 0L && player.isCurrentMediaItemSeekable
            delay(100)
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier
                .width(previewWidth + cardPadding * 2)
                .padding(cardPadding)
        ) {
            Box(
                modifier = Modifier
                    .size(width = previewWidth, height = previewHeight)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { viewContext ->
                        (LayoutInflater.from(viewContext)
                            .inflate(R.layout.view_video_preview, null) as PlayerView).apply {
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.matchParentSize()
                )

                if (isLoadingPreview) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                val progress = if (videoDurationMs > 0) {
                    (currentPositionMs.toDouble() / videoDurationMs).toFloat().coerceIn(0f, 1f)
                } else 0f
                PreviewPlaybackControls(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    progress = progress,
                    compact = previewHeight < 84.dp,
                    canSeek = canSeek,
                    isMuted = isMuted,
                    onSeek = { fraction ->
                        val position = (videoDurationMs * fraction.toDouble()).roundToLong()
                        player.seekTo(position)
                        currentPositionMs = position
                    },
                    onToggleMute = {
                        isMuted = !isMuted
                        player.volume = if (isMuted) 0f else 1f
                        previewPreferences.edit().putBoolean(PREVIEW_MUTED_KEY, isMuted).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun PreviewPlaybackControls(
    modifier: Modifier,
    progress: Float,
    compact: Boolean,
    canSeek: Boolean,
    isMuted: Boolean,
    onSeek: (Float) -> Unit,
    onToggleMute: () -> Unit
) {
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    val sliderColors = SliderDefaults.colors(
        activeTrackColor = Color.White.copy(alpha = 0.90f),
        inactiveTrackColor = Color.White.copy(alpha = 0.28f)
    )
    val timelineLabel = stringResource(R.string.video_tags_preview_timeline)

    Column(modifier.fillMaxWidth()) {
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier.align(Alignment.End).padding(end = 4.dp)
                .size(if (compact) 32.dp else 48.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.60f),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(
                    if (isMuted) R.string.video_tags_preview_unmute else R.string.video_tags_preview_mute
                ),
                modifier = Modifier.size(if (compact) 20.dp else 24.dp)
            )
        }
        Slider(
            value = scrubProgress ?: progress,
            onValueChange = { scrubProgress = it },
            onValueChangeFinished = {
                scrubProgress?.let(onSeek)
                scrubProgress = null
            },
            enabled = canSeek,
            colors = sliderColors,
            thumb = { Box(Modifier.size(10.dp).background(Color.White, CircleShape)) },
            track = { sliderState ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.Black.copy(alpha = 0.40f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(3.dp),
                        colors = sliderColors,
                        enabled = canSeek,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 0.dp,
                        drawStopIndicator = null
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 28.dp else 36.dp)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = timelineLabel }
        )
    }
}
