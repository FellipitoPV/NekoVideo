package com.example.nekovideo.services

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.provider.MediaStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class FolderInfo(
    val path: String,
    val hasVideos: Boolean = false,
    val videoCount: Int = 0,
    val isSecure: Boolean = false,
    val lastModified: Long = 0L,
    val videos: List<VideoInfo> = emptyList()
)

data class VideoInfo(
    val path: String,
    val uri: Uri,
    val lastModified: Long,
    val sizeInBytes: Long
)

object FolderVideoScanner {
    private val _cache = MutableStateFlow<Map<String, FolderInfo>>(emptyMap())
    val cache: StateFlow<Map<String, FolderInfo>> = _cache.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "m4v", "3gp", "flv")

    // ✅ NOVO: FileObserver para auto-refresh
    private var fileObserver: FileObserver? = null
    private var observerContext: Context? = null
    private var observerScope: CoroutineScope? = null

    fun startScan(context: Context, scope: CoroutineScope = GlobalScope) {
        scanJob?.cancel()

        // ✅ NOVO: Salvar contexto e scope para o FileObserver
        observerContext = context
        observerScope = scope

        scanJob = scope.launch(Dispatchers.IO) {
            _isScanning.value = true

            try {
                val folderMap = ConcurrentHashMap<String, FolderInfo>()

                // Scan normal folders via MediaStore
                scanNormalFolders(context, folderMap)

                // Scan secure folders (com .nomedia/.nekovideo)
                scanSecureFolders(folderMap)

                _cache.value = folderMap.toMap()

                // ✅ NOVO: Iniciar FileObserver após primeiro scan
                startFileObserver()

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
            }
        }
    }

    // ✅ NOVO: Método para iniciar FileObserver
    private fun startFileObserver() {
        stopFileObserver() // Para o anterior se existir

        val watchPaths = listOf(
            "/storage/emulated/0/",
            "/storage/emulated/0/Download/",
            "/storage/emulated/0/Movies/",
            "/storage/emulated/0/DCIM/",
            "/storage/emulated/0/Pictures/"
        )

        // Observar múltiplas pastas
        watchPaths.forEach { path ->
            val dir = File(path)
            if (dir.exists()) {
                startObserverForPath(path)
            }
        }
    }

    // ✅ NOVO: FileObserver para uma pasta específica
    private fun startObserverForPath(path: String) {
        try {
            val observer = object : FileObserver(path,
                FileObserver.CREATE or
                        FileObserver.DELETE or
                        FileObserver.MOVED_FROM or
                        FileObserver.MOVED_TO or
                        FileObserver.MODIFY
            ) {
                override fun onEvent(event: Int, fileName: String?) {
                    if (fileName == null) return

                    // Verificar se é um arquivo de vídeo
                    val extension = fileName.substringAfterLast('.', "").lowercase()
                    if (extension !in videoExtensions) return

                    val filePath = File(path, fileName).absolutePath

                    when (event and FileObserver.ALL_EVENTS) {
                        FileObserver.CREATE, FileObserver.MOVED_TO -> {
                            // Arquivo criado/movido para cá - adicionar
                            handleVideoAdded(filePath)
                        }
                        FileObserver.DELETE, FileObserver.MOVED_FROM -> {
                            // Arquivo removido/movido daqui - remover
                            handleVideoRemoved(filePath)
                        }
                        FileObserver.MODIFY -> {
                            // Arquivo modificado - atualizar
                            handleVideoModified(filePath)
                        }
                    }
                }
            }

            observer.startWatching()
            fileObserver = observer // Salvar referência (simplificado para uma pasta)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ✅ NOVO: Handle quando vídeo é adicionado
    private fun handleVideoAdded(filePath: String) {
        observerScope?.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@launch

                val folderPath = file.parent ?: return@launch
                val videoInfo = VideoInfo(
                    path = filePath,
                    uri = Uri.fromFile(file),
                    lastModified = file.lastModified(),
                    sizeInBytes = file.length()
                )

                // Atualizar cache
                val currentCache = _cache.value.toMutableMap()
                addVideoToFolderCache(currentCache, folderPath, videoInfo, false)
                _cache.value = currentCache

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ NOVO: Handle quando vídeo é removido
    private fun handleVideoRemoved(filePath: String) {
        observerScope?.launch(Dispatchers.IO) {
            try {
                val folderPath = File(filePath).parent ?: return@launch

                // Atualizar cache removendo o vídeo
                val currentCache = _cache.value.toMutableMap()
                removeVideoFromFolderCache(currentCache, folderPath, filePath)
                _cache.value = currentCache

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ NOVO: Handle quando vídeo é modificado
    private fun handleVideoModified(filePath: String) {
        observerScope?.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@launch

                val folderPath = file.parent ?: return@launch

                // Atualizar informações do vídeo no cache
                val currentCache = _cache.value.toMutableMap()
                updateVideoInFolderCache(currentCache, folderPath, filePath, file.lastModified(), file.length())
                _cache.value = currentCache

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ✅ NOVO: Adicionar vídeo ao cache (versão para FileObserver)
    private fun addVideoToFolderCache(
        cacheMap: MutableMap<String, FolderInfo>,
        folderPath: String,
        videoInfo: VideoInfo,
        isSecure: Boolean
    ) {
        cacheMap[folderPath] = cacheMap[folderPath]?.let { existing ->
            existing.copy(
                hasVideos = true,
                videoCount = existing.videoCount + 1,
                lastModified = maxOf(videoInfo.lastModified, existing.lastModified),
                videos = existing.videos + videoInfo
            )
        } ?: FolderInfo(
            path = folderPath,
            hasVideos = true,
            videoCount = 1,
            isSecure = isSecure,
            lastModified = videoInfo.lastModified,
            videos = listOf(videoInfo)
        )
    }

    // ✅ NOVO: Remover vídeo do cache
    private fun removeVideoFromFolderCache(
        cacheMap: MutableMap<String, FolderInfo>,
        folderPath: String,
        videoPath: String
    ) {
        cacheMap[folderPath]?.let { existing ->
            val updatedVideos = existing.videos.filter { it.path != videoPath }

            if (updatedVideos.isEmpty()) {
                // Remover pasta do cache se não tem mais vídeos
                cacheMap.remove(folderPath)
            } else {
                // Atualizar contagem
                cacheMap[folderPath] = existing.copy(
                    videoCount = updatedVideos.size,
                    videos = updatedVideos,
                    lastModified = updatedVideos.maxOfOrNull { it.lastModified } ?: existing.lastModified
                )
            }
        }
    }

    // ✅ NOVO: Atualizar vídeo no cache
    private fun updateVideoInFolderCache(
        cacheMap: MutableMap<String, FolderInfo>,
        folderPath: String,
        videoPath: String,
        newLastModified: Long,
        newSize: Long
    ) {
        cacheMap[folderPath]?.let { existing ->
            val updatedVideos = existing.videos.map { video ->
                if (video.path == videoPath) {
                    video.copy(lastModified = newLastModified, sizeInBytes = newSize)
                } else {
                    video
                }
            }

            cacheMap[folderPath] = existing.copy(
                videos = updatedVideos,
                lastModified = maxOf(newLastModified, existing.lastModified)
            )
        }
    }

    // ✅ NOVO: Parar FileObserver
    private fun stopFileObserver() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    // Resto do código original...
    private suspend fun scanNormalFolders(context: Context, folderMap: ConcurrentHashMap<String, FolderInfo>) {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE
        )

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                yield()

                val videoPath = cursor.getString(dataColumn)
                val videoId = cursor.getLong(idColumn)
                val lastModified = cursor.getLong(dateColumn) * 1000L
                val size = cursor.getLong(sizeColumn)
                val parentDir = File(videoPath).parent ?: continue

                val videoUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoId
                )

                val videoInfo = VideoInfo(videoPath, videoUri, lastModified, size)
                addVideoToFolder(folderMap, parentDir, videoInfo, false)
            }
        }
    }

    private suspend fun scanSecureFolders(folderMap: ConcurrentHashMap<String, FolderInfo>) {
        val rootDirs = listOf(
            "/storage/emulated/0/",
            "/storage/emulated/0/Download/",
            "/storage/emulated/0/Movies/",
            "/storage/emulated/0/DCIM/",
            "/storage/emulated/0/Pictures/"
        )

        rootDirs.forEach { rootPath ->
            scanSecureFoldersRecursive(File(rootPath), folderMap)
        }
    }

    private suspend fun scanSecureFoldersRecursive(
        directory: File,
        folderMap: ConcurrentHashMap<String, FolderInfo>
    ) {
        yield()

        if (!directory.exists() || !directory.isDirectory) return

        val isSecure = File(directory, ".nomedia").exists() || File(directory, ".nekovideo").exists()

        if (isSecure) {
            val videoFiles = directory.listFiles()?.filter { file ->
                file.isFile && file.extension.lowercase() in videoExtensions
            } ?: emptyList()

            if (videoFiles.isNotEmpty()) {
                val videos = videoFiles.map { file ->
                    VideoInfo(
                        path = file.absolutePath,
                        uri = Uri.fromFile(file),
                        lastModified = file.lastModified(),
                        sizeInBytes = file.length()
                    )
                }

                folderMap[directory.absolutePath] = FolderInfo(
                    path = directory.absolutePath,
                    hasVideos = true,
                    videoCount = videos.size,
                    isSecure = true,
                    lastModified = directory.lastModified(),
                    videos = videos
                )
            }
        }

        directory.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                if (!subDir.name.startsWith(".") || isSecure || File(subDir, ".nomedia").exists() || File(subDir, ".nekovideo").exists()) {
                    scanSecureFoldersRecursive(subDir, folderMap)
                }
            }
        }
    }

    private fun addVideoToFolder(
        folderMap: ConcurrentHashMap<String, FolderInfo>,
        folderPath: String,
        videoInfo: VideoInfo,
        isSecure: Boolean
    ) {
        folderMap.compute(folderPath) { _, existing ->
            if (existing == null) {
                FolderInfo(
                    path = folderPath,
                    hasVideos = true,
                    videoCount = 1,
                    isSecure = isSecure,
                    lastModified = videoInfo.lastModified,
                    videos = listOf(videoInfo)
                )
            } else {
                existing.copy(
                    videoCount = existing.videoCount + 1,
                    lastModified = maxOf(videoInfo.lastModified, existing.lastModified),
                    videos = existing.videos + videoInfo
                )
            }
        }

        val parentPath = File(folderPath).parent
        if (parentPath != null && parentPath != folderPath) {
            folderMap.compute(parentPath) { _, existing ->
                if (existing == null) {
                    FolderInfo(
                        path = parentPath,
                        hasVideos = true,
                        videoCount = 0,
                        isSecure = false,
                        lastModified = videoInfo.lastModified,
                        videos = emptyList()
                    )
                } else {
                    existing.copy(
                        hasVideos = true,
                        lastModified = maxOf(videoInfo.lastModified, existing.lastModified)
                    )
                }
            }
            propagateToParents(folderMap, parentPath, videoInfo.lastModified)
        }
    }

    private fun propagateToParents(
        folderMap: ConcurrentHashMap<String, FolderInfo>,
        folderPath: String,
        lastModified: Long
    ) {
        val parentPath = File(folderPath).parent
        if (parentPath != null && parentPath != folderPath) {
            folderMap.compute(parentPath) { _, existing ->
                if (existing == null) {
                    FolderInfo(
                        path = parentPath,
                        hasVideos = true,
                        videoCount = 0,
                        isSecure = false,
                        lastModified = lastModified,
                        videos = emptyList()
                    )
                } else {
                    existing.copy(
                        hasVideos = true,
                        lastModified = maxOf(lastModified, existing.lastModified)
                    )
                }
            }
            propagateToParents(folderMap, parentPath, lastModified)
        }
    }

    // Public methods for the UI
    fun hasFolderVideos(folderPath: String): Boolean {
        return _cache.value[folderPath]?.hasVideos ?: false
    }

    fun getFolderInfo(folderPath: String): FolderInfo? {
        return _cache.value[folderPath]
    }

    fun getFoldersWithVideos(): List<FolderInfo> {
        return _cache.value.values.filter { it.hasVideos }
    }

    fun getSecureFolders(): List<FolderInfo> {
        return _cache.value.values.filter { it.isSecure && it.hasVideos }
    }

    fun stopScan() {
        scanJob?.cancel()
        stopFileObserver() // ✅ NOVO: Parar FileObserver também
        _isScanning.value = false
    }

    fun clearCache() {
        _cache.value = emptyMap()
    }

    // ✅ NOVO: Método para forçar refresh manual se necessário
    fun refresh() {
        observerContext?.let { context ->
            observerScope?.let { scope ->
                startScan(context, scope)
            }
        }
    }
}