package com.nkls.nekovideo.components.player

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.player.PlayerUtils.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CastControlsOverlay(
    castManager: DLNACastManager,
    deviceName: String,
    videoTitle: String,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onCurrentIndexChanged: (Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(castManager.isPlaying) }
    var currentPosition by remember { mutableStateOf(castManager.currentPositionMs) }
    var duration by remember { mutableStateOf(castManager.durationMs) }
    var isSeeking by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf(videoTitle) }
    var currentVideoPath by remember { mutableStateOf(castManager.currentVideoPath) }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Poll state from the DLNA manager
    LaunchedEffect(Unit) {
        castManager.onStateChanged = {
            isPlaying = castManager.isPlaying
            if (!isSeeking) currentPosition = castManager.currentPositionMs
            duration = castManager.durationMs
            if (castManager.currentTitle.isNotEmpty()) currentTitle = castManager.currentTitle
            if (castManager.currentVideoPath != currentVideoPath) {
                currentVideoPath = castManager.currentVideoPath
            }
        }
    }

    // Load thumbnail whenever the current video path changes
    LaunchedEffect(currentVideoPath) {
        thumbnailBitmap = null
        if (currentVideoPath.isEmpty()) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            when {
                currentVideoPath.startsWith("locked://") -> {
                    val cleanPath = currentVideoPath.removePrefix("locked://")
                    // Locked videos: try cache only (file is encrypted)
                    OptimizedThumbnailManager.getCachedThumbnail(cleanPath)
                        ?: OptimizedThumbnailManager.loadThumbnailFromDiskSync(cleanPath)
                }
                else -> {
                    val cleanPath = currentVideoPath.removePrefix("file://")
                    OptimizedThumbnailManager.getOrGenerateThumbnailSync(context, cleanPath)
                }
            }
        }
        thumbnailBitmap = bitmap
    }

    // Progress ticker while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isSeeking) currentPosition = castManager.currentPositionMs
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            castManager.onStateChanged = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Background: thumbnail (if available) or solid black
        val thumb = thumbnailBitmap
        if (thumb != null && !thumb.isRecycled) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // Dark scrim so UI stays readable regardless of thumbnail brightness
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
        )

        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = { showDisconnectDialog = true },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CastConnected,
                        contentDescription = "Disconnect",
                        tint = Color(0xFF4CAF50)
                    )
                }
            }
        }

        // Centre — cast icon + info + controls
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = "Casting",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(80.dp)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.cast_playing_on),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
                Text(
                    text = deviceName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (currentTitle.isNotEmpty()) {
                    Text(
                        text = currentTitle,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                if (duration > 0) {
                    Text(
                        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }

            // Playback controls
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { castManager.previous() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(32.dp))

                IconButton(
                    onClick = { if (isPlaying) castManager.pause() else castManager.play() },
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.9f), CircleShape)
                        .size(80.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.width(32.dp))

                IconButton(
                    onClick = { castManager.next() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Disconnect confirmation dialog
        if (showDisconnectDialog) {
            AlertDialog(
                onDismissRequest = { showDisconnectDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.CastConnected,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50)
                    )
                },
                title = { Text(stringResource(R.string.cast_disconnect_title)) },
                text = {
                    Text(stringResource(R.string.cast_disconnect_message, deviceName))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDisconnectDialog = false
                        onDisconnect()
                    }) {
                        Text(stringResource(R.string.cast_disconnect_confirm), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDisconnectDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Seek bar at bottom
        if (duration > 0) {
            var tempPosition by remember { mutableStateOf(currentPosition) }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
                    .padding(24.dp)
            ) {
                Slider(
                    value = if (isSeeking) tempPosition.toFloat() else currentPosition.toFloat(),
                    onValueChange = { newValue ->
                        tempPosition = newValue.toLong()
                        isSeeking = true
                    },
                    onValueChangeFinished = {
                        castManager.seekTo(tempPosition)
                        isSeeking = false
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF4CAF50),
                        activeTrackColor = Color(0xFF4CAF50),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
