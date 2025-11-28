package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.nkls.nekovideo.MediaPlaybackService
import java.io.File

class CastManager(private val context: Context) {

    private val castContext: CastContext by lazy { CastContext.getSharedInstance(context) }
    private var videoServer: LocalVideoServer? = null
    private var connectionListener: ((Boolean) -> Unit)? = null

    init {
        setupSessionListener()
    }

    private fun setupSessionListener() {
        castContext.sessionManager.addSessionManagerListener(
            object : SessionManagerListener<Session> {
                override fun onSessionStarted(session: Session, sessionId: String) {
                    connectionListener?.invoke(true)
                }

                override fun onSessionEnded(session: Session, error: Int) {
                    connectionListener?.invoke(false)
                    stopServer()

                    // Parar serviço completamente
                    MediaPlaybackService.stopService(context)

                    // Delay para garantir que parou
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Iniciar de novo - isso força ExoPlayer a redetectar bluetooth
                        MediaPlaybackService.refreshPlayer(context)
                    }, 300)
                }

                override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
                    connectionListener?.invoke(true)
                }

                override fun onSessionSuspended(session: Session, reason: Int) {}
                override fun onSessionStarting(session: Session) {}
                override fun onSessionEnding(session: Session) {}
                override fun onSessionResuming(session: Session, sessionId: String) {}
                override fun onSessionResumeFailed(session: Session, error: Int) {}
                override fun onSessionStartFailed(session: Session, error: Int) {}
            }
        )
    }

    fun setConnectionStatusListener(listener: (Boolean) -> Unit) {
        connectionListener = listener
    }

    fun castVideo(videoPath: String, videoTitle: String) {
        val session = castContext.sessionManager.currentCastSession ?: return

        try {
            // Iniciar servidor se necessário
            if (videoServer == null) {
                videoServer = LocalVideoServer(context, 8080)
                videoServer?.start()
            }

            videoServer?.addVideo(videoPath)
            val videoUrl = videoServer?.getVideoUrl() ?: return

            // Criar metadata
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, videoTitle)
                putString(MediaMetadata.KEY_SUBTITLE, "Local Video")
            }

            // Criar MediaInfo
            val mediaInfo = MediaInfo.Builder(videoUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("video/mp4")
                .setMetadata(metadata)
                .build()

            // Carregar no Cast
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(0)
                .build()

            session.remoteMediaClient?.load(request)


        } catch (e: Exception) {
            Log.e("CastManager", "Erro ao fazer cast", e)
        }
    }

    fun stopCasting() {
        castContext.sessionManager.endCurrentSession(true)
    }

    fun castPlaylist(
        videosPaths: List<String>,
        videosTitles: List<String>,
        startIndex: Int = 0
    ) {
        val session = castContext.sessionManager.currentCastSession ?: return

        try {
            // Iniciar servidor
            if (videoServer == null) {
                videoServer = LocalVideoServer(context, 8080)
                videoServer?.start()
            }

            // Limpar vídeos anteriores e adicionar novos
            videoServer?.clearVideos()
            videosPaths.forEach { path ->
                videoServer?.addVideo(path) // ✅ Usar addVideo ao invés de setVideoPath
            }

            // Criar queue de vídeos
            val queueItems = videosPaths.mapIndexed { index, videoPath ->
                val videoUrl = getVideoUrl(videoPath)
                val title = videosTitles.getOrNull(index) ?: File(videoPath).nameWithoutExtension

                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, title)
                    putString(MediaMetadata.KEY_SUBTITLE, "Video ${index + 1} of ${videosPaths.size}")
                }

                val mediaInfo = MediaInfo.Builder(videoUrl)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType("video/mp4")
                    .setMetadata(metadata)
                    .build()

                MediaQueueItem.Builder(mediaInfo)
                    .setAutoplay(true)
                    .setPreloadTime(3.0)
                    .build()
            }

            // Carregar queue
            session.remoteMediaClient?.queueLoad(
                queueItems.toTypedArray(),
                startIndex,
                MediaStatus.REPEAT_MODE_REPEAT_OFF,
                null
            )

            Log.d("CastManager", "Playlist carregada: ${videosPaths.size} vídeos")

        } catch (e: Exception) {
            Log.e("CastManager", "Erro ao fazer cast da playlist", e)
        }
    }

    private fun getVideoUrl(videoPath: String): String {
        val videoName = File(videoPath).name
        val ip = videoServer?.getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:8080/video/$videoName"
    }

    private fun stopServer() {
        videoServer?.stop()
        videoServer = null
    }

    fun disconnect() {
        stopCasting()
        stopServer()
    }
}