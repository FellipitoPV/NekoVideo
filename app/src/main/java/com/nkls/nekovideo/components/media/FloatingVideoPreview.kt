package com.nkls.nekovideo.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nkls.nekovideo.components.helpers.HybridDataSourceFactory
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val PREVIEW_SEGMENT_DURATION_MS = 2_000L
private const val PREVIEW_SEGMENT_SPACING_MS = 10_000L
private const val MAX_PREVIEW_SEGMENTS = 10
private const val PREVIEW_SEGMENT_GAP_MS = 120L

fun buildVideoPreviewUri(videoPath: String, isSecureMode: Boolean): Uri? {
    if (videoPath.isBlank()) return null

    return when {
        LockedPlaybackSession.getXorKeyForFile(videoPath) != null -> Uri.parse("locked://$videoPath")
        isSecureMode -> Uri.fromFile(File(videoPath))
        else -> Uri.fromFile(File(videoPath))
    }
}

@Composable
fun FloatingVideoPreview(
    title: String,
    videoUri: Uri,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onPreviewFinished: () -> Unit
) {
    val context = LocalContext.current
    val latestOnPreviewFinished by rememberUpdatedState(onPreviewFinished)
    val latestOnClose by rememberUpdatedState(onClose)
    var hasSignalledFinish by remember(videoUri) { mutableStateOf(false) }
    var aspectRatio by remember(videoUri) { mutableStateOf(16f / 9f) }
    var isLoadingPreview by remember(videoUri) { mutableStateOf(true) }

    fun finishPreview() {
        if (!hasSignalledFinish) {
            hasSignalledFinish = true
            latestOnPreviewFinished()
        }
    }

    val maxPreviewWidth = 220.dp
    val maxPreviewHeight = 260.dp
    val previewWidth = if (aspectRatio >= 1f) {
        maxPreviewWidth
    } else {
        (maxPreviewHeight * aspectRatio).coerceAtMost(maxPreviewWidth)
    }
    val previewHeight = if (aspectRatio >= 1f) {
        (maxPreviewWidth / aspectRatio).coerceAtMost(maxPreviewHeight)
    } else {
        maxPreviewHeight
    }

    val player = remember(videoUri) {
        val dataSourceFactory = HybridDataSourceFactory(context)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                volume = 0f
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
                playWhenReady = false
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio = (videoSize.width.toFloat() / videoSize.height.toFloat()).coerceIn(0.4f, 2.5f)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isLoadingPreview = playbackState == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    isLoadingPreview = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            finishPreview()
        }
    }

    LaunchedEffect(player, videoUri) {
        try {
            player.setMediaItem(ExoMediaItem.fromUri(videoUri))
            player.prepare()

            var durationMs = 0L
            for (attempt in 0 until 40) {
                durationMs = player.duration
                if (durationMs > 0L) break
                delay(100)
            }

            if (durationMs <= 0L) {
                finishPreview()
                return@LaunchedEffect
            }

            suspend fun playWindow(startMs: Long, requestedDurationMs: Long) {
                val maxStart = (durationMs - 500L).coerceAtLeast(0L)
                val segmentStart = startMs.coerceIn(0L, maxStart)
                val remaining = (durationMs - segmentStart).coerceAtLeast(0L)
                val segmentDuration = minOf(requestedDurationMs, remaining)
                if (segmentDuration <= 0L) return

                isLoadingPreview = true
                player.seekTo(segmentStart)
                player.play()
                val targetPosition = (segmentStart + segmentDuration).coerceAtMost(durationMs)

                withTimeoutOrNull(segmentDuration + 15_000L) {
                    while (isActive) {
                        if (player.playbackState == Player.STATE_ENDED) break
                        if (player.currentPosition >= targetPosition) break
                        delay(if (player.isPlaying) 50L else 100L)
                    }
                }
                player.pause()
            }

            val segmentCount = (durationMs / PREVIEW_SEGMENT_SPACING_MS)
                .toInt()
                .coerceIn(1, MAX_PREVIEW_SEGMENTS)
            val spacing = durationMs.toFloat() / segmentCount
            val segmentStarts = List(segmentCount) { index ->
                val sampleCenter = spacing * (index + 0.5f)
                (sampleCenter - (PREVIEW_SEGMENT_DURATION_MS / 2f)).toLong().coerceAtLeast(0L)
            }

            segmentStarts.forEachIndexed { index, startMs ->
                playWindow(startMs, PREVIEW_SEGMENT_DURATION_MS)
                if (index < segmentStarts.lastIndex) {
                    delay(PREVIEW_SEGMENT_GAP_MS)
                }
            }
        } finally {
            player.pause()
            player.stop()
            finishPreview()
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
                .width(previewWidth + 16.dp)
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.width(previewWidth),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = latestOnClose,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar preview",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(width = previewWidth, height = previewHeight)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
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
            }
        }
    }
}
