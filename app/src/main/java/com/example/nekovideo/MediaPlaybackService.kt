package com.example.nekovideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        initializePlayer()
        setupNotificationChannel()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("MediaPlaybackService", "Player error: ${error.message}")
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    Log.d("MediaPlaybackService", "Transition to media item: ${mediaItem?.mediaId}, reason: $reason")
                    updateNotification() // Atualizar notificação ao mudar de vídeo
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d("MediaPlaybackService", "Playback state changed: $playbackState")
                    if (playbackState == Player.STATE_ENDED && hasNextMediaItem()) {
                        seekToNextMediaItem()
                    }
                    updateNotification() // Atualizar notificação ao mudar estado
                }
            })
        }
        mediaSession = MediaSession.Builder(this, player!!).build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "media_playback_channel"
            val channelName = "Media Playback"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        startForeground(1, createNotification())
    }

    private fun createNotification(): Notification {
        val channelId = "media_playback_channel"
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER"
            player?.let { player ->
                val currentPlaylist = (0 until player.mediaItemCount).map { index ->
                    player.getMediaItemAt(index).localConfiguration?.uri.toString()
                }
                putStringArrayListExtra("PLAYLIST", ArrayList(currentPlaylist))
                putExtra("INITIAL_INDEX", player.currentMediaItemIndex)
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val contentText = player?.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: "Playing video"

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_player)
            .setContentTitle("NekoVideo")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(mediaSession!!)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    // Métodos para obter informações do player
    fun getCurrentPlaylist(): List<String> {
        return player?.let { player ->
            (0 until player.mediaItemCount).map { index ->
                player.getMediaItemAt(index).localConfiguration?.uri.toString()
            }
        } ?: emptyList()
    }

    fun getCurrentIndex(): Int {
        return player?.currentMediaItemIndex ?: 0
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying ?: false
    }

    fun getCurrentMediaTitle(): String? {
        return player?.currentMediaItem?.mediaMetadata?.title?.toString()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_PLAYLIST") {
            val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
            val initialIndex = intent.getIntExtra("INITIAL_INDEX", 0)
            updatePlaylist(playlist, initialIndex)
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun updatePlaylist(playlist: List<String>, initialIndex: Int) {
        player?.run {
            clearMediaItems()
            setMediaItems(playlist.map { MediaItem.fromUri(it) }, initialIndex, 0L)
            prepare()
            playWhenReady = true
        }
        updateNotification()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        player = null
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
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
    }
}