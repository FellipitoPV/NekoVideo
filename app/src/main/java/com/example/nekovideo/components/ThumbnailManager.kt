package com.example.nekovideo.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.util.concurrent.TimeUnit

object ThumbnailManager {

    fun getVideoThumbnail(context: Context, videoPath: String, videoUri: Uri?): Bitmap? {
        if (!File(videoPath).exists()) {
            println("ThumbnailManager: Video file does not exist: $videoPath")
            return null
        }

        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)

                // Obter duração e capturar na metade
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L
                val timeUs = if (duration > 0) (duration * 1000 / 2) else 3000000L

                // Thumbnail com qualidade reduzida
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    120, 120
                ) ?: throw Exception("Failed to generate thumbnail")
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    println("ThumbnailManager: Error releasing retriever for $videoPath: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("ThumbnailManager: Error generating thumbnail for $videoPath: ${e.message}")
            null
        }
    }

    fun getVideoDuration(context: Context, videoPath: String): String? {
        if (!File(videoPath).exists()) {
            println("ThumbnailManager: Video file does not exist: $videoPath")
            return null
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            durationMs?.let {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(it)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(it) % 60
                String.format("%02d:%02d", minutes, seconds)
            } ?: run {
                println("ThumbnailManager: No duration metadata for $videoPath")
                null
            }
        } catch (e: Exception) {
            println("ThumbnailManager: Error getting duration for $videoPath: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                println("ThumbnailManager: Error releasing retriever for $videoPath: ${e.message}")
            }
        }
    }
}