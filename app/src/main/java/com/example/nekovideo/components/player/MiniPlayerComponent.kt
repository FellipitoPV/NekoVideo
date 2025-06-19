package com.example.nekovideo.components.player

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

@Composable
fun MiniPlayer(
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

    // Atualizar posição do vídeo a cada segundo
    LaunchedEffect(mediaController, isPlaying) {
        if (mediaController != null && isPlaying) {
            while (isPlaying) {
                mediaController?.let { controller ->
                    currentPosition = controller.currentPosition
                    duration = controller.duration.takeIf { it > 0 } ?: 0L
                }
                delay(1000)
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

                        // Atualizar estado dos botões next/previous
                        hasNext = controller.hasNextMediaItem()
                        hasPrevious = controller.hasPreviousMediaItem()

                        // Atualizar posição e duração
                        currentPosition = controller.currentPosition
                        duration = controller.duration.takeIf { it > 0 } ?: 0L

                        // Carregar thumbnail
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
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(80.dp)
                .clickable { onOpenPlayer() },
            color = MaterialTheme.colorScheme.primary, // Cor primária
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    thumbnail?.let { thumb ->
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = "Video Thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Título e Timer
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Controles de navegação e play/pause
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botão Previous
                    IconButton(
                        onClick = {
                            mediaController?.let { controller ->
                                if (controller.hasPreviousMediaItem()) {
                                    controller.seekToPreviousMediaItem()
                                }
                            }
                        },
                        enabled = hasPrevious,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Botão Play/Pause
                    IconButton(
                        onClick = {
                            mediaController?.let { controller ->
                                if (controller.isPlaying) {
                                    controller.pause()
                                } else {
                                    controller.play()
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Botão Next
                    IconButton(
                        onClick = {
                            mediaController?.let { controller ->
                                if (controller.hasNextMediaItem()) {
                                    controller.seekToNextMediaItem()
                                }
                            }
                        },
                        enabled = hasNext,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// Função para formatar tempo em MM:SS
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
        val bitmap = retriever.getFrameAtTime(1000000L) // 1 segundo
        retriever.release()
        bitmap
    } catch (e: Exception) {
        null
    }
}