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

            // Track display names for URL generation (locked videos use original names)
            val displayNames = mutableListOf<String>()

            videosPaths.forEach { path ->
                if (path.startsWith("locked://")) {
                    val filePath = path.removePrefix("locked://")
                    val xorKey = LockedPlaybackSession.getXorKeyForFile(filePath)
                    val obfuscatedName = File(filePath).name
                    val originalName = LockedPlaybackSession.getOriginalName(obfuscatedName)
                        ?: obfuscatedName

                    if (xorKey != null) {
                        videoServer?.addLockedVideo(filePath, xorKey, originalName)
                    } else {
                        videoServer?.addVideo(filePath)
                    }
                    displayNames.add(originalName)
                } else {
                    val cleanPath = path.removePrefix("file://")
                    videoServer?.addVideo(cleanPath)
                    displayNames.add(File(cleanPath).name)
                }
            }

            // Criar queue de vídeos
            val queueItems = displayNames.mapIndexed { index, displayName ->
                val videoUrl = getVideoUrlByName(displayName)
                val title = videosTitles.getOrNull(index)
                    ?: displayName.substringBeforeLast(".")

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

            Log.d("CastManager", "Playlist carregada: ${videosPaths.size} vídeos (${displayNames.count { LockedPlaybackSession.getOriginalName(File(it).nameWithoutExtension) != null }} locked)")

        } catch (e: Exception) {
            Log.e("CastManager", "Erro ao fazer cast da playlist", e)
        }
    }

    private fun getVideoUrl(videoPath: String): String {
        val videoName = File(videoPath).name
        return getVideoUrlByName(videoName)
    }

    private fun getVideoUrlByName(videoName: String): String {
        val ip = videoServer?.getLocalIpAddress() ?: "127.0.0.1"
        val encoded = java.net.URLEncoder.encode(videoName, "UTF-8").replace("+", "%20")
        return "http://$ip:8080/video/$encoded"
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
