package com.nkls.nekovideo.components.cutter

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nkls.nekovideo.R
import com.nkls.nekovideo.theme.NekoVideoTheme
import com.nkls.nekovideo.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class VideoCutterActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"

        fun createIntent(context: Context, videoPath: String): Intent =
            Intent(context, VideoCutterActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_PATH, videoPath)
            }
    }

    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: run {
            finish()
            return
        }

        themeManager = ThemeManager(this)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        setContent {
            NekoVideoTheme(themeManager = themeManager) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VideoCutterScreen(
                        videoPath = videoPath,
                        onBack = { finish() },
                        onSuccess = { setResult(RESULT_OK); finish() }
                    )
                }
            }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoCutterScreen(
    videoPath: String,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    vm: VideoCutterViewModel = viewModel()
) {
    val context = LocalContext.current
    val segments by vm.segments.collectAsState()
    val cutPoints by vm.cutPoints.collectAsState()
    val cuttingState by vm.cuttingState.collectAsState()

    var centerPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // msPerPx: how many video-milliseconds fit in one screen pixel
    var msPerPx by remember { mutableFloatStateOf(100f) }
    var timelineWidthPx by remember { mutableFloatStateOf(0f) }
    var msPerPxInitialized by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also { player ->
            player.setMediaItem(MediaItem.fromUri(videoPath))
            player.prepare()
            player.playWhenReady = false
        }
    }

    // Zoom bounds computed from video duration and screen width
    val minMsPerPx = 0.5f   // ~0.5ms per pixel = 500ms visible on 1000px screen (sub-second precision)
    val maxMsPerPx = remember(durationMs, timelineWidthPx) {
        if (durationMs > 0 && timelineWidthPx > 0)
            (durationMs.toFloat() / timelineWidthPx).coerceAtLeast(minMsPerPx)
        else 100000f
    }

    // Set initial zoom so the full video fits the timeline
    LaunchedEffect(durationMs, timelineWidthPx) {
        if (!msPerPxInitialized && durationMs > 0 && timelineWidthPx > 0) {
            msPerPx = durationMs.toFloat() / timelineWidthPx
            msPerPxInitialized = true
        }
    }

    // Follow player playback when not scrubbing
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            if (exoPlayer.duration > 0 && durationMs == 0L) {
                durationMs = exoPlayer.duration
                vm.videoDurationMs = durationMs
            }
            isPlaying = exoPlayer.isPlaying
            if (exoPlayer.isPlaying && !isScrubbing) {
                centerPositionMs = exoPlayer.currentPosition
            }
            delay(50)
        }
    }

    LaunchedEffect(cuttingState) {
        when (val state = cuttingState) {
            is CuttingState.Success -> {
                if (state.outputFiles.size > 1) {
                    // Auto-merge without asking
                    vm.mergeFiles(state.outputFiles, videoPath)
                } else {
                    Toast.makeText(context, context.getString(R.string.cut_success), Toast.LENGTH_LONG).show()
                    onSuccess()
                }
            }
            is CuttingState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                vm.resetState()
            }
            else -> {}
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(File(videoPath).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Video preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
                    .clickable { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                        }
                    }
                )
                if (!isPlaying) {
                    IconButton(
                        onClick = { exoPlayer.play() },
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.Black.copy(alpha = 0.4f), shape = RoundedCornerShape(50))
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            // Time labels
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatTime(centerPositionMs), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.cut_timeline_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
                Text(formatTime(durationMs), style = MaterialTheme.typography.bodyMedium)
            }

            // Main timeline (scrollable by drag)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { if (timelineWidthPx == 0f) timelineWidthPx = it.width.toFloat() }
            ) {
                VideoTimeline(
                    durationMs = durationMs,
                    centerPositionMs = centerPositionMs,
                    segments = segments,
                    cutPoints = cutPoints,
                    msPerPx = msPerPx,
                    onSeekCenter = { timeMs ->
                        centerPositionMs = timeMs
                        exoPlayer.seekTo(timeMs)
                    },
                    onScroll = { deltaMs ->
                        val newCenter = (centerPositionMs + deltaMs).coerceIn(0L, durationMs)
                        centerPositionMs = newCenter
                        exoPlayer.seekTo(newCenter)
                    },
                    onRemoveCutPoint = { pt -> vm.removeCutPoint(pt) },
                    onScrubStart = { isScrubbing = true; exoPlayer.pause() },
                    onScrubEnd = { isScrubbing = false }
                )
            }

            // Zoom strip — drag left = zoom in, drag right = zoom out
            ZoomStrip(
                msPerPx = msPerPx,
                minMsPerPx = minMsPerPx,
                maxMsPerPx = maxMsPerPx,
                onZoom = { newMsPerPx -> msPerPx = newMsPerPx }
            )

            // Add cut + Save row
            val isProcessing = cuttingState is CuttingState.Processing
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.addCutPoint(centerPositionMs) },
                    modifier = Modifier.weight(1f),
                    enabled = durationMs > 0,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cut_add_point), style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = { vm.processVideo(videoPath) },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing && segments.any { it.keep },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (isProcessing) {
                        val state = cuttingState as CuttingState.Processing
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            progress = { state.percent / 100f }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cut_processing, state.percent), style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cut_save), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Segments chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(segments) { index, segment ->
                    SegmentChip(
                        index = index,
                        segment = segment,
                        onToggle = { vm.toggleSegment(index) },
                        onSeek = {
                            centerPositionMs = segment.startMs
                            exoPlayer.seekTo(segment.startMs)
                        }
                    )
                }
            }
        }
    }

}

// ── Timeline ──────────────────────────────────────────────────────────────────

@Composable
private fun VideoTimeline(
    durationMs: Long,
    centerPositionMs: Long,
    segments: List<CutSegment>,
    cutPoints: List<Long>,
    msPerPx: Float,
    onSeekCenter: (Long) -> Unit,
    onScroll: (deltaMs: Long) -> Unit,
    onRemoveCutPoint: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit
) {
    // rememberUpdatedState so the gesture handler always reads fresh values
    // WITHOUT being re-keyed (which would cancel an ongoing drag)
    val msPerPxRef = rememberUpdatedState(msPerPx)
    val centerRef = rememberUpdatedState(centerPositionMs)
    val cutPointsRef = rememberUpdatedState(cutPoints)

    val keepColor = Color(0xFF4CAF50)
    val deleteColor = Color(0xFFF44336)
    val cursorColor = Color(0xFFFFD740)
    val markerColor = Color.White
    val tickColor = Color.White.copy(alpha = 0.45f)
    val rulerBgColor = Color(0xFF1A1A1A)
    val segmentBgColor = Color(0xFF242424)

    val tickLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(160, 255, 255, 255)
            textSize = 26f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    if (durationMs <= 0) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(segmentBgColor, RoundedCornerShape(4.dp))
        )
        return
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            // Single stable key — gesture handler lives for the whole composition lifetime.
            // Fresh values are read via rememberUpdatedState refs inside the handler.
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var prevX = down.position.x
                    var hasMoved = false
                    var scrubStarted = false

                    while (true) {
                        val event = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) break

                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val deltaX = change.position.x - prevX

                        // Start drag after touchSlop
                        if (!hasMoved && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                            hasMoved = true
                            if (!scrubStarted) {
                                scrubStarted = true
                                onScrubStart()
                            }
                        }

                        if (hasMoved) {
                            change.consume()
                            // Drag right → earlier, drag left → later
                            val deltaMs = (-deltaX * msPerPxRef.value).toLong()
                            onScroll(deltaMs)
                            prevX = change.position.x
                        }
                    }

                    if (scrubStarted) onScrubEnd()

                    // Tap (finger lifted without drag)
                    if (!hasMoved) {
                        val centerX = size.width / 2f
                        val tapTimeMs = (centerRef.value + ((down.position.x - centerX) * msPerPxRef.value).toLong())
                            .coerceIn(0L, durationMs)

                        val tapThresholdMs = (28 * msPerPxRef.value).toLong().coerceAtLeast(300L)
                        val nearCut = cutPointsRef.value
                            .minByOrNull { abs(it - tapTimeMs) }
                            ?.takeIf { abs(it - tapTimeMs) < tapThresholdMs }

                        if (nearCut != null) onRemoveCutPoint(nearCut)
                        else onSeekCenter(tapTimeMs)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val centerX = w / 2f
        val rulerH = h * 0.28f
        val segTop = rulerH
        val segH = h - segTop

        val curMsPerPx = msPerPxRef.value
        val curCenter = centerRef.value

        // ── Backgrounds ───────────────────────────────────────────────────────

        drawRect(rulerBgColor, Offset(0f, 0f), Size(w, rulerH))
        drawRect(segmentBgColor, Offset(0f, segTop), Size(w, segH))

        // ── Segments ──────────────────────────────────────────────────────────

        segments.forEach { seg ->
            val sx = centerX + (seg.startMs - curCenter) / curMsPerPx
            val ex = centerX + (seg.endMs - curCenter) / curMsPerPx
            val csx = sx.coerceIn(0f, w)
            val cex = ex.coerceIn(0f, w)
            if (cex > csx) {
                drawRect(
                    color = if (seg.keep) keepColor.copy(alpha = 0.75f) else deleteColor.copy(alpha = 0.75f),
                    topLeft = Offset(csx, segTop),
                    size = Size(cex - csx, segH)
                )
            }
        }

        // ── Ruler ticks ───────────────────────────────────────────────────────

        val (minorMs, majorMs) = getTickInterval(curMsPerPx)
        val leftMs = curCenter - (centerX * curMsPerPx).toLong()
        val rightMs = curCenter + ((w - centerX) * curMsPerPx).toLong()
        var tickMs = (leftMs / minorMs - 1) * minorMs

        while (tickMs <= rightMs + minorMs) {
            if (tickMs < 0L) { tickMs += minorMs; continue }
            if (tickMs > durationMs) break

            val x = centerX + (tickMs - curCenter) / curMsPerPx
            val isMajor = tickMs % majorMs == 0L
            val tickH = if (isMajor) rulerH * 0.7f else rulerH * 0.35f

            drawLine(
                color = if (isMajor) tickColor.copy(alpha = 0.9f) else tickColor,
                start = Offset(x, 0f),
                end = Offset(x, tickH),
                strokeWidth = if (isMajor) 1.5f else 0.8f
            )

            if (isMajor) {
                drawContext.canvas.nativeCanvas.drawText(
                    formatTickTime(tickMs),
                    x,
                    rulerH - 2f,
                    tickLabelPaint
                )
            }

            tickMs += minorMs
        }

        // ── Cut markers ───────────────────────────────────────────────────────

        cutPointsRef.value.forEach { pt ->
            val x = centerX + (pt - curCenter) / curMsPerPx
            if (x in -20f..w + 20f) {
                drawLine(markerColor, Offset(x, segTop), Offset(x, h), strokeWidth = 2.5f)
                // Triangle indicator at ruler bottom
                val ts = 6f
                drawPath(
                    Path().apply {
                        moveTo(x - ts, segTop); lineTo(x + ts, segTop); lineTo(x, segTop + ts * 1.6f); close()
                    },
                    color = markerColor
                )
            }
        }

        // ── Fixed center cursor (always at centerX) ───────────────────────────

        drawLine(cursorColor, Offset(centerX, 0f), Offset(centerX, h), strokeWidth = 2.5f)
        val ns = 8f
        drawPath(
            Path().apply {
                moveTo(centerX - ns, 0f); lineTo(centerX + ns, 0f); lineTo(centerX, ns * 2f); close()
            },
            color = cursorColor
        )
    }
}

// ── Zoom strip ────────────────────────────────────────────────────────────────
// Drag LEFT = zoom in (fewer ms visible = more precision)
// Drag RIGHT = zoom out (more ms visible = wider view)

@Composable
private fun ZoomStrip(
    msPerPx: Float,
    minMsPerPx: Float,
    maxMsPerPx: Float,
    onZoom: (Float) -> Unit
) {
    val msPerPxRef = rememberUpdatedState(msPerPx)

    // Normalise on log scale so the handle moves linearly across the strip
    val logMin = remember(minMsPerPx) { ln(minMsPerPx.toDouble()) }
    val logMax = remember(maxMsPerPx) { ln(maxMsPerPx.toDouble().coerceAtLeast(minMsPerPx.toDouble() + 0.001)) }
    val normalised = remember(msPerPx, minMsPerPx, maxMsPerPx) {
        if (logMax <= logMin) 1f
        else ((ln(msPerPx.toDouble().coerceIn(minMsPerPx.toDouble(), maxMsPerPx.toDouble())) - logMin) / (logMax - logMin))
            .toFloat().coerceIn(0f, 1f)
    }
    // normalised = 0 → max zoom in (left), 1 → max zoom out (right)

    val trackColor = Color.White.copy(alpha = 0.15f)
    val filledColor = Color(0xFF4CAF50).copy(alpha = 0.6f)
    val handleColor = Color.White.copy(alpha = 0.9f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // "Zoom in" label — left side
        Text("−", style = MaterialTheme.typography.labelMedium, color = labelColor)

        // Draggable track
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(minMsPerPx, maxMsPerPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var prevX = down.position.x

                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.changes.any { it.pressed }) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()

                            val dx = change.position.x - prevX
                            if (abs(dx) > 0.3f) {
                                // Exponential scaling: symmetric and smooth
                                // Drag right = zoom out = msPerPx increases
                                val factor = exp(dx.toDouble() * 0.012).toFloat()
                                val newVal = (msPerPxRef.value * factor).coerceIn(minMsPerPx, maxMsPerPx)
                                onZoom(newVal)
                                prevX = change.position.x
                            }
                        }
                    }
                }
        ) {
            val w = size.width
            val cy = size.height / 2f
            val handleR = 9f
            val pad = handleR

            // Track background
            drawLine(trackColor, Offset(pad, cy), Offset(w - pad, cy), strokeWidth = 4f)

            // Filled portion (from left = max zoom in, up to current handle)
            val handleX = pad + normalised * (w - 2f * pad)
            if (handleX > pad) {
                drawLine(filledColor, Offset(pad, cy), Offset(handleX, cy), strokeWidth = 4f)
            }

            // Handle
            drawCircle(handleColor, handleR, Offset(handleX, cy))
            drawCircle(Color.Black.copy(alpha = 0.25f), handleR * 0.45f, Offset(handleX, cy))
        }

        // "Zoom out" label — right side
        Text("+", style = MaterialTheme.typography.labelMedium, color = labelColor)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun getTickInterval(msPerPx: Float): Pair<Long, Long> {
    val pxPerSec = 1000f / msPerPx
    return when {
        pxPerSec >= 400  -> 50L       to 500L
        pxPerSec >= 150  -> 100L      to 1_000L
        pxPerSec >= 60   -> 250L      to 1_000L
        pxPerSec >= 25   -> 500L      to 5_000L
        pxPerSec >= 10   -> 1_000L    to 10_000L
        pxPerSec >= 4    -> 2_000L    to 10_000L
        pxPerSec >= 1.5  -> 5_000L    to 30_000L
        pxPerSec >= 0.5  -> 10_000L   to 60_000L
        pxPerSec >= 0.15 -> 30_000L   to 300_000L
        else             -> 60_000L   to 600_000L
    }
}

private fun formatTickTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ── Segment chip ──────────────────────────────────────────────────────────────

@Composable
private fun SegmentChip(
    index: Int,
    segment: CutSegment,
    onToggle: () -> Unit,
    onSeek: () -> Unit
) {
    val keepColor = Color(0xFF4CAF50)
    val deleteColor = Color(0xFFF44336)
    val color = if (segment.keep) keepColor else deleteColor

    Surface(
        modifier = Modifier.clickable { onSeek() },
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column {
                Text(
                    stringResource(R.string.cut_segment_label, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
                Text(
                    "${formatTime(segment.startMs)} › ${formatTime(segment.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Switch(
                checked = segment.keep,
                onCheckedChange = { onToggle() },
                modifier = Modifier.height(24.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = keepColor,
                    checkedTrackColor = keepColor.copy(alpha = 0.5f),
                    uncheckedThumbColor = deleteColor,
                    uncheckedTrackColor = deleteColor.copy(alpha = 0.5f)
                )
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
