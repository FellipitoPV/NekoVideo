package com.example.nekovideo.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// Estados da thumbnail
enum class ThumbnailState {
    IDLE, WAITING, LOADING, LOADED, CANCELLED, ERROR
}

data class VideoMetadata(
    val thumbnail: Bitmap?,
    val duration: String?,
    val fileSize: String?
)

object OptimizedThumbnailManager {
    private val thumbnailSemaphore = Semaphore(4)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val loadedThumbnails = ConcurrentHashMap<String, Boolean>()
    private val pendingCancellations = ConcurrentHashMap<String, Job>()
    private val thumbnailStates = ConcurrentHashMap<String, ThumbnailState>()
    val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    val durationCache = LruCache<String, String>(100)
    val fileSizeCache = LruCache<String, String>(100)
    private val activeTargets = ConcurrentHashMap<String, CustomTarget<Bitmap>>()

    // Configurações baseadas nas settings
    private fun getThumbnailSizeFromSettings(context: Context): Int {
        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        return when (prefs.getString("thumbnail_quality", "medium")) {
            "low" -> 96
            "medium" -> 120
            "high" -> 150
            "original" -> 200
            else -> 120
        }
    }

    private fun shouldShowThumbnails(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("show_thumbnails", true)
    }

    private fun shouldShowDurations(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("show_durations", true)
    }

    private fun shouldShowFileSizes(context: Context): Boolean {
        return context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            .getBoolean("show_file_sizes", false)
    }

    // Fake ImageLoader para manter compatibilidade
    class FakeImageLoader

    @Composable
    fun rememberVideoImageLoader(): FakeImageLoader {
        return remember { FakeImageLoader() }
    }

    fun getThumbnailState(videoPath: String): ThumbnailState {
        val key = videoPath.hashCode().toString()
        return thumbnailStates[key] ?: ThumbnailState.IDLE
    }

    fun getCachedThumbnail(videoPath: String): Bitmap? {
        val key = videoPath.hashCode().toString()
        return thumbnailCache[key]
    }

    suspend fun loadVideoMetadataWithDelay(
        context: Context,
        videoUri: Uri,
        videoPath: String,
        imageLoader: Any?, // Ignorado no Glide
        delayMs: Long = 500L,
        onMetadataLoaded: (VideoMetadata) -> Unit,
        onCancelled: () -> Unit = {},
        onStateChanged: (ThumbnailState) -> Unit = {}
    ) {
        val key = videoPath.hashCode().toString()

        // Cancela job anterior e limpa target
        activeJobs[key]?.cancel()
        activeTargets[key]?.let { target ->
            try {
                Glide.with(context).clear(target)
            } catch (e: Exception) {
                // Ignora erro se contexto foi destruído
            }
            activeTargets.remove(key)
        }

        // SEMPRE lê configurações para saber tamanho da thumbnail
        val thumbnailSize = getThumbnailSizeFromSettings(context)

        // Verifica cache em memória - SEMPRE verifica todos os dados
        val cachedThumbnail = thumbnailCache[key]
        val cachedDuration = durationCache.get(videoPath)
        val cachedFileSize = fileSizeCache.get(videoPath)

        // Se todos os dados estão em cache, retorna imediatamente
        if (cachedThumbnail != null && cachedDuration != null && cachedFileSize != null && loadedThumbnails[key] == true) {
            thumbnailStates[key] = ThumbnailState.LOADED
            onStateChanged(ThumbnailState.LOADED)
            onMetadataLoaded(VideoMetadata(cachedThumbnail, cachedDuration, cachedFileSize))
            return
        }

        thumbnailStates[key] = ThumbnailState.WAITING
        onStateChanged(ThumbnailState.WAITING)

        activeJobs[key] = CoroutineScope(Dispatchers.Main).launch {
            try {
                delay(delayMs)

                if (!isActive) {
                    thumbnailStates[key] = ThumbnailState.CANCELLED
                    onStateChanged(ThumbnailState.CANCELLED)
                    return@launch
                }

                thumbnailStates[key] = ThumbnailState.LOADING
                onStateChanged(ThumbnailState.LOADING)

                thumbnailSemaphore.withPermit {
                    if (!isActive) {
                        thumbnailStates[key] = ThumbnailState.CANCELLED
                        onStateChanged(ThumbnailState.CANCELLED)
                        return@withPermit
                    }

                    // SEMPRE carrega todos os metadata em paralelo
                    val durationDeferred = async(Dispatchers.IO) {
                        cachedDuration ?: getVideoDuration(videoPath)
                    }

                    val fileSizeDeferred = async(Dispatchers.IO) {
                        cachedFileSize ?: getFileSize(videoPath)
                    }

                    // SEMPRE carrega thumbnail
                    val thumbnail = if (cachedThumbnail != null) {
                        cachedThumbnail
                    } else {
                        suspendCancellableCoroutine<Bitmap?> { continuation ->
                            val target = object : CustomTarget<Bitmap>() {
                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    activeTargets.remove(key)
                                    if (continuation.isActive) {
                                        continuation.resume(resource)
                                    }
                                }

                                override fun onLoadFailed(errorDrawable: Drawable?) {
                                    activeTargets.remove(key)
                                    if (continuation.isActive) {
                                        continuation.resume(null)
                                    }
                                }

                                override fun onLoadCleared(placeholder: Drawable?) {
                                    activeTargets.remove(key)
                                    if (continuation.isActive) {
                                        continuation.resume(null)
                                    }
                                }
                            }

                            activeTargets[key] = target

                            try {
                                Glide.with(context)
                                    .asBitmap()
                                    .load(videoUri)
                                    .override(thumbnailSize, thumbnailSize)
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                                    .centerCrop()
                                    .into(target)
                            } catch (e: Exception) {
                                activeTargets.remove(key)
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }

                            continuation.invokeOnCancellation {
                                activeTargets[key]?.let { activeTarget ->
                                    try {
                                        Glide.with(context).clear(activeTarget)
                                    } catch (e: Exception) {
                                        // Ignora erro se contexto foi destruído
                                    }
                                    activeTargets.remove(key)
                                }
                            }
                        }
                    }

                    val duration = durationDeferred.await()
                    val fileSize = fileSizeDeferred.await()

                    if (isActive && activeJobs[key] == this@launch) {
                        loadedThumbnails[key] = true
                        thumbnailStates[key] = ThumbnailState.LOADED

                        // SEMPRE faz cache de todos os dados
                        thumbnail?.let { thumbnailCache[key] = it }
                        duration?.let { durationCache.put(videoPath, it) }
                        fileSize?.let { fileSizeCache.put(videoPath, it) }

                        onStateChanged(ThumbnailState.LOADED)
                        onMetadataLoaded(VideoMetadata(thumbnail, duration, fileSize))
                    }
                }
            } catch (e: CancellationException) {
                thumbnailStates[key] = ThumbnailState.CANCELLED
                onStateChanged(ThumbnailState.CANCELLED)
                onCancelled()
            } catch (e: Exception) {
                thumbnailStates[key] = ThumbnailState.ERROR
                onStateChanged(ThumbnailState.ERROR)
                onCancelled()
                println("Erro ao carregar metadata: ${e.message}")
            }
        }
    }

    fun cancelLoading(videoPath: String) {
        val key = videoPath.hashCode().toString()
        pendingCancellations[key]?.cancel()

        pendingCancellations[key] = CoroutineScope(Dispatchers.IO).launch {
            delay(300L)

            activeJobs[key]?.cancel()
            activeJobs.remove(key)
            activeTargets.remove(key)
            loadedThumbnails.remove(key)
            thumbnailStates[key] = ThumbnailState.CANCELLED
            pendingCancellations.remove(key)
        }
    }

    fun clearCache() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        activeTargets.values.forEach { target ->
            try {
                target.hashCode()
            } catch (e: Exception) {
                // Target já foi limpo ou contexto destruído
            }
        }
        activeTargets.clear()

        loadedThumbnails.clear()
        thumbnailStates.clear()
        thumbnailCache.clear()
        durationCache.evictAll()
        fileSizeCache.evictAll()

        pendingCancellations.values.forEach { it.cancel() }
        pendingCancellations.clear()
    }

    private fun getVideoDuration(videoPath: String): String? {
        if (!File(videoPath).exists()) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            durationMs?.let {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(it)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(it) % 60
                String.format("%02d:%02d", minutes, seconds)
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignora erro de release
            }
        }
    }

    private fun getFileSize(videoPath: String): String? {
        return try {
            val file = File(videoPath)
            if (!file.exists()) return null

            val bytes = file.length()
            val df = DecimalFormat("#.##")

            when {
                bytes < 1024 -> "${bytes}B"
                bytes < 1024 * 1024 -> "${df.format(bytes / 1024.0)}KB"
                bytes < 1024 * 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0))}MB"
                else -> "${df.format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
            }
        } catch (e: Exception) {
            null
        }
    }

    // Funções utilitárias para acessar configurações
    fun isShowThumbnailsEnabled(context: Context) = shouldShowThumbnails(context)
    fun isShowDurationsEnabled(context: Context) = shouldShowDurations(context)
    fun isShowFileSizesEnabled(context: Context) = shouldShowFileSizes(context)
    fun getCurrentThumbnailSize(context: Context) = getThumbnailSizeFromSettings(context)
}