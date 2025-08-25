package com.nkls.nekovideo.components.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nkls.nekovideo.MediaPlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton para gerenciar MediaController e evitar ServiceConnection leaks
 */
object MediaControllerManager {
    private var _mediaController = MutableStateFlow<MediaController?>(null)
    val mediaController: StateFlow<MediaController?> = _mediaController.asStateFlow()

    private var isConnecting = false
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    fun connect(context: Context) {
        if (_mediaController.value != null || isConnecting) {
            Log.d("MediaControllerManager", "Already connected or connecting")
            return
        }

        isConnecting = true

        try {
            val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

            controllerFuture?.addListener({
                try {
                    val controller = controllerFuture?.get()
                    _mediaController.value = controller
                    isConnecting = false
                    Log.d("MediaControllerManager", "MediaController connected successfully")
                } catch (e: Exception) {
                    Log.e("MediaControllerManager", "Failed to connect MediaController", e)
                    isConnecting = false
                }
            }, MoreExecutors.directExecutor())

        } catch (e: Exception) {
            Log.e("MediaControllerManager", "Error creating MediaController", e)
            isConnecting = false
        }
    }

    fun disconnect() {
        Log.d("MediaControllerManager", "Disconnecting MediaController")

        // Cancel future se ainda estiver pendente
        controllerFuture?.cancel(true)
        controllerFuture = null

        // Release controller se existir
        _mediaController.value?.let { controller ->
            try {
                controller.release()
                Log.d("MediaControllerManager", "MediaController released successfully")
            } catch (e: Exception) {
                Log.e("MediaControllerManager", "Error releasing MediaController", e)
            }
        }

        _mediaController.value = null
        isConnecting = false
    }

    fun isConnected(): Boolean = _mediaController.value != null

    fun getCurrentController(): MediaController? = _mediaController.value
}