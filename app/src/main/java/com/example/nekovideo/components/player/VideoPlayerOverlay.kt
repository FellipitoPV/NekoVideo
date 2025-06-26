package com.example.nekovideo.components.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nekovideo.components.helpers.FilesManager
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.example.nekovideo.MediaPlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Novos imports para a interface customizada
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("OpaqueUnitKey")
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onVideoDeleted: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var hasRefreshed by remember { mutableStateOf(false) }
    var overlayActuallyVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentVideoPath by remember { mutableStateOf("") }

    // Estados para controles customizados
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var currentVideoTitle by remember { mutableStateOf("") }
    var isSeekingActive by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // PlayerView sem controles nativos
    val playerView = remember {
        Log.d("VideoPlayer", "Criando PlayerView")
        PlayerView(context).apply {
            useController = false // Desabilitar controles nativos
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // Efeito para atualizar posição do vídeo
    LaunchedEffect(mediaController, isSeekingActive) {
        if (mediaController != null && !isSeekingActive) {
            while (overlayActuallyVisible) {
                currentPosition = mediaController!!.currentPosition
                duration = mediaController!!.duration.takeIf { it > 0 } ?: 0L
                isPlaying = mediaController!!.isPlaying
                delay(100)
            }
        }
    }

    // Função para esconder controles automaticamente
    LaunchedEffect(controlsVisible) {
        if (controlsVisible && isPlaying) {
            delay(4000)
            controlsVisible = false
        }
    }

    // Dialog de confirmação para deletar
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                mediaController?.play()
            },
            title = {
                Text(
                    text = "Delete Video",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val fileName = File(currentVideoPath).nameWithoutExtension
                Text(
                    text = "Are you sure you want to delete \"$fileName\"?\n\nThis action cannot be undone.",
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        coroutineScope.launch {
                            deleteCurrentVideo(
                                context = context,
                                videoPath = currentVideoPath,
                                mediaController = mediaController,
                                onVideoDeleted = onVideoDeleted
                            )
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        mediaController?.play()
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Conectar ao MediaController
    LaunchedEffect(Unit) {
        Log.d("VideoPlayer", "Conectando ao MediaController")
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                Log.d("VideoPlayer", "MediaController conectado com sucesso")
            } catch (e: Exception) {
                Log.e("VideoPlayer", "Erro ao conectar MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    // Controlar overlay
    LaunchedEffect(isVisible) {
        if (isVisible && mediaController != null && !hasRefreshed) {
            Log.d("VideoPlayer", "Overlay visível - fazendo refresh único do serviço")
            hasRefreshed = true
            overlayActuallyVisible = true

            MediaPlaybackService.refreshPlayer(context)
            delay(800)

            val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

            controllerFuture.addListener({
                try {
                    val newController = controllerFuture.get()
                    mediaController = newController
                    playerView.player = newController

                    newController.currentMediaItem?.localConfiguration?.uri?.let { uri ->
                        currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                        currentVideoTitle = File(currentVideoPath).nameWithoutExtension
                    }

                    if (overlayActuallyVisible) {
                        val videoSize = newController.videoSize
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val activity = context.findActivity()
                            val newOrientation = if (videoSize.width > videoSize.height) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            activity?.requestedOrientation = newOrientation
                        }
                    }

                    Log.d("VideoPlayer", "PlayerView conectado e configurado")
                } catch (e: Exception) {
                    Log.e("VideoPlayer", "Erro ao reconectar MediaController", e)
                }
            }, MoreExecutors.directExecutor())

        } else if (!isVisible) {
            Log.d("VideoPlayer", "Overlay oculto - desconectando PlayerView")
            playerView.player = null
            hasRefreshed = false
            overlayActuallyVisible = false
        }
    }

    // Listener para mudanças
    DisposableEffect(mediaController, overlayActuallyVisible) {
        var listener: Player.Listener? = null

        if (overlayActuallyVisible && mediaController != null) {
            listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!overlayActuallyVisible) return

                    Log.d("VideoPlayer", "Video size changed: ${videoSize.width}x${videoSize.height}")
                    val activity = context.findActivity()
                    if (activity != null && videoSize.width > 0 && videoSize.height > 0) {
                        val newOrientation = if (videoSize.width > videoSize.height) {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        activity.requestedOrientation = newOrientation
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!overlayActuallyVisible) return

                    mediaItem?.localConfiguration?.uri?.let { uri ->
                        currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                        currentVideoTitle = File(currentVideoPath).nameWithoutExtension
                    }

                    val videoSize = mediaController!!.videoSize
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val activity = context.findActivity()
                        val newOrientation = if (videoSize.width > videoSize.height) {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        activity?.requestedOrientation = newOrientation
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d("VideoPlayer", "Playback state: $playbackState")
                    if (playbackState == Player.STATE_ENDED && mediaController!!.hasNextMediaItem()) {
                        mediaController!!.seekToNextMediaItem()
                    }
                }
            }

            mediaController!!.addListener(listener)
        }

        onDispose {
            listener?.let {
                mediaController?.removeListener(it)
            }
        }
    }

    // Controle de modo imersivo
    DisposableEffect(isVisible) {
        if (isVisible) {
            val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)

            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            isFullscreen = true

            onDispose {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                isFullscreen = false
            }
        } else {
            onDispose { }
        }
    }

    // Handle back press
    LaunchedEffect(isVisible) {
        if (isVisible && backDispatcher != null) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onDismiss()
                }
            }
            backDispatcher.addCallback(callback)
        }
    }

    // Overlay animado
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    controlsVisible = !controlsVisible
                }
        ) {
            // PlayerView em background
            AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize()
            )

            // Interface customizada
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                CustomVideoControls(
                    mediaController = mediaController,
                    currentPosition = currentPosition,
                    duration = duration,
                    isPlaying = isPlaying,
                    videoTitle = currentVideoTitle,
                    onSeekStart = { isSeekingActive = true },
                    onSeekEnd = { isSeekingActive = false },
                    onDeleteClick = {
                        mediaController?.pause()
                        showDeleteDialog = true
                    },
                    onBackClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun CustomVideoControls(
    mediaController: MediaController?,
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    videoTitle: String,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onDeleteClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val controller = mediaController ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        // Header com gradiente
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
                    onClick = onBackClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
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
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Controles centrais
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botão Previous
            IconButton(
                onClick = {
                    if (controller.hasPreviousMediaItem()) {
                        controller.seekToPreviousMediaItem()
                    }
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp),
                enabled = controller.hasPreviousMediaItem()
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (controller.hasPreviousMediaItem()) Color.White else Color.Gray,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Botão Play/Pause
            IconButton(
                onClick = {
                    if (isPlaying) {
                        controller.pause()
                    } else {
                        controller.play()
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

            // Botão Next
            IconButton(
                onClick = {
                    if (controller.hasNextMediaItem()) {
                        controller.seekToNextMediaItem()
                    }
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp),
                enabled = controller.hasNextMediaItem()
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = if (controller.hasNextMediaItem()) Color.White else Color.Gray,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Bottom controls com gradiente
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
            // Seek bar
            if (duration > 0) {
                var tempPosition by remember { mutableStateOf(currentPosition) }
                var isDragging by remember { mutableStateOf(false) }

                Slider(
                    value = if (isDragging) tempPosition.toFloat() else currentPosition.toFloat(),
                    onValueChange = { newValue ->
                        tempPosition = newValue.toLong()
                        if (!isDragging) {
                            isDragging = true
                            onSeekStart()
                        }
                    },
                    onValueChangeFinished = {
                        controller.seekTo(tempPosition)
                        isDragging = false
                        onSeekEnd()
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Time display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(if (isDragging) tempPosition else currentPosition),
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

// Função auxiliar existente
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

// Função para deletar vídeo atual e atualizar playlist
private suspend fun deleteCurrentVideo(
    context: Context,
    videoPath: String,
    mediaController: MediaController?,
    onVideoDeleted: (String) -> Unit
) {
    try {
        val controller = mediaController ?: return
        val currentIndex = controller.currentMediaItemIndex
        val totalItems = controller.mediaItemCount

        Log.d("VideoPlayer", "Deletando vídeo: $videoPath (índice: $currentIndex)")

        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val isSecureVideo = videoPath.startsWith(secureFolderPath)

        val updatedPlaylist = mutableListOf<String>()
        for (i in 0 until totalItems) {
            if (i != currentIndex) {
                val itemUri = controller.getMediaItemAt(i).localConfiguration?.uri.toString()
                updatedPlaylist.add(itemUri)
            }
        }

        val nextIndex = when {
            updatedPlaylist.isEmpty() -> {
                Log.d("VideoPlayer", "Última mídia deletada - fechando player")
                return
            }
            currentIndex >= updatedPlaylist.size -> updatedPlaylist.size - 1
            else -> currentIndex
        }

        val success = if (isSecureVideo) {
            deleteSecureFile(context, videoPath)
        } else {
            deleteRegularFile(context, videoPath)
        }

        if (success) {
            Log.d("VideoPlayer", "Arquivo deletado com sucesso")

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onVideoDeleted(videoPath)
            }

            if (updatedPlaylist.isNotEmpty()) {
                Log.d("VideoPlayer", "Atualizando playlist: ${updatedPlaylist.size} itens, próximo índice: $nextIndex")
                MediaPlaybackService.updatePlaylistAfterDeletion(context, updatedPlaylist, nextIndex)
            } else {
                Log.d("VideoPlayer", "Playlist vazia - parando serviço")
                MediaPlaybackService.stopService(context)
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Video deleted successfully", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Failed to delete video", android.widget.Toast.LENGTH_SHORT).show()
                controller.play()
            }
        }

    } catch (e: Exception) {
        Log.e("VideoPlayer", "Erro ao deletar vídeo", e)
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(context, "Error deleting video: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            mediaController?.play()
        }
    }
}

private suspend fun deleteRegularFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo regular", e)
            false
        }
    }

private suspend fun deleteSecureFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo seguro", e)
            false
        }
    }