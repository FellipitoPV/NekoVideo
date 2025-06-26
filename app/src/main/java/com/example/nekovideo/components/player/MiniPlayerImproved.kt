package com.example.nekovideo.components.player

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.nekovideo.MediaPlaybackService
import com.google.common.util.concurrent.MoreExecutors
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
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("") }
    var currentUri by remember { mutableStateOf("") }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrevious by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    // Animações
    val expandedHeight by animateDpAsState(
        targetValue = if (isExpanded) 120.dp else 80.dp,
        animationSpec = tween(300)
    )

    val playButtonScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 1.1f,
        animationSpec = tween(200)
    )

    // Conectar ao MediaController
    LaunchedEffect(Unit) {
        try {
            val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

            controllerFuture.addListener({
                try {
                    val controller = controllerFuture.get()
                    mediaController = controller
                } catch (e: Exception) {
                    // Service não está rodando
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            // Erro ao conectar
        }
    }

    // Atualizar posição do vídeo
    LaunchedEffect(mediaController, isPlaying) {
        if (mediaController != null && isPlaying) {
            while (isPlaying) {
                mediaController?.let { controller ->
                    currentPosition = controller.currentPosition
                    duration = controller.duration.takeIf { it > 0 } ?: 0L
                }
                delay(100) // Atualização mais suave
            }
        }
    }

    // Monitorar mudanças no player
    LaunchedEffect(mediaController) {
        mediaController?.let { controller ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isPlaying = controller.isPlaying
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    mediaItem?.let { item ->
                        currentTitle = item.mediaMetadata.title?.toString()
                            ?: File(item.localConfiguration?.uri?.path ?: "").nameWithoutExtension
                        currentUri = item.localConfiguration?.uri?.toString() ?: ""

                        hasNext = controller.hasNextMediaItem()
                        hasPrevious = controller.hasPreviousMediaItem()
                        currentPosition = controller.currentPosition
                        duration = controller.duration.takeIf { it > 0 } ?: 0L

                        if (currentUri.isNotEmpty()) {
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                val thumb = generateThumbnail(currentUri)
                                withContext(Dispatchers.Main) {
                                    thumbnail = thumb
                                }
                            }
                        }
                    }
                }
            }

            controller.addListener(listener)

            // Estado inicial
            isPlaying = controller.isPlaying
            hasNext = controller.hasNextMediaItem()
            hasPrevious = controller.hasPreviousMediaItem()
            currentPosition = controller.currentPosition
            duration = controller.duration.takeIf { it > 0 } ?: 0L

            controller.currentMediaItem?.let { item ->
                currentTitle = item.mediaMetadata.title?.toString()
                    ?: File(item.localConfiguration?.uri?.path ?: "").nameWithoutExtension
                currentUri = item.localConfiguration?.uri?.toString() ?: ""

                if (currentUri.isNotEmpty()) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        val thumb = generateThumbnail(currentUri)
                        withContext(Dispatchers.Main) {
                            thumbnail = thumb
                        }
                    }
                }
            }
        }
    }

    // Só mostrar se há um vídeo carregado
    if (mediaController != null && currentTitle.isNotEmpty()) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .height(expandedHeight)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onOpenPlayer() },
                        onLongPress = { isExpanded = !isExpanded }
                    )
                },
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column {
                // Progress bar no topo
                if (duration > 0) {
                    val progress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thumbnail com animação
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        thumbnail?.let { thumb ->
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = "Thumbnail",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Overlay de play quando pausado
                        if (!isPlaying) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Paused",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Título e informações
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White,
                            maxLines = if (isExpanded) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp
                            )

                            if (isExpanded) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = "Status",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    // Controles compactos
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous
                        IconButton(
                            onClick = {
                                mediaController?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
                            },
                            enabled = hasPrevious,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Play/Pause com animação
                        Surface(
                            onClick = {
                                mediaController?.let {
                                    if (it.isPlaying) it.pause() else it.play()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .graphicsLayer(
                                    scaleX = playButtonScale,
                                    scaleY = playButtonScale
                                ),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Next
                        IconButton(
                            onClick = {
                                mediaController?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
                            },
                            enabled = hasNext,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Área expandida com controles extras
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Progress slider interativo
                        if (duration > 0) {
                            val sliderProgress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

                            Slider(
                                value = sliderProgress,
                                onValueChange = { newProgress ->
                                    val newPosition = (newProgress * duration).toLong()
                                    mediaController?.seekTo(newPosition)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
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

private suspend fun generateThumbnail(videoUri: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val retriever = MediaMetadataRetriever()
        val path = videoUri.removePrefix("file://")
        retriever.setDataSource(path)
        val bitmap = retriever.getFrameAtTime(1000000L)
        retriever.release()
        bitmap
    } catch (e: Exception) {
        null
    }
}