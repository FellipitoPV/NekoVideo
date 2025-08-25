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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.File

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
        Log.d("MediaPlaybackService", "🚀 onCreate - Media3 oficial")

        setupAudioManager()

        // OFICIAL: Criar player como na documentação
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(playerListener)
        }

        // OFICIAL: Criar MediaSession como na documentação
        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(mediaSessionCallback)
            .build()

        Log.d("MediaPlaybackService", "✅ MediaSession criado - notificação automática do Media3")
    }

    // NOVO: Callback da MediaSession seguindo documentação
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            Log.d("MediaPlaybackService", "🔗 Controller conectado: ${controller.packageName}")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        // NOVO: Método para configurar intent personalizada da notificação
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            Log.d("MediaPlaybackService", "📱 Configurando intent da notificação")

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

                Log.d("MediaPlaybackService", "✅ SessionActivity configurado para notificação")

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
            Log.d("MediaPlaybackService", "Transition to media item: ${mediaItem?.mediaId}, reason: $reason")

            // IMPORTANTE: Atualizar intent da notificação quando muda vídeo
            updateNotificationIntent()

            // Auto-play quando usuário pula/volta pela notificação
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                    Log.d("MediaPlaybackService", "Usuário pulou vídeo - dando play automaticamente")
                    player?.play()
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    Log.d("MediaPlaybackService", "Vídeo repetindo - dando play")
                    player?.play()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d("MediaPlaybackService", "Playback state changed: $playbackState")

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
            if (isPlaying) {
                if (!hasAudioFocus) {
                    if (!requestAudioFocus()) {
                        player?.pause()
                        return
                    }
                }
            }
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

            Log.d("MediaPlaybackService", "🔄 Intent da notificação atualizado")

        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "❌ Erro ao atualizar intent: ${e.message}")
        }
    }

    // OFICIAL: Como na documentação
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Log.d("MediaPlaybackService", "🎮 onGetSession chamado para: ${controllerInfo.packageName}")
        return mediaSession
    }

    // Função para criar MediaItem com metadata (mantém existente)
    private fun createMediaItemWithMetadata(uri: String): MediaItem {
        val file = File(uri.removePrefix("file://"))
        val title = file.nameWithoutExtension

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist("NekoVideo")
            .build()

        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
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
                Log.d("MediaPlaybackService", "Received REFRESH_PLAYER action")
                refreshPlayerWithCurrentState()
            }
            "UPDATE_PLAYLIST_AFTER_DELETION" -> {
                val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
                val nextIndex = intent.getIntExtra("NEXT_INDEX", 0)
                updatePlaylistAfterDeletion(playlist, nextIndex)
            }
            "STOP_SERVICE" -> {
                Log.d("MediaPlaybackService", "Stopping service and clearing everything")
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
        if (!requestAudioFocus()) {
            Log.w("MediaPlaybackService", "Não foi possível obter AudioFocus")
            return
        }

        player?.run {
            clearMediaItems()
            setMediaItems(
                playlist.map { createMediaItemWithMetadata(it) },
                initialIndex,
                0L
            )
            prepare()
            playWhenReady = true
        }

        // IMPORTANTE: Atualizar intent após carregar playlist
        updateNotificationIntent()

        Log.d("MediaPlaybackService", "✅ Playlist atualizada com notificação")
    }

    private fun updatePlaylistAfterDeletion(playlist: List<String>, nextIndex: Int) {
        player?.run {
            if (playlist.isEmpty()) {
                Log.d("MediaPlaybackService", "Playlist vazia após deleção - parando player")
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
        val isCurrentlyPlaying = currentPlayer.isPlaying
        val currentPlaylist = (0 until currentPlayer.mediaItemCount).map { index ->
            currentPlayer.getMediaItemAt(index).localConfiguration?.uri.toString()
        }

        currentPlayer.release()

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
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

        // Atualizar intent após refresh
        updateNotificationIntent()
    }

    override fun onDestroy() {
        Log.d("MediaPlaybackService", "🔚 onDestroy")
        abandonAudioFocus()
        mediaSession?.run {
            player?.release()
            release()
        }
        player = null
        mediaSession = null
        super.onDestroy()
    }

    // AudioFocus functions (mantém código existente)
    private fun setupAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
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