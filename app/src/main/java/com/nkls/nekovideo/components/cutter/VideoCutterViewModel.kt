package com.nkls.nekovideo.components.cutter

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import android.util.SparseIntArray
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
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

            val success = concatenateFiles(files, mergedOutput.absolutePath)

            if (success) {
                files.forEach { File(it).delete() }
                _cuttingState.value = CuttingState.Success(listOf(mergedOutput.absolutePath))
            } else {
                _cuttingState.value = CuttingState.Error("Falha ao mesclar arquivos")
            }
        }
    }

    private fun mapSampleFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0)
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        return flags
    }

    private fun getOutputDir(): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(dcim, "Cuts")
    }

    private fun cutSegment(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(inputPath)

            val trackMap = SparseIntArray()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap.put(i, muxer.addTrack(format))
                    extractor.selectTrack(i)
                }
            }

            muxer.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024) // 4 MB
            val bufferInfo = MediaCodec.BufferInfo()
            val rangeDurationUs = (endUs - startUs).coerceAtLeast(1L)
            var lastReportedPercent = -1

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                val muxerTrack = trackMap[trackIndex, -1]
                if (muxerTrack >= 0) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size >= 0) {
                        bufferInfo.offset = 0
                        bufferInfo.size = size
                        bufferInfo.presentationTimeUs = sampleTime - startUs
                        bufferInfo.flags = mapSampleFlags(extractor.sampleFlags)
                        if (bufferInfo.presentationTimeUs >= 0) {
                            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                        }
                    }
                }
                extractor.advance()

                // Emite progresso apenas quando muda 1% (evita overhead de StateFlow por frame)
                val percent = ((sampleTime - startUs) * 100L / rangeDurationUs).toInt().coerceIn(0, 99)
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    onProgress(percent / 100f)
                }
            }

            muxer.stop()
            true
        } catch (e: Exception) {
            Log.e("VideoCutter", "Erro ao cortar segmento", e)
            File(outputPath).delete()
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun concatenateFiles(inputPaths: List<String>, outputPath: String): Boolean {
        if (inputPaths.isEmpty()) return false
        if (inputPaths.size == 1) {
            File(inputPaths[0]).copyTo(File(outputPath), overwrite = true)
            return true
        }

        var muxer: MediaMuxer? = null
        return try {
            // Setup muxer from first file's tracks
            val firstExtractor = MediaExtractor()
            firstExtractor.setDataSource(inputPaths[0])

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = SparseIntArray()

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap.put(i, muxer.addTrack(format))
                }
            }
            firstExtractor.release()

            muxer.start()

            val buffer = ByteBuffer.allocate(1 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var timeOffsetUs = 0L
            var lastEndTimeUs = 0L

            for (path in inputPaths) {
                val extractor = MediaExtractor()
                extractor.setDataSource(path)

                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        extractor.selectTrack(i)
                    }
                }

                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var segLastTime = 0L

                while (true) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break
                    val sampleTime = extractor.sampleTime
                    val muxerTrack = trackMap[trackIndex, -1]

                    if (muxerTrack >= 0) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size >= 0) {
                            bufferInfo.offset = 0
                            bufferInfo.size = size
                            bufferInfo.presentationTimeUs = sampleTime + timeOffsetUs
                            bufferInfo.flags = mapSampleFlags(extractor.sampleFlags)
                            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                            if (sampleTime > segLastTime) segLastTime = sampleTime
                        }
                    }
                    extractor.advance()
                }

                timeOffsetUs += segLastTime + 33_333L // approx 1 frame gap
                extractor.release()
            }

            muxer.stop()
            true
        } catch (e: Exception) {
            Log.e("VideoCutter", "Erro ao concatenar arquivos", e)
            File(outputPath).delete()
            false
        } finally {
            runCatching { muxer?.release() }
        }
    }
}
