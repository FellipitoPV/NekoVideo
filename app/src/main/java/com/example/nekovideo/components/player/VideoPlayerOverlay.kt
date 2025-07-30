package com.example.nekovideo.components.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.example.nekovideo.MediaPlaybackService
import com.example.nekovideo.R
import com.example.nekovideo.components.helpers.FilesManager
import com.example.nekovideo.components.settings.SettingsManager
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RepeatMode {
    NONE,           // Para no final da playlist
    REPEAT_ALL,     // Loop da playlist
    REPEAT_ONE      // Loop do vídeo atual
}

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("OpaqueUnitKey")
@Composable
fun VideoPlayerOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onVideoDeleted: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var hasRefreshed by remember { mutableStateOf(false) }
    var overlayActuallyVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentVideoPath by remember { mutableStateOf("") }

    // Estados para controles customizados
    var controlsVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var currentVideoTitle by remember { mutableStateOf("") }
    var isSeekingActive by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(RepeatMode.NONE) }

    // Timer regressivo para UI (em segundos)
    var uiTimer by remember { mutableStateOf(0) }

    // Função para resetar timer da UI (definida no escopo correto com tipo explícito)
    val resetUITimer: () -> Unit = {
        uiTimer = 4
    }

    // Estados para sliders laterais invisíveis
    var leftSliderActive by remember { mutableStateOf(false) }
    var rightSliderActive by remember { mutableStateOf(false) }
    var brightnessIndicator by remember { mutableStateOf<Float?>(null) }
    var volumeIndicator by remember { mutableStateOf<Int?>(null) }
    var currentVolume by remember { mutableStateOf(50) }
    var currentBrightness by remember { mutableStateOf(50f) }

    // Variáveis para manter os últimos valores válidos durante a animação
    var lastValidBrightness by remember { mutableStateOf(50f) }
    var lastValidVolume by remember { mutableStateOf(50) }

    // Estados para controles de gestos (mantendo só o seek)
    var seekIndicator by remember { mutableStateOf<String?>(null) }
    var seekSide by remember { mutableStateOf(Alignment.Center) }

    val coroutineScope = rememberCoroutineScope()

    var lastTapTime by remember { mutableStateOf(0L) }
    var tapCount by remember { mutableStateOf(0) }
    val doubleTapTimeWindow = 300L // 300ms para detectar double tap


    // PlayerView sem controles nativos
    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun setupController(controller: MediaController) {
        mediaController = controller
        playerView.player = controller

        controller.currentMediaItem?.localConfiguration?.uri?.let { uri ->
            currentVideoPath = uri.path?.removePrefix("file://") ?: ""
            currentVideoTitle = File(currentVideoPath).nameWithoutExtension
        }

        if (overlayActuallyVisible) {
            val videoSize = controller.videoSize
            if (videoSize.width > 0 && videoSize.height > 0) {
                val activity = context.findActivity()
                val newOrientation = if (videoSize.width > videoSize.height) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                activity?.requestedOrientation = newOrientation
            }
        }

        Log.d("VideoPlayer", "Controller configurado - Posição: ${controller.currentPosition}ms, Tocando: ${controller.isPlaying}")
    }

    // Inicializar valores de volume e brilho
    LaunchedEffect(Unit) {
        // Volume inicial
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentVolume = ((volume.toFloat() / maxVolume) * 100).toInt()

        // Brilho inicial
        val activity = context.findActivity()
        if (activity != null) {
            val window = activity.window
            val layoutParams = window.attributes
            currentBrightness = if (layoutParams.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                } catch (e: Exception) {
                    0.5f
                }
            } else {
                layoutParams.screenBrightness
            }
        }
    }

    // Efeito para atualizar posição do vídeo
    LaunchedEffect(mediaController, isSeekingActive) {
        if (mediaController != null && !isSeekingActive) {
            while (overlayActuallyVisible) {
                currentPosition = mediaController!!.currentPosition
                duration = mediaController!!.duration.takeIf { it > 0 } ?: 0L
                isPlaying = mediaController!!.isPlaying
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
        }
    }

    // Esconder indicadores após tempo
    LaunchedEffect(seekIndicator) {
        if (seekIndicator != null) {
            delay(500)
            seekIndicator = null
        }
    }

    LaunchedEffect(brightnessIndicator) {
        if (brightnessIndicator != null) {
            lastValidBrightness = brightnessIndicator!! // Salvar último valor válido
            delay(2000)
            brightnessIndicator = null
        }
    }

    LaunchedEffect(volumeIndicator) {
        if (volumeIndicator != null) {
            lastValidVolume = volumeIndicator!! // Salvar último valor válido
            delay(2000)
            volumeIndicator = null
        }
    }

    // Dialog de confirmação para deletar
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                mediaController?.play()
            },
            title = {
                Text(
                    text = stringResource(R.string.delete_video),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val fileName = File(currentVideoPath).nameWithoutExtension
                Text(
                    text = stringResource(R.string.delete_video_confirmation, fileName),
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
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
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        mediaController?.play()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
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
                            Log.d("VideoPlayer", "Controller sem mídia - refresh necessário")
                            true
                        }
                        newController.playbackState == Player.STATE_IDLE -> {
                            Log.d("VideoPlayer", "Player em estado IDLE - refresh necessário")
                            true
                        }
                        else -> {
                            Log.d("VideoPlayer", "Player funcionando normalmente - sem refresh")
                            false
                        }
                    }

                    if (needsRefresh) {
                        // Só faz refresh se realmente precisar
                        Log.d("VideoPlayer", "Executando refresh do player...")
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
                        Log.d("VideoPlayer", "Conectando ao player existente...")
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
    DisposableEffect(mediaController, overlayActuallyVisible, repeatMode) { // Adicione repeatMode aqui
        var listener: Player.Listener? = null

        if (overlayActuallyVisible && mediaController != null) {
            listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!overlayActuallyVisible) return

                    val activity = context.findActivity()
                    if (activity != null && videoSize.width > 0 && videoSize.height > 0) {
                        val newOrientation = if (videoSize.width > videoSize.height) {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        activity.requestedOrientation = newOrientation
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!overlayActuallyVisible) return

                    val wasPlayerPaused = !mediaController!!.isPlaying

                    mediaItem?.localConfiguration?.uri?.let { uri ->
                        currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                        currentVideoTitle = File(currentVideoPath).nameWithoutExtension
                    }

                    val videoSize = mediaController!!.videoSize
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val activity = context.findActivity()
                        val newOrientation = if (videoSize.width > videoSize.height) {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        activity?.requestedOrientation = newOrientation
                    }

                    if (controlsVisible) {
                        resetUITimer()
                    }

                    if (wasPlayerPaused && !mediaController!!.isPlaying) {
                        mediaController!!.play()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // NOVA LÓGICA para os modos de repetição
                    if (playbackState == Player.STATE_ENDED) {
                        when (repeatMode) {
                            RepeatMode.REPEAT_ONE -> {
                                // Loop do vídeo atual
                                mediaController!!.seekTo(0)
                                mediaController!!.play()
                            }
                            RepeatMode.REPEAT_ALL -> {
                                // Loop da playlist
                                if (mediaController!!.hasNextMediaItem()) {
                                    mediaController!!.seekToNextMediaItem()
                                } else {
                                    // Volta para o primeiro vídeo da playlist
                                    mediaController!!.seekTo(0, 0)
                                    mediaController!!.play()
                                }
                            }
                            RepeatMode.NONE -> {
                                // Comportamento original: só avança se há próximo
                                if (mediaController!!.hasNextMediaItem()) {
                                    mediaController!!.seekToNextMediaItem()
                                }
                                // Se não há próximo, para a reprodução
                            }
                        }
                    }
                }
            }

            mediaController!!.addListener(listener)
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
            val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)

            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            isFullscreen = true

            onDispose {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                isFullscreen = false
            }
        } else {
            onDispose { }
        }
    }

    // Handle back press (mesmo código)
    LaunchedEffect(isVisible) {
        if (isVisible && backDispatcher != null) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onDismiss()
                }
            }
            backDispatcher.addCallback(callback)
        }
    }

    // Overlay animado com gestos SIMPLIFICADOS (só double tap para seek)
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
    ) {
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
                                val doubleTapSeek = SettingsManager.getDoubleTapSeek(context) * 1000L

                                mediaController?.let { controller ->
                                    val currentPos = controller.currentPosition

                                    if (offset.x < screenWidth / 2) {
                                        // Lado esquerdo - voltar
                                        val newPosition = (currentPos - doubleTapSeek).coerceAtLeast(0)
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

            // Slider invisível do lado esquerdo (BRILHO) - 70% da altura, centralizado
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.7f)
                    .fillMaxWidth(0.35f) // 35% da largura da tela
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                leftSliderActive = true
                                // Calcular brilho baseado na posição inicial do toque
                                val percentage = ((size.height - offset.y) / size.height)
                                    .coerceIn(0f, 1f)
                                currentBrightness = percentage
                                setBrightness(context, percentage)
                                brightnessIndicator = percentage
                            },
                            onDragEnd = {
                                leftSliderActive = false
                            }
                        ) { change, _ ->
                            // Durante o drag, calcular brilho baseado na posição atual do dedo
                            val percentage = ((size.height - change.position.y) / size.height)
                                .coerceIn(0f, 1f)
                            currentBrightness = percentage
                            setBrightness(context, percentage)
                            brightnessIndicator = percentage
                        }
                    }
            )

            // Slider invisível do lado direito (VOLUME) - 70% da altura, centralizado
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.7f)
                    .fillMaxWidth(0.35f) // 35% da largura da tela
                    .align(Alignment.CenterEnd)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                rightSliderActive = true
                                // Calcular volume baseado na posição inicial do toque
                                val percentage = ((size.height - offset.y) / size.height * 100)
                                    .coerceIn(0f, 100f).toInt()
                                currentVolume = percentage
                                setVolume(context, percentage)
                                volumeIndicator = percentage
                            },
                            onDragEnd = {
                                rightSliderActive = false
                            }
                        ) { change, _ ->
                            // Durante o drag, calcular volume baseado na posição atual do dedo
                            val percentage = ((size.height - change.position.y) / size.height * 100)
                                .coerceIn(0f, 100f).toInt()
                            currentVolume = percentage
                            setVolume(context, percentage)
                            volumeIndicator = percentage
                        }
                    }
            )

            // Indicadores visuais
            GestureIndicators(
                brightnessLevel = brightnessIndicator,
                volumeLevel = volumeIndicator,
                lastValidBrightness = lastValidBrightness,
                lastValidVolume = lastValidVolume,
                seekInfo = seekIndicator,
                seekAlignment = seekSide
            )

            // Interface customizada com ícones de volume e brilho
            AnimatedVisibility(
                visible = controlsVisible,
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
                    repeatMode = repeatMode, // NOVO PARÂMETRO
                    onRepeatModeChange = { newMode -> // NOVO PARÂMETRO
                        repeatMode = newMode
                    }
                )
            }
        }
    }
}

@Composable
private fun GestureIndicators(
    brightnessLevel: Float?,
    volumeLevel: Int?,
    lastValidBrightness: Float,
    lastValidVolume: Int,
    seekInfo: String?,
    seekAlignment: Alignment = Alignment.Center
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Indicador de brilho (lado esquerdo)
        AnimatedVisibility(
            visible = brightnessLevel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Card(
                modifier = Modifier.padding(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Brightness6,
                        contentDescription = "Brightness",
                        tint = Color(0xFFFF9800), // Laranja
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(lastValidBrightness * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Indicador de volume (lado direito)
        AnimatedVisibility(
            visible = volumeLevel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Card(
                modifier = Modifier.padding(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = when {
                            lastValidVolume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                            lastValidVolume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = "Volume",
                        tint = Color(0xFF4CAF50), // Verde
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$lastValidVolume%",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Indicador de seek (posição dinâmica, mais rápido e sem fundo)
        AnimatedVisibility(
            visible = seekInfo != null,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(animationSpec = tween(200)),
            modifier = Modifier.align(seekAlignment)
        ) {
            Box(
                modifier = Modifier.padding(32.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAdvancing = seekInfo?.startsWith("+") == true
                    val seekIcon = if (isAdvancing) Icons.Default.FastForward else Icons.Default.FastRewind

                    if (isAdvancing) {
                        Text(
                            text = seekInfo ?: "",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black,
                                    offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                                    blurRadius = 4f
                                )
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = seekIcon,
                            contentDescription = "Seek Forward",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        Icon(
                            imageVector = seekIcon,
                            contentDescription = "Seek Backward",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = seekInfo ?: "",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black,
                                    offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                                    blurRadius = 4f
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

// Funções para ajustar volume e brilho
private fun setVolume(context: Context, volumePercent: Int) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val newVolume = ((volumePercent / 100f) * maxVolume).toInt()
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
}

private fun setBrightness(context: Context, brightnessPercent: Float) {
    val activity = context.findActivity() ?: return
    val window = activity.window
    val layoutParams = window.attributes
    layoutParams.screenBrightness = brightnessPercent.coerceIn(0.01f, 1.0f)
    window.attributes = layoutParams
}

@Composable
private fun CustomVideoControls(
    mediaController: MediaController?,
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    videoTitle: String,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onDeleteClick: () -> Unit,
    onBackClick: () -> Unit,
    resetUITimer: () -> Unit,
    repeatMode: RepeatMode,
    onRepeatModeChange: (RepeatMode) -> Unit
) {
    val controller = mediaController ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        // Header com gradiente
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = videoTitle,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                )

                // Ícone delete
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Controles centrais
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botão Previous
            IconButton(
                onClick = {
                    if (controller.hasPreviousMediaItem()) {
                        resetUITimer()
                        controller.seekToPreviousMediaItem()
                    }
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp),
                enabled = controller.hasPreviousMediaItem()
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (controller.hasPreviousMediaItem()) Color.White else Color.Gray,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Botão Play/Pause
            IconButton(
                onClick = {
                    if (isPlaying) {
                        controller.pause()
                    } else {
                        controller.play()
                    }
                },
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
                    .size(72.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp)
                )
            }

            // Botão Next
            IconButton(
                onClick = {
                    if (controller.hasNextMediaItem()) {
                        resetUITimer()
                        controller.seekToNextMediaItem()
                    }
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(56.dp),
                enabled = controller.hasNextMediaItem()
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = if (controller.hasNextMediaItem()) Color.White else Color.Gray,
                    modifier = Modifier.size(32.dp)
                )
            }
        }



        // Bottom controls com gradiente
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            // Seek bar
            if (duration > 0) {
                var tempPosition by remember { mutableStateOf(currentPosition) }
                var isDragging by remember { mutableStateOf(false) }

                Slider(
                    value = if (isDragging) tempPosition.toFloat() else currentPosition.toFloat(),
                    onValueChange = { newValue ->
                        tempPosition = newValue.toLong()
                        if (!isDragging) {
                            isDragging = true
                            onSeekStart()
                        }
                    },
                    onValueChangeFinished = {
                        controller.seekTo(tempPosition)
                        isDragging = false
                        onSeekEnd()
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Time display com botão de repetição
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(if (isDragging) tempPosition else currentPosition),
                        color = Color.White,
                        fontSize = 14.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 14.sp
                        )

                        // Botão de modo de repetição (inferior direito)
                        IconButton(
                            onClick = {
                                val nextMode = when (repeatMode) {
                                    RepeatMode.NONE -> RepeatMode.REPEAT_ALL
                                    RepeatMode.REPEAT_ALL -> RepeatMode.REPEAT_ONE
                                    RepeatMode.REPEAT_ONE -> RepeatMode.NONE
                                }
                                onRepeatModeChange(nextMode)
                                resetUITimer()
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .size(32.dp)
                        ) {
                            val (icon, contentDescription, iconColor) = when (repeatMode) {
                                RepeatMode.NONE -> Triple(
                                    Icons.Default.PlaylistPlay,
                                    "Normal Play",
                                    Color.White.copy(alpha = 0.7f)
                                )
                                RepeatMode.REPEAT_ALL -> Triple(
                                    Icons.Default.Repeat,
                                    "Repeat Playlist",
                                    Color(0xFF4CAF50)
                                )
                                RepeatMode.REPEAT_ONE -> Triple(
                                    Icons.Default.RepeatOne,
                                    "Repeat One",
                                    Color(0xFF2196F3)
                                )
                            }

                            Icon(
                                imageVector = icon,
                                contentDescription = contentDescription,
                                tint = iconColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

// Função auxiliar existente
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

// Função para deletar vídeo atual e atualizar playlist (mesmo código anterior)
private suspend fun deleteCurrentVideo(
    context: Context,
    videoPath: String,
    mediaController: MediaController?,
    onVideoDeleted: (String) -> Unit
) {
    try {
        val controller = mediaController ?: return
        val currentIndex = controller.currentMediaItemIndex
        val totalItems = controller.mediaItemCount

        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val isSecureVideo = videoPath.startsWith(secureFolderPath)

        val updatedPlaylist = mutableListOf<String>()
        for (i in 0 until totalItems) {
            if (i != currentIndex) {
                val itemUri = controller.getMediaItemAt(i).localConfiguration?.uri.toString()
                updatedPlaylist.add(itemUri)
            }
        }

        val nextIndex = when {
            updatedPlaylist.isEmpty() -> {
                return
            }
            currentIndex >= updatedPlaylist.size -> updatedPlaylist.size - 1
            else -> currentIndex
        }

        val success = if (isSecureVideo) {
            deleteSecureFile(context, videoPath)
        } else {
            deleteRegularFile(context, videoPath)
        }

        if (success) {

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onVideoDeleted(videoPath)
            }

            if (updatedPlaylist.isNotEmpty()) {
                MediaPlaybackService.updatePlaylistAfterDeletion(context, updatedPlaylist, nextIndex)
            } else {
                MediaPlaybackService.stopService(context)
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Video deleted successfully", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Failed to delete video", android.widget.Toast.LENGTH_SHORT).show()
                controller.play()
            }
        }

    } catch (e: Exception) {
        Log.e("VideoPlayer", "Erro ao deletar vídeo", e)
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(context, "Error deleting video: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            mediaController?.play()
        }
    }
}

private suspend fun deleteRegularFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo regular", e)
            false
        }
    }

private suspend fun deleteSecureFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo seguro", e)
            false
        }
    }