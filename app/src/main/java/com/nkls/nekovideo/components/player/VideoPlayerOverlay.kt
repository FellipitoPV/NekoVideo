package com.nkls.nekovideo.components.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.text.Layout
import android.util.Log
import android.util.Rational
import android.util.TypedValue
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.window.DialogProperties
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
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.common.util.concurrent.MoreExecutors
import com.nkls.nekovideo.MainActivity
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.R
import com.nkls.nekovideo.billing.PremiumManager
import com.nkls.nekovideo.components.helpers.CastManager
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.layout.InterstitialAdManager
import com.nkls.nekovideo.components.settings.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File


enum class RepeatMode {
    NONE,
    REPEAT_ALL,
    REPEAT_ONE
}

enum class RotationMode {
    AUTO,      // Adaptar ao vídeo (comportamento atual)
    PORTRAIT,  // Sempre vertical
    LANDSCAPE  // Sempre horizontal
}

private var interstitialAdManager: InterstitialAdManager? = null

// ✅ FUNÇÕES PIP - Adicionar ANTES do @Composable
@RequiresApi(Build.VERSION_CODES.O)
private fun createPiPParams(
    context: Context,
    mediaController: MediaController?
): PictureInPictureParams {
    val actions = ArrayList<RemoteAction>()

    // Previous
    actions.add(createRemoteAction(
        context,
        android.R.drawable.ic_media_previous,
        "Previous",
        REQUEST_CODE_PREVIOUS
    ))

    // Play/Pause
    val isPlaying = mediaController?.isPlaying ?: false
    actions.add(createRemoteAction(
        context,
        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        if (isPlaying) "Pause" else "Play",
        REQUEST_CODE_PLAY_PAUSE
    ))

    // Next
    actions.add(createRemoteAction(
        context,
        android.R.drawable.ic_media_next,
        "Next",
        REQUEST_CODE_NEXT
    ))

    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setActions(actions)
        .build()
}

@RequiresApi(Build.VERSION_CODES.O)
private fun createRemoteAction(
    context: Context,
    iconResId: Int,
    title: String,
    requestCode: Int
): RemoteAction {
    val intent = Intent("PIP_CONTROL").apply {
        putExtra("action", requestCode)
    }

    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val icon = Icon.createWithResource(context, iconResId)

    return RemoteAction(icon, title, title, pendingIntent)
}

private const val REQUEST_CODE_PLAY_PAUSE = 1
private const val REQUEST_CODE_NEXT = 2
private const val REQUEST_CODE_PREVIOUS = 3

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("OpaqueUnitKey")
@Composable
fun VideoPlayerOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onVideoDeleted: (String) -> Unit = {},
    premiumManager: PremiumManager
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var hasRefreshed by remember { mutableStateOf(false) }
    var overlayActuallyVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentVideoPath by remember { mutableStateOf("") }
    var isCasting by remember { mutableStateOf(false) }
    var connectedDeviceName by remember { mutableStateOf("") }
    val castManager = remember { CastManager(context) }

    var isInPiPMode by remember { mutableStateOf(false) }

    val interstitialManager = remember {
        if (interstitialAdManager == null) {
            interstitialAdManager = InterstitialAdManager(context, premiumManager)
            interstitialAdManager?.loadAd()
        }
        interstitialAdManager!!
    }

    // Estados para controles customizados
    var controlsVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var currentVideoTitle by remember { mutableStateOf("") }
    var isSeekingActive by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(RepeatMode.NONE) }

    var isShuffleActive by remember { mutableStateOf(PlaylistManager.isShuffleEnabled) }

    //Controle de rotação
    var rotationMode by remember { mutableStateOf(RotationMode.AUTO) }
    var lastValidOrientation by remember { mutableStateOf<Int?>(null) }


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
        val activity = context.findActivity() ?: return

        val targetOrientation = when (rotationMode) {
            RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            RotationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            RotationMode.AUTO -> {
                if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                    // VideoSize válido - calcular orientação
                    if (videoSize.width > videoSize.height) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
            activity.requestedOrientation = targetOrientation
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
            currentVideoPath = uri.path?.removePrefix("file://") ?: ""
            currentVideoTitle = File(currentVideoPath).nameWithoutExtension
        }

        checkAvailableTracks(controller)

        if (overlayActuallyVisible) {
            applyRotation(mediaController!!.videoSize)
        }

    }

    val activity = context.findActivity() as? MainActivity
    LaunchedEffect(isVisible, isPlaying) {
        activity?.setPlayerState(isVisible, isPlaying)
    }

    // ✅ RECEPTOR DE COMANDOS PIP
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra("action", -1)) {
                    REQUEST_CODE_PLAY_PAUSE -> {
                        mediaController?.let {
                            if (it.isPlaying) it.pause() else it.play()
                        }
                    }
                    REQUEST_CODE_NEXT -> {
                        mediaController?.let {
                            when (val result = PlaylistManager.next()) {
                                is PlaylistManager.NavigationResult.Success -> {
                                    if (result.needsWindowUpdate) {
                                        val newWindow = PlaylistManager.getCurrentWindow()
                                        val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                                        MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                                    } else {
                                        it.seekToNextMediaItem()
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    REQUEST_CODE_PREVIOUS -> {
                        mediaController?.let {
                            when (val result = PlaylistManager.previous()) {
                                is PlaylistManager.NavigationResult.Success -> {
                                    if (result.needsWindowUpdate) {
                                        val newWindow = PlaylistManager.getCurrentWindow()
                                        val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                                        MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                                    } else {
                                        it.seekToPreviousMediaItem()
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter("PIP_CONTROL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

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

    LaunchedEffect(Unit) {
        castManager.setConnectionStatusListener { connected ->
            isCasting = connected
            if (!connected) {
                connectedDeviceName = ""
            }
        }
    }

    fun getSubtitleDisplayName(group: Tracks.Group, index: Int): String {
        val format = group.getTrackFormat(index)

        // Prioridade: label > language > "Legenda X"
        val label = format.label?.takeIf { it.isNotBlank() }
        val language = format.language?.takeIf { it.isNotBlank() }

        return when {
            label != null && language != null -> "$label - [$language]"
            label != null -> label
            language != null -> {
                // Traduzir códigos comuns
                val langName = when(language.lowercase()) {
                    "pt", "pt-br", "por" -> "Português"
                    "en", "eng" -> "Inglês"
                    "es", "spa" -> "Espanhol"
                    "ja", "jpn" -> "Japonês"
                    else -> language.uppercase()
                }
                langName
            }
            else -> "Legenda ${index + 1}"
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

    if (showTrackSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showTrackSelectionDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),  // ADICIONE ESTA LINHA
            modifier = Modifier.fillMaxWidth(0.95f),  // ADICIONE ESTA LINHA (95% da largura da tela)
            title = {
                Text(
                    "Legendas e Áudio",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // COLUNA LEGENDAS
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Text(
                            "LEGENDAS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            // Desativado
                            item {
                                TextButton(
                                    onClick = {
                                        disableSubtitles()
                                        showTrackSelectionDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Desativado",
                                            fontSize = 13.sp,
                                            color = if (selectedSubtitleTrack == null)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                        if (selectedSubtitleTrack == null) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Todas as legendas
                            items(availableSubtitles.size) { groupIndex ->
                                val group = availableSubtitles[groupIndex]
                                for (trackIndex in 0 until group.length) {
                                    val displayName = getSubtitleDisplayName(group, trackIndex)
                                    val isSelected = selectedSubtitleTrack == groupIndex

                                    TextButton(
                                        onClick = {
                                            selectSubtitleTrack(groupIndex, trackIndex)
                                            showTrackSelectionDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                displayName,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 13.sp,
                                                color = if (isSelected)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // DIVISOR VERTICAL
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )

                    // COLUNA ÁUDIO
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Text(
                            "ÁUDIO",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (availableAudioTracks.isEmpty()) {
                            Text(
                                "Nenhuma faixa disponível",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(8.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(availableAudioTracks.size) { groupIndex ->
                                    val group = availableAudioTracks[groupIndex]
                                    for (trackIndex in 0 until group.length) {
                                        val format = group.getTrackFormat(trackIndex)
                                        val displayName = format.label ?: format.language ?: "Áudio ${trackIndex + 1}"
                                        val isSelected = selectedAudioTrack == groupIndex

                                        TextButton(
                                            onClick = {
                                                selectAudioTrack(groupIndex, trackIndex)
                                                showTrackSelectionDialog = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    displayName,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontSize = 13.sp,
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurface
                                                )
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showTrackSelectionDialog = false },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)  // ADICIONE ISTO
                ) {
                    Text("Fechar")
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
                        currentVideoPath = uri.path?.removePrefix("file://") ?: ""
                        currentVideoTitle = File(currentVideoPath).nameWithoutExtension
                    }

                    applyRotation(mediaController!!.videoSize)

                    if (controlsVisible) {
                        resetUITimer()
                    }

                    if (wasPlayerPaused && !mediaController!!.isPlaying) {
                        mediaController!!.play()
                    }

                    // ✅ NOVO: Atualizar window PREVENTIVAMENTE
                    // quando está próximo do fim
                    coroutineScope.launch {
                        val currentIndexInWindow = mediaController!!.currentMediaItemIndex
                        val windowSize = mediaController!!.mediaItemCount

                        // Se está nos últimos 3 vídeos da window, preparar próxima
                        if (currentIndexInWindow >= windowSize - 3) {
                            if (PlaylistManager.hasNext()) {
                                val newWindow = PlaylistManager.getCurrentWindow()
                                val adjustedIndex = PlaylistManager.getCurrentIndexInWindow()

                                MediaPlaybackService.updatePlayerWindow(
                                    context,
                                    newWindow,
                                    adjustedIndex
                                )
                            }
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        when (repeatMode) {
                            RepeatMode.REPEAT_ONE -> {
                                mediaController!!.seekTo(0)
                                mediaController!!.play()
                            }
                            RepeatMode.REPEAT_ALL -> {
                                // ✅ USAR PlaylistManager ao invés de hasNextMediaItem
                                if (PlaylistManager.hasNext()) {
                                    coroutineScope.launch {
                                        when (val result = PlaylistManager.next()) {
                                            is PlaylistManager.NavigationResult.Success -> {
                                                if (result.needsWindowUpdate) {
                                                    val newWindow = PlaylistManager.getCurrentWindow()
                                                    val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                                                    MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                                                } else {
                                                    mediaController!!.seekToNextMediaItem()
                                                }
                                            }
                                            else -> {
                                                // Voltar pro início da playlist
                                                PlaylistManager.jumpTo(0)
                                                val newWindow = PlaylistManager.getCurrentWindow()
                                                MediaPlaybackService.updatePlayerWindow(context, newWindow, 0)
                                            }
                                        }
                                    }
                                } else {
                                    // Voltar pro início
                                    PlaylistManager.jumpTo(0)
                                    val newWindow = PlaylistManager.getCurrentWindow()
                                    MediaPlaybackService.updatePlayerWindow(context, newWindow, 0)
                                }
                            }
                            RepeatMode.NONE -> {
                                // ✅ USAR PlaylistManager
                                if (PlaylistManager.hasNext()) {
                                    coroutineScope.launch {
                                        when (val result = PlaylistManager.next()) {
                                            is PlaylistManager.NavigationResult.Success -> {
                                                if (result.needsWindowUpdate) {
                                                    val newWindow = PlaylistManager.getCurrentWindow()
                                                    val currentInWindow = PlaylistManager.getCurrentIndexInWindow()
                                                    MediaPlaybackService.updatePlayerWindow(context, newWindow, currentInWindow)
                                                } else {
                                                    mediaController!!.seekToNextMediaItem()
                                                }
                                            }
                                            else -> {
                                                Log.d("VideoPlayer", "Fim da playlist")
                                            }
                                        }
                                    }
                                } else {
                                    Log.d("VideoPlayer", "Fim da playlist, parando")
                                }
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

    LaunchedEffect(Unit) {
        val sessionManager = CastContext.getSharedInstance(context).sessionManager

        val listener = object : SessionManagerListener<Session> {
            override fun onSessionStarted(session: Session, sessionId: String) {
                interstitialManager.showAdOnCast {
                    // ✅ NOVO: Pegar playlist completa do PlaylistManager
                    val playlist = PlaylistManager.getFullPlaylist()
                    val titles = playlist.map { path ->
                        File(path.removePrefix("file://")).nameWithoutExtension
                    }
                    val currentIndex = PlaylistManager.getCurrentIndex()

                    // Parar MediaPlaybackService
                    MediaPlaybackService.stopService(context)

                    // Iniciar Cast com playlist completa
                    castManager.castPlaylist(playlist, titles, currentIndex)

                    // Fechar overlay
                    onDismiss()
                }
            }

            override fun onSessionEnded(session: Session, error: Int) {
                // Reseta flag para poder mostrar ad novamente na próxima sessão
                interstitialManager.resetCastAdFlag()
            }

            override fun onSessionResumed(session: Session, wasSuspended: Boolean) {}
            override fun onSessionSuspended(session: Session, reason: Int) {}
            override fun onSessionStarting(session: Session) {}
            override fun onSessionEnding(session: Session) {}
            override fun onSessionResuming(session: Session, sessionId: String) {}
            override fun onSessionResumeFailed(session: Session, error: Int) {}
            override fun onSessionStartFailed(session: Session, error: Int) {}
        }

        sessionManager.addSessionManagerListener(listener)
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
                        // Parar completamente
                        MediaPlaybackService.stopService(context)

                        // Recriar após 300ms
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            MediaPlaybackService.refreshPlayer(context)
                        }, 300)

                        isCasting = false
                        onDismiss()
                    }, 100)
                },
                onBack = onDismiss,
                onCurrentIndexChanged = { index ->
                    var castCurrentIndex = index // Atualizar índice rastreado
                },
                premiumManager = premiumManager
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

                // Slider invisível do lado esquerdo (BRILHO) - 70% da altura, centralizado
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.75f)
                        .fillMaxWidth(0.35f) // 35% da largura da tela
                        .align(Alignment.CenterStart)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    // Não fazer nada no início, aguardar o movimento
                                },
                                onDragEnd = {
                                    leftSliderActive = false
                                }
                            ) { change, dragAmount ->
                                // Verificar se o movimento é predominantemente vertical
                                val isVerticalMovement =
                                    kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x) * 2

                                if (isVerticalMovement) {
                                    if (!leftSliderActive) {
                                        // Primeira vez que detecta movimento vertical válido
                                        leftSliderActive = true
                                    }

                                    // Calcular mudança relativa baseada no movimento
                                    val sensitivity =
                                        0.001f // Ajuste a sensibilidade conforme necessário
                                    val brightnessChange =
                                        -dragAmount.y * sensitivity // Negativo porque Y cresce para baixo

                                    // Aplicar mudança ao valor atual
                                    val newBrightness =
                                        (currentBrightness + brightnessChange).coerceIn(0f, 1f)
                                    currentBrightness = newBrightness
                                    setBrightness(context, newBrightness)
                                    brightnessIndicator = newBrightness
                                }
                            }
                        }
                )

                // Slider invisível do lado direito (VOLUME) - 70% da altura, centralizado
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.75f)
                        .fillMaxWidth(0.35f) // 35% da largura da tela
                        .align(Alignment.CenterEnd)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    // Não fazer nada no início, aguardar o movimento
                                },
                                onDragEnd = {
                                    rightSliderActive = false
                                }
                            ) { change, dragAmount ->
                                // Verificar se o movimento é predominantemente vertical
                                val isVerticalMovement =
                                    kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x) * 2

                                if (isVerticalMovement) {
                                    if (!rightSliderActive) {
                                        // Primeira vez que detecta movimento vertical válido
                                        rightSliderActive = true
                                    }

                                    // Calcular mudança relativa baseada no movimento
                                    val sensitivity =
                                        0.1f // Ajuste a sensibilidade conforme necessário
                                    val volumeChange =
                                        -dragAmount.y * sensitivity // Negativo porque Y cresce para baixo

                                    // Aplicar mudança ao valor atual
                                    val newVolume =
                                        (currentVolume + volumeChange).coerceIn(0f, 100f).toInt()
                                    currentVolume = newVolume
                                    setVolume(context, newVolume)
                                    volumeIndicator = newVolume
                                }
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
                        isShuffleActive = isShuffleActive,  // ✅ NOVO
                        onShuffleToggle = {                  // ✅ NOVO
                            if (PlaylistManager.isShuffleEnabled) {
                                PlaylistManager.disableShuffle()
                            } else {
                                PlaylistManager.enableShuffle(PlaylistManager.getCurrentIndex())
                            }
                            isShuffleActive = PlaylistManager.isShuffleEnabled
                        },
                        isCasting = isCasting,
                        onCastClick = {

                            resetUITimer()
                        },
                        rotationMode = rotationMode,
                        onRotationModeChange = { newMode ->       // NOVO
                            rotationMode = newMode
                            applyRotation(mediaController?.videoSize)
                        },
                        // Legendas
                        hasSubtitles = availableSubtitles.isNotEmpty(),
                        subtitlesEnabled = selectedSubtitleTrack != null,
                        onSubtitlesClick = { showTrackSelectionDialog = true },
                        onPiPClick = { // ✅ ADICIONAR
                            controlsVisible = false
                            val activity = context.findActivity() as? MainActivity
                            activity?.enterPiPMode()
                        }
                    )
                }
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

        // Indicadores de volume e brilho centralizados abaixo dos controles de play
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 90.dp), // Posiciona abaixo dos controles de play
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Indicador de brilho
            AnimatedVisibility(
                visible = brightnessLevel != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.8f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Brightness6,
                            contentDescription = "Brightness",
                            tint = Color(0xFFFF9800), // Laranja
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "${(lastValidBrightness * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Indicador de volume
            AnimatedVisibility(
                visible = volumeLevel != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.8f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                lastValidVolume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                                lastValidVolume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = "Volume",
                            tint = Color(0xFF4CAF50), // Verde
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "$lastValidVolume%",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Indicador de seek (posição dinâmica conforme antes)
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

// Função auxiliar existente
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
