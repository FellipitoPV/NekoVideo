package com.nkls.nekovideo.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.nkls.nekovideo.MainActivity
import com.nkls.nekovideo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

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

        const val ACTION_CANCEL_CURRENT = "com.nkls.nekovideo.ACTION_CANCEL_CURRENT"
        const val ACTION_CANCEL_ALL    = "com.nkls.nekovideo.ACTION_CANCEL_ALL"
        const val ACTION_OPEN_CUTS_FOLDER = "com.nkls.nekovideo.ACTION_OPEN_CUTS_FOLDER"
        const val EXTRA_CUTS_FOLDER_PATH  = "cuts_folder_path"

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

    @Volatile private var jobsQueued = 0
    @Volatile private var jobsCompleted = 0
    @Volatile private var jobsSuccess = 0
    @Volatile private var jobsFailed = 0
    @Volatile private var cancelCurrentJob = false
    @Volatile private var currentSessionId = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        FFmpegKitConfig.enableLogCallback(null)
        FFmpegKitConfig.enableStatisticsCallback(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_CURRENT -> {
                cancelCurrentJob = true
                if (currentSessionId >= 0) FFmpegKit.cancel(currentSessionId)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                cancelCurrentJob = true
                if (currentSessionId >= 0) FFmpegKit.cancel(currentSessionId)
                jobQueue.clear()
                return START_NOT_STICKY
            }
            ACTION_CUT -> { /* continua abaixo */ }
            else -> return START_NOT_STICKY
        }

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
            FolderVideoScanner.startScan(applicationContext, forceRefresh = true)
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
                    cancelCurrentJob = false
                    jobsCompleted++; jobsFailed++
                    processNext()
                    return@launch
                }
                outputFiles.add(outputPath)
            }

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

    // ── Corte lossless via FFmpegKit (-c copy) ────────────────────────────────
    //
    // Funciona para qualquer formato (MP4, WebM, MKV, AVI...) sem re-encoding.
    // O arquivo de saída mantém o mesmo formato e nunca fica maior que o original.

    private suspend fun cutSegment(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit
    ): Boolean = suspendCancellableCoroutine { cont ->

        val startSec = startUs / 1_000_000.0
        val endSec = endUs / 1_000_000.0
        val durationMs = ((endUs - startUs) / 1_000.0).coerceAtLeast(1.0)

        // -ss antes de -i: seek rápido no container, sem decodificar frames
        // -c copy: cópia lossless, sem re-encoding
        // -avoid_negative_ts make_zero: corrige timestamps no ponto de corte
        val command = "-ss $startSec -to $endSec -i \"$inputPath\" -c copy -avoid_negative_ts make_zero -y \"$outputPath\""

        val session = FFmpegKit.executeAsync(
            command,
            { session ->
                currentSessionId = -1
                if (ReturnCode.isSuccess(session.returnCode)) {
                    if (cont.isActive) cont.resume(true)
                } else {
                    Log.e(TAG, "FFmpeg falhou [${session.returnCode}]: ${session.failStackTrace}")
                    File(outputPath).delete()
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
            { statistics ->
                val progressMs = statistics.time.toDouble()
                val progress = (progressMs / durationMs).coerceIn(0.0, 0.99).toFloat()
                onProgress(progress)
            }
        )

        currentSessionId = session.sessionId

        cont.invokeOnCancellation {
            FFmpegKit.cancel(session.sessionId)
            File(outputPath).delete()
        }
    }

    // ── Merge lossless via FFmpegKit (concat demuxer) ─────────────────────────

    private suspend fun mergeSegments(files: List<String>, outputPath: String): Boolean {
        if (files.size == 1) return File(files[0]).renameTo(File(outputPath))

        // Cria arquivo de lista temporário para o concat demuxer
        val concatList = File(cacheDir, "ffmpeg_concat_${System.currentTimeMillis()}.txt")
        return try {
            concatList.writeText(files.joinToString("\n") { "file '${it.replace("'", "\\'")}'" })

            val command = "-f concat -safe 0 -i \"${concatList.absolutePath}\" -c copy -y \"$outputPath\""

            suspendCancellableCoroutine { cont ->
                val session = FFmpegKit.executeAsync(
                    command,
                    { session ->
                        currentSessionId = -1
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            if (cont.isActive) cont.resume(true)
                        } else {
                            Log.e(TAG, "Merge FFmpeg falhou [${session.returnCode}]")
                            File(outputPath).delete()
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                    null
                )
                currentSessionId = session.sessionId

                cont.invokeOnCancellation {
                    FFmpegKit.cancel(session.sessionId)
                    File(outputPath).delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "mergeSegments falhou: ${e.message}", e)
            File(outputPath).delete()
            false
        } finally {
            concatList.delete()
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

    private fun buildProgressNotification(title: String, text: String, progress: Int): android.app.Notification {
        val cancelCurrentPi = PendingIntent.getService(
            this, 10,
            Intent(this, VideoCutService::class.java).apply { action = ACTION_CANCEL_CURRENT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cut)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .addAction(0, "Cancelar", cancelCurrentPi)

        if (jobQueue.isNotEmpty()) {
            val cancelAllPi = PendingIntent.getService(
                this, 11,
                Intent(this, VideoCutService::class.java).apply { action = ACTION_CANCEL_ALL },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Cancelar tudo", cancelAllPi)
        }

        return builder.build()
    }

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

        val cutsDir = getOutputDir()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_CUTS_FOLDER
            putExtra(EXTRA_CUTS_FOLDER_PATH, cutsDir.absolutePath)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 20, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Corte finalizado")
            .setSmallIcon(R.drawable.ic_stat_cut)
            .setAutoCancel(true)
            .setContentIntent(openPi)

        if (jobsSuccess > 0) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText("$text\nSalvo em DCIM/Cuts")
            )
            builder.setContentText(text)
            builder.setSubText("Salvo em DCIM/Cuts")
        } else {
            builder.setContentText(text)
        }

        getSystemService(NotificationManager::class.java).notify(resultNotifId++, builder.build())
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
