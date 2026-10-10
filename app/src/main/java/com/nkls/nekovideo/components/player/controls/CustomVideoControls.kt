package com.nkls.nekovideo.components.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.AppBottomSheet
import androidx.media3.session.MediaController
import com.nkls.nekovideo.MediaPlaybackService
import com.nkls.nekovideo.components.helpers.FilesManager
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.PlaylistNavigator
import com.nkls.nekovideo.components.helpers.DLNACastManager
import com.nkls.nekovideo.components.helpers.XorMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import java.io.File

// Design tokens — sistema de cores unificado para os controles
private val CtrlBtnBg = Color.White.copy(alpha = 0.1f)
private val CtrlBtnBgActive = Color.White.copy(alpha = 0.18f)
private val CtrlIconOn = Color.White
private val CtrlIconOff = Color.White.copy(alpha = 0.38f)
private val CtrlDrawerBg = Color(0xFF15181D).copy(alpha = 0.985f)
private val CtrlDrawerBorder = Color.White.copy(alpha = 0.08f)
private val CtrlDrawerItemBg = Color.White.copy(alpha = 0.045f)
private val CtrlDrawerItemActiveBg = Color.White.copy(alpha = 0.09f)
private val CtrlDrawerItemDestructiveBg = Color(0xFFB84040).copy(alpha = 0.12f)
private val CtrlDrawerIconBg = Color.White.copy(alpha = 0.07f)
private val CtrlDrawerIconActiveBg = Color(0xFF4CAF50).copy(alpha = 0.18f)
private val CtrlDrawerDivider = Color.White.copy(alpha = 0.07f)
private val CtrlDrawerDeleteTint = Color(0xFFFF8A80)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun CustomVideoControls(
    mediaController: MediaController?,
    castManager: DLNACastManager,
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    hideTransportControls: Boolean = false,
    videoTitle: String,
    videoPath: String = "",
    allowTimelinePreview: Boolean = false,
    onSeekStart: () -> Unit,
    onSeekEnd: (wasPlayingBeforeSeek: Boolean) -> Unit,
    onDeleteClick: () -> Unit,
    onTagsClick: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    resetUITimer: () -> Unit,
    repeatMode: RepeatMode,
    onRepeatModeChange: (RepeatMode) -> Unit,
    playbackSpeed: PlaybackSpeed,
    onPlaybackSpeedChange: (PlaybackSpeed) -> Unit,
    onSpeedDialogOpen: () -> Unit,
    onSpeedDialogClose: () -> Unit,
    isCasting: Boolean,
    onDisconnectClick: () -> Unit,
    currentVideoTagCount: Int,
    onCastClick: (Boolean) -> Unit,
    rotationMode: RotationMode,
    onRotationModeChange: (RotationMode) -> Unit,
    hasSubtitles: Boolean,
    subtitlesEnabled: Boolean,
    onSubtitlesClick: () -> Unit,
    onPiPClick: () -> Unit,
    sleepTimerActive: Boolean,
    sleepTimerEndAtMs: Long,
    onSleepTimerStarted: (Long) -> Unit,
    onSleepTimerCleared: () -> Unit,
    onSleepTimerConfirmed: () -> Unit
) {
    val controller = mediaController
    if (controller == null && !isCasting) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = LocalConfiguration.current
    val preferences = remember {
        context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
    }
    var autoPiPEnabled by remember {
        mutableStateOf(preferences.getBoolean("auto_pip", true))
    }
    var showActionDrawer by remember { mutableStateOf(false) }
    var resumeAfterActionDrawer by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSleepTimerStatusDialog by remember { mutableStateOf(false) }
    var resumeAfterSleepTimerStatusDialog by remember { mutableStateOf(false) }
    var sleepTimerRemainingMs by remember { mutableStateOf(0L) }
    var tempPosition by remember(videoPath) { mutableLongStateOf(currentPosition) }
    var isDragging by remember(videoPath) { mutableStateOf(false) }
    var scrubPreviewBitmap by remember(videoPath) { mutableStateOf<Bitmap?>(null) }
    var isLoadingScrubPreview by remember(videoPath) { mutableStateOf(false) }
    var wasPlayingBeforeTimelineSeek by remember { mutableStateOf(false) }
    val finishTimelineSeek = rememberUpdatedState<() -> Unit> {
        Log.d(
            "TIMELINE",
            "finish requested; dragging=$isDragging casting=$isCasting " +
                "wasPlayingBefore=$wasPlayingBeforeTimelineSeek controllerPlaying=${controller?.isPlaying} " +
                "position=$tempPosition"
        )
        if (isDragging) {
            isDragging = false
            isLoadingScrubPreview = false
            if (isCasting) {
                castManager.seekTo(
                    posMs = tempPosition,
                    resumePlaybackIfPaused = true
                )
            }
            Log.d(
                "TIMELINE",
                "seek finalized; casting=$isCasting resumeLocal=$wasPlayingBeforeTimelineSeek " +
                    "controllerPlaying=${controller?.isPlaying} position=$tempPosition"
            )
            onSeekEnd(wasPlayingBeforeTimelineSeek)
        } else {
            Log.d("TIMELINE", "finish ignored; no active drag")
        }
    }
    val sleepTimerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sleepTimerStatusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sleepTimerOptionsMs = remember {
        buildList {
            if (BuildConfig.DEBUG) {
                add(15_000L)
            }
            addAll((1..18).map { it * 5L * 60_000L })
        }
    }
    val defaultSleepTimerDurationMs = 30L * 60_000L
    var sleepTimerOptionIndex by remember {
        mutableStateOf(sleepTimerOptionsMs.indexOf(defaultSleepTimerDurationMs).coerceAtLeast(0).toFloat())
    }
    val actionDrawerScrollState = rememberScrollState()

    LaunchedEffect(currentPosition, isDragging) {
        if (!isDragging) tempPosition = currentPosition
    }

    LaunchedEffect(videoPath, allowTimelinePreview, duration) {
        scrubPreviewBitmap = null
        isLoadingScrubPreview = false
        if (!allowTimelinePreview || videoPath.isBlank() || duration <= 0L) return@LaunchedEffect

        val retriever = MediaMetadataRetriever()
        var lockedDataSource: XorMediaDataSource? = null
        try {
            withContext(Dispatchers.IO) {
                val xorKey = LockedPlaybackSession.getXorKeyForFile(videoPath)
                if (xorKey != null) {
                    XorMediaDataSource(File(videoPath), xorKey).also { dataSource ->
                        lockedDataSource = dataSource
                        retriever.setDataSource(dataSource)
                    }
                } else {
                    retriever.setDataSource(videoPath)
                }
            }
            snapshotFlow { if (isDragging) tempPosition else null }
                .map { position -> position?.coerceIn(0L, duration)?.div(1_000L)?.times(1_000L) }
                .distinctUntilChanged()
                .sample(180L)
                .collectLatest { framePositionMs ->
                    if (framePositionMs == null) {
                        isLoadingScrubPreview = false
                        return@collectLatest
                    }
                    isLoadingScrubPreview = scrubPreviewBitmap == null
                    val bitmap = withContext(Dispatchers.IO) {
                        retriever.getScaledFrameAtTime(
                            framePositionMs * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            240,
                            135
                        )
                    }
                    if (bitmap != null) scrubPreviewBitmap = bitmap
                    isLoadingScrubPreview = false
                }
        } catch (exception: Exception) {
            isLoadingScrubPreview = false
            Log.d("TimelinePreview", "Could not load preview frame for $videoPath", exception)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { retriever.release() }
                runCatching { lockedDataSource?.close() }
            }
        }
    }

    val currentGlobalIndex = if (isCasting) castManager.currentPlaylistIndex
        else controller?.currentMediaItemIndex ?: 0
    val totalPlaylistSize = if (isCasting) castManager.playlistSize else PlaylistManager.getTotalSize()

    fun pauseActivePlayback() {
        if (isCasting) castManager.pause() else controller?.pause()
    }

    fun resumeActivePlayback() {
        if (isCasting) castManager.play() else controller?.play()
    }
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val maxDrawerWidth = configuration.screenWidthDp.dp * if (isLandscape) 0.25f else 0.5f
    val preferredDrawerWidth = if (isLandscape) 220.dp else 240.dp
    val drawerWidth = minOf(preferredDrawerWidth, maxDrawerWidth)

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "auto_pip") {
                autoPiPEnabled = prefs.getBoolean("auto_pip", true)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    fun openActionDrawer() {
        resumeAfterActionDrawer = if (isCasting) isPlaying else controller?.isPlaying == true
        if (resumeAfterActionDrawer) {
            pauseActivePlayback()
        }
        showActionDrawer = true
        resetUITimer()
    }

    fun closeActionDrawer(shouldResumePlayback: Boolean) {
        showActionDrawer = false
        if (shouldResumePlayback && resumeAfterActionDrawer) {
            resumeActivePlayback()
        }
        resumeAfterActionDrawer = false
    }

    fun openSleepTimerStatusDialog() {
        resumeAfterSleepTimerStatusDialog = if (isCasting) isPlaying else controller?.isPlaying == true
        if (resumeAfterSleepTimerStatusDialog) {
            pauseActivePlayback()
        }
        showSleepTimerStatusDialog = true
        resetUITimer()
    }

    fun closeSleepTimerStatusDialog(shouldResumePlayback: Boolean) {
        showSleepTimerStatusDialog = false
        if (shouldResumePlayback && resumeAfterSleepTimerStatusDialog) {
            resumeActivePlayback()
        }
        resumeAfterSleepTimerStatusDialog = false
    }

    fun closeSleepTimerDialog() {
        showSleepTimerDialog = false
        if (resumeAfterActionDrawer) {
            resumeActivePlayback()
            resumeAfterActionDrawer = false
        }
    }

    LaunchedEffect(sleepTimerActive, sleepTimerEndAtMs) {
        if (!sleepTimerActive || sleepTimerEndAtMs <= 0L) {
            sleepTimerRemainingMs = 0L
            showSleepTimerStatusDialog = false
            resumeAfterSleepTimerStatusDialog = false
            return@LaunchedEffect
        }

        while (sleepTimerActive) {
            val remaining = (sleepTimerEndAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            sleepTimerRemainingMs = remaining
            if (remaining <= 0L) {
                showSleepTimerStatusDialog = false
                resumeAfterSleepTimerStatusDialog = false
                onSleepTimerCleared()
                break
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    val sleepPulse by rememberInfiniteTransition(label = "sleepTimerPulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "sleepTimerPulseAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (!showSleepTimerDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )

            // Header com gradiente
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .background(CtrlBtnBg, CircleShape)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.player_back),
                            tint = CtrlIconOn,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Text(
                        text = videoTitle,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isCasting) {
                            IconButton(
                                onClick = {
                                    onCastClick(isPlaying || controller?.isPlaying == true)
                                    resetUITimer()
                                },
                                modifier = Modifier.background(CtrlBtnBg, CircleShape).size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = stringResource(R.string.player_cast),
                                    tint = CtrlIconOn,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (!isCasting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !autoPiPEnabled) {
                            IconButton(
                                onClick = {
                                    resetUITimer()
                                    onPiPClick()
                                },
                                modifier = Modifier
                                    .background(CtrlBtnBg, CircleShape)
                                    .size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPicture,
                                    contentDescription = stringResource(R.string.player_picture_in_picture),
                                    tint = CtrlIconOn,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (sleepTimerActive && !isCasting) {
                            IconButton(
                                onClick = { openSleepTimerStatusDialog() },
                                modifier = Modifier
                                    .background(CtrlBtnBgActive, CircleShape)
                                    .graphicsLayer { alpha = sleepPulse }
                                    .size(44.dp)
                            ) {
                            Icon(
                                imageVector = Icons.Default.NightsStay,
                                contentDescription = stringResource(R.string.player_sleep_timer_active_content_description),
                                tint = CtrlIconOn,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        }

                        if (isCasting) {
                            IconButton(
                                onClick = onDisconnectClick,
                                modifier = Modifier.background(CtrlBtnBg, CircleShape).size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CastConnected,
                                    contentDescription = stringResource(R.string.cast_disconnect_confirm),
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { openActionDrawer() },
                                modifier = Modifier.background(CtrlBtnBg, CircleShape).size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.player_more_actions),
                                    tint = CtrlIconOn,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Controles centrais
            if (!hideTransportControls) Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        resetUITimer()
                        if (isCasting) castManager.previous() else PlaylistNavigator.previous(context)
                    },
                    modifier = Modifier
                        .background(CtrlBtnBg, CircleShape)
                        .size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.player_previous),
                        tint = CtrlIconOn,
                        modifier = Modifier.size(30.dp)
                    )
                }

                IconButton(
                    onClick = {
                        if (isPlaying) {
                            pauseActivePlayback()
                        } else {
                            resumeActivePlayback()
                        }
                    },
                    modifier = Modifier
                        .background(Color.White, CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .size(70.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = {
                        resetUITimer()
                        if (isCasting) castManager.next() else PlaylistNavigator.next(context)
                    },
                    modifier = Modifier
                        .background(CtrlBtnBg, CircleShape)
                        .size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.player_next),
                        tint = CtrlIconOn,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // Bottom controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                // Seek bar
                if (duration > 0) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        val previewWidth = 144.dp
                        // Ignore movements smaller than five dp so resting-finger jitter does not
                        // constantly change the preview or the final seek point.
                        val timelineDeadZoneMs = kotlin.math.ceil(
                            duration.toDouble() * 5f / maxWidth.value.coerceAtLeast(1f)
                        ).toLong().coerceAtLeast(1L)
                        Slider(
                            value = if (isDragging) tempPosition.toFloat() else currentPosition.toFloat(),
                            onValueChange = { newValue ->
                                val requestedPosition = newValue.toLong()
                                val positionDelta = kotlin.math.abs(requestedPosition - tempPosition)
                                val shouldAcceptPosition =
                                    !isDragging || positionDelta >= timelineDeadZoneMs

                                if (!isDragging) {
                                    wasPlayingBeforeTimelineSeek =
                                        controller?.isPlaying == true || controller?.playWhenReady == true || isPlaying
                                    Log.d(
                                        "TIMELINE",
                                        "drag started; casting=$isCasting controllerPlaying=${controller?.isPlaying} " +
                                            "controllerPlayWhenReady=${controller?.playWhenReady} " +
                                            "uiPlaying=$isPlaying capturedPlaying=$wasPlayingBeforeTimelineSeek " +
                                            "position=$tempPosition requested=$requestedPosition deadZoneMs=$timelineDeadZoneMs"
                                    )
                                    isDragging = true
                                    isLoadingScrubPreview = allowTimelinePreview && scrubPreviewBitmap == null
                                    onSeekStart()
                                }
                                if (shouldAcceptPosition) {
                                    tempPosition = requestedPosition
                                    if (!isCasting) controller?.seekTo(tempPosition)
                                }
                            },
                            onValueChangeFinished = {
                                Log.d(
                                    "TIMELINE",
                                    "Slider.onValueChangeFinished; dragging=$isDragging " +
                                        "controllerPlaying=${controller?.isPlaying} position=$tempPosition"
                                )
                                finishTimelineSeek.value()
                            },
                            valueRange = 0f..duration.toFloat(),
                            thumb = {
                                Box(
                                    modifier = Modifier
                                        .size(if (isDragging) 16.dp else 12.dp)
                                        .background(Color.White, CircleShape)
                                )
                            },
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    sliderState = sliderState,
                                    modifier = Modifier.height(3.dp),
                                    colors = SliderDefaults.colors(
                                        activeTrackColor = Color.White,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                    ),
                                    thumbTrackGapSize = 0.dp,
                                    trackInsideCornerSize = 0.dp,
                                    drawStopIndicator = null
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                        )

                        val previewBitmap = scrubPreviewBitmap
                        if (allowTimelinePreview && isDragging) {
                            val fraction = (tempPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            val previewX = (maxWidth * fraction - previewWidth / 2)
                                .coerceIn(0.dp, (maxWidth - previewWidth).coerceAtLeast(0.dp))
                            Surface(
                                modifier = Modifier
                                    .offset(x = previewX, y = (-88).dp)
                                    .width(previewWidth)
                                    .height(84.dp)
                                    .zIndex(2f),
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black,
                                tonalElevation = 4.dp
                            ) {
                                Box {
                                    if (previewBitmap != null && !previewBitmap.isRecycled) {
                                        Image(
                                            bitmap = previewBitmap.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    if (isLoadingScrubPreview) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = if (previewBitmap == null) 0.18f else 0.45f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                    Text(
                                        text = formatTime(tempPosition),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.72f))
                                            .padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tempo atual
                        Text(
                            text = formatTime(if (isDragging) tempPosition else currentPosition),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Light
                        )

                        if (totalPlaylistSize > 1) {
                            Text(
                                text = "${currentGlobalIndex + 1} / $totalPlaylistSize",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier
                                    .background(
                                        Color.White.copy(alpha = 0.08f),
                                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Duração total
                            Text(
                                text = formatTime(duration),
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Light
                            )

                            // Legendas
                            val subtitleBg = if (subtitlesEnabled) CtrlBtnBgActive else CtrlBtnBg
                            IconButton(
                                onClick = {
                                    onSubtitlesClick()
                                    resetUITimer()
                                },
                                modifier = Modifier
                                    .background(subtitleBg, CircleShape)
                                    .size(38.dp)
                            ) {
                                Box(modifier = Modifier.size(22.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Subtitles,
                                        contentDescription = stringResource(R.string.player_subtitles),
                                        tint = if (subtitlesEnabled) CtrlIconOn else CtrlIconOff,
                                        modifier = Modifier.align(Alignment.Center).size(20.dp)
                                    )
                                    if (isCasting) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(12.dp)
                                                .background(Color.Black, CircleShape)
                                        )
                                    }
                                }
                            }

                            if (!isCasting) {
                                // Rotação
                                val (rotIcon, rotDesc, rotActive) = when (rotationMode) {
                                    RotationMode.AUTO -> Triple(Icons.Default.ScreenRotation, stringResource(R.string.player_rotation_auto), false)
                                    RotationMode.PORTRAIT -> Triple(Icons.Default.StayCurrentPortrait, stringResource(R.string.player_rotation_portrait), true)
                                    RotationMode.LANDSCAPE -> Triple(Icons.Default.StayCurrentLandscape, stringResource(R.string.player_rotation_landscape), true)
                                }
                                IconButton(
                                    onClick = {
                                        val nextMode = when (rotationMode) {
                                            RotationMode.AUTO -> RotationMode.PORTRAIT
                                            RotationMode.PORTRAIT -> RotationMode.LANDSCAPE
                                            RotationMode.LANDSCAPE -> RotationMode.AUTO
                                        }
                                        onRotationModeChange(nextMode)
                                        resetUITimer()
                                    },
                                    modifier = Modifier
                                        .background(if (rotActive) CtrlBtnBgActive else CtrlBtnBg, CircleShape)
                                        .size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = rotIcon,
                                        contentDescription = rotDesc,
                                        tint = if (rotActive) CtrlIconOn else CtrlIconOff,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (!isCasting) {
                                // Velocidade
                                var showSpeedDialog by remember { mutableStateOf(false) }
                                val speedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                                val speedActive = playbackSpeed.value != 1.0f
                                IconButton(
                                    onClick = {
                                        onSpeedDialogOpen()
                                        showSpeedDialog = true
                                    },
                                    modifier = Modifier
                                        .background(if (speedActive) CtrlBtnBgActive else CtrlBtnBg, CircleShape)
                                        .size(38.dp)
                                ) {
                                    Text(
                                        text = formatSpeedLabel(playbackSpeed),
                                        color = if (speedActive) CtrlIconOn else CtrlIconOff,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }

                                if (showSpeedDialog) {
                                    AppBottomSheet(
                                        onDismissRequest = {
                                            showSpeedDialog = false
                                            onSpeedDialogClose()
                                        },
                                        sheetState = speedSheetState,
                                        title = stringResource(R.string.playback_speed_title),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = formatSpeedLabel(playbackSpeed),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 40.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Slider(
                                                value = playbackSpeed.value,
                                                onValueChange = {
                                                    val closest = PlaybackSpeed.entries.minByOrNull { speed ->
                                                        kotlin.math.abs(speed.value - it)
                                                    }
                                                    closest?.let { onPlaybackSpeedChange(it) }
                                                },
                                                valueRange = 0.25f..2.0f,
                                                steps = 6,
                                                colors = SliderDefaults.colors(
                                                    thumbColor = MaterialTheme.colorScheme.primary,
                                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                PlaybackSpeed.entries.forEach { speed ->
                                                    Text(
                                                        text = formatSpeedLabel(speed),
                                                        color = if (speed == playbackSpeed) MaterialTheme.colorScheme.onSurface
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Repeat mode
                            val (repIcon, repDesc, repActive) = when (repeatMode) {
                                RepeatMode.NONE -> Triple(Icons.Default.PlaylistPlay, stringResource(R.string.player_repeat_normal), false)
                                RepeatMode.REPEAT_ALL -> Triple(Icons.Default.Repeat, stringResource(R.string.player_repeat_all), true)
                                RepeatMode.REPEAT_ONE -> Triple(Icons.Default.RepeatOne, stringResource(R.string.player_repeat_one), true)
                            }
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
                                    .background(if (repActive) CtrlBtnBgActive else CtrlBtnBg, CircleShape)
                                    .size(38.dp)
                            ) {
                                Icon(
                                    imageVector = repIcon,
                                    contentDescription = repDesc,
                                    tint = if (repActive) CtrlIconOn else CtrlIconOff,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showActionDrawer,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it })
        ) {
            val dismissInteractionSource = remember { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
                    .clickable(
                        interactionSource = dismissInteractionSource,
                        indication = null
                    ) {
                        closeActionDrawer(shouldResumePlayback = true)
                    }
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .border(1.dp, CtrlDrawerBorder, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .background(CtrlDrawerBg)
                        .padding(14.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.White.copy(alpha = 0.035f),
                                androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.05f),
                                androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.player_more_actions),
                                color = Color.White.copy(alpha = 0.96f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = videoTitle,
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(actionDrawerScrollState)
                    ) {
                        DrawerActionItem(
                            icon = Icons.Default.LocalOffer,
                            label = stringResource(R.string.action_tags),
                            tint = CtrlIconOn,
                            trailingLabel = currentVideoTagCount.takeIf { it > 0 }?.toString(),
                            onClick = {
                                val shouldResumeAfterTagsDialog = resumeAfterActionDrawer
                                closeActionDrawer(shouldResumePlayback = false)
                                onTagsClick(shouldResumeAfterTagsDialog)
                                resetUITimer()
                            }
                        )

                        DrawerActionItem(
                            icon = Icons.Default.NightsStay,
                            label = stringResource(R.string.player_sleep_timer),
                            tint = CtrlIconOn,
                            isActive = sleepTimerActive,
                            onClick = {
                                showActionDrawer = false
                                showSleepTimerDialog = true
                                resetUITimer()
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        DrawerDivider()
                        Spacer(modifier = Modifier.height(10.dp))

                        DrawerActionItem(
                            icon = Icons.Default.Delete,
                            label = stringResource(R.string.action_delete),
                            tint = CtrlDrawerDeleteTint,
                            isDestructive = true,
                            onClick = {
                                closeActionDrawer(shouldResumePlayback = false)
                                onDeleteClick()
                            }
                        )
                    }
                }
            }
        }

        if (showSleepTimerDialog) {
            ModalBottomSheet(
                onDismissRequest = { closeSleepTimerDialog() },
                sheetState = sleepTimerSheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                dragHandle = {
                    Surface(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    ) {
                        Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.player_sleep_timer),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = formatSleepTimerOption(context, sleepTimerOptionsMs[sleepTimerOptionIndex.toInt()]),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Slider(
                        modifier = Modifier.heightIn(min = 20.dp),
                        value = sleepTimerOptionIndex,
                        onValueChange = { value ->
                            sleepTimerOptionIndex = value.toInt().coerceIn(0, sleepTimerOptionsMs.lastIndex).toFloat()
                        },
                        valueRange = 0f..sleepTimerOptionsMs.lastIndex.toFloat(),
                        steps = (sleepTimerOptionsMs.size - 2).coerceAtLeast(0),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        },
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier.height(2.dp),
                                colors = SliderDefaults.colors(
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                thumbTrackGapSize = 0.dp,
                                drawStopIndicator = null
                            )
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (BuildConfig.DEBUG) {
                            Text(
                                text = formatSleepTimerOption(context, sleepTimerOptionsMs.first()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            stringResource(R.string.player_sleep_timer_minutes_value, 5),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                        Text(
                            stringResource(R.string.player_sleep_timer_minutes_value, 90),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { closeSleepTimerDialog() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            onClick = {
                                val durationMs = sleepTimerOptionsMs[sleepTimerOptionIndex.toInt()]
                                onSleepTimerStarted(System.currentTimeMillis() + durationMs)
                                onSleepTimerConfirmed()
                                MediaPlaybackService.startSleepTimer(
                                    context,
                                    durationMs
                                )
                                closeSleepTimerDialog()
                            }
                        ) {
                            Text(stringResource(R.string.player_sleep_timer_start))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        if (showSleepTimerStatusDialog && sleepTimerActive) {
            ModalBottomSheet(
                onDismissRequest = { closeSleepTimerStatusDialog(shouldResumePlayback = true) },
                sheetState = sleepTimerStatusSheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                dragHandle = {
                    Surface(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    ) {
                        Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.player_sleep_timer_active_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(
                            R.string.player_sleep_timer_remaining,
                            formatRemainingTime(context, sleepTimerRemainingMs)
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.player_sleep_timer_cancel_prompt),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { closeSleepTimerStatusDialog(shouldResumePlayback = true) }) {
                            Text(stringResource(R.string.close))
                        }
                        TextButton(
                            onClick = {
                                onSleepTimerCleared()
                                MediaPlaybackService.clearSleepTimer(context)
                                closeSleepTimerStatusDialog(shouldResumePlayback = true)
                            }
                        ) {
                            Text(stringResource(R.string.player_sleep_timer_cancel_action))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DrawerActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    isActive: Boolean = false,
    isDestructive: Boolean = false,
    trailingLabel: String? = null,
    onClick: () -> Unit
) {
    val containerColor = when {
        isDestructive -> CtrlDrawerItemDestructiveBg
        isActive -> CtrlDrawerItemActiveBg
        else -> CtrlDrawerItemBg
    }
    val iconContainerColor = when {
        isDestructive -> tint.copy(alpha = 0.16f)
        isActive -> CtrlDrawerIconActiveBg
        else -> CtrlDrawerIconBg
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .border(
                1.dp,
                if (isActive) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.04f),
                androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(iconContainerColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }

        Text(
            text = label,
            color = if (isDestructive) tint else Color.White.copy(alpha = 0.95f),
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        if (!trailingLabel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = trailingLabel,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isActive) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tint, CircleShape)
            )
        }
    }

    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun DrawerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CtrlDrawerDivider)
    )
}

suspend fun deleteCurrentVideo(
    context: Context,
    videoPath: String,
    mediaController: MediaController?,
    onVideoDeleted: (String) -> Unit
) {
    try {
        val controller = mediaController ?: return

        val file = File(videoPath)
        val parentPath = file.parent
        val isLockedVideo = parentPath != null && FolderLockManager.isLocked(parentPath)
        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val isSecureVideo = videoPath.startsWith(secureFolderPath)

        val success = when {
            isLockedVideo -> deleteLockedFile(context, videoPath)
            isSecureVideo -> deleteSecureFile(context, videoPath)
            else -> deleteRegularFile(context, videoPath)
        }

        if (success) {
            val removedPlaylistIndex = PlaylistManager.getCurrentIndex()

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onVideoDeleted(videoPath)
            }

            when (val result = PlaylistManager.removeCurrent()) {
                is PlaylistManager.RemovalResult.Success -> {
                    // SEMPRE atualizar o player após exclusão usando a função correta
                    MediaPlaybackService.removePlaylistItem(
                        context,
                        removeIndex = removedPlaylistIndex,
                        nextIndex = PlaylistManager.getCurrentIndex()
                    )
                }
                PlaylistManager.RemovalResult.PlaylistEmpty -> {
                    MediaPlaybackService.stopService(context)
                }
                else -> {}
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.video_delete_success),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.video_delete_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                controller?.play()
            }
        }

    } catch (e: Exception) {
        Log.e("VideoPlayer", "Erro ao deletar vídeo", e)
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                context.getString(
                    R.string.video_delete_error,
                    e.message ?: context.getString(R.string.delete_items_error_generic)
                ),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            mediaController?.play()
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

suspend fun deleteRegularFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo regular", e)
            false
        }
    }

suspend fun deleteLockedFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            val folderPath = file.parent ?: return@withContext false
            val obfuscatedName = file.name

            // Delete the obfuscated video file
            if (!file.delete()) return@withContext false

            // Update manifest and delete thumbnail
            val password = LockedPlaybackSession.sessionPassword
            if (password != null) {
                val updatedManifest = FolderLockManager.removeFileFromManifest(
                    context, folderPath, obfuscatedName, password
                )
                if (updatedManifest != null) {
                    LockedPlaybackSession.updateManifest(folderPath, updatedManifest)
                }
            }

            true
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo de pasta trancada", e)
            false
        }
    }

suspend fun deleteSecureFile(context: Context, videoPath: String): Boolean =
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = File(videoPath)
            file.delete()
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Erro ao deletar arquivo seguro", e)
            false
        }
    }

private fun formatSpeedLabel(speed: PlaybackSpeed): String = when (speed) {
    PlaybackSpeed.SPEED_0_25 -> "0.25x"
    PlaybackSpeed.SPEED_0_50 -> "0.5x"
    PlaybackSpeed.SPEED_0_75 -> "0.75x"
    PlaybackSpeed.SPEED_1_00 -> "1x"
    PlaybackSpeed.SPEED_1_25 -> "1.25x"
    PlaybackSpeed.SPEED_1_50 -> "1.5x"
    PlaybackSpeed.SPEED_1_75 -> "1.75x"
    PlaybackSpeed.SPEED_2_00 -> "2x"
}

private fun formatRemainingTime(context: Context, remainingMs: Long): String {
    if (remainingMs in 1..59_999L) {
        val seconds = kotlin.math.ceil(remainingMs / 1000.0).toInt()
        return "${seconds}s"
    }

    val totalMinutes = kotlin.math.ceil(remainingMs.coerceAtLeast(0L) / 60000.0).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 && minutes > 0 -> context.getString(R.string.player_sleep_timer_hours_minutes_value, hours, minutes)
        hours > 0 -> context.getString(R.string.player_sleep_timer_hours_value, hours)
        else -> context.getString(R.string.player_sleep_timer_only_minutes_value, minutes)
    }
}

private fun formatSleepTimerOption(context: Context, durationMs: Long): String {
    if (durationMs in 1..59_999L) {
        val seconds = kotlin.math.ceil(durationMs / 1000.0).toInt()
        return "${seconds}s"
    }

    return context.getString(R.string.player_sleep_timer_minutes_value, (durationMs / 60_000L).toInt())
}
