package com.nkls.nekovideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.helpers.FolderLockManager

/**
 * Foreground service that publishes a media-style notification for DLNA cast,
 * enabling playback control from the notification shade, lock screen, and
 * Bluetooth devices (headsets, car audio, etc.).
 */
class DLNACastService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private lateinit var castManager: DLNACastManager

    override fun onCreate() {
        super.onCreate()
        castManager = DLNACastManager.getInstance(this)
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "DLNACast").apply {
            setCallback(sessionCallback)
            setPlaybackState(buildPlaybackState())
            setMetadata(buildMediaMetadata())
            isActive = true
        }

        castManager.onServiceStateChanged = { updateNotification() }

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (castManager.isPlaying) castManager.pause() else castManager.play()
            ACTION_NEXT      -> castManager.next()
            ACTION_PREVIOUS  -> castManager.previous()
            ACTION_STOP_CAST -> {
                castManager.stopCasting()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay()           { castManager.play() }
        override fun onPause()          { castManager.pause() }
        override fun onSkipToNext()     { castManager.next() }
        override fun onSkipToPrevious() { castManager.previous() }
        override fun onSeekTo(pos: Long) { castManager.seekTo(pos) }
        override fun onStop() {
            castManager.stopCasting()
            stopSelf()
        }
    }

    private fun updateNotification() {
        mediaSession?.setPlaybackState(buildPlaybackState())
        mediaSession?.setMetadata(buildMediaMetadata())
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // ── Playback state (position + actions) ──────────────────────────────────

    private fun buildPlaybackState(): PlaybackStateCompat {
        val state = if (castManager.isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
        return PlaybackStateCompat.Builder()
            .setState(state, castManager.currentPositionMs, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
    }

    // ── Media metadata (title + duration + thumbnail) ─────────────────────────

    private fun buildMediaMetadata(): MediaMetadataCompat {
        val title = castManager.currentTitle.ifEmpty { getString(R.string.cast_notification_idle) }
        val thumbnail = loadThumbnail()
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, castManager.connectedDeviceName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, castManager.durationMs)
            .apply { if (thumbnail != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, thumbnail) }
            .build()
    }

    /** Returns thumbnail from RAM cache → disk cache → null (avoids generation at this stage). */
    private fun loadThumbnail(): Bitmap? {
        val path = castManager.currentVideoPath.ifEmpty { return null }
        return if (path.startsWith("locked://")) {
            FolderLockManager.getLockedThumbnail(path.removePrefix("locked://"))
        } else {
            val cleanPath = path.removePrefix("file://")
            val key = cleanPath.hashCode().toString()
            OptimizedThumbnailManager.thumbnailCache.get(key)
                ?: OptimizedThumbnailManager.loadThumbnailFromDiskSync(cleanPath)
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val sessionToken = mediaSession!!.sessionToken
        val thumbnail = loadThumbnail()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent      = pendingServiceIntent(1, ACTION_PREVIOUS)
        val playPauseIntent = pendingServiceIntent(2, ACTION_PLAY_PAUSE)
        val nextIntent      = pendingServiceIntent(3, ACTION_NEXT)
        val stopIntent      = pendingServiceIntent(4, ACTION_STOP_CAST)

        val playPauseIcon  = if (castManager.isPlaying) android.R.drawable.ic_media_pause
                             else android.R.drawable.ic_media_play
        val playPauseLabel = if (castManager.isPlaying) getString(R.string.pause)
                             else getString(R.string.play)

        val title = castManager.currentTitle.ifEmpty { getString(R.string.cast_notification_idle) }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.cast_notification_casting_to, castManager.connectedDeviceName))
            .setSmallIcon(R.drawable.ic_stat_player)
            .setLargeIcon(thumbnail)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.previous), prevIntent)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, getString(R.string.next), nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cast_disconnect_confirm), stopIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()
    }

    private fun pendingServiceIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, DLNACastService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.cast_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        castManager.onServiceStateChanged = null
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "dlna_cast_channel"
        const val ACTION_PLAY_PAUSE = "com.nkls.nekovideo.CAST_PLAY_PAUSE"
        const val ACTION_NEXT       = "com.nkls.nekovideo.CAST_NEXT"
        const val ACTION_PREVIOUS   = "com.nkls.nekovideo.CAST_PREVIOUS"
        const val ACTION_STOP_CAST  = "com.nkls.nekovideo.CAST_STOP"

        fun start(context: Context) {
            context.startService(Intent(context, DLNACastService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DLNACastService::class.java))
        }
    }
}
