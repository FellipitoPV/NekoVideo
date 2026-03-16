package com.nkls.nekovideo.components.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.text.Layout
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.google.common.util.concurrent.MoreExecutors
import com.nkls.nekovideo.MainActivity
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.components.helpers.CastManager
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.PlaylistNavigator
import com.nkls.nekovideo.components.player.PlayerUtils.findActivity
import com.nkls.nekovideo.components.settings.SettingsManager
import com.nkls.nekovideo.components.FixVideoMetadataDialog
import com.nkls.nekovideo.components.helpers.VideoRemuxer
import com.nkls.nekovideo.services.FolderVideoScanner
import android.widget.Toast
import com.nkls.nekovideo.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("OpaqueUnitKey")
@Composable
fun VideoPlayerOverlay(
    isVisible: Boolean,
    canControlRotation: Boolean,
    onDismiss: () -> Unit,
    onVideoDeleted: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = lifecycleOwner as? ComponentActivity
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var hasRefreshed by remember { mutableStateOf(false) }
    var overlayActuallyVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentVideoPath by remember { mutableStateOf("") }
    var isCasting by remember { mutableStateOf(false) }
    var connectedDeviceName by remember { mutableStateOf("") }
    val castManager = remember { CastManager(context) }
    var showCastDevicePicker by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<DLNACastManager.DLNADevice>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }

    // ✅ Observar estado de PIP da MainActivity (usando State para reatividade)
    val mainActivity = activity as? MainActivity
    val isInPiPMode by mainActivity?.isInPiPModeState ?: remember { mutableStateOf(false) }

    // Estados para controles customizados
    var controlsVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var currentVideoTitle by remember { mutableStateOf("") }
    var isSeekingActive by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(RepeatMode.NONE) }

    // Estados para fix de metadados
    var showFixMetadataDialog by remember { mutableStateOf(false) }
    var isFixingVideo by remember { mutableStateOf(false) }
    var isSeekable by remember { mutableStateOf(true) }

    //Controle de rotação
    var rotationMode by remember { mutableStateOf(RotationMode.AUTO) }
    var lastValidOrientation by remember { mutableStateOf<Int?>(null) }


    // Timer regressivo para UI (em segundos)
    var uiTimer by remember { mutableStateOf(0) }

    // Função para resetar timer da UI (definida no escopo correto com tipo explícito)
    val resetUITimer: () -> Unit = {
        uiTimer = 4
    }

    // Estados para controles de gestos (apenas seek - brilho/volume removidos)
    var seekIndicator by remember { mutableStateOf<String?>(null) }
    var seekSide by remember { mutableStateOf(Alignment.Center) }

    val coroutineScope = rememberCoroutineScope()

    var lastTapTime by remember { mutableStateOf(0L) }
    var tapCount by remember { mutableStateOf(0) }
    val doubleTapTimeWindow = 300L // 300ms para detectar double tap

    // ✅ Esconder controles quando entrar no PIP
    LaunchedEffect(isInPiPMode) {
        if (isInPiPMode) {
            controlsVisible = false
        }
    }

    // ✅ Restaurar orientação padrão quando não pode controlar rotação
    LaunchedEffect(canControlRotation) {
        if (!canControlRotation) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    //Legendas
    var availableSubtitles by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var availableAudioTracks by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var selectedSubtitleTrack by remember { mutableStateOf<Int?>(null) }
    var selectedAudioTrack by remember { mutableStateOf<Int?>(null) }
    var showTrackSelectionDialog by remember { mutableStateOf(false) }

    // PlayerView sem controles nativos

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

            subtitleView?.apply {
                // Renderização via WebView para melhor suporte a estilos
                setViewType(SubtitleView.VIEW_TYPE_WEB)

                // Define tamanho fixo da fonte
                setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)

                // Remove estilos e tamanhos embutidos para ter controle total
                setApplyEmbeddedStyles(false)
                setApplyEmbeddedFontSizes(false)

                // Ajusta padding inferior (opcional)
                setBottomPaddingFraction(0.08f)
            }

            // Listener para interceptar e ajustar os Cues (legendas)
            player?.addListener(object : Player.Listener {
                override fun onCues(cues: MutableList<Cue>) {
                    val adjustedCues = cues.map { cue ->
                        cue.buildUpon()
                            // Aumenta largura para 95% da tela
                            .setSize(0.95f)
                            // Alinha texto à esquerda (pode usar ALIGN_CENTER se preferir)
                            .setTextAlignment(Layout.Alignment.ALIGN_NORMAL)
                            // Mantém posição vertical padrão (ou ajuste com setLine)
                            .build()
                    }
                    subtitleView?.setCues(adjustedCues)
                }
            })
        }
    }


    fun applyRotation(videoSize: VideoSize? = null) {
        val localActivity = activity ?: return

        // Se não pode controlar rotação, restaurar orientação padrão e sair
        if (!canControlRotation) {
            localActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }

        val targetOrientation = when (rotationMode) {
            RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            RotationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            RotationMode.AUTO -> {
                if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                    // VideoSize válido - calcular orientação
                    if (videoSize.width > videoSize.height) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                } else {
                    // VideoSize inválido - manter última orientação conhecida
                    lastValidOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }

        // Só aplicar se mudou e atualizar cache
        if (targetOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            lastValidOrientation = targetOrientation
            localActivity.requestedOrientation = targetOrientation
        }
    }

    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        mediaController?.let { controller ->
            val trackSelectionParameters = controller.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(
                        availableSubtitles[groupIndex].mediaTrackGroup,
                        trackIndex
                    )
                )
                .build()

            controller.trackSelectionParameters = trackSelectionParameters
            selectedSubtitleTrack = groupIndex
        }
    }

    fun disableSubtitles() {
        mediaController?.let { controller ->
            controller.trackSelectionParameters = controller.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            selectedSubtitleTrack = null
        }
    }

    fun checkAvailableTracks(controller: MediaController) {
        val tracks = controller.currentTracks
        val subtitleGroups = mutableListOf<Tracks.Group>()
        val audioGroups = mutableListOf<Tracks.Group>()


        for (trackGroup in tracks.groups) {

            if (trackGroup.type == C.TRACK_TYPE_TEXT) {
                subtitleGroups.add(trackGroup)
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                }
            } else if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                audioGroups.add(trackGroup)
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                }
            }
        }

        availableSubtitles = subtitleGroups
        availableAudioTracks = audioGroups

    }

    fun setupController(controller: MediaController) {
        mediaController = controller
        playerView.player = controller

        controller.currentMediaItem?.localConfiguration?.uri?.let { uri ->
            val uriStr = uri.toString()
            if (uriStr.startsWith("locked://")) {
                currentVideoPath = uriStr.removePrefix("locked://")
                val obfuscatedName = File(currentVideoPath).name
                currentVideoTitle = LockedPlaybackSession.getOriginalName(obfuscatedName)
                    ?.substringBeforeLast(".") ?: obfuscatedName
            } else {
                currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                currentVideoTitle = File(currentVideoPath).nameWithoutExtension
            }
        }

        checkAvailableTracks(controller)

        if (overlayActuallyVisible) {
            applyRotation(mediaController!!.videoSize)
        }

    }

    LaunchedEffect(isVisible, isPlaying) {
        (activity as? MainActivity)?.setPlayerState(isVisible, isPlaying)
    }

    // ✅ RECEPTOR DE COMANDOS PIP
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra("action", -1)) {
                    PiPConstants.REQUEST_CODE_PLAY_PAUSE -> {
                        mediaController?.let {
                            if (it.isPlaying) it.pause() else it.play()
                        }
                    }
                    PiPConstants.REQUEST_CODE_NEXT -> {
                        // ✅ CENTRALIZADO: Usa PlaylistNavigator
                        PlaylistNavigator.next(context)
                    }
                    PiPConstants.REQUEST_CODE_PREVIOUS -> {
                        // ✅ CENTRALIZADO: Usa PlaylistNavigator
                        PlaylistNavigator.previous(context)
                    }
                }
            }
        }

        val filter = IntentFilter("PIP_CONTROL")
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Ajustar orientação ao desconectar do cast
    LaunchedEffect(isCasting, mediaController) {
        if (!isCasting && mediaController != null) {
            // Pequeno delay para garantir que saiu do CastControlsOverlay
            delay(100)

            applyRotation(mediaController!!.videoSize)
        }
    }

    // Efeito para atualizar posição do vídeo
    LaunchedEffect(mediaController, isSeekingActive) {
        if (mediaController != null && !isSeekingActive) {
            while (overlayActuallyVisible) {
                currentPosition = mediaController!!.currentPosition
                duration = mediaController!!.duration.takeIf { it > 0 } ?: 0L
                isPlaying = mediaController!!.isPlaying
                isSeekable = mediaController!!.isCurrentMediaItemSeekable
                delay(100)
            }
        }
    }

    LaunchedEffect(controlsVisible, uiTimer, isPlaying) {
        if (controlsVisible && isPlaying && uiTimer > 0) {
            while (uiTimer > 0 && controlsVisible && isPlaying) {
                delay(1000) // Aguarda 1 segundo
                uiTimer -= 1
            }

            // Se o timer chegou a 0 e os controles ainda estão visíveis
            if (uiTimer <= 0 && controlsVisible) {
                controlsVisible = false
            }
        }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            uiTimer = 4 // Inicia com 4 segundos
        } else if (isVisible) {
            // Re-hide system bars whenever controls are dismissed
            val window = activity?.window ?: return@LaunchedEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // Esconder indicador de seek após tempo
    LaunchedEffect(seekIndicator) {
        if (seekIndicator != null) {
            delay(500)
            seekIndicator = null
        }
    }

    LaunchedEffect(Unit) {
        castManager.setConnectionStatusListener { connected ->
            isCasting = connected
            if (!connected) {
                connectedDeviceName = ""
            }
        }
    }

    fun selectAudioTrack(groupIndex: Int, trackIndex: Int) {
        mediaController?.let { controller ->
            val trackSelectionParameters = controller.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(
                        availableAudioTracks[groupIndex].mediaTrackGroup,
                        trackIndex
                    )
                )
                .build()

            controller.trackSelectionParameters = trackSelectionParameters
            selectedAudioTrack = groupIndex
        }
    }

    // Dialog de confirmação para deletar (movido para Dialogs.kt)
    if (showDeleteDialog) {
        com.nkls.nekovideo.components.DeleteVideoDialog(
            videoPath = currentVideoPath,
            onDismiss = {
                showDeleteDialog = false
                mediaController?.play()
            },
            onConfirm = {
                showDeleteDialog = false
                coroutineScope.launch {
                    deleteCurrentVideo(
                        context = context,
                        videoPath = currentVideoPath,
                        mediaController = mediaController,
                        onVideoDeleted = onVideoDeleted
                    )
                }
            }
        )
    }

    // Dialog para corrigir metadados do vídeo (moov atom)
    if (showFixMetadataDialog && currentVideoPath.isNotEmpty()) {
        FixVideoMetadataDialog(
            videoPath = currentVideoPath,
            onDismiss = {
                showFixMetadataDialog = false
                mediaController?.play()
            },
            onConfirm = {
                showFixMetadataDialog = false
                isFixingVideo = true

                coroutineScope.launch {
                    val result = VideoRemuxer.remuxVideo(
                        context = context,
                        inputPath = currentVideoPath
                    )

                    isFixingVideo = false

                    when (result) {
                        is VideoRemuxer.RemuxResult.Success -> {
                            // Atualiza o cache do scanner
                            FolderVideoScanner.startScan(context, coroutineScope, forceRefresh = true)

                            Toast.makeText(
                                context,
                                context.getString(R.string.fix_video_success),
                                Toast.LENGTH_SHORT
                            ).show()

                            // Para o serviço completamente para limpar cache
                            MediaPlaybackService.stopService(context)

                            // Aguarda e reinicia com a playlist atual
                            delay(300)

                            val window = PlaylistManager.getCurrentWindow()
                            val currentIndexInWindow = PlaylistManager.getCurrentIndexInWindow()
                            MediaPlaybackService.startWithPlaylist(context, window, currentIndexInWindow)
                        }
                        is VideoRemuxer.RemuxResult.Error -> {
                            Toast.makeText(
                                context,
                                "${context.getString(R.string.fix_video_error)}: ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            mediaController?.play()
                        }
                    }
                }
            }
        )
    }

    // Diálogo de seleção de legendas/áudio (movido para TrackSelectionDialog.kt)
    if (showTrackSelectionDialog) {
        TrackSelectionDialog(
            availableSubtitles = availableSubtitles,
            availableAudioTracks = availableAudioTracks,
            selectedSubtitleTrack = selectedSubtitleTrack,
            selectedAudioTrack = selectedAudioTrack,
            onSubtitleSelected = { groupIndex, trackIndex ->
                selectSubtitleTrack(groupIndex, trackIndex)
            },
            onSubtitlesDisabled = { disableSubtitles() },
            onAudioSelected = { groupIndex, trackIndex ->
                selectAudioTrack(groupIndex, trackIndex)
            },
            onDismiss = { showTrackSelectionDialog = false }
        )
    }

    LaunchedEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
            } catch (_: Exception) {
            }
        }, MoreExecutors.directExecutor())
    }

    // Controlar overlay (mesmo código)
    LaunchedEffect(isVisible) {
        if (isVisible && !hasRefreshed) {
            hasRefreshed = true
            overlayActuallyVisible = true

            // Primeiro, tenta conectar ao MediaController existente
            val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

            controllerFuture.addListener({
                try {
                    val newController = controllerFuture.get()

                    // Verifica se o controller está funcionando e tem mídia
                    val needsRefresh = when {
                        newController.mediaItemCount == 0 -> {
                            true
                        }
                        newController.playbackState == Player.STATE_IDLE -> {
                            true
                        }
                        else -> {
                            false
                        }
                    }

                    if (needsRefresh) {
                        // Só faz refresh se realmente precisar
                        MediaPlaybackService.refreshPlayer(context)

                        // Usa corrotina para o delay e reconexão
                        coroutineScope.launch {
                            delay(800)

                            // Reconecta após o refresh
                            val refreshedControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                            refreshedControllerFuture.addListener({
                                try {
                                    val refreshedController = refreshedControllerFuture.get()
                                    setupController(refreshedController)
                                } catch (e: Exception) {
                                    Log.e("VideoPlayer", "Erro ao conectar controller após refresh", e)
                                }
                            }, MoreExecutors.directExecutor())
                        }
                    } else {
                        // Player está funcionando, só conecta normalmente
                        setupController(newController)
                    }

                } catch (e: Exception) {
                    Log.e("VideoPlayer", "Erro ao conectar controller inicial", e)
                }
            }, MoreExecutors.directExecutor())

        } else if (!isVisible) {
            playerView.player = null
            hasRefreshed = false
            overlayActuallyVisible = false
        }
    }

    // Listener para mudanças com melhorias de UX
    DisposableEffect(mediaController, overlayActuallyVisible, repeatMode) {
        var listener: Player.Listener? = null

        if (overlayActuallyVisible && mediaController != null) {
            listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!overlayActuallyVisible) return
                    applyRotation(videoSize)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!overlayActuallyVisible) return

                    val wasPlayerPaused = !mediaController!!.isPlaying

                    mediaItem?.localConfiguration?.uri?.let { uri ->
                        val uriStr = uri.toString()
                        if (uriStr.startsWith("locked://")) {
                            currentVideoPath = uriStr.removePrefix("locked://")
                            val obfuscatedName = File(currentVideoPath).name
                            currentVideoTitle = LockedPlaybackSession.getOriginalName(obfuscatedName)
                                ?.substringBeforeLast(".") ?: obfuscatedName
                        } else {
                            currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                            currentVideoTitle = File(currentVideoPath).nameWithoutExtension
                        }
                    }

                    applyRotation(mediaController!!.videoSize)

                    if (controlsVisible) {
                        resetUITimer()
                    }

                    if (wasPlayerPaused && !mediaController!!.isPlaying) {
                        mediaController!!.play()
                    }

                    // ✅ REMOVIDO: A atualização de window agora é centralizada no MediaPlaybackService
                    // Isso evita duplicação de chamadas e dessincronização
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // ✅ SIMPLIFICADO: A navegação automática é feita pelo MediaPlaybackService
                    // Aqui apenas tratamos o REPEAT_ONE (que precisa de seekTo local)
                    if (playbackState == Player.STATE_ENDED) {
                        when (repeatMode) {
                            RepeatMode.REPEAT_ONE -> {
                                // Apenas REPEAT_ONE precisa de tratamento local
                                mediaController!!.seekTo(0)
                                mediaController!!.play()
                            }
                            else -> {
                                // REPEAT_ALL e NONE são tratados pelo MediaPlaybackService
                                // O onMediaItemTransition vai atualizar os estados quando o vídeo mudar
                            }
                        }
                    }
                }

                // ADICIONE AQUI DENTRO:
                override fun onTracksChanged(tracks: Tracks) {
                    checkAvailableTracks(mediaController!!)
                }
            }

            mediaController!!.addListener(listener)

            // Apply rotation immediately — onVideoSizeChanged may have fired before
            // this listener was registered (race between recomposition and ExoPlayer decode)
            applyRotation(mediaController!!.videoSize)
        }

        onDispose {
            listener?.let {
                mediaController?.removeListener(it)
            }
        }
    }

    // Controle de modo imersivo (mesmo código)
    DisposableEffect(isVisible) {
        if (isVisible) {
            val localActivity = activity ?: return@DisposableEffect onDispose {}
            val window = localActivity.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)

            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            isFullscreen = true

            // Manter tela ligada enquanto o vídeo está tocando
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            onDispose {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                // Restaurar orientação padrão ao voltar para o FolderScreen
                localActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                isFullscreen = false

                // Remover flag de manter tela ligada
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } else {
            onDispose { }
        }
    }

    // Reaplicar flags quando o app volta do background
    DisposableEffect(lifecycleOwner, isVisible) {
        Log.d("BackDebug", "🎬 VideoPlayerOverlay - DisposableEffect montado, isVisible: $isVisible")

        val observer = LifecycleEventObserver { _, event ->
            Log.d("BackDebug", "🎬 VideoPlayerOverlay - Lifecycle event: $event, isVisible: $isVisible")

            if (event == Lifecycle.Event.ON_RESUME && isVisible) {
                Log.d("BackDebug", "🎬 VideoPlayerOverlay - ON_RESUME com overlay visível, reaplicando flags")
                val window = activity?.window ?: return@LifecycleEventObserver
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)

                // Reaplicar modo imersivo
                WindowCompat.setDecorFitsSystemWindows(window, false)
                insetsController.apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }

                // Reaplicar keep screen on
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d("BackDebug", "🎬 VideoPlayerOverlay - DisposableEffect desmontado")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Back press é gerenciado pelo MainScreen para evitar conflitos

    // Dialog de seleção de dispositivos DLNA
    if (showCastDevicePicker) {
        DLNADevicePickerDialog(
            devices = discoveredDevices,
            isDiscovering = isDiscovering,
            onDeviceSelected = { device ->
                showCastDevicePicker = false
                connectedDeviceName = device.name
                castManager.connectToDevice(device)

                val playlist = PlaylistManager.getFullPlaylist()
                val titles = playlist.map { path ->
                    if (path.startsWith("locked://")) {
                        val obfuscatedName = File(path.removePrefix("locked://")).name
                        LockedPlaybackSession.getOriginalName(obfuscatedName)
                            ?.substringBeforeLast(".") ?: obfuscatedName
                    } else {
                        File(path.removePrefix("file://")).nameWithoutExtension
                    }
                }
                val currentIndex = PlaylistManager.getCurrentIndex()
                MediaPlaybackService.stopService(context)
                castManager.castPlaylist(playlist, titles, currentIndex)
                onDismiss()
            },
            onDismiss = { showCastDevicePicker = false }
        )
    }

    // Overlay animado com gestos SIMPLIFICADOS (só double tap para seek)
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
    ) {
        if (isCasting) {
            // UI de controle do Cast
            CastControlsOverlay(
                castManager = castManager,
                deviceName = connectedDeviceName,
                videoTitle = currentVideoTitle,
                onDisconnect = {
                    castManager.stopCasting()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        MediaPlaybackService.stopService(context)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            MediaPlaybackService.refreshPlayer(context)
                        }, 300)
                        isCasting = false
                        onDismiss()
                    }, 100)
                },
                onBack = onDismiss,
                onCurrentIndexChanged = { /* index tracked by DLNACastManager */ }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                val currentTime = System.currentTimeMillis()

                                // Se é o primeiro tap ou passou muito tempo desde o último
                                if (tapCount == 0 || (currentTime - lastTapTime) > doubleTapTimeWindow) {
                                    tapCount = 1
                                    lastTapTime = currentTime

                                    // Toggle da UI imediatamente (sem delay!)
                                    controlsVisible = !controlsVisible
                                    if (controlsVisible) {
                                        resetUITimer()
                                    }

                                    // Inicia timer para detectar se vai ter um segundo tap
                                    coroutineScope.launch {
                                        delay(doubleTapTimeWindow)
                                        // Se passou o tempo e ainda é apenas 1 tap, mantém o estado atual
                                        if (tapCount == 1) {
                                            tapCount = 0
                                        }
                                    }

                                } else if (tapCount == 1 && (currentTime - lastTapTime) <= doubleTapTimeWindow) {
                                    // É um double tap!
                                    tapCount = 0

                                    // Esconde a UI imediatamente
                                    controlsVisible = false

                                    // Executa a lógica de seek
                                    val screenWidth = size.width
                                    val doubleTapSeek =
                                        SettingsManager.getDoubleTapSeek(context) * 1000L

                                    mediaController?.let { controller ->
                                        val currentPos = controller.currentPosition

                                        if (offset.x < screenWidth / 2) {
                                            // Lado esquerdo - voltar
                                            val newPosition =
                                                (currentPos - doubleTapSeek).coerceAtLeast(0)
                                            controller.seekTo(newPosition)
                                            seekIndicator = "-${doubleTapSeek / 1000}s"
                                            seekSide = Alignment.CenterStart
                                        } else {
                                            // Lado direito - avançar
                                            val newPosition = currentPos + doubleTapSeek
                                            controller.seekTo(newPosition)
                                            seekIndicator = "+${doubleTapSeek / 1000}s"
                                            seekSide = Alignment.CenterEnd
                                        }
                                    }
                                }
                            }
                        )
                    }
            ) {
                // PlayerView em background
                AndroidView(
                    factory = { playerView },
                    modifier = Modifier.fillMaxSize()
                )

                // Indicadores visuais (apenas seek - brilho/volume removidos)
                GestureIndicators(
                    seekInfo = seekIndicator,
                    seekAlignment = seekSide
                )

                // Overlay de loading enquanto corrige o vídeo
                if (isFixingVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = Color.White
                            )
                            androidx.compose.material3.Text(
                                text = context.getString(R.string.fixing_video),
                                color = Color.White
                            )
                        }
                    }
                }

                // Interface customizada com ícones de volume e brilho
                AnimatedVisibility(
                    visible = controlsVisible && !isInPiPMode, // ✅ MANTER ASSIM
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    CustomVideoControls(
                        mediaController = mediaController,
                        currentPosition = currentPosition,
                        duration = duration,
                        isPlaying = isPlaying,
                        videoTitle = currentVideoTitle,
                        onSeekStart = { isSeekingActive = true },
                        onSeekEnd = { isSeekingActive = false },
                        onDeleteClick = {
                            mediaController?.pause()
                            showDeleteDialog = true
                        },
                        onBackClick = onDismiss,
                        resetUITimer = resetUITimer,
                        repeatMode = repeatMode,
                        onRepeatModeChange = { newMode ->
                            repeatMode = newMode

                            mediaController?.let { controller ->
                                controller.repeatMode = when (newMode) {
                                    RepeatMode.NONE -> Player.REPEAT_MODE_OFF
                                    RepeatMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                                    RepeatMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                                }
                            }
                        },
                        isCasting = isCasting,
                        onCastClick = {
                            discoveredDevices = emptyList()
                            isDiscovering = true
                            showCastDevicePicker = true
                            castManager.onDevicesFound = { devices ->
                                discoveredDevices = devices
                                isDiscovering = false
                            }
                            castManager.discoverDevices()
                            resetUITimer()
                        },
                        rotationMode = rotationMode,
                        onRotationModeChange = { newMode ->
                            rotationMode = newMode
                            applyRotation(mediaController?.videoSize)
                        },
                        // Legendas
                        hasSubtitles = availableSubtitles.isNotEmpty(),
                        subtitlesEnabled = selectedSubtitleTrack != null,
                        onSubtitlesClick = { showTrackSelectionDialog = true },
                        onPiPClick = {
                            controlsVisible = false
                            (activity as? MainActivity)?.enterPiPMode()
                        },
                        needsMetadataFix = !isSeekable,
                        onFixMetadataClick = {
                            mediaController?.pause()
                            showFixMetadataDialog = true
                        }
                    )
                }
            }
        }
    }
}

// GestureIndicators, setVolume, setBrightness e findActivity movidos para arquivos separados
// GestureIndicators.kt, PlayerUtils.kt
