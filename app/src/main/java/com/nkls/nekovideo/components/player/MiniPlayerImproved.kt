package com.nkls.nekovideo.components.player

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.PlaylistNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayerImproved(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Local player state
    val mediaController by MediaControllerManager.mediaController.collectAsStateWithLifecycle()
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("") }
    var currentUri by remember { mutableStateOf("") }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrevious by remember { mutableStateOf(false) }

    // Cast state
    val castManager = remember { DLNACastManager.getInstance(context) }
    var isCasting by remember { mutableStateOf(castManager.isConnected) }
    var castTitle by remember { mutableStateOf(castManager.currentTitle) }
    var castIsPlaying by remember { mutableStateOf(castManager.isPlaying) }
    var castPosition by remember { mutableStateOf(castManager.currentPositionMs) }
    var castDuration by remember { mutableStateOf(castManager.durationMs) }
    var castVideoPath by remember { mutableStateOf(castManager.currentVideoPath) }
    var castThumbnail by remember { mutableStateOf<Bitmap?>(null) }

    // Effective values — cast takes priority when connected
    val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying
    val effectiveTitle = if (isCasting) castTitle else currentTitle
    val effectivePosition = if (isCasting) castPosition else currentPosition
    val effectiveDuration = if (isCasting) castDuration else duration
    val effectiveThumbnail = if (isCasting) castThumbnail else thumbnail

    val playButtonScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "playButtonScale"
    )

    val thumbnailScale by animateFloatAsState(
        targetValue = if (effectiveIsPlaying) 1f else 0.95f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "thumbnailScale"
    )

    // Cast connection listener
    DisposableEffect(Unit) {
        MediaControllerManager.connect(context)
        castManager.onConnectionStateChanged = { connected ->
            isCasting = connected
            if (!connected) {
                castTitle = ""
                castPosition = 0L
                castDuration = 0L
                castIsPlaying = false
                castThumbnail = null
                castVideoPath = ""
            }
        }
        onDispose {
            castManager.onConnectionStateChanged = null
        }
    }

    // Cast state polling — avoids conflicting with CastControlsOverlay's onStateChanged slot
    LaunchedEffect(isCasting) {
        while (isCasting) {
            castIsPlaying = castManager.isPlaying
            castPosition = castManager.currentPositionMs
            castDuration = castManager.durationMs
            castTitle = castManager.currentTitle
            val newPath = castManager.currentVideoPath
            if (newPath != castVideoPath) castVideoPath = newPath
            delay(500)
        }
    }

    // Load cast thumbnail when the cast video changes
    LaunchedEffect(castVideoPath) {
        if (castVideoPath.isEmpty()) return@LaunchedEffect
        castThumbnail = withContext(Dispatchers.IO) {
            generateThumbnail(context, castVideoPath)
        }
    }

    // Local player position ticker
    LaunchedEffect(mediaController, isPlaying) {
        while (isPlaying) {
            mediaController?.let {
                currentPosition = it.currentPosition
                duration = it.duration.takeIf { d -> d > 0 } ?: 0L
            }
            delay(500)
        }
    }

    // Local player listener
    LaunchedEffect(mediaController) {
        mediaController?.let { controller ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isPlaying = controller.isPlaying
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int
                ) {
                    mediaItem?.let { item ->
                        currentTitle = item.mediaMetadata.title?.toString()
                            ?: File(item.localConfiguration?.uri?.path ?: "").nameWithoutExtension
                        currentUri = item.localConfiguration?.uri?.toString() ?: ""
                        hasNext = PlaylistManager.hasNext()
                        hasPrevious = PlaylistManager.hasPrevious()
                        currentPosition = controller.currentPosition
                        duration = controller.duration.takeIf { it > 0 } ?: 0L

                        if (currentUri.isNotEmpty()) {
                            coroutineScope.launch {
                                thumbnail = withContext(Dispatchers.IO) {
                                    generateThumbnail(context, currentUri)
                                }
                            }
                        }
                    }
                }
            }

            controller.addListener(listener)
            isPlaying = controller.isPlaying
            hasNext = PlaylistManager.hasNext()
            hasPrevious = PlaylistManager.hasPrevious()
            currentPosition = controller.currentPosition
            duration = controller.duration.takeIf { it > 0 } ?: 0L

            controller.currentMediaItem?.let { item ->
                currentTitle = item.mediaMetadata.title?.toString()
                    ?: File(item.localConfiguration?.uri?.path ?: "").nameWithoutExtension
                currentUri = item.localConfiguration?.uri?.toString() ?: ""

                if (currentUri.isNotEmpty()) {
                    coroutineScope.launch {
                        thumbnail = withContext(Dispatchers.IO) {
                            generateThumbnail(context, currentUri)
                        }
                    }
                }
            }
        }
    }

    fun closePlayer() {
        if (isCasting) {
            castManager.stopPlayback()
        } else {
            PlaylistManager.clear()
            MediaPlaybackService.stopService(context)
            currentTitle = ""
            currentUri = ""
            thumbnail = null
            currentPosition = 0L
            duration = 0L
            isPlaying = false
            hasNext = false
            hasPrevious = false
        }
    }

    val shouldShow = (isCasting && castTitle.isNotEmpty()) ||
            (mediaController != null && currentTitle.isNotEmpty())

    if (shouldShow) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .height(80.dp)
                .clickable { onOpenPlayer() },
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Column {
                // Progress bar
                if (effectiveDuration > 0) {
                    val progress = (effectivePosition.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = if (isCasting) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thumbnail with cast badge when casting
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .scale(thumbnailScale)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        effectiveThumbnail?.let { thumb ->
                            if (!thumb.isRecycled) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        if (!effectiveIsPlaying) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Cast badge
                        if (isCasting) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(3.dp)
                                    .background(Color(0xFF4CAF50), CircleShape)
                                    .padding(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Title + time
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = effectiveTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = if (effectiveDuration > 0)
                                "${formatTime(effectivePosition)} / ${formatTime(effectiveDuration)}"
                            else if (isCasting) "Transmitindo…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCasting) Color(0xFF4CAF50).copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous
                        IconButton(
                            onClick = {
                                if (isCasting) castManager.previous()
                                else PlaylistNavigator.previous(context)
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Play/Pause
                        Surface(
                            onClick = {
                                if (isCasting) {
                                    if (castIsPlaying) castManager.pause() else castManager.play()
                                } else {
                                    mediaController?.let {
                                        if (it.isPlaying) it.pause() else it.play()
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .scale(playButtonScale),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AnimatedContent(
                                    targetState = effectiveIsPlaying,
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(150)) togetherWith
                                                fadeOut(animationSpec = tween(150))
                                    },
                                    label = "playPauseIcon"
                                ) { playing ->
                                    Icon(
                                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (playing) "Pause" else "Play",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        // Next
                        IconButton(
                            onClick = {
                                if (isCasting) castManager.next()
                                else PlaylistNavigator.next(context)
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Close / Stop cast
                        IconButton(
                            onClick = { closePlayer() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    if (timeMs <= 0) return "00:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(timeMs)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private suspend fun generateThumbnail(context: android.content.Context, videoUri: String): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            if (videoUri.startsWith("locked://")) {
                val path = videoUri.removePrefix("locked://")
                FolderLockManager.getLockedThumbnail(path)
            } else {
                OptimizedThumbnailManager.getOrGenerateThumbnailSync(context, videoUri)
            }
        } catch (e: Exception) {
            null
        }
    }
