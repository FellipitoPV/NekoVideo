package com.nkls.nekovideo.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nkls.nekovideo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

class VideoCutService : Service() {

    companion object {
        private const val TAG = "VideoCutService"
        private const val CHANNEL_ID = "video_cut_channel"
        private const val NOTIF_PROGRESS_ID = 1002
        private const val NOTIF_RESULT_BASE_ID = 3000

        const val ACTION_CUT = "com.nkls.nekovideo.ACTION_CUT"
        const val EXTRA_INPUT_PATH = "input_path"
        const val EXTRA_BASE_NAME = "base_name"
        const val EXTRA_EXT = "ext"
        const val EXTRA_SEGMENTS_START = "segments_start"
        const val EXTRA_SEGMENTS_END = "segments_end"

        fun buildIntent(
            context: Context,
            inputPath: String,
            segments: List<Pair<Long, Long>>
        ): Intent {
            val inputFile = File(inputPath)
            return Intent(context, VideoCutService::class.java).apply {
                action = ACTION_CUT
                putExtra(EXTRA_INPUT_PATH, inputPath)
                putExtra(EXTRA_BASE_NAME, inputFile.nameWithoutExtension)
                putExtra(EXTRA_EXT, inputFile.extension)
                putExtra(EXTRA_SEGMENTS_START, segments.map { it.first }.toLongArray())
                putExtra(EXTRA_SEGMENTS_END, segments.map { it.second }.toLongArray())
            }
        }
    }

    private data class CutJob(
        val inputPath: String,
        val baseName: String,
        val ext: String,
        val segments: List<Pair<Long, Long>>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobQueue = ArrayDeque<CutJob>()
    private var isProcessing = false
    private var resultNotifId = NOTIF_RESULT_BASE_ID

    // Contadores da sessão atual (resetados quando fila esvazia)
    @Volatile private var jobsQueued = 0
    @Volatile private var jobsCompleted = 0
    @Volatile private var jobsSuccess = 0
    @Volatile private var jobsFailed = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CUT) return START_NOT_STICKY

        val inputPath = intent.getStringExtra(EXTRA_INPUT_PATH) ?: return START_NOT_STICKY
        val baseName = intent.getStringExtra(EXTRA_BASE_NAME) ?: return START_NOT_STICKY
        val ext = intent.getStringExtra(EXTRA_EXT) ?: return START_NOT_STICKY
        val starts = intent.getLongArrayExtra(EXTRA_SEGMENTS_START) ?: return START_NOT_STICKY
        val ends = intent.getLongArrayExtra(EXTRA_SEGMENTS_END) ?: return START_NOT_STICKY

        val segments = starts.indices.map { i -> starts[i] to ends[i] }
        jobQueue.add(CutJob(inputPath, baseName, ext, segments))
        jobsQueued++

        if (!isProcessing) {
            jobsCompleted = 0; jobsSuccess = 0; jobsFailed = 0
            startForeground(NOTIF_PROGRESS_ID, buildProgressNotification("Iniciando…", "", 0))
            processNext()
        }

        return START_NOT_STICKY
    }

    private fun processNext() {
        val job = jobQueue.removeFirstOrNull() ?: run {
            isProcessing = false
            showSummaryNotification()
            jobsQueued = 0; jobsCompleted = 0; jobsSuccess = 0; jobsFailed = 0
            stopSelf()
            return
        }

        isProcessing = true
        scope.launch {
            val outputDir = getOutputDir().also { it.mkdirs() }
            val outputFiles = mutableListOf<String>()
            val totalSegs = job.segments.size
            val currentNum = jobsCompleted + 1
            val queueTotal = jobsQueued
            val queueLabel = if (queueTotal > 1) "$currentNum/$queueTotal" else ""

            for ((index, seg) in job.segments.withIndex()) {
                val outputPath = File(outputDir, "${job.baseName}_cut${index + 1}.${job.ext}").absolutePath
                val segSuffix = if (totalSegs > 1) " [${index + 1}/$totalSegs]" else ""

                val ok = cutSegment(
                    inputPath = job.inputPath,
                    outputPath = outputPath,
                    startUs = seg.first,
                    endUs = seg.second
                ) { progress ->
                    val overall = ((index + progress) / totalSegs * 100).toInt()
                    updateProgressNotification(
                        title = if (queueLabel.isNotEmpty()) "Cortando vídeo $queueLabel" else "Cortando vídeo",
                        text = "${job.baseName}$segSuffix",
                        progress = overall
                    )
                }

                if (!ok) {
                    jobsCompleted++; jobsFailed++
                    processNext()
                    return@launch
                }
                outputFiles.add(outputPath)
            }

            // Mesclar segmentos se houver mais de um
            if (outputFiles.size > 1) {
                updateProgressNotification(
                    title = if (queueLabel.isNotEmpty()) "Cortando vídeo $queueLabel" else "Cortando vídeo",
                    text = "Unindo segmentos de ${job.baseName}…",
                    progress = 99
                )
                val mergedPath = File(outputDir, "${job.baseName}_merged.${job.ext}").absolutePath
                if (mergeSegments(outputFiles, mergedPath)) {
                    outputFiles.forEach { File(it).delete() }
                    jobsSuccess++
                } else {
                    jobsFailed++
                }
            } else {
                if (outputFiles.isNotEmpty()) jobsSuccess++ else jobsFailed++
            }

            jobsCompleted++
            processNext()
        }
    }

    // ── Corte lossless ────────────────────────────────────────────────────────

    private suspend fun cutSegment(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit
    ): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackIndexMap = mutableMapOf<Int, Int>()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackIndexMap[i] = muxer.addTrack(format)
                    extractor.selectTrack(i)
                }
            }

            if (trackIndexMap.isEmpty()) return false

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStartUs = extractor.sampleTime.coerceAtLeast(0L)
            val durationUs = (endUs - actualStartUs).coerceAtLeast(1L)

            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                val muxerIdx = trackIndexMap[extractor.sampleTrackIndex]
                if (muxerIdx != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = (sampleTimeUs - actualStartUs).coerceAtLeast(0L)
                    bufferInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(muxerIdx, buffer, bufferInfo)
                }

                onProgress(((sampleTimeUs - actualStartUs).toFloat() / durationUs).coerceIn(0f, 0.99f))
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()
            extractor = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "cutSegment falhou: ${e.message}", e)
            File(outputPath).delete()
            false
        } finally {
            extractor?.release()
            try { muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
    }

    // ── Merge lossless ────────────────────────────────────────────────────────

    private suspend fun mergeSegments(files: List<String>, outputPath: String): Boolean {
        if (files.size == 1) return File(files[0]).renameTo(File(outputPath))

        var muxer: MediaMuxer? = null
        val extractors = mutableListOf<MediaExtractor>()
        return try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            val mimeToMuxerTrack = mutableMapOf<String, Int>()
            var timeOffsetUs = 0L
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (filePath in files) {
                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)
                extractors.add(extractor)

                val localTrackMap = mutableMapOf<Int, Int>()
                var fileDurationUs = 0L

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue

                    extractor.selectTrack(i)

                    val muxerIdx = if (!muxerStarted) {
                        val idx = muxer.addTrack(format)
                        mimeToMuxerTrack[mime] = idx
                        idx
                    } else {
                        mimeToMuxerTrack[mime] ?: continue
                    }
                    localTrackMap[i] = muxerIdx

                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        val dur = format.getLong(MediaFormat.KEY_DURATION)
                        if (dur > fileDurationUs) fileDurationUs = dur
                    }
                }

                if (!muxerStarted) {
                    muxer.start()
                    muxerStarted = true
                }

                var lastSampleTimeUs = 0L
                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val muxerIdx = localTrackMap[extractor.sampleTrackIndex]
                    if (muxerIdx != null) {
                        val sampleTimeUs = extractor.sampleTime
                        lastSampleTimeUs = maxOf(lastSampleTimeUs, sampleTimeUs)
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = sampleTimeUs + timeOffsetUs
                        bufferInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(muxerIdx, buffer, bufferInfo)
                    }
                    extractor.advance()
                }

                if (fileDurationUs == 0L) fileDurationUs = lastSampleTimeUs
                timeOffsetUs += fileDurationUs
                extractor.release()
            }

            muxer.stop()
            muxer.release()
            muxer = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "mergeSegments falhou: ${e.message}", e)
            File(outputPath).delete()
            false
        } finally {
            extractors.forEach { try { it.release() } catch (_: Exception) {} }
            try { muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
    }

    // ── Notificações ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Corte de Vídeo",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progresso do corte de vídeos em background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildProgressNotification(title: String, text: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cut)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .build()

    private fun updateProgressNotification(title: String, text: String, progress: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_PROGRESS_ID, buildProgressNotification(title, text, progress))
    }

    private fun showSummaryNotification() {
        val total = jobsSuccess + jobsFailed
        val text = when {
            total == 0 -> return
            jobsFailed == 0 -> if (total == 1) "Vídeo cortado com sucesso!" else "$total vídeos cortados com sucesso!"
            jobsSuccess == 0 -> if (total == 1) "Falha ao cortar o vídeo" else "Falha ao cortar $total vídeo(s)"
            else -> "$jobsSuccess de $total vídeo(s) cortados com sucesso"
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Corte finalizado")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cut)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(resultNotifId++, notif)
    }

    private fun getOutputDir(): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(dcim, "Cuts")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
