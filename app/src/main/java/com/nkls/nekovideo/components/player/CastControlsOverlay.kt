package com.nkls.nekovideo.components.player

import android.content.pm.ActivityInfo
import android.util.Log
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.nkls.nekovideo.components.helpers.CastManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CastControlsOverlay(
    castManager: CastManager,
    deviceName: String,
    videoTitle: String,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onCurrentIndexChanged: (Int) -> Unit
) {
    val remoteMediaClient = remember {
        CastContext.getSharedInstance()
            ?.sessionManager
            ?.currentCastSession
            ?.remoteMediaClient
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentQueueIndex by remember { mutableStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }


    // Atualizar estado do cast
    DisposableEffect(remoteMediaClient) {
        var firstItemId = -1L  // Salvar o primeiro itemId
        var lastReportedIndex = -1
        var lastUpdateTime = 0L

        val listener = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                remoteMediaClient?.let { client ->
                    isPlaying = client.isPlaying
                    if (!isSeeking) {
                        currentPosition = client.approximateStreamPosition
                    }
                    duration = client.streamDuration.takeIf { it > 0 } ?: 0L

                    val status = client.mediaStatus
                    if (status != null) {
                        val currentItemId = status.currentItemId

                        // Salvar primeiro itemId
                        if (firstItemId == -1L && currentItemId > 0) {
                            firstItemId = currentItemId.toLong()
                            Log.d("CastControlsOverlay", "Primeiro itemId: $firstItemId")
                        }

                        // Calcular índice baseado no offset do itemId
                        val indexOffset = if (firstItemId > 0) {
                            (currentItemId - firstItemId).toInt()
                        } else {
                            0
                        }

                        val currentTime = System.currentTimeMillis()

                        if (indexOffset != lastReportedIndex && indexOffset >= 0 && (currentTime - lastUpdateTime) > 1000) {
                            lastReportedIndex = indexOffset
                            lastUpdateTime = currentTime
                            currentQueueIndex = indexOffset
                            onCurrentIndexChanged(indexOffset)
                            Log.d("CastControlsOverlay", "Offset calculado: $indexOffset (itemId atual: $currentItemId, primeiro: $firstItemId)")
                        }
                    }
                }
            }

            override fun onQueueStatusUpdated() {}
            override fun onMetadataUpdated() {}
            override fun onPreloadStatusUpdated() {}
            override fun onSendingRemoteMediaRequest() {}
        }

        remoteMediaClient?.registerCallback(listener)

        // Update loop
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            while (true) {
                remoteMediaClient?.let { client ->
                    isPlaying = client.isPlaying
                    if (!isSeeking) {
                        currentPosition = client.approximateStreamPosition
                    }
                    duration = client.streamDuration.takeIf { it > 0 } ?: 0L
                }
                delay(500)
            }
        }

        onDispose {
            remoteMediaClient?.unregisterCallback(listener)
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        onDispose {
            // Voltar ao comportamento automático ao sair
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Indicador de Cast no centro
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = "Casting",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(80.dp)
            )

            Text(
                text = "Reproduzindo em",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )

            Text(
                text = deviceName,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            Color.Transparent
                        )
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

                Text(
                    text = videoTitle,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                )

                IconButton(
                    onClick = onDisconnect,
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

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            // Controles de play/pause
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        remoteMediaClient?.queuePrev(null)
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(24.dp))

                IconButton(
                    onClick = {
                        if (isPlaying) {
                            remoteMediaClient?.pause()
                        } else {
                            remoteMediaClient?.play()
                        }
                    },
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.9f), CircleShape)
                        .size(72.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(24.dp))

                IconButton(
                    onClick = {
                        remoteMediaClient?.queueNext(null)
                    },
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

            // Seek bar
            if (duration > 0) {
                var tempPosition by remember { mutableStateOf(currentPosition) }

                Slider(
                    value = if (isSeeking) tempPosition.toFloat() else currentPosition.toFloat(),
                    onValueChange = { newValue ->
                        tempPosition = newValue.toLong()
                        if (!isSeeking) {
                            isSeeking = true
                        }
                    },
                    onValueChangeFinished = {
                        remoteMediaClient?.seek(tempPosition)
                        isSeeking = false
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(if (isSeeking) tempPosition else currentPosition),
                        color = Color.White,
                        fontSize = 14.sp
                    )

                    Text(
                        text = formatTime(duration),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
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