package com.example.nekovideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
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
        player = ExoPlayer.Builder(this).build()
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
        startForeground(1, createNotification())
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

    private fun createNotification(): Notification {
        val channelId = "media_playback_channel"
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_player) // Substitua pelo ícone desejado
            .setContentTitle("NekoVideo")
            .setContentText("Playing video")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(mediaSession!!)
                    .setShowActionsInCompactView(0, 1, 2) // Play/pause, próximo, anterior
            )
            .build()
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