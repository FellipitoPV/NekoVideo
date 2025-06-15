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

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("OpaqueUnitKey")
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onVideoDeleted: (String) -> Unit = {} // Callback para notificar deleção
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var hasRefreshed by remember { mutableStateOf(false) }
    var overlayActuallyVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentVideoPath by remember { mutableStateOf("") }
    var controlsVisible by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // PlayerView que será reutilizado
    val playerView = remember {
        Log.d("VideoPlayer", "Criando PlayerView")
        PlayerView(context).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 5000
            controllerAutoShow = true
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // Dialog de confirmação para deletar
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                mediaController?.play() // Retomar reprodução se cancelar
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
                    Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        mediaController?.play() // Retomar reprodução
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Conectar ao MediaController uma única vez
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

    // Controlar overlay - refresh apenas uma vez quando fica visível
    LaunchedEffect(isVisible) {
        if (isVisible && mediaController != null && !hasRefreshed) {
            Log.d("VideoPlayer", "Overlay visível - fazendo refresh único do serviço")
            hasRefreshed = true
            overlayActuallyVisible = true

            // 1. Fazer refresh do serviço
            MediaPlaybackService.refreshPlayer(context)

            // 2. Aguardar refresh
            delay(800)

            // 3. Reconectar MediaController
            val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

            controllerFuture.addListener({
                try {
                    val newController = controllerFuture.get()
                    mediaController = newController

                    // 4. Conectar PlayerView
                    Log.d("VideoPlayer", "Conectando PlayerView ao MediaController refreshado")
                    playerView.player = newController

                    // Atualizar caminho do vídeo atual
                    newController.currentMediaItem?.localConfiguration?.uri?.let { uri ->
                        currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                    }

                    // 5. Configurar orientação APENAS se overlay estiver visível
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
            hasRefreshed = false // Reset para próxima vez que overlay ficar visível
            overlayActuallyVisible = false // IMPORTANTE: Marcar como realmente oculto
        }
    }

    // Listener para mudanças quando overlay está visível
    DisposableEffect(mediaController, overlayActuallyVisible) {
        var listener: Player.Listener? = null

        if (overlayActuallyVisible && mediaController != null) {
            listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // SÓ atualizar orientação se overlay estiver REALMENTE visível
                    if (!overlayActuallyVisible) {
                        Log.d("VideoPlayer", "Video size changed ignorado - overlay não visível")
                        return
                    }

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
                    // SÓ atualizar orientação se overlay estiver REALMENTE visível
                    if (!overlayActuallyVisible) {
                        Log.d("VideoPlayer", "Media transition ignorado - overlay não visível (reason: $reason)")
                        return
                    }

                    Log.d("VideoPlayer", "Media transition - reason: $reason")

                    // Atualizar caminho do vídeo atual
                    mediaItem?.localConfiguration?.uri?.let { uri ->
                        currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                    }

                    // Atualizar orientação para novo vídeo
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

            Log.d("VideoPlayer", "Adicionando listener")
            mediaController!!.addListener(listener)
        }

        onDispose {
            listener?.let {
                Log.d("VideoPlayer", "Removendo listener")
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
        ) {
            AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Monitorar visibilidade dos controles usando o tipo específico
                    view.setControllerVisibilityListener(
                        androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        }
                    )
                }
            )

            // Botão de deletar no canto superior direito - só visível quando controles estão visíveis
            if (overlayActuallyVisible && currentVideoPath.isNotEmpty() && controlsVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = {
                            // Pausar vídeo e mostrar dialog
                            mediaController?.pause()
                            showDeleteDialog = true
                        },
                        modifier = Modifier
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Video",
                            tint = Color.White
                        )
                    }
                }
            }
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

        // Verificar se é pasta segura
        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val isSecureVideo = videoPath.startsWith(secureFolderPath)

        // Salvar playlist atual (exceto o item que será deletado)
        val updatedPlaylist = mutableListOf<String>()
        for (i in 0 until totalItems) {
            if (i != currentIndex) {
                val itemUri = controller.getMediaItemAt(i).localConfiguration?.uri.toString()
                updatedPlaylist.add(itemUri)
            }
        }

        // Determinar próximo índice
        val nextIndex = when {
            updatedPlaylist.isEmpty() -> {
                // Última mídia da playlist
                Log.d("VideoPlayer", "Última mídia deletada - fechando player")
                return
            }
            currentIndex >= updatedPlaylist.size -> updatedPlaylist.size - 1
            else -> currentIndex
        }

        // Deletar arquivo físico
        val success = if (isSecureVideo) {
            deleteSecureFile(context, videoPath)
        } else {
            deleteRegularFile(context, videoPath)
        }

        if (success) {
            Log.d("VideoPlayer", "Arquivo deletado com sucesso")

            // Notificar a UI principal sobre a deleção
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onVideoDeleted(videoPath)
            }

            // Atualizar playlist no serviço
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
                // Retomar reprodução se falhou
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