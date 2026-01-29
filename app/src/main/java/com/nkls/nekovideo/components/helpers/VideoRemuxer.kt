package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Helper para fazer remux de vídeos MP4 que não possuem metadados de duração.
 * O remux apenas reescreve o container sem re-encodar, preservando a qualidade original.
 */
object VideoRemuxer {
    private const val TAG = "VideoRemuxer"
    private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    sealed class RemuxResult {
        data class Success(val outputPath: String) : RemuxResult()
        data class Error(val message: String) : RemuxResult()
    }

    /**
     * Faz o remux de um vídeo MP4, corrigindo os metadados de duração.
     * O arquivo original é substituído pelo arquivo corrigido.
     *
     * @param context Context do Android
     * @param inputPath Caminho do vídeo original
     * @param onProgress Callback de progresso (0.0 a 1.0)
     * @return RemuxResult indicando sucesso ou erro
     */
    suspend fun remuxVideo(
        context: Context,
        inputPath: String,
        onProgress: ((Float) -> Unit)? = null
    ): RemuxResult = withContext(Dispatchers.IO) {
        val inputFile = File(inputPath)

        if (!inputFile.exists()) {
            return@withContext RemuxResult.Error("Arquivo não encontrado")
        }

        val tempFile = File(inputFile.parent, ".temp_${System.currentTimeMillis()}_${inputFile.name}")

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                return@withContext RemuxResult.Error("Nenhuma track encontrada no vídeo")
            }

            // Criar o muxer
            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Mapear tracks do extractor para o muxer
            val trackIndexMap = mutableMapOf<Int, Int>()
            var totalDuration = 0L

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                // Só processar video e audio
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrackIndex = muxer.addTrack(format)
                    trackIndexMap[i] = muxerTrackIndex
                    extractor.selectTrack(i)

                    // Pegar duração se disponível
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        val duration = format.getLong(MediaFormat.KEY_DURATION)
                        if (duration > totalDuration) {
                            totalDuration = duration
                        }
                    }
                }
            }

            if (trackIndexMap.isEmpty()) {
                return@withContext RemuxResult.Error("Nenhuma track de vídeo ou áudio encontrada")
            }

            muxer.start()

            // Buffer para copiar os dados
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            var totalBytesRead = 0L
            val fileSize = inputFile.length()

            // Copiar todos os samples
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)

                if (sampleSize < 0) {
                    break
                }

                val trackIndex = extractor.sampleTrackIndex
                val muxerTrackIndex = trackIndexMap[trackIndex]

                if (muxerTrackIndex != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }

                totalBytesRead += sampleSize
                onProgress?.invoke((totalBytesRead.toFloat() / fileSize).coerceIn(0f, 1f))

                extractor.advance()
            }

            // Finalizar
            muxer.stop()
            muxer.release()
            muxer = null

            extractor.release()
            extractor = null

            // Substituir o arquivo original pelo novo
            val backupFile = File(inputFile.parent, ".backup_${inputFile.name}")

            // Fazer backup do original
            if (!inputFile.renameTo(backupFile)) {
                tempFile.delete()
                return@withContext RemuxResult.Error("Falha ao fazer backup do arquivo original")
            }

            // Mover o novo arquivo para o lugar do original
            if (!tempFile.renameTo(inputFile)) {
                // Restaurar backup se falhar
                backupFile.renameTo(inputFile)
                tempFile.delete()
                return@withContext RemuxResult.Error("Falha ao substituir o arquivo original")
            }

            // Remover backup
            backupFile.delete()

            Log.d(TAG, "Remux concluído com sucesso: $inputPath")
            return@withContext RemuxResult.Success(inputPath)

        } catch (e: Exception) {
            Log.e(TAG, "Erro durante remux: ${e.message}", e)
            tempFile.delete()
            return@withContext RemuxResult.Error(e.message ?: "Erro desconhecido durante o remux")
        } finally {
            try {
                extractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao liberar extractor", e)
            }
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao liberar muxer", e)
            }
        }
    }
}
