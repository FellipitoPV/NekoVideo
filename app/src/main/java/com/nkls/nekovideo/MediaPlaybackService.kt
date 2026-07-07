package com.nkls.nekovideo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.io.File
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import com.google.common.util.concurrent.ListenableFuture
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import kotlinx.coroutines.*
import android.net.Uri
import androidx.media3.session.SessionResult
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.HybridDataSourceFactory
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.PlaylistNavigator
import com.nkls.nekovideo.components.helpers.ContinueWatchingStore
import com.nkls.nekovideo.components.helpers.ContinueWatchingEntry
import com.nkls.nekovideo.components.helpers.ContinueWatchingTrackPreference
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    companion object {
        private const val DISABLE_PLAYBACK_ARTWORK_REFRESH_FOR_DEBUG = false

        fun startWithPlaylist(
            context: Context,
            playlist: List<String>,
            initialIndex: Int = 0,
            initialPositionMs: Long = 0L
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_PLAYLIST"
                putStringArrayListExtra("PLAYLIST", ArrayList(playlist))
                putExtra("INITIAL_INDEX", initialIndex)
                putExtra("INITIAL_POSITION_MS", initialPositionMs)
            }
            context.startService(intent)
        }

        fun refreshPlayer(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "REFRESH_PLAYER"
            }
            context.startService(intent)
        }

        fun updatePlayerWindow(context: Context, window: List<String>, currentIndexInWindow: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_WINDOW"
                putStringArrayListExtra("WINDOW", ArrayList(window))
                putExtra("CURRENT_INDEX_IN_WINDOW", currentIndexInWindow)
            }
            context.startService(intent)
        }

        fun seekToPlaylistIndex(context: Context, playlistIndex: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "SEEK_TO_PLAYLIST_INDEX"
                putExtra("PLAYLIST_INDEX", playlistIndex)
            }
            context.startService(intent)
        }

        fun removePlaylistItem(context: Context, removeIndex: Int, nextIndex: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "REMOVE_PLAYLIST_ITEM"
                putExtra("REMOVE_INDEX", removeIndex)
                putExtra("NEXT_INDEX", nextIndex)
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

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    // AudioFocus
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentPlaybackProcessingJob: Job? = null

    // Flag para evitar recursão na atualização de metadados
    private var isUpdatingMetadata = false
    private var lastAppliedArtworkKey: String? = null
    private val artworkUpdateDelayMs = 5000L
    private val rapidNavigationSettleMs = 700L

    // ✅ NOVO: Timestamp da última atualização de window para ignorar eventos assíncronos
    private var lastWindowUpdateTime = 0L
    private val WINDOW_UPDATE_COOLDOWN_MS = 500L // Ignora eventos por 500ms após atualização
    private var pendingContinueWatchingRestore: ContinueWatchingEntry? = null
    private var pendingSeekIndex: Int? = null
    private var activeSeekIndex: Int? = null
    private var lastNavigationCommandUptimeMs = 0L

    private fun createConfiguredPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Keep the custom datasource on every player recreation so locked:// URIs
        // continue working after refreshes triggered by cast disconnects.
        val dataSourceFactory = HybridDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setAudioAttributes(audioAttributes, true)
                addListener(playerListener)
            }
    }


    override fun onCreate() {
        super.onCreate()

        player = createConfiguredPlayer()

        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(mediaSessionCallback)
            .build()
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // ✅ Sempre habilitar next/previous
            val availableCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .setAvailablePlayerCommands(availableCommands)
                .build()
        }

        // ✅ INTERCEPTAR NEXT/PREVIOUS - Bloqueia comando do ExoPlayer e usa PlaylistNavigator
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT -> {
                    Log.d("MediaPlaybackService", "onPlayerCommandRequest: SEEK_TO_NEXT recebido")
                    // ✅ CENTRALIZADO: Usa PlaylistNavigator (não deixa ExoPlayer navegar)
                    PlaylistNavigator.next(this@MediaPlaybackService)
                    // Retorna RESULT_SUCCESS para "consumir" o comando e evitar que ExoPlayer faça sua própria navegação
                    return SessionResult.RESULT_SUCCESS
                }

                Player.COMMAND_SEEK_TO_PREVIOUS -> {
                    Log.d("MediaPlaybackService", "onPlayerCommandRequest: SEEK_TO_PREVIOUS recebido")
                    // ✅ CENTRALIZADO: Usa PlaylistNavigator (não deixa ExoPlayer navegar)
                    PlaylistNavigator.previous(this@MediaPlaybackService)
                    // Retorna RESULT_SUCCESS para "consumir" o comando e evitar que ExoPlayer faça sua própria navegação
                    return SessionResult.RESULT_SUCCESS
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            try {
                val intent = Intent(this@MediaPlaybackService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    this@MediaPlaybackService, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                session.setSessionActivity(pendingIntent)
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Erro ao configurar intent: ${e.message}")
            }
        }
    }

    // ✅ REMOVIDO: handleNext() e handlePrevious() - agora usa PlaylistNavigator centralizado

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e("MediaPlaybackService", "Player error: ${error.message}")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isUpdatingMetadata) {
                return
            }

            currentPlaybackProcessingJob?.cancel()

            player?.let { currentPlayer ->
                val currentPlaylistIndex = currentPlayer.currentMediaItemIndex
                if (currentPlaylistIndex != PlaylistManager.getCurrentIndex()) {
                    PlaylistManager.confirmCurrentIndex(currentPlaylistIndex)
                }

                if (activeSeekIndex == currentPlaylistIndex) {
                    activeSeekIndex = null
                }

                processPendingSeekIfNeeded(currentPlayer)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isPlaying = player?.isPlaying ?: false
            val intent = Intent("PLAYBACK_STATE_CHANGED")
            intent.putExtra("IS_PLAYING", isPlaying)
            sendBroadcast(intent)

            if (playbackState == Player.STATE_ENDED) {
                player?.let(::handleEndOfPlaylistIfNeeded)
            }

            if (playbackState == Player.STATE_READY) {
                scheduleCurrentPlaybackProcessing()
            } else {
                currentPlaybackProcessingJob?.cancel()
            }

            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_IDLE) {
                player?.let(::processPendingSeekIfNeeded)
            }

            // ✅ REMOVIDO: handleNext() não é mais necessário aqui
            // O ExoPlayer já navega automaticamente dentro da window
            // e o onMediaItemTransition sincroniza o PlaylistManager
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyPendingContinueWatchingRestore(tracks)
        }
    }

    // MediaPlaybackService.kt
    private var lastMediaItemIndex = 0 // ✅ ADICIONAR esta variável

    private fun updateNotificationIntent() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            mediaSession?.setSessionActivity(pendingIntent)
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Erro ao atualizar intent: ${e.message}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Check if any URI in a playlist uses the locked:// scheme
     */
    private fun hasLockedUris(playlist: List<String>): Boolean {
        return playlist.any { it.startsWith("locked://") }
    }

    // Keep playlist creation cheap for large queues. Artwork is refreshed only
    // for the current item after thumbnail generation completes.
    private fun createMediaItemWithMetadata(uri: String): MediaItem {
        val isLocked = uri.startsWith("locked://")
        val filePath = if (isLocked) uri.removePrefix("locked://") else uri.removePrefix("file://")
        val file = File(filePath)
        // For locked files, try to get original name from session
        val title = if (isLocked) {
            LockedPlaybackSession.getOriginalName(file.name)?.substringBeforeLast(".") ?: file.nameWithoutExtension
        } else {
            file.nameWithoutExtension
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist("NekoVideo")

        // Keep locked:// URI so HybridDataSource can detect and handle it
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    // ✅ ATUALIZADO: Carrega ou gera thumbnail
    private fun loadThumbnailWithContext(videoPath: String): Bitmap? {
        val key = videoPath.hashCode().toString()

        // 1. Tenta RAM primeiro
        val ramBitmap = OptimizedThumbnailManager.thumbnailCache.get(key)
        if (ramBitmap != null && !ramBitmap.isRecycled) {
            return ramBitmap
        }

        // 2. Busca do disco
        val diskBitmap = OptimizedThumbnailManager.loadThumbnailFromDiskSync(this, videoPath)
        if (diskBitmap != null) {
            OptimizedThumbnailManager.thumbnailCache.put(key, diskBitmap)
            return diskBitmap
        }

        // 3. Não existe - retorna null (será gerada em background)
        // Não geramos aqui para não bloquear a thread principal
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_PLAYLIST" -> {
                val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
                val initialIndex = intent.getIntExtra("INITIAL_INDEX", 0)
                val initialPositionMs = intent.getLongExtra("INITIAL_POSITION_MS", 0L)
                updatePlaylist(playlist, initialIndex, initialPositionMs)
            }
            "UPDATE_WINDOW" -> {
                val window = intent.getStringArrayListExtra("WINDOW") ?: emptyList()
                val currentIndexInWindow = intent.getIntExtra("CURRENT_INDEX_IN_WINDOW", 0)
                updateWindow(window, currentIndexInWindow)
            }
            "SEEK_TO_PLAYLIST_INDEX" -> {
                val playlistIndex = intent.getIntExtra("PLAYLIST_INDEX", 0)
                seekToPlaylistIndex(playlistIndex)
            }
            "REMOVE_PLAYLIST_ITEM" -> {
                val removeIndex = intent.getIntExtra("REMOVE_INDEX", -1)
                val nextIndex = intent.getIntExtra("NEXT_INDEX", 0)
                removePlaylistItem(removeIndex, nextIndex)
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
                pendingSeekIndex = null
                activeSeekIndex = null
                persistContinueWatchingState()
                ContinueWatchingStore.setPlaybackActive(false)
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

    private fun updatePlaylist(playlist: List<String>, initialIndex: Int, initialPositionMs: Long = 0L) {
        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition
        pendingSeekIndex = null
        activeSeekIndex = null
        ContinueWatchingStore.setPlaybackActive(playlist.isNotEmpty())
        pendingContinueWatchingRestore = buildPendingContinueWatchingRestore(
            playlist = playlist,
            initialIndex = initialIndex,
            initialPositionMs = initialPositionMs
        )

        player?.run {
            clearMediaItems()
            setMediaItems(
                playlist.map { createMediaItemWithMetadata(it) },
                initialIndex,
                initialPositionMs.coerceAtLeast(0L)
            )
            prepare()
            playWhenReady = true
        }

        PlaylistManager.syncLoadedWindow(initialIndex)

        isUpdatingMetadata = false

        updateNotificationIntent()

    }

    private fun updatePlaylistAfterDeletion(playlist: List<String>, nextIndex: Int) {
        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition
        pendingSeekIndex = null
        activeSeekIndex = null
        ContinueWatchingStore.setPlaybackActive(playlist.isNotEmpty())

        player?.run {
            if (playlist.isEmpty()) {
                pause()
                clearMediaItems()
                abandonAudioFocus()
                ContinueWatchingStore.setPlaybackActive(false)
                isUpdatingMetadata = false
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

        PlaylistManager.syncLoadedWindow(nextIndex)

        isUpdatingMetadata = false

        updateNotificationIntent()

    }

    private fun preloadThumbnailsForWindow(videoPaths: List<String>) {
        if (videoPaths.isEmpty()) return

        preloadScope.launch {
            var generatedCount = 0

            videoPaths.forEach { videoPath ->
                val isLocked = videoPath.startsWith("locked://")
                val cleanPath = if (isLocked) videoPath.removePrefix("locked://") else videoPath.removePrefix("file://")

                val thumbnail = if (isLocked) {
                    FolderLockManager.getLockedThumbnail(cleanPath)
                } else {
                    loadThumbnailWithContext(cleanPath)
                }

                if (thumbnail != null) {
                    generatedCount++
                    Log.d("MediaPlaybackService", "Thumbnail OK: ${cleanPath.substringAfterLast("/")}")
                } else {
                    Log.w("MediaPlaybackService", "Thumbnail falhou: ${cleanPath.substringAfterLast("/")}")
                }

                // Pequeno delay entre gerações para não sobrecarregar
                delay(100)
            }

            Log.d("MediaPlaybackService", "Preload concluído: $generatedCount/${videoPaths.size} thumbnails")

            // Artwork/metadata refresh temporariamente desabilitado para isolar
            // o custo de decode/atualização durante playback contínuo.
        }
    }

    private fun scheduleCurrentPlaybackProcessing() {
        currentPlaybackProcessingJob?.cancel()

        if (DISABLE_PLAYBACK_ARTWORK_REFRESH_FOR_DEBUG) {
            return
        }

        currentPlaybackProcessingJob = preloadScope.launch {
            val navigationDelayMs = when {
                activeSeekIndex != null || pendingSeekIndex != null -> rapidNavigationSettleMs
                else -> (rapidNavigationSettleMs - (SystemClock.uptimeMillis() - lastNavigationCommandUptimeMs)).coerceAtLeast(0L)
            }

            delay(maxOf(artworkUpdateDelayMs, navigationDelayMs))

            if (activeSeekIndex != null || pendingSeekIndex != null) {
                return@launch
            }

            refreshCurrentMediaItemMetadata()
        }
    }

    private data class ArtworkUpdate(
        val mediaUri: String,
        val title: String,
        val artworkData: ByteArray
    )

    private data class CurrentArtworkTarget(
        val mediaUri: String,
        val videoPath: String,
        val title: String,
        val isLocked: Boolean
    )

    /**
     * Atualiza os metadados do MediaItem atual para refletir thumbnails recém-geradas
     */
    private suspend fun refreshCurrentMediaItemMetadata() {
        if (isUpdatingMetadata) return // ✅ Evita recursão

        val artworkTarget = withContext(Dispatchers.Main) { getCurrentArtworkTarget() } ?: return
        val artworkUpdate = buildCurrentArtworkUpdate(artworkTarget) ?: return
        val artworkKey = "${artworkUpdate.mediaUri}:${artworkUpdate.artworkData.size}:${artworkUpdate.artworkData.contentHashCode()}"

        if (lastAppliedArtworkKey == artworkKey) {
            return
        }

        withContext(Dispatchers.Main) {
            player?.let { currentPlayer ->
                val currentIndex = currentPlayer.currentMediaItemIndex
                val currentItem = currentPlayer.currentMediaItem ?: return@let
                val currentUri = currentItem.localConfiguration?.uri?.toString() ?: return@let

                if (currentUri != artworkUpdate.mediaUri) {
                    return@let
                }

                isUpdatingMetadata = true

                try {
                    val newMetadata = MediaMetadata.Builder()
                        .setTitle(artworkUpdate.title)
                        .setDisplayTitle(artworkUpdate.title)
                        .setArtist("NekoVideo")
                        .setArtworkData(artworkUpdate.artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()

                    val newMediaItem = currentItem.buildUpon()
                        .setMediaMetadata(newMetadata)
                        .build()

                    currentPlayer.replaceMediaItem(currentIndex, newMediaItem)
                    lastAppliedArtworkKey = artworkKey
                    Log.d("MediaPlaybackService", "Notificação atualizada com thumbnail")
                } finally {
                    isUpdatingMetadata = false
                }
            }
        }
    }

    private fun getCurrentArtworkTarget(): CurrentArtworkTarget? {
        val currentItem = player?.currentMediaItem ?: return null
        val uri = currentItem.localConfiguration?.uri?.toString() ?: return null
        val isLocked = uri.startsWith("locked://")
        val videoPath = if (isLocked) uri.removePrefix("locked://") else uri.removePrefix("file://")
        val file = File(videoPath)
        val title = if (isLocked) {
            LockedPlaybackSession.getOriginalName(file.name)?.substringBeforeLast(".") ?: file.nameWithoutExtension
        } else {
            file.nameWithoutExtension
        }

        return CurrentArtworkTarget(
            mediaUri = uri,
            videoPath = videoPath,
            title = title,
            isLocked = isLocked
        )
    }

    private suspend fun buildCurrentArtworkUpdate(target: CurrentArtworkTarget): ArtworkUpdate? = withContext(Dispatchers.IO) {
        val thumbnail = if (target.isLocked) {
            FolderLockManager.getLockedThumbnail(target.videoPath)
        } else {
            loadThumbnailWithContext(target.videoPath)
        } ?: return@withContext null

        ArtworkUpdate(
            mediaUri = target.mediaUri,
            title = target.title,
            artworkData = bitmapToByteArray(thumbnail)
        )
    }

    private fun updateWindow(window: List<String>, currentIndexInWindow: Int) {
        updatePlaylist(window, currentIndexInWindow)
    }

    private fun seekToPlaylistIndex(playlistIndex: Int) {
        player?.let { currentPlayer ->
            if (playlistIndex !in 0 until currentPlayer.mediaItemCount) {
                Log.w("MediaPlaybackService", "seekToPlaylistIndex ignorado: índice inválido $playlistIndex/${currentPlayer.mediaItemCount}")
                return
            }

            pendingSeekIndex = playlistIndex
            lastNavigationCommandUptimeMs = SystemClock.uptimeMillis()
            processPendingSeekIfNeeded(currentPlayer)
        }
    }

    private fun processPendingSeekIfNeeded(currentPlayer: ExoPlayer) {
        val targetIndex = pendingSeekIndex ?: return

        if (activeSeekIndex != null) {
            return
        }

        if (targetIndex !in 0 until currentPlayer.mediaItemCount) {
            pendingSeekIndex = null
            Log.w("MediaPlaybackService", "processPendingSeekIfNeeded ignorado: índice inválido $targetIndex/${currentPlayer.mediaItemCount}")
            return
        }

        if (currentPlayer.currentMediaItemIndex == targetIndex) {
            pendingSeekIndex = null
            PlaylistManager.confirmCurrentIndex(targetIndex)
            scheduleCurrentPlaybackProcessing()
            return
        }

        pendingSeekIndex = null
        activeSeekIndex = targetIndex
        currentPlaybackProcessingJob?.cancel()
        currentPlayer.seekToDefaultPosition(targetIndex)
    }

    private fun handleEndOfPlaylistIfNeeded(currentPlayer: ExoPlayer) {
        if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
            return
        }

        val currentIndex = currentPlayer.currentMediaItemIndex
        val isLastItem = currentIndex >= currentPlayer.mediaItemCount - 1

        if (!isLastItem || PlaylistManager.hasNext()) {
            return
        }

        pendingSeekIndex = null
        activeSeekIndex = null
        currentPlayer.playWhenReady = false
        currentPlayer.pause()
        PlaylistManager.confirmCurrentIndex(currentIndex)
    }

    private fun removePlaylistItem(removeIndex: Int, nextIndex: Int) {
        player?.let { currentPlayer ->
            if (removeIndex !in 0 until currentPlayer.mediaItemCount) {
                Log.w("MediaPlaybackService", "removePlaylistItem ignorado: índice inválido $removeIndex/${currentPlayer.mediaItemCount}")
                return
            }

            currentPlayer.removeMediaItem(removeIndex)

            if (currentPlayer.mediaItemCount == 0) {
                pendingSeekIndex = null
                activeSeekIndex = null
                persistContinueWatchingState()
                ContinueWatchingStore.setPlaybackActive(false)
                stopSelf()
                return
            }

            val targetIndex = nextIndex.coerceIn(0, currentPlayer.mediaItemCount - 1)
            PlaylistManager.syncLoadedWindow(targetIndex)
        }
    }

    private fun refreshPlayerWithCurrentState() {
        val currentPlayer = player ?: return
        val currentSession = mediaSession ?: return

        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition
        pendingSeekIndex = null
        activeSeekIndex = null

        val currentPosition = currentPlayer.currentPosition
        val currentMediaIndex = currentPlayer.currentMediaItemIndex
        val currentRepeatMode = currentPlayer.repeatMode
        val currentPlaybackSpeed = currentPlayer.playbackParameters.speed
        val shouldPlayWhenReady = currentPlayer.playWhenReady
        val currentPlaylist = (0 until currentPlayer.mediaItemCount).map { index ->
            currentPlayer.getMediaItemAt(index).localConfiguration?.uri.toString()
        }

        currentPlayer.release()

        player = createConfiguredPlayer().apply {
            repeatMode = currentRepeatMode
        }

        currentSession.player = player!!

        player?.run {
            if (currentPlaylist.isNotEmpty()) {
                setMediaItems(
                    currentPlaylist.map { createMediaItemWithMetadata(it!!) },
                    currentMediaIndex,
                    currentPosition
                )
                setPlaybackSpeed(currentPlaybackSpeed)
                prepare()
                playWhenReady = shouldPlayWhenReady
            }
        }

        isUpdatingMetadata = false

        updateNotificationIntent()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistContinueWatchingState()
        ContinueWatchingStore.setPlaybackActive(false)
        val currentPlayer = player
        if (currentPlayer == null || !currentPlayer.isPlaying) {
            // App removido dos recentes com vídeo pausado → limpa playlist e para o serviço
            PlaylistManager.clear()
            currentPlayer?.run {
                pause()
                clearMediaItems()
                stop()
            }
            stopSelf()
            return
        }
        // Se estiver tocando, o serviço continua em background
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistContinueWatchingState()
        ContinueWatchingStore.setPlaybackActive(false)
        pendingSeekIndex = null
        activeSeekIndex = null
        // Serviço destruído com vídeo pausado (ex: notificação arrastada enquanto pausado)
        if (player?.isPlaying == false) {
            PlaylistManager.clear()
        }
        currentPlaybackProcessingJob?.cancel()
        preloadScope.cancel() // Cancela thumbnails em progresso
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

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return stream.toByteArray()
    }

    private fun persistContinueWatchingState() {
        val currentPlayer = player ?: return
        val currentItem = currentPlayer.currentMediaItem ?: return
        val currentUri = currentItem.localConfiguration?.uri?.toString() ?: return
        val currentPosition = currentPlayer.currentPosition
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: 0L
        val title = currentItem.mediaMetadata.title?.toString()
            ?: File(
                if (currentUri.startsWith("locked://")) currentUri.removePrefix("locked://")
                else currentUri.removePrefix("file://")
            ).nameWithoutExtension
        val audioTrack = extractSelectedTrackPreference(currentPlayer.currentTracks, C.TRACK_TYPE_AUDIO)
        val subtitleTrack = extractSelectedTrackPreference(currentPlayer.currentTracks, C.TRACK_TYPE_TEXT)
        val subtitlesDisabled = currentPlayer.trackSelectionParameters
            .disabledTrackTypes
            .contains(C.TRACK_TYPE_TEXT)

        ContinueWatchingStore.save(
            context = this,
            videoPath = currentUri,
            title = title,
            positionMs = currentPosition,
            durationMs = duration,
            audioTrack = audioTrack,
            subtitleTrack = subtitleTrack,
            subtitlesDisabled = subtitlesDisabled
        )
    }

    private fun buildPendingContinueWatchingRestore(
        playlist: List<String>,
        initialIndex: Int,
        initialPositionMs: Long
    ): ContinueWatchingEntry? {
        if (initialPositionMs <= 0L) return null

        val targetPath = playlist.getOrNull(initialIndex)?.let(::normalizeUriPath) ?: return null
        val entry = ContinueWatchingStore.get(this) ?: return null

        return entry.takeIf { it.videoPath == targetPath }
    }

    private fun applyPendingContinueWatchingRestore(tracks: Tracks) {
        val restore = pendingContinueWatchingRestore ?: return
        val currentPath = player?.currentMediaItem
            ?.localConfiguration
            ?.uri
            ?.toString()
            ?.let(::normalizeUriPath)
            ?: return

        if (currentPath != restore.videoPath) return

        val currentPlayer = player ?: return
        var parameters = currentPlayer.trackSelectionParameters
            .buildUpon()

        if (restore.subtitlesDisabled) {
            parameters = parameters
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else if (restore.subtitleTrack != null) {
            findMatchingTrack(tracks, C.TRACK_TYPE_TEXT, restore.subtitleTrack)?.let { (group, trackIndex) ->
                parameters = parameters
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
        }

        if (restore.audioTrack != null) {
            findMatchingTrack(tracks, C.TRACK_TYPE_AUDIO, restore.audioTrack)?.let { (group, trackIndex) ->
                parameters = parameters
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
        }

        currentPlayer.trackSelectionParameters = parameters.build()
        pendingContinueWatchingRestore = null
    }

    private fun findMatchingTrack(
        tracks: Tracks,
        trackType: Int,
        preferredTrack: ContinueWatchingTrackPreference
    ): Pair<Tracks.Group, Int>? {
        tracks.groups.forEach { group ->
            if (group.type != trackType) return@forEach

            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                if (preferredTrack.matches(format)) {
                    return group to trackIndex
                }
            }
        }

        return null
    }

    private fun extractSelectedTrackPreference(
        tracks: Tracks,
        trackType: Int
    ): ContinueWatchingTrackPreference? {
        tracks.groups.forEach { group ->
            if (group.type != trackType) return@forEach

            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) {
                    return group.getTrackFormat(trackIndex).toContinueWatchingTrackPreference()
                }
            }
        }

        return null
    }

    private fun ContinueWatchingTrackPreference.matches(format: Format): Boolean {
        val normalizedLabel = label?.trim()?.lowercase()
        val normalizedLanguage = language?.trim()?.lowercase()
        val normalizedMimeType = mimeType?.trim()?.lowercase()
        val formatLabel = format.label?.trim()?.lowercase()
        val formatLanguage = format.language?.trim()?.lowercase()
        val formatMimeType = format.sampleMimeType?.trim()?.lowercase()

        return when {
            normalizedLabel != null && normalizedLanguage != null -> {
                formatLabel == normalizedLabel && formatLanguage == normalizedLanguage
            }
            normalizedLanguage != null && normalizedMimeType != null -> {
                formatLanguage == normalizedLanguage && formatMimeType == normalizedMimeType
            }
            normalizedLanguage != null -> formatLanguage == normalizedLanguage
            normalizedLabel != null -> formatLabel == normalizedLabel
            normalizedMimeType != null -> formatMimeType == normalizedMimeType
            else -> false
        }
    }

    private fun Format.toContinueWatchingTrackPreference(): ContinueWatchingTrackPreference {
        return ContinueWatchingTrackPreference(
            label = label?.takeIf { it.isNotBlank() },
            language = language?.takeIf { it.isNotBlank() },
            mimeType = sampleMimeType?.takeIf { it.isNotBlank() }
        )
    }

    private fun normalizeUriPath(uri: String): String {
        return when {
            uri.startsWith("locked://") -> uri.removePrefix("locked://")
            uri.startsWith("file://") -> uri.removePrefix("file://")
            else -> uri
        }
    }

}
