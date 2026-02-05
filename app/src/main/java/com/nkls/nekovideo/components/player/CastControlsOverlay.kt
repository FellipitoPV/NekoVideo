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
import com.nkls.nekovideo.components.layout.BannerAd
import com.nkls.nekovideo.billing.PremiumManager
import com.nkls.nekovideo.components.player.PlayerUtils.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CastControlsOverlay(
    castManager: CastManager,
    deviceName: String,
    videoTitle: String,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onCurrentIndexChanged: (Int) -> Unit,
    premiumManager: PremiumManager
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
    var currentTitle by remember { mutableStateOf(videoTitle) } // Para título dinâmico

    val isPremium by premiumManager.isPremium.collectAsState()

    // Atualizar estado do cast
    DisposableEffect(remoteMediaClient) {
        var firstItemId = -1L
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

                        // Atualizar título atual
                        status.mediaInfo?.metadata?.let { metadata ->
                            val title = metadata.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)
                            if (!title.isNullOrEmpty()) {
                                currentTitle = title
                            }
                        }

                        if (firstItemId == -1L && currentItemId > 0) {
                            firstItemId = currentItemId.toLong()
                        }

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
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header - só com botões de navegação
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

        // Área central - indicador de Cast, títulos e controles principais
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Ícone Cast
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = "Casting",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(80.dp)
            )

            // Informações de Cast e título
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Reproduzindo em",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )

                Text(
                    text = deviceName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                // Título do vídeo atual
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

                // Tempo atual / total
                if (duration > 0) {
                    Text(
                        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }

            // Controles principais centralizados
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
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

                Spacer(modifier = Modifier.width(32.dp))

                // Botão play/pause maior e destacado
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
        }

        // Seek bar na parte inferior
        if (duration > 0) {
            var tempPosition by remember { mutableStateOf(currentPosition) }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f)
                            )
                        )
                    )
                    .padding(24.dp)
            ) {
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
                        thumbColor = Color(0xFF4CAF50),
                        activeTrackColor = Color(0xFF4CAF50),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                BannerAd(isPremium = isPremium)

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