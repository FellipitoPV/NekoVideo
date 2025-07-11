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

enum class ThumbnailState {
    IDLE, WAITING, LOADING, LOADED, CANCELLED, ERROR
}

data class VideoMetadata(
    val thumbnail: Bitmap?,
    val duration: String?,
    val fileSize: String?
)

object OptimizedThumbnailManager {
    private val thumbnailSemaphore = Semaphore(3) // Reduzido para 3
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val loadedThumbnails = ConcurrentHashMap<String, Boolean>()
    private val pendingCancellations = ConcurrentHashMap<String, Job>()
    private val thumbnailStates = ConcurrentHashMap<String, ThumbnailState>()
    private val activeTargets = ConcurrentHashMap<String, CustomTarget<Bitmap>>()

    // OTIMIZAÇÃO: LruCache com limite para bitmaps comprimidos
    private val maxMemory = Runtime.getRuntime().maxMemory()
    private val cacheSize = (maxMemory / 8).toInt() // 12.5% da memória

    val thumbnailCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }

        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            oldValue?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
        }
    }

    val durationCache = LruCache<String, String>(200) // Aumentado
    val fileSizeCache = LruCache<String, String>(200)

    // OTIMIZAÇÃO: Pool de MediaMetadataRetriever
    private val retrieverPool = mutableListOf<MediaMetadataRetriever>()
    private val maxPoolSize = 2

    private fun borrowRetriever(): MediaMetadataRetriever {
        return synchronized(retrieverPool) {
            if (retrieverPool.isNotEmpty()) {
                retrieverPool.removeAt(0)
            } else {
                MediaMetadataRetriever()
            }
        }
    }

    private fun returnRetriever(retriever: MediaMetadataRetriever) {
        synchronized(retrieverPool) {
            if (retrieverPool.size < maxPoolSize) {
                try {
                    retriever.release()
                    retrieverPool.add(MediaMetadataRetriever())
                } catch (e: Exception) {
                    // Erro ao reciclar, descarta
                }
            } else {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Ignora erro
                }
            }
        }
    }

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
        return thumbnailCache.get(key)
    }

    // OTIMIZAÇÃO: Carregamento seletivo sob demanda
    suspend fun loadVideoMetadataWithDelay(
        context: Context,
        videoUri: Uri,
        videoPath: String,
        imageLoader: Any?,
        delayMs: Long = 300L, // Reduzido delay padrão
        onMetadataLoaded: (VideoMetadata) -> Unit,
        onCancelled: () -> Unit = {},
        onStateChanged: (ThumbnailState) -> Unit = {}
    ) {
        val key = videoPath.hashCode().toString()

        // Cancela job anterior
        activeJobs[key]?.cancel()
        activeTargets[key]?.let { target ->
            try {
                Glide.with(context).clear(target)
            } catch (e: Exception) {}
            activeTargets.remove(key)
        }

        // OTIMIZAÇÃO: Lê configurações uma vez só
        val showThumbnails = shouldShowThumbnails(context)
        val showDurations = shouldShowDurations(context)
        val showFileSizes = shouldShowFileSizes(context)
        val thumbnailSize = getThumbnailSizeFromSettings(context)

        // OTIMIZAÇÃO: Verificação inteligente de cache
        val cachedThumbnail = if (showThumbnails) thumbnailCache.get(key) else null
        val cachedDuration = if (showDurations) durationCache.get(videoPath) else null
        val cachedFileSize = if (showFileSizes) fileSizeCache.get(videoPath) else null

        // Se tudo que é necessário está em cache, retorna
        val hasAllNeeded = (!showThumbnails || cachedThumbnail != null) &&
                (!showDurations || cachedDuration != null) &&
                (!showFileSizes || cachedFileSize != null)

        if (hasAllNeeded && loadedThumbnails[key] == true) {
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

                    // OTIMIZAÇÃO: Carrega apenas o que é necessário
                    val durationDeferred = if (showDurations && cachedDuration == null) {
                        async(Dispatchers.IO) { getVideoDuration(videoPath) }
                    } else null

                    val fileSizeDeferred = if (showFileSizes && cachedFileSize == null) {
                        async(Dispatchers.IO) { getFileSize(videoPath) }
                    } else null

                    // Thumbnail
                    val thumbnail = if (showThumbnails && cachedThumbnail == null) {
                        loadThumbnailWithGlide(context, videoUri, key, thumbnailSize)
                    } else {
                        cachedThumbnail
                    }

                    val duration = cachedDuration ?: durationDeferred?.await()
                    val fileSize = cachedFileSize ?: fileSizeDeferred?.await()

                    if (isActive && activeJobs[key] == this@launch) {
                        loadedThumbnails[key] = true
                        thumbnailStates[key] = ThumbnailState.LOADED

                        // Cache apenas o que foi carregado
                        if (showThumbnails && thumbnail != null) {
                            thumbnailCache.put(key, thumbnail)
                        }
                        if (showDurations && duration != null) {
                            durationCache.put(videoPath, duration)
                        }
                        if (showFileSizes && fileSize != null) {
                            fileSizeCache.put(videoPath, fileSize)
                        }

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
            }
        }
    }

    // OTIMIZAÇÃO: Função separada para thumbnail
    private suspend fun loadThumbnailWithGlide(
        context: Context,
        videoUri: Uri,
        key: String,
        thumbnailSize: Int
    ): Bitmap? = suspendCancellableCoroutine { continuation ->
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                activeTargets.remove(key)
                if (continuation.isActive) {
                    // OTIMIZAÇÃO: Comprime bitmap se necessário
                    val compressedBitmap = if (resource.byteCount > 1024 * 1024) { // 1MB
                        Bitmap.createScaledBitmap(resource, thumbnailSize, thumbnailSize, true).also {
                            if (resource != it) resource.recycle()
                        }
                    } else {
                        resource
                    }
                    continuation.resume(compressedBitmap)
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
                } catch (e: Exception) {}
                activeTargets.remove(key)
            }
        }
    }

    fun cancelLoading(videoPath: String) {
        val key = videoPath.hashCode().toString()

        // OTIMIZAÇÃO: Cancelamento imediato
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        activeTargets[key]?.let { target ->
            try {
                // Limpa target imediatamente se possível
                target.hashCode() // Test se ainda é válido
            } catch (e: Exception) {}
            activeTargets.remove(key)
        }
        loadedThumbnails.remove(key)
        thumbnailStates[key] = ThumbnailState.CANCELLED
    }

    // OTIMIZAÇÃO: Limpeza inteligente
    fun clearCache() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        activeTargets.clear()
        loadedThumbnails.clear()
        thumbnailStates.clear()

        // Limpa caches de forma segura
        thumbnailCache.evictAll()
        durationCache.evictAll()
        fileSizeCache.evictAll()

        pendingCancellations.values.forEach { it.cancel() }
        pendingCancellations.clear()

        // Limpa pool de retrievers
        synchronized(retrieverPool) {
            retrieverPool.forEach {
                try { it.release() } catch (e: Exception) {}
            }
            retrieverPool.clear()
        }
    }

    // OTIMIZAÇÃO: Usa pool de retrievers
    private fun getVideoDuration(videoPath: String): String? {
        if (!File(videoPath).exists()) return null

        val retriever = borrowRetriever()
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
            returnRetriever(retriever)
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

    // NOVA: Limpeza automática periódica
    private var cleanupJob: Job? = null

    fun startPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(300_000) // 5 minutos
                // Remove jobs antigos inativos
                val currentTime = System.currentTimeMillis()
                thumbnailStates.entries.removeAll { (key, state) ->
                    if (state == ThumbnailState.LOADED || state == ThumbnailState.CANCELLED) {
                        activeJobs[key] == null
                    } else false
                }
            }
        }
    }

    fun stopPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    fun isShowThumbnailsEnabled(context: Context) = shouldShowThumbnails(context)
    fun isShowDurationsEnabled(context: Context) = shouldShowDurations(context)
    fun isShowFileSizesEnabled(context: Context) = shouldShowFileSizes(context)
    fun getCurrentThumbnailSize(context: Context) = getThumbnailSizeFromSettings(context)

    // NOVA: Função para obter estatísticas
    fun getCacheStats(): String {
        val thumbnailSize = thumbnailCache.size()
        val thumbnailMemory = thumbnailCache.size()
        val activeJobsCount = activeJobs.size
        val activeTargetsCount = activeTargets.size

        return "Thumbnails: $thumbnailSize | Jobs: $activeJobsCount | Targets: $activeTargetsCount | Memory: ${thumbnailMemory / 1024}KB"
    }
}