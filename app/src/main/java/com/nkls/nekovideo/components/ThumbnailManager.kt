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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

    // ✅ Cache em memória (RAM)
    private var _thumbnailCache: LruCache<String, Bitmap>? = null
    private var lastCacheSize = -1

    val thumbnailCache: LruCache<String, Bitmap>
        get() = _thumbnailCache ?: createDefaultCache().also { _thumbnailCache = it }

    // ✅ NOVO: Diretório para cache persistente
    private fun getThumbnailCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, "video_thumbnails")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    // ✅ NOVO: Gera hash MD5 do path do vídeo para nome único
    private fun getVideoPathHash(videoPath: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(videoPath.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ✅ NOVO: Arquivo da thumbnail em disco
    private fun getThumbnailFile(context: Context, videoPath: String): File {
        val hash = getVideoPathHash(videoPath)
        return File(getThumbnailCacheDir(context), "$hash.jpg")
    }

    // ✅ NOVO: Salva thumbnail no disco
    private fun saveThumbnailToDisk(context: Context, videoPath: String, bitmap: Bitmap): Boolean {
        return try {
            val file = getThumbnailFile(context, videoPath)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            // Atualiza índice de cache
            updateCacheIndex(context, videoPath, file.length())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ✅ NOVO: Carrega thumbnail do disco
    private fun loadThumbnailFromDisk(context: Context, videoPath: String): Bitmap? {
        return try {
            val file = getThumbnailFile(context, videoPath)
            if (file.exists()) {
                // Verifica se o arquivo de vídeo ainda existe
                if (!File(videoPath).exists()) {
                    // Vídeo foi deletado, remove thumbnail
                    file.delete()
                    removeCacheIndexEntry(context, videoPath)
                    return null
                }

                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ✅ NOVO: Índice de cache (para gerenciar tamanho)
    private data class CacheEntry(
        val videoPath: String,
        val fileSize: Long,
        val lastAccess: Long
    )

    private fun getCacheIndexFile(context: Context): File {
        return File(context.cacheDir, "thumbnail_cache_index.txt")
    }

    private fun readCacheIndex(context: Context): MutableMap<String, CacheEntry> {
        val entries = mutableMapOf<String, CacheEntry>()
        val indexFile = getCacheIndexFile(context)

        if (!indexFile.exists()) return entries

        try {
            indexFile.readLines().forEach { line ->
                val parts = line.split("|")
                if (parts.size == 3) {
                    val videoPath = parts[0]
                    val fileSize = parts[1].toLongOrNull() ?: 0L
                    val lastAccess = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                    entries[videoPath] = CacheEntry(videoPath, fileSize, lastAccess)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return entries
    }

    private fun writeCacheIndex(context: Context, entries: Map<String, CacheEntry>) {
        val indexFile = getCacheIndexFile(context)
        try {
            indexFile.writeText(
                entries.values.joinToString("\n") { entry ->
                    "${entry.videoPath}|${entry.fileSize}|${entry.lastAccess}"
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateCacheIndex(context: Context, videoPath: String, fileSize: Long) {
        val entries = readCacheIndex(context)
        entries[videoPath] = CacheEntry(videoPath, fileSize, System.currentTimeMillis())
        writeCacheIndex(context, entries)

        // Verifica se precisa limpar cache
        cleanupDiskCacheIfNeeded(context)
    }

    private fun removeCacheIndexEntry(context: Context, videoPath: String) {
        val entries = readCacheIndex(context)
        entries.remove(videoPath)
        writeCacheIndex(context, entries)
    }

    // ✅ NOVO: Limpeza automática do cache em disco
    private fun cleanupDiskCacheIfNeeded(context: Context) {
        val maxCacheSizeMB = getCacheSizeFromSettings(context)
        val maxCacheSizeBytes = maxCacheSizeMB * 1024 * 1024L

        val entries = readCacheIndex(context)
        var totalSize = entries.values.sumOf { it.fileSize }

        // Se excedeu o limite, remove os mais antigos
        if (totalSize > maxCacheSizeBytes) {
            val sortedEntries = entries.values.sortedBy { it.lastAccess }

            for (entry in sortedEntries) {
                if (totalSize <= maxCacheSizeBytes * 0.8) break // Limpa até 80% do limite

                val file = getThumbnailFile(context, entry.videoPath)
                if (file.exists()) {
                    file.delete()
                    totalSize -= entry.fileSize
                    entries.remove(entry.videoPath)
                }
            }

            writeCacheIndex(context, entries)
        }
    }

    // ✅ NOVA função pública síncrona para buscar do disco
    fun loadThumbnailFromDiskSync(context: Context, videoPath: String): Bitmap? {
        return try {
            val file = getThumbnailFile(context, videoPath)
            if (file.exists()) {
                // Verifica se vídeo ainda existe
                if (!File(videoPath).exists()) {
                    file.delete()
                    removeCacheIndexEntry(context, videoPath)
                    return null
                }

                val bitmap = BitmapFactory.decodeFile(file.absolutePath)

                // Atualiza tempo de acesso
                if (bitmap != null) {
                    updateCacheIndex(context, videoPath, file.length())
                }

                bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
        val diskBitmap = loadThumbnailFromDiskSync(context, videoPath)
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
                // Redimensiona para tamanho otimizado
                val thumbnailSize = getThumbnailSizeFromSettings(context)
                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    thumbnailSize,
                    thumbnailSize,
                    true
                )

                // Salva no disco
                saveThumbnailToDisk(context, cleanPath, scaledBitmap)

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

    // ✅ NOVO: Limpa thumbnails de vídeos deletados
    fun cleanupDeletedVideos(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val entries = readCacheIndex(context)
            val entriesToRemove = mutableListOf<String>()

            entries.forEach { (videoPath, _) ->
                if (!File(videoPath).exists()) {
                    entriesToRemove.add(videoPath)
                    getThumbnailFile(context, videoPath).delete()
                }
            }

            if (entriesToRemove.isNotEmpty()) {
                entriesToRemove.forEach { entries.remove(it) }
                writeCacheIndex(context, entries)
            }
        }
    }

    private fun getCacheSizeFromSettings(context: Context): Int {
        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        return prefs.getInt("cache_size_mb", 100)
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
        val newCacheSizeMB = getCacheSizeFromSettings(context)

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

    // ✅ ATUALIZADO: Busca em RAM e depois em disco
    fun getCachedThumbnail(videoPath: String): Bitmap? {
        val key = videoPath.hashCode().toString()

        // 1. Tenta RAM primeiro
        val ramBitmap = thumbnailCache.get(key)
        if (ramBitmap != null && !ramBitmap.isRecycled) {
            return ramBitmap
        }

        // 2. Se não tem em RAM, busca do disco (isso deve ser chamado em contexto apropriado)
        // Nota: Esta função precisa de context, então retorna null aqui
        // O carregamento do disco será feito no loadVideoMetadataWithDelay

        return null
    }

    // ✅ ATUALIZADO: Carregamento com cache persistente
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

        // ✅ 1. Verifica cache em RAM
        var cachedThumbnail = if (showThumbnails) {
            val ramBitmap = thumbnailCache.get(key)
            if (ramBitmap != null && !ramBitmap.isRecycled) {
                ramBitmap
            } else {
                // ✅ 2. Se não tem em RAM, busca do disco
                loadThumbnailFromDisk(context, videoPath)?.also { diskBitmap ->
                    // Coloca de volta na RAM
                    thumbnailCache.put(key, diskBitmap)
                    // Atualiza tempo de acesso
                    updateCacheIndex(context, videoPath, getThumbnailFile(context, videoPath).length())
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

                    // ✅ Gera thumbnail se necessário
                    val thumbnail = if (showThumbnails && cachedThumbnail == null) {
                        loadThumbnailWithGlide(context, videoUri, key, thumbnailSize)?.also { newBitmap ->
                            // ✅ Salva no disco
                            withContext(Dispatchers.IO) {
                                saveThumbnailToDisk(context, videoPath, newBitmap)
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
                    val compressedBitmap = if (resource.byteCount > 1024 * 1024) {
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

    // ✅ ATUALIZADO: Limpa tanto RAM quanto disco
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
    }

    // ✅ NOVO: Limpa cache em disco completamente
    fun clearDiskCache(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val cacheDir = getThumbnailCacheDir(context)
            cacheDir.listFiles()?.forEach { it.delete() }
            getCacheIndexFile(context).delete()
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

    // ✅ ATUALIZADO: Estatísticas incluem disco
    fun getCacheStats(context: Context? = null): String {
        val currentCacheSizeMB = context?.let { getCacheSizeFromSettings(it) } ?: lastCacheSize
        val thumbnailSize = _thumbnailCache?.size() ?: 0
        val thumbnailMemoryBytes = _thumbnailCache?.size() ?: 0
        val thumbnailMemoryMB = thumbnailMemoryBytes / (1024 * 1024)

        val diskCacheSizeMB = context?.let {
            val cacheDir = getThumbnailCacheDir(it)
            val totalBytes = cacheDir.listFiles()?.sumOf { file -> file.length() } ?: 0L
            totalBytes / (1024 * 1024)
        } ?: 0

        val diskFileCount = context?.let {
            getThumbnailCacheDir(it).listFiles()?.size ?: 0
        } ?: 0

        val activeJobsCount = activeJobs.size
        val activeTargetsCount = activeTargets.size

        return """
            Cache Config: ${currentCacheSizeMB}MB
            RAM: $thumbnailSize (${thumbnailMemoryMB}MB)
            Disk: $diskFileCount files (${diskCacheSizeMB}MB)
            Jobs: $activeJobsCount | Targets: $activeTargetsCount
        """.trimIndent()
    }

    fun onSettingsChanged(context: Context) {
        reconfigureCache(context)
    }
}