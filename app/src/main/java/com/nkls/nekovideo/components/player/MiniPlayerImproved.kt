package com.nkls.nekovideo.components.player

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.nkls.nekovideo.MediaPlaybackService
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

    // Estados locais
    val mediaController by MediaControllerManager.mediaController.collectAsStateWithLifecycle()

    // Estados do Cast
    var isCasting by remember { mutableStateOf(false) }
    var remoteMediaClient by remember { mutableStateOf<RemoteMediaClient?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("") }
    var currentUri by remember { mutableStateOf("") }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrevious by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    // Cores (manter as mesmas)
    val backgroundColor = Color(0xFF1A1A1A)
    val surfaceColor = Color(0xFF2D2D2D)
    val primaryColor = Color(0xFF6366F1)
    val onSurfaceColor = Color(0xFFE0E0E0)
    val onSurfaceVariant = Color(0xFFB0B0B0)

    // Animações (manter as mesmas)
    val expandedHeight by animateDpAsState(
        targetValue = if (isExpanded) 120.dp else 80.dp,
        animationSpec = tween(300)
    )

    val playButtonScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 1.1f,
        animationSpec = tween(200)
    )

    // Detectar sessão Cast
    LaunchedEffect(Unit) {
        val castContext = CastContext.getSharedInstance(context)
        val sessionManager = castContext.sessionManager

        val listener = object : SessionManagerListener<Session> {
            override fun onSessionStarted(session: Session, sessionId: String) {
                isCasting = true
                remoteMediaClient = (session as? CastSession)?.remoteMediaClient
            }

            override fun onSessionEnded(session: Session, error: Int) {
                isCasting = false
                remoteMediaClient = null
            }

            override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
                isCasting = true
                remoteMediaClient = (session as? CastSession)?.remoteMediaClient
            }

            override fun onSessionSuspended(session: Session, reason: Int) {}
            override fun onSessionStarting(session: Session) {}
            override fun onSessionEnding(session: Session) {}
            override fun onSessionResuming(session: Session, sessionId: String) {}
            override fun onSessionResumeFailed(session: Session, error: Int) {}
            override fun onSessionStartFailed(session: Session, error: Int) {}
        }

        sessionManager.addSessionManagerListener(listener)

        // Verificar se já há sessão ativa
        sessionManager.currentCastSession?.let { session ->
            isCasting = true
            remoteMediaClient = session.remoteMediaClient
        }
    }

    // Atualizar posição - CAST ou LOCAL
    LaunchedEffect(isCasting, remoteMediaClient, mediaController, isPlaying) {
        while (isPlaying) {
            if (isCasting && remoteMediaClient != null) {
                // Dados do Cast
                currentPosition = remoteMediaClient!!.approximateStreamPosition
                duration = remoteMediaClient!!.streamDuration.takeIf { it > 0 } ?: 0L
            } else if (!isCasting && mediaController != null) {
                // Dados locais
                currentPosition = mediaController!!.currentPosition
                duration = mediaController!!.duration.takeIf { it > 0 } ?: 0L
            }
            delay(500)
        }
    }

    // Listener do Cast
    DisposableEffect(remoteMediaClient) {
        val listener = remoteMediaClient?.let { client ->
            object : RemoteMediaClient.Callback() {
                override fun onStatusUpdated() {
                    isPlaying = client.isPlaying
                    currentPosition = client.approximateStreamPosition
                    duration = client.streamDuration.takeIf { it > 0 } ?: 0L

                    client.mediaStatus?.let { status ->
                        val metadata = status.mediaInfo?.metadata
                        currentTitle = metadata?.getString(MediaMetadata.KEY_TITLE) ?: "Casting..."

                        val queueItems = status.queueItems
                        val currentItemId = status.currentItemId
                        val currentIndex = queueItems?.indexOfFirst { it.itemId == currentItemId } ?: -1

                        hasNext = currentIndex >= 0 && currentIndex < (queueItems?.size ?: 0) - 1
                        hasPrevious = currentIndex > 0
                    }
                }

                override fun onQueueStatusUpdated() {
                    client.mediaStatus?.let { status ->
                        val queueItems = status.queueItems
                        val currentItemId = status.currentItemId
                        val currentIndex = queueItems?.indexOfFirst { it.itemId == currentItemId } ?: -1

                        hasNext = currentIndex >= 0 && currentIndex < (queueItems?.size ?: 0) - 1
                        hasPrevious = currentIndex > 0
                    }
                }

                override fun onMetadataUpdated() {}
                override fun onPreloadStatusUpdated() {}
                override fun onSendingRemoteMediaRequest() {}
            }
        }

        listener?.let { remoteMediaClient?.registerCallback(it) }

        onDispose {
            listener?.let { remoteMediaClient?.unregisterCallback(it) }
        }
    }

    // Listener do player local
    LaunchedEffect(mediaController) {
        if (!isCasting) {  // Só atualizar se NÃO estiver casting
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
    }

// Conectar ao MediaController
    DisposableEffect(Unit) {
        MediaControllerManager.connect(context)
        onDispose { }
    }

    // Função para fechar
    fun closePlayer() {
        if (isCasting) {
            CastContext.getSharedInstance(context)
                .sessionManager
                .endCurrentSession(true)
        } else {
            MediaPlaybackService.stopService(context)
        }
        // Reset states
        currentTitle = ""
        currentUri = ""
        thumbnail = null
        currentPosition = 0L
        duration = 0L
        isPlaying = false
        hasNext = false
        hasPrevious = false
        isExpanded = false
    }

    // UI - Mostrar se há Cast OU MediaController
    if ((isCasting && remoteMediaClient != null) || (mediaController != null && currentTitle.isNotEmpty())) {
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
                containerColor = backgroundColor
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
                        color = primaryColor,
                        trackColor = onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thumbnail com ícone Cast quando casting
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(surfaceColor)
                    ) {
                        if (isCasting) {
                            // Ícone Cast ao invés de thumbnail
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = "Casting",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        } else {
                            // Thumbnail normal
                            thumbnail?.let { thumb ->
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = "Thumbnail",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

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
                            color = onSurfaceColor,
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
                                color = onSurfaceVariant,
                                fontSize = 11.sp
                            )

                            if (isExpanded) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = "Status",
                                    tint = onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    // Controles adaptados
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous
                        IconButton(
                            onClick = {
                                if (isCasting) {
                                    remoteMediaClient?.queuePrev(null)
                                } else {
                                    mediaController?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
                                }
                            },
                            enabled = hasPrevious,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = if (hasPrevious) onSurfaceColor else onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Play/Pause
                        Surface(
                            onClick = {
                                if (isCasting) {
                                    if (isPlaying) remoteMediaClient?.pause() else remoteMediaClient?.play()
                                } else {
                                    mediaController?.let {
                                        if (it.isPlaying) it.pause() else it.play()
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .graphicsLayer(
                                    scaleX = playButtonScale,
                                    scaleY = playButtonScale
                                ),
                            shape = CircleShape,
                            color = surfaceColor
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = onSurfaceColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Next
                        IconButton(
                            onClick = {
                                if (isCasting) {
                                    remoteMediaClient?.queueNext(null)
                                } else {
                                    mediaController?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
                                }
                            },
                            enabled = hasNext,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = if (hasNext) onSurfaceColor else onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Close
                        IconButton(
                            onClick = { closePlayer() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Área expandida (mesmo código, mas adaptar seek)
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (duration > 0) {
                            val sliderProgress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

                            Slider(
                                value = sliderProgress,
                                onValueChange = { newProgress ->
                                    val newPosition = (newProgress * duration).toLong()
                                    if (isCasting) {
                                        remoteMediaClient?.seek(newPosition)
                                    } else {
                                        mediaController?.seekTo(newPosition)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor,
                                    inactiveTrackColor = onSurfaceVariant.copy(alpha = 0.3f)
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