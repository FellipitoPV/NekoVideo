package com.example.nekovideo.components

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.example.nekovideo.MediaPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

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

    // Criar o PlayerView uma única vez
    val playerView = remember { PlayerView(context).apply { useController = true } }

    // Iniciar o serviço com a nova playlist e vincular o PlayerView
    DisposableEffect(playlist, initialIndex) {
        MediaPlaybackService.startWithPlaylist(context, playlist, initialIndex)

        val listener = Runnable {
            controllerFuture.get()?.let { controller ->
                playerView.player = controller
            }
        }
        controllerFuture.addListener(listener, MoreExecutors.directExecutor())

        onDispose {
            controllerFuture.get()?.release()
            MediaController.releaseFuture(controllerFuture)
            playerView.player = null
        }
    }

    AndroidView(
        factory = { playerView },
        modifier = Modifier.fillMaxSize()
    )
}