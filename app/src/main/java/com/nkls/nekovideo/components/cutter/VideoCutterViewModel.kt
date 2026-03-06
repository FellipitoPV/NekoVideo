package com.nkls.nekovideo.components.cutter

import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs

data class CutSegment(
    val startMs: Long,
    val endMs: Long,
    val keep: Boolean = true
)

sealed class CuttingState {
    object Idle : CuttingState()
    data class Processing(val percent: Int) : CuttingState()
    data class Success(val outputFiles: List<String>) : CuttingState()
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

    fun processVideo(inputPath: String) {
        val keptSegments = _segments.value.filter { it.keep }
        if (keptSegments.isEmpty()) {
            _cuttingState.value = CuttingState.Error("Nenhum segmento selecionado")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _cuttingState.value = CuttingState.Processing(0)

            val inputFile = File(inputPath)
            val outputDir = getOutputDir().also { it.mkdirs() }
            val baseName = inputFile.nameWithoutExtension
            val ext = inputFile.extension
            val outputFiles = mutableListOf<String>()

            val totalDurationMs = keptSegments.sumOf { it.endMs - it.startMs }.coerceAtLeast(1L)
            var processedMs = 0L

            for ((index, seg) in keptSegments.withIndex()) {
                val segDurationMs = seg.endMs - seg.startMs
                val output = File(outputDir, "${baseName}_cut${index + 1}.$ext")

                val success = cutSegment(
                    inputPath = inputPath,
                    outputPath = output.absolutePath,
                    startUs = seg.startMs * 1000L,
                    endUs = seg.endMs * 1000L
                ) { segProgress ->
                    val overall = (processedMs + segProgress * segDurationMs) / totalDurationMs.toFloat()
                    _cuttingState.value = CuttingState.Processing((overall * 100).toInt().coerceIn(0, 99))
                }

                if (success) {
                    outputFiles.add(output.absolutePath)
                    processedMs += segDurationMs
                } else {
                    _cuttingState.value = CuttingState.Error("Falha ao cortar segmento ${index + 1}")
                    return@launch
                }
            }

            _cuttingState.value = CuttingState.Success(outputFiles)
        }
    }

    fun mergeFiles(files: List<String>, inputPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _cuttingState.value = CuttingState.Processing(99)

            val inputFile = File(inputPath)
            val outputDir = getOutputDir().also { it.mkdirs() }
            val baseName = inputFile.nameWithoutExtension
            val ext = inputFile.extension
            val mergedOutput = File(outputDir, "${baseName}_merged.$ext")

            val success = mergeWithFFmpeg(files, mergedOutput.absolutePath)

            if (success) {
                files.forEach { File(it).delete() }
                _cuttingState.value = CuttingState.Success(listOf(mergedOutput.absolutePath))
            } else {
                _cuttingState.value = CuttingState.Error("Falha ao mesclar arquivos")
            }
        }
    }

    private fun getOutputDir(): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(dcim, "Cuts")
    }

    // Corte lossless: ffprobe localiza o keyframe exato → fast seek preciso → sem freeze
    private suspend fun cutSegment(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val rawStartSec = startUs / 1_000_000.0
        val endSec      = endUs   / 1_000_000.0

        // Para segmentos que não começam no 0, busca o keyframe exato antes do corte
        val startSec = if (startUs > 0L) findKeyframeBefore(inputPath, rawStartSec) else 0.0
        val durationSec = (endSec - startSec).coerceAtLeast(0.001)
        val durationMs  = durationSec * 1000.0

        Log.d("VideoCutter", "cutSegment: target=${rawStartSec}s → keyframe=${startSec}s, dur=${durationSec}s")

        return suspendCancellableCoroutine { cont ->
            val args = arrayOf(
                "-y",
                "-ss", startSec.toString(), // fast seek no keyframe exato: sem freeze, sem conteúdo extra
                "-i", inputPath,
                "-t", durationSec.toString(),
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-movflags", "+faststart",
                outputPath
            )

            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { session ->
                    if (cont.isActive) {
                        if (!ReturnCode.isSuccess(session.returnCode)) {
                            Log.e("VideoCutter", "cutSegment falhou: ${session.logsAsString}")
                        }
                        onProgress(1f)
                        cont.resume(ReturnCode.isSuccess(session.returnCode))
                    }
                },
                null,
                { statistics ->
                    val progress = (statistics.time / durationMs).toFloat().coerceIn(0f, 0.99f)
                    onProgress(progress)
                }
            )

            cont.invokeOnCancellation { session.cancel() }
        }
    }

    // Usa ffprobe para encontrar o timestamp do keyframe imediatamente ANTES de targetSec.
    // Fast seek nesse keyframe garante: sem freeze (começa num I-frame) e sem conteúdo
    // extra antes do ponto de corte (o keyframe é o mais próximo possível, sem ultrapassar).
    // Fallback: retorna targetSec se ffprobe falhar ou não encontrar keyframes.
    private suspend fun findKeyframeBefore(
        inputPath: String,
        targetSec: Double
    ): Double = suspendCancellableCoroutine { cont ->
        // Escaneia pacotes num janela de 60s até targetSec para garantir achar pelo menos 1 keyframe
        val scanStart = (targetSec - 60.0).coerceAtLeast(0.0)
        val args = arrayOf(
            "-v", "quiet",
            "-select_streams", "v:0",
            "-show_packets",
            "-show_entries", "packet=pts_time,flags",
            "-of", "csv",
            "-read_intervals", "${scanStart}%+65",
            inputPath
        )

        FFprobeKit.executeWithArgumentsAsync(args) { session ->
            val output = session.output ?: ""
            // Linhas CSV: "packet,<pts_time>,<flags>" — flags contém "K" para keyframes
            val keyframeTimes = output.lines()
                .filter { line ->
                    val parts = line.split(",")
                    parts.size >= 3 && parts.last().contains("K")
                }
                .mapNotNull { line -> line.split(",").getOrNull(1)?.toDoubleOrNull() }
                .filter { it <= targetSec + 0.001 } // apenas keyframes em/antes do ponto de corte
                .sorted()

            val result = keyframeTimes.lastOrNull() ?: targetSec
            Log.d("VideoCutter", "findKeyframeBefore: target=${targetSec}s → keyframe=${result}s")
            if (cont.isActive) cont.resume(result)
        }
    }

    // Concatenação lossless via FFmpeg concat demuxer
    private suspend fun mergeWithFFmpeg(
        files: List<String>,
        outputPath: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (files.size == 1) {
            cont.resume(File(files[0]).renameTo(File(outputPath)))
            return@suspendCancellableCoroutine
        }

        val listFile = File(File(outputPath).parent!!, "ffmpeg_concat_list.txt")
        try {
            // Escape single quotes in paths so the concat list is valid
            listFile.writeText(files.joinToString("\n") { "file '${it.replace("'", "\\'")}'" })
        } catch (e: Exception) {
            Log.e("VideoCutter", "Erro ao escrever lista de concat", e)
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        // Array form avoids shell-parsing issues with paths containing spaces
        val args = arrayOf(
            "-y", "-f", "concat", "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            outputPath
        )

        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { session ->
                listFile.delete()
                if (cont.isActive) {
                    if (!ReturnCode.isSuccess(session.returnCode)) {
                        Log.e("VideoCutter", "mergeWithFFmpeg falhou: ${session.logsAsString}")
                    }
                    cont.resume(ReturnCode.isSuccess(session.returnCode))
                }
            },
            null,
            null
        )

        cont.invokeOnCancellation {
            session.cancel()
            listFile.delete()
        }
    }
}
