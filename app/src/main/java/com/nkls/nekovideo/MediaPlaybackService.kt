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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    // AudioFocus
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Flag para evitar recursão na atualização de metadados
    private var isUpdatingMetadata = false

    // ✅ NOVO: Timestamp da última atualização de window para ignorar eventos assíncronos
    private var lastWindowUpdateTime = 0L
    private val WINDOW_UPDATE_COOLDOWN_MS = 500L // Ignora eventos por 500ms após atualização


    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Use HybridDataSourceFactory so ExoPlayer can handle both normal and locked URIs
        val dataSourceFactory = HybridDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            setAudioAttributes(audioAttributes, true)
            addListener(playerListener)
        }

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

        // ✅ ATUALIZADO: Sincronizar PlaylistManager quando vídeo avança automaticamente
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // ✅ Ignora transições causadas por SEEK ou atualização de metadados
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || isUpdatingMetadata) {
                Log.d("MediaPlaybackService", "onMediaItemTransition ignorado (reason=$reason, isUpdating=$isUpdatingMetadata)")
                return
            }

            // ✅ NOVO: Ignora eventos que chegam logo após uma atualização de window
            // Isso evita dessincronização causada por eventos assíncronos do ExoPlayer
            val timeSinceLastUpdate = System.currentTimeMillis() - lastWindowUpdateTime
            if (timeSinceLastUpdate < WINDOW_UPDATE_COOLDOWN_MS) {
                Log.d("MediaPlaybackService", "onMediaItemTransition ignorado (cooldown: ${timeSinceLastUpdate}ms)")
                return
            }

            player?.let { currentPlayer ->
                val currentIndexInWindow = currentPlayer.currentMediaItemIndex
                val windowSize = currentPlayer.mediaItemCount

                // ✅ SIMPLIFICADO: Quando vídeo avança AUTOMATICAMENTE (AUTO), sincronizar PlaylistManager
                // Isso acontece quando o vídeo termina e o ExoPlayer passa para o próximo na window
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    val globalIndex = PlaylistManager.getWindowStartIndex() + currentIndexInWindow
                    if (globalIndex != PlaylistManager.getCurrentIndex()) {
                        // Apenas sincroniza o índice, não chama updateWindow para evitar loops
                        PlaylistManager.jumpTo(globalIndex)
                        Log.d("MediaPlaybackService", "PlaylistManager sincronizado (AUTO): índice $globalIndex")
                    }

                    // Se está nos últimos 3 vídeos da window, atualizar preventivamente
                    if (currentIndexInWindow >= windowSize - 3 && PlaylistManager.hasNext()) {
                        val newWindow = PlaylistManager.getCurrentWindow()
                        val adjustedIndex = PlaylistManager.getCurrentIndexInWindow()
                        updateWindow(newWindow, adjustedIndex)
                        Log.d("MediaPlaybackService", "Window atualizada preventivamente")
                    }
                }

                // Gera thumbnail do vídeo atual se não existir
                mediaItem?.localConfiguration?.uri?.let { uri ->
                    val uriStr = uri.toString()
                    if (!uriStr.startsWith("locked://")) {
                        preloadScope.launch {
                            val videoPath = uri.path ?: return@launch
                            OptimizedThumbnailManager.getOrGenerateThumbnailSync(
                                this@MediaPlaybackService,
                                videoPath
                            )
                        }
                    }
                    // Locked thumbnails are already pre-generated in .neko_thumbs, no need to generate
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isPlaying = player?.isPlaying ?: false
            val intent = Intent("PLAYBACK_STATE_CHANGED")
            intent.putExtra("IS_PLAYING", isPlaying)
            sendBroadcast(intent)

            // ✅ REMOVIDO: handleNext() não é mais necessário aqui
            // O ExoPlayer já navega automaticamente dentro da window
            // e o onMediaItemTransition sincroniza o PlaylistManager
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
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

    // ✅ SIMPLIFICADO: Apenas usa thumbnail do cache, não gera
    // MediaPlaybackService.kt
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

        // ✅ CORRIGIDO: Para locked, busca thumbnail da .neko_thumbs; para normal, busca do cache
        val cachedThumbnail = if (isLocked) {
            FolderLockManager.getLockedThumbnail(filePath)
        } else {
            loadThumbnailWithContext(filePath)
        }
        if (cachedThumbnail != null) {
            val artworkData = bitmapToByteArray(cachedThumbnail)
            metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

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
        val diskBitmap = OptimizedThumbnailManager.loadThumbnailFromDiskSync(videoPath)
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
                updatePlaylist(playlist, initialIndex)
            }
            "UPDATE_WINDOW" -> {
                val window = intent.getStringArrayListExtra("WINDOW") ?: emptyList()
                val currentIndexInWindow = intent.getIntExtra("CURRENT_INDEX_IN_WINDOW", 0)
                updateWindow(window, currentIndexInWindow)
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

    private fun updatePlaylist(playlist: List<String>, initialIndex: Int) {
        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition

        val isLocked = hasLockedUris(playlist)

        player?.run {
            clearMediaItems()
            setMediaItems(
                playlist.map { createMediaItemWithMetadata(it) },
                initialIndex,
                0L  // ✅ Sempre começar do início quando uma nova playlist é iniciada
            )
            prepare()
            playWhenReady = true
        }

        isUpdatingMetadata = false

        updateNotificationIntent()

        // ✅ Gera/carrega thumbnails em background (normal + locked)
        if (playlist.isNotEmpty()) {
            preloadThumbnailsForWindow(playlist)
        }
    }

    private fun updatePlaylistAfterDeletion(playlist: List<String>, nextIndex: Int) {
        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition

        player?.run {
            if (playlist.isEmpty()) {
                pause()
                clearMediaItems()
                abandonAudioFocus()
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

        isUpdatingMetadata = false

        updateNotificationIntent()
    }

    private fun preloadThumbnailsForWindow(videoPaths: List<String>) {
        preloadScope.launch {
            var generatedCount = 0

            videoPaths.forEach { videoPath ->
                val isLocked = videoPath.startsWith("locked://")
                val cleanPath = if (isLocked) videoPath.removePrefix("locked://") else videoPath.removePrefix("file://")

                val thumbnail = if (isLocked) {
                    // Locked thumbnails are already stored in .neko_thumbs, just load them
                    FolderLockManager.getLockedThumbnail(cleanPath)
                } else {
                    // Usa a função síncrona que verifica cache e gera se necessário
                    OptimizedThumbnailManager.getOrGenerateThumbnailSync(
                        this@MediaPlaybackService,
                        cleanPath
                    )
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

            // Atualiza a notificação com as novas thumbnails
            if (generatedCount > 0) {
                withContext(Dispatchers.Main) {
                    refreshCurrentMediaItemMetadata()
                }
            }
        }
    }

    /**
     * Atualiza os metadados do MediaItem atual para refletir thumbnails recém-geradas
     */
    private fun refreshCurrentMediaItemMetadata() {
        if (isUpdatingMetadata) return // ✅ Evita recursão

        player?.let { currentPlayer ->
            isUpdatingMetadata = true // ✅ Marca que está atualizando

            try {
                val currentIndex = currentPlayer.currentMediaItemIndex
                val currentItem = currentPlayer.currentMediaItem ?: return

                val uri = currentItem.localConfiguration?.uri?.toString() ?: return
                val isLocked = uri.startsWith("locked://")
                val videoPath = if (isLocked) uri.removePrefix("locked://") else uri.removePrefix("file://")

                // Busca thumbnail atualizada - locked usa .neko_thumbs, normal usa cache padrão
                val thumbnail = if (isLocked) {
                    FolderLockManager.getLockedThumbnail(videoPath)
                } else {
                    loadThumbnailWithContext(videoPath)
                }

                if (thumbnail != null) {
                    val file = File(videoPath)
                    val title = if (isLocked) {
                        LockedPlaybackSession.getOriginalName(file.name)?.substringBeforeLast(".") ?: file.nameWithoutExtension
                    } else {
                        file.nameWithoutExtension
                    }

                    val newMetadata = MediaMetadata.Builder()
                        .setTitle(title)
                        .setDisplayTitle(title)
                        .setArtist("NekoVideo")
                        .setArtworkData(bitmapToByteArray(thumbnail), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()

                    val newMediaItem = currentItem.buildUpon()
                        .setMediaMetadata(newMetadata)
                        .build()

                    // ✅ Apenas substitui o item - NÃO faz seekTo (já mantém a posição)
                    currentPlayer.replaceMediaItem(currentIndex, newMediaItem)

                    Log.d("MediaPlaybackService", "Notificação atualizada com thumbnail")
                }
            } finally {
                isUpdatingMetadata = false // ✅ Libera a flag
            }
        }
    }

    private fun updateWindow(window: List<String>, currentIndexInWindow: Int) {
        isUpdatingMetadata = true // ✅ Evita que onMediaItemTransition processe durante update
        lastWindowUpdateTime = System.currentTimeMillis() // ✅ Marca timestamp para cooldown

        Log.d("MediaPlaybackService", "updateWindow: windowSize=${window.size}, index=$currentIndexInWindow")

        player?.run {
            if (window.isEmpty()) {
                pause()
                clearMediaItems()
                abandonAudioFocus()
                isUpdatingMetadata = false
                return
            }

            val wasPlaying = isPlaying

            clearMediaItems()
            setMediaItems(
                window.map { createMediaItemWithMetadata(it) },
                currentIndexInWindow,
                0L
            )
            prepare()

            if (wasPlaying) {
                playWhenReady = true
            }
        }

        isUpdatingMetadata = false // ✅ Libera após configurar

        updateNotificationIntent()

        // ✅ Gera thumbnails em background
        preloadThumbnailsForWindow(window)
    }

    private fun refreshPlayerWithCurrentState() {
        val currentPlayer = player ?: return
        val currentSession = mediaSession ?: return

        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition

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

        isUpdatingMetadata = false

        updateNotificationIntent()
    }

    override fun onDestroy() {
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

        // ✅ NOVO: Atualizar apenas a window
        fun updatePlayerWindow(context: Context, window: List<String>, currentIndexInWindow: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_WINDOW"
                putStringArrayListExtra("WINDOW", ArrayList(window))
                putExtra("CURRENT_INDEX_IN_WINDOW", currentIndexInWindow)
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