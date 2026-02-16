package com.nkls.nekovideo.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.nkls.nekovideo.components.helpers.FolderLockManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
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
    private val thumbnailSemaphore = Semaphore(3)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val loadedThumbnails = ConcurrentHashMap<String, Boolean>()
    private val pendingCancellations = ConcurrentHashMap<String, Job>()
    private val thumbnailStates = ConcurrentHashMap<String, ThumbnailState>()
    private val activeTargets = ConcurrentHashMap<String, CustomTarget<Bitmap>>()

    private val THUMB_XOR_KEY = byteArrayOf(0x4E, 0x45, 0x4B, 0x4F) // "NEKO"
    private const val THUMBS_DIR = ".neko_thumbs"

    // Cache em memória (RAM)
    private var _thumbnailCache: LruCache<String, Bitmap>? = null
    private var lastCacheSize = -1

    val thumbnailCache: LruCache<String, Bitmap>
        get() = _thumbnailCache ?: createDefaultCache().also { _thumbnailCache = it }

    // Retorna .neko_thumbs/{videoFileNameSemExtensao} na pasta do vídeo
    private fun getThumbnailFile(videoPath: String): File {
        val videoFile = File(videoPath)
        val thumbsDir = File(videoFile.parentFile, THUMBS_DIR)
        return File(thumbsDir, videoFile.nameWithoutExtension)
    }

    // Salva thumbnail XOR-encriptada na pasta do vídeo
    private fun saveThumbnailToDisk(videoPath: String, bitmap: Bitmap): Boolean {
        return try {
            val thumbFile = getThumbnailFile(videoPath)
            val thumbsDir = thumbFile.parentFile!!

            if (!thumbsDir.exists()) {
                thumbsDir.mkdirs()
                // Cria .nomedia para o Android não indexar
                File(thumbsDir, ".nomedia").createNewFile()
            }

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val thumbBytes = baos.toByteArray()

            // XOR encrypt
            for (i in thumbBytes.indices) {
                thumbBytes[i] = (thumbBytes[i].toInt() xor THUMB_XOR_KEY[i % THUMB_XOR_KEY.size].toInt()).toByte()
            }

            thumbFile.writeBytes(thumbBytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Carrega thumbnail XOR-decriptada do disco
    private fun loadThumbnailFromDisk(videoPath: String): Bitmap? {
        return try {
            val thumbFile = getThumbnailFile(videoPath)
            if (!thumbFile.exists()) return null

            // Verifica se o vídeo ainda existe
            if (!File(videoPath).exists()) {
                thumbFile.delete()
                return null
            }

            val thumbBytes = thumbFile.readBytes()
            // XOR decrypt (mesma operação)
            for (i in thumbBytes.indices) {
                thumbBytes[i] = (thumbBytes[i].toInt() xor THUMB_XOR_KEY[i % THUMB_XOR_KEY.size].toInt()).toByte()
            }

            BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Função pública síncrona para buscar do disco
    fun loadThumbnailFromDiskSync(videoPath: String): Bitmap? {
        return loadThumbnailFromDisk(videoPath)
    }

    /**
     * Cria uma thumbnail quadrada com CENTER CROP (corta no centro ao invés de amassar)
     */
    private fun createCenterCroppedThumbnail(source: Bitmap, targetSize: Int): Bitmap {
        val sourceWidth = source.width
        val sourceHeight = source.height

        // Determina o menor lado para cortar um quadrado no centro
        val cropSize = minOf(sourceWidth, sourceHeight)

        // Calcula offset para centralizar o corte
        val xOffset = (sourceWidth - cropSize) / 2
        val yOffset = (sourceHeight - cropSize) / 2

        // Corta o quadrado central
        val croppedBitmap = Bitmap.createBitmap(source, xOffset, yOffset, cropSize, cropSize)

        // Redimensiona para o tamanho final
        val scaledBitmap = if (cropSize != targetSize) {
            Bitmap.createScaledBitmap(croppedBitmap, targetSize, targetSize, true).also {
                if (croppedBitmap != source && croppedBitmap != it) {
                    croppedBitmap.recycle()
                }
            }
        } else {
            croppedBitmap
        }

        return scaledBitmap
    }

    /**
     * Gera thumbnail de forma síncrona usando MediaMetadataRetriever.
     * DEVE ser chamada em thread de background (IO).
     * Retorna a thumbnail gerada e salva no disco, ou null se falhar.
     */
    fun generateThumbnailSync(context: Context, videoPath: String): Bitmap? {
        // Primeiro verifica se já existe em cache (RAM ou disco)
        val key = videoPath.hashCode().toString()

        // 1. Verifica RAM
        val ramBitmap = thumbnailCache.get(key)
        if (ramBitmap != null && !ramBitmap.isRecycled) {
            return ramBitmap
        }

        // 2. Verifica disco
        val diskBitmap = loadThumbnailFromDisk(videoPath)
        if (diskBitmap != null) {
            thumbnailCache.put(key, diskBitmap)
            return diskBitmap
        }

        // 3. Gera nova thumbnail
        val retriever = MediaMetadataRetriever()
        return try {
            val cleanPath = videoPath.removePrefix("file://")
            if (!File(cleanPath).exists()) return null

            retriever.setDataSource(cleanPath)

            // Pega frame do início (1 segundo)
            val bitmap = retriever.getFrameAtTime(
                1_000_000, // 1 segundo em microsegundos
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )

            if (bitmap != null) {
                // Redimensiona para tamanho otimizado COM CENTER CROP
                val thumbnailSize = getThumbnailSizeFromSettings(context)
                val scaledBitmap = createCenterCroppedThumbnail(bitmap, thumbnailSize)

                // Salva no disco (per-folder .neko_thumbs)
                saveThumbnailToDisk(cleanPath, scaledBitmap)

                // Adiciona ao cache RAM
                thumbnailCache.put(key, scaledBitmap)

                // Recicla bitmap original se diferente
                if (bitmap != scaledBitmap) {
                    bitmap.recycle()
                }

                scaledBitmap
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignora erro ao liberar
            }
        }
    }

    /**
     * Obtém thumbnail de forma síncrona: busca em cache ou gera se necessário.
     * DEVE ser chamada em thread de background (IO).
     */
    fun getOrGenerateThumbnailSync(context: Context, videoPath: String): Bitmap? {
        val cleanPath = videoPath.removePrefix("file://")
        return generateThumbnailSync(context, cleanPath)
    }

    /**
     * Sincroniza thumbnails ao entrar numa pasta:
     * - Remove thumbnails órfãs (vídeo deletado mas thumb ficou)
     * - Pré-gera thumbnails faltantes em background
     * Não sincroniza pastas locked (elas gerenciam suas próprias thumbs)
     */
    fun syncThumbnails(context: Context, folderPath: String) {
        // Não sincroniza pastas locked
        if (FolderLockManager.isLocked(folderPath)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val folder = File(folderPath)
                if (!folder.exists() || !folder.isDirectory) return@launch

                val thumbsDir = File(folder, THUMBS_DIR)

                // 1. Remove thumbnails órfãs
                if (thumbsDir.exists()) {
                    val videoNamesWithoutExt = folder.listFiles()
                        ?.filter { it.isFile && isVideoFile(it.name) }
                        ?.map { it.nameWithoutExtension }
                        ?.toSet() ?: emptySet()

                    thumbsDir.listFiles()?.forEach { thumbFile ->
                        if (thumbFile.name != ".nomedia" && thumbFile.name !in videoNamesWithoutExt) {
                            thumbFile.delete()
                        }
                    }
                }

                // 2. Pré-gera thumbnails faltantes
                val videoFiles = folder.listFiles()
                    ?.filter { it.isFile && isVideoFile(it.name) }
                    ?: return@launch

                for (videoFile in videoFiles) {
                    val thumbFile = File(thumbsDir, videoFile.nameWithoutExtension)
                    if (!thumbFile.exists()) {
                        // Gera thumbnail (inclui salvar no disco)
                        generateThumbnailSync(context, videoFile.absolutePath)
                        delay(50) // Pequeno delay entre gerações
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "mpg", "mpeg")
    }

    /**
     * Limpa o cache centralizado antigo (one-time migration).
     * Chamado do MainActivity.onCreate().
     */
    fun clearOldCentralizedCache(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val oldCacheDir = File(context.cacheDir, "video_thumbnails")
                if (oldCacheDir.exists()) {
                    oldCacheDir.deleteRecursively()
                }
                val oldIndexFile = File(context.cacheDir, "thumbnail_cache_index.txt")
                if (oldIndexFile.exists()) {
                    oldIndexFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createDefaultCache(): LruCache<String, Bitmap> {
        val defaultSizeBytes = 50 * 1024 * 1024 // 50MB em memória
        return object : LruCache<String, Bitmap>(defaultSizeBytes) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount
            }

            override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
                // Não recicla - Android gerencia
            }
        }
    }

    fun reconfigureCache(context: Context) {
        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        val newCacheSizeMB = prefs.getInt("cache_size_mb", 100)

        if (newCacheSizeMB != lastCacheSize) {
            val newCacheSizeBytes = (newCacheSizeMB * 0.5 * 1024 * 1024).toInt() // 50% para RAM

            val newCache = object : LruCache<String, Bitmap>(newCacheSizeBytes) {
                override fun sizeOf(key: String, bitmap: Bitmap): Int {
                    return bitmap.byteCount
                }

                override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
                    // Não recicla
                }
            }

            val oldCache = _thumbnailCache
            _thumbnailCache = newCache
            lastCacheSize = newCacheSizeMB

            oldCache?.evictAll()
        }
    }

    private fun ensureCacheInitialized(context: Context) {
        if (_thumbnailCache == null) {
            reconfigureCache(context)
        }
    }

    val durationCache = LruCache<String, String>(200)
    val fileSizeCache = LruCache<String, String>(200)

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
                } catch (e: Exception) {}
            } else {
                try {
                    retriever.release()
                } catch (e: Exception) {}
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

    // Busca em RAM apenas (disco é feito no loadVideoMetadataWithDelay)
    fun getCachedThumbnail(videoPath: String): Bitmap? {
        val key = videoPath.hashCode().toString()

        val ramBitmap = thumbnailCache.get(key)
        if (ramBitmap != null && !ramBitmap.isRecycled) {
            return ramBitmap
        }

        return null
    }

    // Carregamento com cache persistente per-folder
    suspend fun loadVideoMetadataWithDelay(
        context: Context,
        videoUri: Uri,
        videoPath: String,
        imageLoader: Any?,
        delayMs: Long = 300L,
        onMetadataLoaded: (VideoMetadata) -> Unit,
        onCancelled: () -> Unit = {},
        onStateChanged: (ThumbnailState) -> Unit = {}
    ) {
        ensureCacheInitialized(context)
        val key = videoPath.hashCode().toString()

        activeJobs[key]?.cancel()
        activeTargets[key]?.let { target ->
            try {
                Glide.with(context).clear(target)
            } catch (e: Exception) {}
            activeTargets.remove(key)
        }

        val showThumbnails = shouldShowThumbnails(context)
        val showDurations = shouldShowDurations(context)
        val showFileSizes = shouldShowFileSizes(context)
        val thumbnailSize = getThumbnailSizeFromSettings(context)

        // 1. Verifica cache em RAM, depois disco
        var cachedThumbnail = if (showThumbnails) {
            val ramBitmap = thumbnailCache.get(key)
            if (ramBitmap != null && !ramBitmap.isRecycled) {
                ramBitmap
            } else {
                // Busca do disco (per-folder .neko_thumbs)
                loadThumbnailFromDisk(videoPath)?.also { diskBitmap ->
                    thumbnailCache.put(key, diskBitmap)
                }
            }
        } else null

        val cachedDuration = if (showDurations) durationCache.get(videoPath) else null
        val cachedFileSize = if (showFileSizes) fileSizeCache.get(videoPath) else null

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

                    val durationDeferred = if (showDurations && cachedDuration == null) {
                        async(Dispatchers.IO) { getVideoDuration(videoPath) }
                    } else null

                    val fileSizeDeferred = if (showFileSizes && cachedFileSize == null) {
                        async(Dispatchers.IO) { getFileSize(videoPath) }
                    } else null

                    // Gera thumbnail se necessário
                    val thumbnail = if (showThumbnails && cachedThumbnail == null) {
                        loadThumbnailWithGlide(context, videoUri, key, thumbnailSize)?.also { newBitmap ->
                            // Salva no disco (per-folder .neko_thumbs)
                            withContext(Dispatchers.IO) {
                                saveThumbnailToDisk(videoPath, newBitmap)
                            }
                        }
                    } else {
                        cachedThumbnail
                    }

                    val duration = cachedDuration ?: durationDeferred?.await()
                    val fileSize = cachedFileSize ?: fileSizeDeferred?.await()

                    if (isActive && activeJobs[key] == this@launch) {
                        loadedThumbnails[key] = true
                        thumbnailStates[key] = ThumbnailState.LOADED

                        if (showThumbnails && thumbnail != null && !thumbnail.isRecycled) {
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
                    // Glide já faz centerCrop, então só precisa redimensionar se muito grande
                    val compressedBitmap = if (resource.byteCount > 1024 * 1024) {
                        createCenterCroppedThumbnail(resource, thumbnailSize).also {
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

        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        activeTargets[key]?.let { target ->
            try {
                target.hashCode()
            } catch (e: Exception) {}
            activeTargets.remove(key)
        }
        loadedThumbnails.remove(key)
        thumbnailStates[key] = ThumbnailState.CANCELLED
    }

    fun clearCache() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        activeTargets.clear()
        loadedThumbnails.clear()
        thumbnailStates.clear()

        _thumbnailCache?.evictAll()
        durationCache.evictAll()
        fileSizeCache.evictAll()

        pendingCancellations.values.forEach { it.cancel() }
        pendingCancellations.clear()

        synchronized(retrieverPool) {
            retrieverPool.forEach {
                try { it.release() } catch (e: Exception) {}
            }
            retrieverPool.clear()
        }

        _thumbnailCache = null
        lastCacheSize = -1
    }

    // Limpa thumbnail de um vídeo específico (RAM + disco)
    fun clearCacheForPath(videoPath: String) {
        val key = videoPath.hashCode().toString()

        // Remove da RAM
        thumbnailCache.remove(key)

        // Remove estados
        loadedThumbnails.remove(key)
        thumbnailStates[key] = ThumbnailState.IDLE

        // Cancela jobs ativos
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        activeTargets.remove(key)

        // Remove do disco (.neko_thumbs)
        try {
            val thumbFile = getThumbnailFile(videoPath)
            if (thumbFile.exists()) {
                thumbFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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

    private var cleanupJob: Job? = null

    fun startPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(300_000) // 5 minutos
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

    // Estatísticas (RAM only)
    fun getCacheStats(): String {
        val thumbnailMemoryBytes = _thumbnailCache?.size() ?: 0
        val thumbnailMemoryMB = thumbnailMemoryBytes / (1024 * 1024)

        val activeJobsCount = activeJobs.size
        val activeTargetsCount = activeTargets.size

        return """
            RAM: ${thumbnailMemoryMB}MB
            Jobs: $activeJobsCount | Targets: $activeTargetsCount
        """.trimIndent()
    }

    fun onSettingsChanged(context: Context) {
        reconfigureCache(context)
    }
}
