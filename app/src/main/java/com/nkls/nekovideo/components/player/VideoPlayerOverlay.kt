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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
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
import com.nkls.nekovideo.components.VideoTagsDialog
import com.nkls.nekovideo.components.helpers.CastManager
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.PlaylistNavigator
import com.nkls.nekovideo.components.helpers.TagEntity
import com.nkls.nekovideo.components.helpers.TagScope
import com.nkls.nekovideo.components.helpers.VideoTagStore
import com.nkls.nekovideo.components.player.PlayerUtils.findActivity
import com.nkls.nekovideo.components.settings.SettingsManager
import android.widget.Toast
import com.nkls.nekovideo.R
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private data class PreferredTrack(
    val label: String?,
    val language: String?,
    val mimeType: String?,
    val selectionFlags: Int,
    val roleFlags: Int
)

private fun Format.toPreferredTrack(): PreferredTrack {
    return PreferredTrack(
        label = label?.takeIf { it.isNotBlank() },
        language = language?.takeIf { it.isNotBlank() },
        mimeType = sampleMimeType?.takeIf { it.isNotBlank() },
        selectionFlags = selectionFlags,
        roleFlags = roleFlags
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("OpaqueUnitKey")
@Composable
fun VideoPlayerOverlay(
    isVisible: Boolean,
    canControlRotation: Boolean,
    onDismiss: () -> Unit,
    onManageTags: () -> Unit = {},
    onVideoDeleted: (String) -> Unit = {}
) {
    val autoplayDebugTag = "AutoplayDebug"

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = lifecycleOwner as? ComponentActivity
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var hasRefreshed by remember { mutableStateOf(false) }
    var overlayActuallyVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showVideoTagsDialog by remember { mutableStateOf(false) }
    var shouldResumeAfterTagsDialog by remember { mutableStateOf(false) }
    var shouldResumeAfterOverlayDialog by remember { mutableStateOf(false) }
    var isSpeedDialogOpen by remember { mutableStateOf(false) }
    var currentVideoPath by remember { mutableStateOf("") }
    var availableTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var commonSelectedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var availableTagsScope by remember { mutableStateOf<TagScope?>(null) }
    val tagChangeEvent by VideoTagStore.tagChangeEvent.collectAsState()
    LaunchedEffect(tagChangeEvent) {
        availableTagsScope = null
        availableTags = emptyList()
    }
    val castManager = remember { DLNACastManager.getInstance(context) }
    var isCasting by remember { mutableStateOf(castManager.isConnected) }
    var connectedDeviceName by remember { mutableStateOf(if (castManager.isConnected) castManager.connectedDeviceName else "") }
    var showCastDevicePicker by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<DLNACastManager.DLNADevice>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }

    // ✅ Observar estado de PIP da MainActivity (usando State para reatividade)
    val mainActivity = activity as? MainActivity
    val isInPiPMode by mainActivity?.isInPiPModeState ?: remember { mutableStateOf(false) }

    // Estados para controles customizados
    var controlsVisible by remember { mutableStateOf(false) }
    var hasLoadedVideo by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var currentVideoTitle by remember { mutableStateOf("") }
    var isSeekingActive by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(RepeatMode.NONE) }
    var playbackSpeed by remember { mutableStateOf(PlaybackSpeed.SPEED_1_00) }
    var currentPlaybackState by remember { mutableStateOf(Player.STATE_IDLE) }

    //Controle de rotação
    var rotationMode by remember { mutableStateOf(RotationMode.AUTO) }
    var lastValidOrientation by remember { mutableStateOf<Int?>(null) }
    var isWaitingForRotationGate by remember { mutableStateOf(false) }
    var resumeAfterRotationGate by remember { mutableStateOf(false) }
    var gatedMediaUri by remember { mutableStateOf<String?>(null) }
    var pendingAutoPlayOnReady by remember { mutableStateOf(false) }


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
    var preferredSubtitleTrack by remember { mutableStateOf<PreferredTrack?>(null) }
    var preferredAudioTrack by remember { mutableStateOf<PreferredTrack?>(null) }
    var subtitlesExplicitlyDisabled by remember { mutableStateOf(false) }
    var showTrackSelectionDialog by remember { mutableStateOf(false) }

    // PlayerView sem controles nativos

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
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

        // Não interferir na orientação enquanto cast estiver ativo
        if (isCasting) return

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

        // Evita reaplicar a mesma orientação e disparar trabalho extra no Activity.
        if (
            targetOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
            localActivity.requestedOrientation != targetOrientation
        ) {
            lastValidOrientation = targetOrientation
            localActivity.requestedOrientation = targetOrientation
        }
    }

    fun applyRotationForCurrentMode(videoSize: VideoSize? = null) {
        applyRotation(if (rotationMode == RotationMode.AUTO) videoSize else null)
    }

    fun currentMediaUri(controller: MediaController): String? {
        return controller.currentMediaItem?.localConfiguration?.uri?.toString()
    }

    fun shouldGatePlaybackForRotation(controller: MediaController): Boolean {
        return overlayActuallyVisible &&
            rotationMode == RotationMode.AUTO &&
            canControlRotation &&
            !isCasting &&
            currentMediaUri(controller) != null
    }

    fun finishRotationGateIfReady(controller: MediaController, videoSize: VideoSize? = controller.videoSize) {
        if (!isWaitingForRotationGate) return

        val mediaUri = currentMediaUri(controller)
        if (mediaUri == null || mediaUri != gatedMediaUri) {
            isWaitingForRotationGate = false
            resumeAfterRotationGate = false
            gatedMediaUri = null
            return
        }

        if (videoSize == null || videoSize.width <= 0 || videoSize.height <= 0) {
            return
        }

        applyRotation(videoSize)
        val shouldResume = resumeAfterRotationGate
        Log.d(
            autoplayDebugTag,
            "Overlay.finishRotationGateIfReady uri=$mediaUri shouldResume=$shouldResume playWhenReady=${controller.playWhenReady} isPlaying=${controller.isPlaying} state=${controller.playbackState}"
        )
        isWaitingForRotationGate = false
        resumeAfterRotationGate = false
        gatedMediaUri = null
        pendingAutoPlayOnReady = false

        coroutineScope.launch {
            delay(120)
            if (
                shouldResume &&
                mediaController === controller &&
                currentMediaUri(controller) == mediaUri &&
                !showDeleteDialog &&
                !showVideoTagsDialog &&
                !showCastDevicePicker &&
                !showTrackSelectionDialog &&
                !isSpeedDialogOpen
            ) {
                Log.d(autoplayDebugTag, "Overlay.finishRotationGateIfReady calling play uri=$mediaUri")
                controller.play()
            }
        }
    }

    fun beginRotationGateIfNeeded(controller: MediaController) {
        if (!shouldGatePlaybackForRotation(controller)) {
            Log.d(
                autoplayDebugTag,
                "Overlay.beginRotationGateIfNeeded skipped uri=${currentMediaUri(controller)} playWhenReady=${controller.playWhenReady} isPlaying=${controller.isPlaying} state=${controller.playbackState}"
            )
            isWaitingForRotationGate = false
            resumeAfterRotationGate = false
            gatedMediaUri = null
            applyRotationForCurrentMode(controller.videoSize)
            return
        }

        val mediaUri = currentMediaUri(controller) ?: return
        val videoSize = controller.videoSize
        if (videoSize.width > 0 && videoSize.height > 0) {
            Log.d(
                autoplayDebugTag,
                "Overlay.beginRotationGateIfNeeded immediateRotation uri=$mediaUri playWhenReady=${controller.playWhenReady} isPlaying=${controller.isPlaying}"
            )
            isWaitingForRotationGate = false
            resumeAfterRotationGate = false
            gatedMediaUri = null
            applyRotation(videoSize)
            return
        }

        gatedMediaUri = mediaUri
        isWaitingForRotationGate = true
        resumeAfterRotationGate = controller.playWhenReady
        Log.d(
            autoplayDebugTag,
            "Overlay.beginRotationGateIfNeeded gating uri=$mediaUri resumeAfter=$resumeAfterRotationGate playWhenReady=${controller.playWhenReady} isPlaying=${controller.isPlaying} state=${controller.playbackState}"
        )
    }

    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        mediaController?.let { controller ->
            val trackFormat = availableSubtitles[groupIndex].getTrackFormat(trackIndex)
            val trackSelectionParameters = controller.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    TrackSelectionOverride(
                        availableSubtitles[groupIndex].mediaTrackGroup,
                        trackIndex
                    )
                )
                .build()

            controller.trackSelectionParameters = trackSelectionParameters
            selectedSubtitleTrack = groupIndex
            preferredSubtitleTrack = trackFormat.toPreferredTrack()
            subtitlesExplicitlyDisabled = false
        }
    }

    fun disableSubtitles() {
        mediaController?.let { controller ->
            controller.trackSelectionParameters = controller.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            selectedSubtitleTrack = null
            preferredSubtitleTrack = null
            subtitlesExplicitlyDisabled = true
        }
    }

    fun PreferredTrack.matches(format: Format): Boolean {
        val normalizedLabel = label?.trim()?.lowercase()
        val normalizedLanguage = language?.trim()?.lowercase()
        val normalizedMimeType = mimeType?.trim()?.lowercase()

        val formatLabel = format.label?.trim()?.lowercase()
        val formatLanguage = format.language?.trim()?.lowercase()
        val formatMimeType = format.sampleMimeType?.trim()?.lowercase()
        val flagsMatch = selectionFlags == format.selectionFlags
        val rolesMatch = roleFlags == format.roleFlags

        val baseMatch = when {
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

        return baseMatch && flagsMatch && rolesMatch
    }

    fun findMatchingTrack(groups: List<Tracks.Group>, preferredTrack: PreferredTrack?): Pair<Int, Int>? {
        if (preferredTrack == null) return null

        groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (preferredTrack.matches(group.getTrackFormat(trackIndex))) {
                    return groupIndex to trackIndex
                }
            }
        }

        return null
    }

    fun reapplyPreferredTracks(controller: MediaController) {
        val subtitleMatch = findMatchingTrack(availableSubtitles, preferredSubtitleTrack)
        if (subtitleMatch != null) {
            val (groupIndex, trackIndex) = subtitleMatch
            val updatedParams = controller.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    TrackSelectionOverride(
                        availableSubtitles[groupIndex].mediaTrackGroup,
                        trackIndex
                    )
                )
                .build()
            controller.trackSelectionParameters = updatedParams
            selectedSubtitleTrack = groupIndex
        } else if (preferredSubtitleTrack != null || subtitlesExplicitlyDisabled) {
            controller.trackSelectionParameters = controller.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            selectedSubtitleTrack = null
        }

        val audioMatch = findMatchingTrack(availableAudioTracks, preferredAudioTrack)
        if (audioMatch != null) {
            val (groupIndex, trackIndex) = audioMatch
            val updatedParams = controller.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(
                        availableAudioTracks[groupIndex].mediaTrackGroup,
                        trackIndex
                    )
                )
                .build()
            controller.trackSelectionParameters = updatedParams
            selectedAudioTrack = groupIndex
        } else if (preferredAudioTrack != null) {
            selectedAudioTrack = null
        }
    }

    fun checkAvailableTracks(controller: MediaController) {
        val tracks = controller.currentTracks
        val subtitleGroups = mutableListOf<Tracks.Group>()
        val audioGroups = mutableListOf<Tracks.Group>()
        var detectedSubtitleTrack: PreferredTrack? = null
        var detectedAudioTrack: PreferredTrack? = null
        var detectedSubtitleGroupIndex: Int? = null
        var detectedAudioGroupIndex: Int? = null


        for (trackGroup in tracks.groups) {

            if (trackGroup.type == C.TRACK_TYPE_TEXT) {
                val groupIndex = subtitleGroups.size
                subtitleGroups.add(trackGroup)
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        detectedSubtitleTrack = trackGroup.getTrackFormat(i).toPreferredTrack()
                        detectedSubtitleGroupIndex = groupIndex
                    }
                }
            } else if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                val groupIndex = audioGroups.size
                audioGroups.add(trackGroup)
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        detectedAudioTrack = trackGroup.getTrackFormat(i).toPreferredTrack()
                        detectedAudioGroupIndex = groupIndex
                    }
                }
            }
        }

        availableSubtitles = subtitleGroups
        availableAudioTracks = audioGroups
        subtitlesExplicitlyDisabled = controller.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        selectedSubtitleTrack = detectedSubtitleGroupIndex
        selectedAudioTrack = detectedAudioGroupIndex
        if (detectedSubtitleTrack != null) {
            preferredSubtitleTrack = detectedSubtitleTrack
        }
        if (detectedAudioTrack != null) {
            preferredAudioTrack = detectedAudioTrack
        }
        reapplyPreferredTracks(controller)

    }

    fun setupController(controller: MediaController) {
        mediaController = controller
        playerView.player = controller
        currentPlaybackState = controller.playbackState
        hasLoadedVideo = controller.currentMediaItem != null
        pendingAutoPlayOnReady = controller.playWhenReady && controller.playbackState != Player.STATE_READY
        Log.d(
            autoplayDebugTag,
            "Overlay.setupController uri=${currentMediaUri(controller)} pendingAutoPlay=$pendingAutoPlayOnReady playWhenReady=${controller.playWhenReady} isPlaying=${controller.isPlaying} state=${controller.playbackState}"
        )
        repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.REPEAT_ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.REPEAT_ONE
            else -> RepeatMode.NONE
        }
        controller.setPlaybackSpeed(playbackSpeed.value)

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

    }

    fun getTagScopeForPath(videoPath: String): TagScope {
        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val nekoPrivatePath = FilesManager.SecureStorage.getNekoPrivateFolderPath()
        return if (
            videoPath.startsWith(secureFolderPath) ||
            videoPath.startsWith(nekoPrivatePath) ||
            videoPath.contains("/.private/") ||
            videoPath.contains("/secure/") ||
            videoPath.contains(".secure_videos") ||
            videoPath.endsWith(".secure_videos") ||
            File(videoPath).parent?.let { FolderLockManager.isLocked(it) } == true
        ) {
            TagScope.PRIVATE
        } else {
            TagScope.NORMAL
        }
    }

    fun resumePlaybackAfterTagsDialog() {
        if (shouldResumeAfterTagsDialog) {
            mediaController?.play()
            shouldResumeAfterTagsDialog = false
        }
    }

    fun pausePlaybackForOverlayDialog() {
        shouldResumeAfterOverlayDialog = mediaController?.isPlaying == true
        mediaController?.pause()
    }

    fun resumePlaybackAfterOverlayDialog() {
        if (shouldResumeAfterOverlayDialog) {
            mediaController?.play()
            shouldResumeAfterOverlayDialog = false
        }
    }

    fun isPlaybackBlockedByDialog(): Boolean {
        return showDeleteDialog ||
            showVideoTagsDialog ||
            showCastDevicePicker ||
            showTrackSelectionDialog ||
            isSpeedDialogOpen
    }

    if (showVideoTagsDialog && currentVideoPath.isNotEmpty()) {
        VideoTagsDialog(
            selectedVideoCount = 1,
            tags = availableTags,
            initialSelectedTagIds = commonSelectedTagIds,
            onDismiss = {
                showVideoTagsDialog = false
                resumePlaybackAfterTagsDialog()
            },
            onManageTags = {
                showVideoTagsDialog = false
                shouldResumeAfterTagsDialog = false
                availableTagsScope = null
                onManageTags()
            },
            onSave = { selectedTagIds ->
                val targetVideo = currentVideoPath
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    VideoTagStore.syncCommonTagsForVideos(
                        context = context,
                        videoPaths = listOf(targetVideo),
                        initiallyCommonTagIds = commonSelectedTagIds,
                        selectedTagIds = selectedTagIds
                    )
                }
                commonSelectedTagIds = selectedTagIds
                Toast.makeText(context, context.getString(R.string.video_tags_add_success), Toast.LENGTH_SHORT).show()
                Result.success(Unit)
            }
        )
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

            applyRotationForCurrentMode(mediaController!!.videoSize)
        }
    }

    // Efeito para atualizar posição do vídeo
    LaunchedEffect(mediaController, isSeekingActive) {
        if (mediaController != null && !isSeekingActive) {
            while (overlayActuallyVisible) {
                currentPlaybackState = mediaController!!.playbackState
                hasLoadedVideo = mediaController!!.currentMediaItem != null
                currentPosition = mediaController!!.currentPosition
                duration = mediaController!!.duration.takeIf { it > 0 } ?: 0L
                isPlaying = mediaController!!.isPlaying
                delay(100)
            }
        }
    }

    LaunchedEffect(Unit) {
        val savedSpeed = SettingsManager.getPlaybackSpeed(context)
        playbackSpeed = PlaybackSpeed.entries.find { it.value == savedSpeed } ?: PlaybackSpeed.SPEED_1_00
        mediaController?.setPlaybackSpeed(playbackSpeed.value)
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible && isPlaying) {
            uiTimer = 4
            while (uiTimer > 0 && controlsVisible && isPlaying) {
                delay(1000)
                if (!isSeekingActive) {
                    uiTimer -= 1
                }
            }
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
            val trackFormat = availableAudioTracks[groupIndex].getTrackFormat(trackIndex)
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
            preferredAudioTrack = trackFormat.toPreferredTrack()
        }
    }

    // Dialog de confirmação para deletar (movido para Dialogs.kt)
    if (showDeleteDialog) {
        com.nkls.nekovideo.components.DeleteVideoDialog(
            videoPath = currentVideoPath,
            onDismiss = {
                showDeleteDialog = false
                resumePlaybackAfterOverlayDialog()
            },
            onConfirm = {
                showDeleteDialog = false
                shouldResumeAfterOverlayDialog = false
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
            onDismiss = {
                showTrackSelectionDialog = false
                resumePlaybackAfterOverlayDialog()
            },
            onOpen = {
                pausePlaybackForOverlayDialog()
                resetUITimer()
            },
            onClose = {
                resumePlaybackAfterOverlayDialog()
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
            isWaitingForRotationGate = false
            resumeAfterRotationGate = false
            gatedMediaUri = null
            hasLoadedVideo = false
            controlsVisible = false
        }
    }

    // Listener para mudanças com melhorias de UX
    DisposableEffect(mediaController, overlayActuallyVisible, repeatMode) {
        var listener: Player.Listener? = null

        if (overlayActuallyVisible && mediaController != null) {
            listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!overlayActuallyVisible) return
                    if (isWaitingForRotationGate) {
                        finishRotationGateIfReady(mediaController!!, videoSize)
                    } else if (rotationMode == RotationMode.AUTO) {
                        applyRotation(videoSize)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!overlayActuallyVisible) return

                    val wasControlsVisible = controlsVisible
                    currentPlaybackState = mediaController!!.playbackState
                    hasLoadedVideo = mediaController!!.currentMediaItem != null

                    mediaItem?.localConfiguration?.uri?.let { uri ->
                        val uriStr = uri.toString()
                        pendingAutoPlayOnReady = mediaController!!.playWhenReady
                        Log.d(
                            autoplayDebugTag,
                            "Overlay.onMediaItemTransition reason=$reason uri=$uriStr pendingAutoPlay=$pendingAutoPlayOnReady playWhenReady=${mediaController!!.playWhenReady} isPlaying=${mediaController!!.isPlaying} state=${mediaController!!.playbackState}"
                        )
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

                    beginRotationGateIfNeeded(mediaController!!)

                    controlsVisible = wasControlsVisible

                    if (wasControlsVisible) {
                        resetUITimer()
                    }

                    if (isPlaybackBlockedByDialog() && mediaController!!.isPlaying) {
                        Log.d(autoplayDebugTag, "Overlay.onMediaItemTransition pausingBecauseDialog")
                        mediaController!!.pause()
                    }

                    mediaController!!.setPlaybackSpeed(playbackSpeed.value)

                    // ✅ REMOVIDO: A atualização de window agora é centralizada no MediaPlaybackService
                    // Isso evita duplicação de chamadas e dessincronização
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    currentPlaybackState = playbackState
                    Log.d(
                        autoplayDebugTag,
                        "Overlay.onPlaybackStateChanged state=$playbackState pendingAutoPlay=$pendingAutoPlayOnReady waitingRotation=$isWaitingForRotationGate playWhenReady=${mediaController!!.playWhenReady} isPlaying=${mediaController!!.isPlaying} blocked=${isPlaybackBlockedByDialog()} uri=${currentMediaUri(mediaController!!)}"
                    )
                    if (playbackState == Player.STATE_READY) {
                        hasLoadedVideo = mediaController!!.currentMediaItem != null
                        finishRotationGateIfReady(mediaController!!)
                        if (pendingAutoPlayOnReady && !isWaitingForRotationGate && !isPlaybackBlockedByDialog()) {
                            pendingAutoPlayOnReady = false
                            Log.d(autoplayDebugTag, "Overlay.onPlaybackStateChanged calling play on READY")
                            mediaController!!.play()
                        }
                        if (isPlaybackBlockedByDialog() && mediaController!!.isPlaying) {
                            Log.d(autoplayDebugTag, "Overlay.onPlaybackStateChanged pausingBecauseDialogOnReady")
                            mediaController!!.pause()
                        }
                    } else if (playbackState == Player.STATE_IDLE) {
                        hasLoadedVideo = mediaController!!.currentMediaItem != null
                    }

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
            beginRotationGateIfNeeded(mediaController!!)
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
                shouldResumeAfterOverlayDialog = false
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
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            },
            onDismiss = {
                showCastDevicePicker = false
                resumePlaybackAfterOverlayDialog()
            }
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
                    .pointerInput(hasLoadedVideo, currentPlaybackState) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val downTime = System.currentTimeMillis()
                            val downX = down.position.x
                            val screenWidth = size.width.toFloat()

                            val edgeMargin = size.width * 0.05f
                            val isNearEdge = down.position.x < edgeMargin || down.position.x > size.width - edgeMargin || down.position.y > size.height - 100.dp.toPx()

                            val touchSlopResult = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                if (!isNearEdge) change.consume()
                            }

                            if (touchSlopResult != null) {
                                if (!isNearEdge && !controlsVisible) {
                                    val initialPosition = mediaController?.currentPosition ?: 0L
                                    val videoDuration = mediaController?.duration?.takeIf { it > 0 } ?: 0L
                                    val seekSensitivity = screenWidth / 30f
                                    var totalDragX = touchSlopResult.position.x - downX

                                    var lastSeekSeconds = 0

                                    do {
                                        val seekSeconds = (totalDragX / seekSensitivity).toInt()

                                        if (seekSeconds != lastSeekSeconds) {
                                            lastSeekSeconds = seekSeconds
                                            seekIndicator = if (seekSeconds > 0) "+${seekSeconds}s" else "${seekSeconds}s"
                                            seekSide = if (seekSeconds > 0) Alignment.CenterEnd else Alignment.CenterStart
                                        }

                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        totalDragX = change.position.x - downX
                                        change.consume()
                                    } while (change.pressed)

                                    val finalSeekSeconds = (totalDragX / seekSensitivity).toInt()

                                    if (finalSeekSeconds != 0 && duration > 0) {
                                        mediaController?.let { controller ->
                                            val newPosition = (initialPosition + finalSeekSeconds * 1000L)
                                                .coerceIn(0, videoDuration)
                                            controller.seekTo(newPosition)
                                        }
                                    }
                                }
                            } else {
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
                                    val doubleTapSeek =
                                        SettingsManager.getDoubleTapSeek(context) * 1000L

                                    mediaController?.let { controller ->
                                        val currentPos = controller.currentPosition

                                        if (downX < screenWidth / 2) {
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
                        }
                    }
            ) {
                // PlayerView em background
                AndroidView(
                    factory = { playerView },
                    modifier = Modifier.fillMaxSize()
                )

                if (isWaitingForRotationGate) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }

                // Indicadores visuais (apenas seek - brilho/volume removidos)
                GestureIndicators(
                    seekInfo = seekIndicator,
                    seekAlignment = seekSide
                )

                if (hasLoadedVideo && currentPlaybackState == Player.STATE_BUFFERING && !isSeekingActive) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }

                // Interface customizada com ícones de volume e brilho
                AnimatedVisibility(
                    visible = (controlsVisible || isSeekingActive) && !isInPiPMode && hasLoadedVideo && (currentPlaybackState != Player.STATE_BUFFERING || isSeekingActive),
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    CustomVideoControls(
                        mediaController = mediaController,
                        currentPosition = currentPosition,
                        duration = duration,
                        isPlaying = isPlaying,
                        videoTitle = currentVideoTitle,
                        onSeekStart = {
                            isSeekingActive = true
                            controlsVisible = true
                            resetUITimer()
                            mediaController?.pause()
                        },
                        onSeekEnd = {
                            isSeekingActive = false
                            controlsVisible = true
                            resetUITimer()
                            mediaController?.play()
                        },
                        onDeleteClick = {
                            pausePlaybackForOverlayDialog()
                            showDeleteDialog = true
                        },
                        onTagsClick = {
                            if (currentVideoPath.isNotEmpty()) {
                                shouldResumeAfterTagsDialog = mediaController?.isPlaying == true
                                mediaController?.pause()
                                coroutineScope.launch {
                                    val scope = getTagScopeForPath(currentVideoPath)
                                    coroutineScope {
                                        val commonTagsDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                                            VideoTagStore.getCommonTagIds(context, listOf(currentVideoPath), scope)
                                        }
                                        if (availableTagsScope != scope || availableTags.isEmpty()) {
                                            availableTags = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                VideoTagStore.getAllTags(context, scope)
                                            }
                                            availableTagsScope = scope
                                        }
                                        commonSelectedTagIds = commonTagsDeferred.await()
                                    }
                                    showVideoTagsDialog = true
                                }
                            }
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
                        playbackSpeed = playbackSpeed,
                        onPlaybackSpeedChange = { newSpeed ->
                            playbackSpeed = newSpeed
                            SettingsManager.setPlaybackSpeed(context, newSpeed.value)
                            mediaController?.setPlaybackSpeed(newSpeed.value)
                        },
                        onSpeedDialogOpen = {
                            isSpeedDialogOpen = true
                            pausePlaybackForOverlayDialog()
                            resetUITimer()
                        },
                        onSpeedDialogClose = {
                            isSpeedDialogOpen = false
                            resumePlaybackAfterOverlayDialog()
                        },
                        isCasting = isCasting,
                        onCastClick = {
                            pausePlaybackForOverlayDialog()
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
                            applyRotationForCurrentMode(mediaController?.videoSize)
                        },
                        // Legendas
                        hasSubtitles = availableSubtitles.isNotEmpty(),
                        subtitlesEnabled = selectedSubtitleTrack != null,
                        onSubtitlesClick = {
                            pausePlaybackForOverlayDialog()
                            showTrackSelectionDialog = true
                        },
                        onPiPClick = {
                            controlsVisible = false
                            (activity as? MainActivity)?.enterPiPMode()
                        }
                    )
                }
            }
        }
    }
}

// GestureIndicators, setVolume, setBrightness e findActivity movidos para arquivos separados
// GestureIndicators.kt, PlayerUtils.kt
