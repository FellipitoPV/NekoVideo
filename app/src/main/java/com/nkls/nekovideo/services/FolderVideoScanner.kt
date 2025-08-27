package com.nkls.nekovideo.services

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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

    fun startScan(context: Context, scope: CoroutineScope = GlobalScope) {
        scanJob?.cancel()

        scanJob = scope.launch(Dispatchers.IO) {
            _isScanning.value = true

            try {
                val folderMap = ConcurrentHashMap<String, FolderInfo>()

                // Scan normal folders via MediaStore
                scanNormalFolders(context, folderMap)

                // Scan secure folders (com .nomedia/.nekovideo)
                scanSecureFolders(folderMap)

                // ✅ Scan direto das pastas principais para pegar arquivos não indexados (como Downloads)
                scanDirectFolders(folderMap)

                _cache.value = folderMap.toMap()

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
            }
        }
    }

    // Escanear diretamente pastas principais para pegar vídeos não indexados (como Downloads)
    private suspend fun scanDirectFolders(folderMap: ConcurrentHashMap<String, FolderInfo>) {
        val directScanPaths = listOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads", // Algumas ROMs usam plural
            "/storage/emulated/0/Movies",
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Pictures"
        )

        directScanPaths.forEach { path ->
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                scanDirectoryForVideos(dir, folderMap)
            }
        }
    }

    // Escanear diretório específico em busca de vídeos
    private suspend fun scanDirectoryForVideos(
        directory: File,
        folderMap: ConcurrentHashMap<String, FolderInfo>
    ) {
        yield()

        if (!directory.exists() || !directory.isDirectory) return

        try {
            // Escanear arquivos de vídeo diretamente na pasta
            val videoFiles = directory.listFiles()?.filter { file ->
                file.isFile && file.extension.lowercase() in videoExtensions
            } ?: emptyList()

            // Adicionar vídeos encontrados
            videoFiles.forEach { videoFile ->
                val videoInfo = VideoInfo(
                    path = videoFile.absolutePath,
                    uri = Uri.fromFile(videoFile),
                    lastModified = videoFile.lastModified(),
                    sizeInBytes = videoFile.length()
                )

                addVideoToFolder(folderMap, directory.absolutePath, videoInfo, false)
            }

            // Escanear subpastas também
            directory.listFiles()?.forEach { subDir ->
                if (subDir.isDirectory && !subDir.name.startsWith(".")) {
                    scanDirectoryForVideos(subDir, folderMap)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
                // Evitar duplicatas
                val existingVideos = existing.videos
                val isDuplicate = existingVideos.any { it.path == videoInfo.path }

                if (!isDuplicate) {
                    existing.copy(
                        videoCount = existing.videoCount + 1,
                        lastModified = maxOf(videoInfo.lastModified, existing.lastModified),
                        videos = existingVideos + videoInfo
                    )
                } else {
                    existing // Não adiciona se já existe
                }
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
        _isScanning.value = false
    }

    fun clearCache() {
        _cache.value = emptyMap()
    }
}