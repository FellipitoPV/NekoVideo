package com.example.nekovideo.components

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.example.nekovideo.MediaPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

// Função auxiliar para encontrar a Activity
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@SuppressLint("OpaqueUnitKey")
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    playlist: List<String>,
    initialIndex: Int = 0
) {
    val context = LocalContext.current
    val controllerFuture = remember {
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        MediaController.Builder(context, sessionToken).buildAsync()
    }

    // Criar o PlayerView com controles padrão
    val playerView = remember {
        PlayerView(context).apply {
            useController = true // Mantém controles padrão
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
    }

    // Função para ajustar a orientação com base na proporção do vídeo
    fun updateOrientation(controller: MediaController) {
        val activity = context.findActivity() ?: return
        val videoSize = controller.videoSize
        val width = videoSize.width
        val height = videoSize.height

        if (width > 0 && height > 0) {
            val currentOrientation = activity.requestedOrientation
            val newOrientation = if (width > height) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            // Apenas mudar a orientação se for diferente da atual
            if (currentOrientation != newOrientation) {
                activity.requestedOrientation = newOrientation
            }
        }
    }

    // Configurar modo imersivo para ocultar barra de notificação e navegação
    DisposableEffect(Unit) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        // Entrar no modo imersivo
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            // Restaurar barras do sistema ao sair
            WindowCompat.setDecorFitsSystemWindows(window, true)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Iniciar o serviço com a nova playlist e vincular o PlayerView
    DisposableEffect(playlist, initialIndex) {
        MediaPlaybackService.startWithPlaylist(context, playlist, initialIndex)

        val listener = Runnable {
            controllerFuture.get()?.let { controller ->
                playerView.player = controller

                // Configurar o player para avançar automaticamente na playlist
                controller.repeatMode = Player.REPEAT_MODE_OFF // Garante que não repita um único vídeo
                controller.shuffleModeEnabled = false // Desativa shuffle, se não desejado

                // Atualizar orientação inicial
                updateOrientation(controller)

                // Adicionar listener para mudanças no vídeo e no estado de reprodução
                val playerListener = object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        updateOrientation(controller)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && controller.hasNextMediaItem()) {
                            controller.seekToNextMediaItem()
                            updateOrientation(controller) // Atualizar orientação para o próximo vídeo
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        updateOrientation(controller) // Atualizar orientação quando houver transição
                    }
                }
                controller.addListener(playerListener)

                // Remover o listener e liberar recursos ao destruir
                onDispose {
                    controller.removeListener(playerListener)
                    controller.release()
                    MediaController.releaseFuture(controllerFuture)
                    playerView.player = null
                    // Restaurar orientação para retrato ao sair da tela
                    context.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
        controllerFuture.addListener(listener, MoreExecutors.directExecutor())

        onDispose {
            // Garantir que a orientação seja restaurada caso o listener não seja chamado
            context.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // Envolver o AndroidView em um Box com fundo preto
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Fundo preto para a área do vídeo
    ) {
        AndroidView(
            factory = { playerView },
            modifier = Modifier.fillMaxSize()
        )
    }
}