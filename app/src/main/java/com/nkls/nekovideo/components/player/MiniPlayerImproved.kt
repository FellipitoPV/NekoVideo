package com.nkls.nekovideo.components.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.components.helpers.PlaylistManager
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

    // ✅ Animações suaves e minimalistas
    val playButtonScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "playButtonScale"
    )

    val thumbnailScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "thumbnailScale"
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

        sessionManager.currentCastSession?.let { session ->
            isCasting = true
            remoteMediaClient = session.remoteMediaClient
        }
    }

    // Atualizar posição
    LaunchedEffect(isCasting, remoteMediaClient, mediaController, isPlaying) {
        while (isPlaying) {
            if (isCasting && remoteMediaClient != null) {
                currentPosition = remoteMediaClient!!.approximateStreamPosition
                duration = remoteMediaClient!!.streamDuration.takeIf { it > 0 } ?: 0L
            } else if (!isCasting && mediaController != null) {
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
        if (!isCasting) {
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

                            hasNext = PlaylistManager.hasNext()
                            hasPrevious = PlaylistManager.hasPrevious()
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

    DisposableEffect(Unit) {
        MediaControllerManager.connect(context)
        onDispose { }
    }

    fun closePlayer() {
        if (isCasting) {
            CastContext.getSharedInstance(context)
                .sessionManager
                .endCurrentSession(true)
        } else {
            MediaPlaybackService.stopService(context)
        }
        currentTitle = ""
        currentUri = ""
        thumbnail = null
        currentPosition = 0L
        duration = 0L
        isPlaying = false
        hasNext = false
        hasPrevious = false
    }

    // ✅ UI MINIMALISTA E CLEAN
    if ((isCasting && remoteMediaClient != null) || (mediaController != null && currentTitle.isNotEmpty())) {
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
                // ✅ Progress bar minimalista no topo
                if (duration > 0) {
                    val progress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ✅ Thumbnail com animação sutil
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .scale(thumbnailScale)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (isCasting) {
                            Icon(
                                imageVector = Icons.Default.Cast,
                                contentDescription = "Casting",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.Center)
                            )
                        } else {
                            thumbnail?.let { thumb ->
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = "Thumbnail",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        // ✅ Overlay de pause discreto
                        if (!isPlaying) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Paused",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // ✅ Informações do vídeo
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = currentTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // ✅ Controles minimalistas
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
                                    when (val result = PlaylistManager.previous()) {
                                        is PlaylistManager.NavigationResult.Success -> {
                                            // ✅ Atualizar ANTES de executar ação
                                            hasNext = PlaylistManager.hasNext()
                                            hasPrevious = PlaylistManager.hasPrevious()

                                            if (result.needsWindowUpdate) {
                                                val newWindow = PlaylistManager.getCurrentWindow()
                                                val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                                                MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                                            } else {
                                                mediaController?.seekToPreviousMediaItem()
                                            }
                                        }
                                        else -> {
                                            // ✅ Atualizar mesmo em casos de erro
                                            hasNext = PlaylistManager.hasNext()
                                            hasPrevious = PlaylistManager.hasPrevious()
                                        }
                                    }
                                }
                            },
                            enabled = hasPrevious,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = if (hasPrevious) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // ✅ Play/Pause com animação
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
                                .size(44.dp)
                                .scale(playButtonScale),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                // ✅ Animação de troca de ícone
                                AnimatedContent(
                                    targetState = isPlaying,
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
                                if (isCasting) {
                                    remoteMediaClient?.queueNext(null)
                                } else {
                                    when (val result = PlaylistManager.next()) {
                                        is PlaylistManager.NavigationResult.Success -> {
                                            // ✅ Atualizar ANTES de executar ação
                                            hasNext = PlaylistManager.hasNext()
                                            hasPrevious = PlaylistManager.hasPrevious()

                                            if (result.needsWindowUpdate) {
                                                val newWindow = PlaylistManager.getCurrentWindow()
                                                val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                                                MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                                            } else {
                                                mediaController?.seekToNextMediaItem()
                                            }
                                        }
                                        else -> {
                                            // ✅ Atualizar mesmo em casos de erro
                                            hasNext = PlaylistManager.hasNext()
                                            hasPrevious = PlaylistManager.hasPrevious()
                                        }
                                    }
                                }
                            },
                            enabled = hasNext,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = if (hasNext) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // ✅ Close minimalista
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