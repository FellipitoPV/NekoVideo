package com.nkls.nekovideo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.File
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import android.media.MediaMetadataRetriever
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import kotlinx.coroutines.launch
import android.net.Uri

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentNotificationThumbnail: Bitmap? = null

    // AudioFocus (mantém o código existente)
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            addListener(playerListener)
        }

        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(mediaSessionCallback)
            .build()

    }

    // NOVO: Callback da MediaSession seguindo documentação
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        // NOVO: Método para configurar intent personalizada da notificação
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)

            // SOLUÇÃO: Configurar intent diretamente na sessão
            try {
                val intent = Intent(this@MediaPlaybackService, MainActivity::class.java).apply {
                    action = "OPEN_PLAYER"
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

                    player?.let { player ->
                        val currentPlaylist = (0 until player.mediaItemCount).map { index ->
                            player.getMediaItemAt(index).localConfiguration?.uri.toString()
                        }
                        putStringArrayListExtra("PLAYLIST", ArrayList(currentPlaylist))
                        putExtra("INITIAL_INDEX", player.currentMediaItemIndex)
                        putExtra("AUTO_OPEN_PLAYER", true)
                    }
                }

                val pendingIntent = PendingIntent.getActivity(
                    this@MediaPlaybackService,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // CRÍTICO: Configurar o sessionActivity para a notificação
                session.setSessionActivity(pendingIntent)


            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "❌ Erro ao configurar intent: ${e.message}")
            }
        }
    }

    // Player listener (mantém lógica existente)
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e("MediaPlaybackService", "Player error: ${error.message}")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {

            // ✅ ADICIONA: Gera thumbnail do vídeo atual
            generateCurrentVideoThumbnail()

            // Auto-play quando usuário pula/volta pela notificação
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                    player?.play()
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    player?.play()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {

            // Broadcast do estado
            val isPlaying = player?.isPlaying ?: false
            val intent = Intent("PLAYBACK_STATE_CHANGED")
            intent.putExtra("IS_PLAYING", isPlaying)
            sendBroadcast(intent)

            if (playbackState == Player.STATE_ENDED && player?.hasNextMediaItem() == true) {
                player?.seekToNextMediaItem()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
//            if (isPlaying) {
//                if (!hasAudioFocus) {
//                    if (!requestAudioFocus()) {
//                        player?.pause()
//                        return
//                    }
//                }
//            }
            super.onIsPlayingChanged(isPlaying)
        }
    }


    // NOVA função para atualizar intent da notificação
    private fun updateNotificationIntent() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = "OPEN_PLAYER"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

                player?.let { player ->
                    val currentPlaylist = (0 until player.mediaItemCount).map { index ->
                        player.getMediaItemAt(index).localConfiguration?.uri.toString()
                    }
                    putStringArrayListExtra("PLAYLIST", ArrayList(currentPlaylist))
                    putExtra("INITIAL_INDEX", player.currentMediaItemIndex)
                    putExtra("AUTO_OPEN_PLAYER", true)
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                System.currentTimeMillis().toInt(), // RequestCode único
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Atualizar sessionActivity
            mediaSession?.setSessionActivity(pendingIntent)


        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "❌ Erro ao atualizar intent: ${e.message}")
        }
    }

    // OFICIAL: Como na documentação
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    // Função para criar MediaItem com metadata (mantém existente)
    private fun createMediaItemWithMetadata(uri: String, generateThumbnail: Boolean = false): MediaItem {
        val file = File(uri.removePrefix("file://"))
        val title = file.nameWithoutExtension
        val videoPath = uri.removePrefix("file://")

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist("NekoVideo")

        // ✅ SÓ adiciona artwork se já tiver em cache
        val cachedThumbnail = OptimizedThumbnailManager.getCachedThumbnail(videoPath)
        if (cachedThumbnail != null) {
            val artworkData = bitmapToByteArray(cachedThumbnail)
            metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        // ✅ REMOVIDO: generateThumbnailInBackground daqui

        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    // ✅ NOVA: Gera thumbnail apenas do vídeo que está tocando
    private fun generateCurrentVideoThumbnail() {
        player?.let { player ->
            val currentItem = player.currentMediaItem ?: return
            val uri = currentItem.localConfiguration?.uri.toString()
            val videoPath = uri.removePrefix("file://")

            // Verifica se já tem em cache
            if (OptimizedThumbnailManager.getCachedThumbnail(videoPath) != null) {
                return
            }

            serviceScope.launch(Dispatchers.IO) {
                try {
                    OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                        context = this@MediaPlaybackService,
                        videoUri = Uri.parse(uri),
                        videoPath = videoPath,
                        imageLoader = null,
                        delayMs = 0L,
                        onMetadataLoaded = { metadata ->
                            metadata.thumbnail?.let { bitmap ->
                                updateMediaItemArtwork(uri, bitmap)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e("MediaPlaybackService", "Erro ao gerar thumbnail: ${e.message}")
                }
            }
        }
    }

    // onStartCommand (mantém lógica existente)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_PLAYLIST" -> {
                val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
                val initialIndex = intent.getIntExtra("INITIAL_INDEX", 0)
                updatePlaylist(playlist, initialIndex)
            }
            "REFRESH_PLAYER" -> {
                refreshPlayerWithCurrentState()
            }
            "UPDATE_PLAYLIST_AFTER_DELETION" -> {
                val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
                val nextIndex = intent.getIntExtra("NEXT_INDEX", 0)
                updatePlaylistAfterDeletion(playlist, nextIndex)
            }
            "RESUME_LOCAL_PLAYBACK" -> {
                resumeLocalPlayback()
            }
            "STOP_SERVICE" -> {
                player?.run {
                    pause()
                    clearMediaItems()
                    stop()
                }

                val intent = Intent("PLAYER_CLOSED")
                sendBroadcast(intent)

                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // Funções de playlist (mantém)
    private fun updatePlaylist(playlist: List<String>, initialIndex: Int) {
        player?.run {
            clearMediaItems()
            setMediaItems(
                playlist.map { createMediaItemWithMetadata(it) }, // ✅ Sem generateThumbnail
                initialIndex,
                0L
            )
            prepare()
            playWhenReady = true

            // ✅ ADICIONA: Gera thumb do primeiro vídeo
            generateCurrentVideoThumbnail()
        }

        updateNotificationIntent()
    }

    private fun updatePlaylistAfterDeletion(playlist: List<String>, nextIndex: Int) {
        player?.run {
            if (playlist.isEmpty()) {
                pause()
                clearMediaItems()
                abandonAudioFocus()
                return
            }

            clearMediaItems()
            setMediaItems(
                playlist.map { createMediaItemWithMetadata(it) },
                nextIndex,
                0L
            )
            prepare()
            playWhenReady = true
        }

        // Atualizar intent após deleção
        updateNotificationIntent()
    }

    private fun refreshPlayerWithCurrentState() {
        val currentPlayer = player ?: return
        val currentSession = mediaSession ?: return

        val currentPosition = currentPlayer.currentPosition
        val currentMediaIndex = currentPlayer.currentMediaItemIndex
        val currentPlaylist = (0 until currentPlayer.mediaItemCount).map { index ->
            currentPlayer.getMediaItemAt(index).localConfiguration?.uri.toString()
        }

        currentPlayer.release()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setAudioAttributes(audioAttributes, true)
            addListener(playerListener)
        }

        currentSession.player = player!!

        player?.run {
            if (currentPlaylist.isNotEmpty()) {
                setMediaItems(
                    currentPlaylist.map { createMediaItemWithMetadata(it!!) },
                    currentMediaIndex,
                    currentPosition
                )
                prepare()
                playWhenReady = true
            }
        }

        updateNotificationIntent()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        mediaSession?.run {
            player?.release()
            release()
        }
        player = null
        mediaSession = null
        super.onDestroy()
    }

    private fun resumeLocalPlayback() {
        player?.let {
            if (!it.isPlaying) {
                it.play()
            }
        }
    }

    // AudioFocus functions (mantém código existente)
    private fun setupAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                setOnAudioFocusChangeListener(audioFocusChangeListener)
                setAcceptsDelayedFocusGain(true)
            }.build()
        }
    }


    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            hasAudioFocus = false
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                player?.let {
                    it.volume = 1.0f
                    if (!it.isPlaying) {
                        it.play()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.volume = 0.2f
            }
        }
    }

    private fun generateThumbnailInBackground(uri: String, videoPath: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                OptimizedThumbnailManager.loadVideoMetadataWithDelay(
                    context = this@MediaPlaybackService,
                    videoUri = Uri.parse(uri),
                    videoPath = videoPath,
                    imageLoader = null,
                    delayMs = 0L, // Sem delay, gera imediatamente
                    onMetadataLoaded = { metadata ->
                        metadata.thumbnail?.let { bitmap ->
                            // Atualiza o MediaItem com a thumbnail gerada
                            updateMediaItemArtwork(uri, bitmap)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Erro ao gerar thumbnail: ${e.message}")
            }
        }
    }

    private fun updateMediaItemArtwork(uri: String, bitmap: Bitmap) {
        player?.let { player ->
            val artworkData = bitmapToByteArray(bitmap)

            for (i in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(i)
                if (item.localConfiguration?.uri.toString() == uri) {
                    val updatedMetadata = item.mediaMetadata.buildUpon()
                        .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()

                    val updatedItem = item.buildUpon()
                        .setMediaMetadata(updatedMetadata)
                        .build()

                    player.replaceMediaItem(i, updatedItem)
                    break
                }
            }
        }
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return stream.toByteArray()
    }

    companion object {
        fun startWithPlaylist(context: Context, playlist: List<String>, initialIndex: Int = 0) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_PLAYLIST"
                putStringArrayListExtra("PLAYLIST", ArrayList(playlist))
                putExtra("INITIAL_INDEX", initialIndex)
            }
            context.startService(intent)
        }

        fun refreshPlayer(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "REFRESH_PLAYER"
            }
            context.startService(intent)
        }

        fun resumeLocalPlayback(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "RESUME_LOCAL_PLAYBACK"
            }
            context.startService(intent)
        }

        fun updatePlaylistAfterDeletion(context: Context, playlist: List<String>, nextIndex: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_PLAYLIST_AFTER_DELETION"
                putStringArrayListExtra("PLAYLIST", ArrayList(playlist))
                putExtra("NEXT_INDEX", nextIndex)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "STOP_SERVICE"
            }
            context.startService(intent)
        }
    }
}