package com.nkls.nekovideo.components.cutter

import android.content.Context
import androidx.lifecycle.ViewModel
import com.nkls.nekovideo.services.VideoCutService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

data class CutSegment(
    val startMs: Long,
    val endMs: Long,
    val keep: Boolean = true
)

sealed class CuttingState {
    object Idle : CuttingState()
    object Dispatched : CuttingState()
    data class Error(val message: String) : CuttingState()
}

class VideoCutterViewModel : ViewModel() {

    private val _cutPoints = MutableStateFlow<List<Long>>(emptyList())
    val cutPoints: StateFlow<List<Long>> = _cutPoints

    private val _segments = MutableStateFlow<List<CutSegment>>(emptyList())
    val segments: StateFlow<List<CutSegment>> = _segments

    private val _cuttingState = MutableStateFlow<CuttingState>(CuttingState.Idle)
    val cuttingState: StateFlow<CuttingState> = _cuttingState

    var videoDurationMs = 0L
        set(value) {
            field = value
            if (value > 0) updateSegments(_cutPoints.value)
        }

    fun addCutPoint(positionMs: Long) {
        if (positionMs <= 0 || positionMs >= videoDurationMs) return
        val newPoints = (_cutPoints.value + positionMs).sorted().distinct()
        _cutPoints.value = newPoints
        updateSegments(newPoints)
    }

    fun moveCutPoint(oldPos: Long, newPos: Long) {
        val current = _cutPoints.value.toMutableList()
        val idx = current.indexOfFirst { abs(it - oldPos) < 200 }
        if (idx < 0) return
        current[idx] = newPos.coerceIn(1L, videoDurationMs - 1L)
        val sorted = current.sorted().distinct()
        _cutPoints.value = sorted
        updateSegments(sorted)
    }

    fun removeCutPoint(positionMs: Long) {
        val newPoints = _cutPoints.value.filter { abs(it - positionMs) > 100 }
        _cutPoints.value = newPoints
        updateSegments(newPoints)
    }

    fun toggleSegment(index: Int) {
        val current = _segments.value.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(keep = !current[index].keep)
            _segments.value = current
        }
    }

    fun resetState() {
        _cuttingState.value = CuttingState.Idle
    }

    private fun updateSegments(points: List<Long>) {
        if (videoDurationMs <= 0) return
        val boundaries = listOf(0L) + points + listOf(videoDurationMs)
        val previous = _segments.value
        _segments.value = (0 until boundaries.size - 1).map { i ->
            CutSegment(
                startMs = boundaries[i],
                endMs = boundaries[i + 1],
                keep = previous.getOrNull(i)?.keep ?: true
            )
        }
    }

    fun processVideo(context: Context, inputPath: String) {
        val keptSegments = _segments.value.filter { it.keep }
        if (keptSegments.isEmpty()) {
            _cuttingState.value = CuttingState.Error("Nenhum segmento selecionado")
            return
        }

        val segments = keptSegments.map { it.startMs * 1000L to it.endMs * 1000L }
        val intent = VideoCutService.buildIntent(context, inputPath, segments)
        context.startForegroundService(intent)

        _cuttingState.value = CuttingState.Dispatched
    }
}
