package com.example.nekovideo.services

import android.content.ContentUris
import android.content.Context
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
    val lastModified: Long = 0L
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

                _cache.value = folderMap.toMap()

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
            }
        }
    }

    private suspend fun scanNormalFolders(context: Context, folderMap: ConcurrentHashMap<String, FolderInfo>) {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                yield() // Allow cancellation

                val videoPath = cursor.getString(dataColumn)
                val lastModified = cursor.getLong(dateColumn) * 1000L
                val parentDir = File(videoPath).parent ?: continue

                updateFolderInfo(folderMap, parentDir, lastModified, false)
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
        yield() // Allow cancellation

        if (!directory.exists() || !directory.isDirectory) return

        val isSecure = File(directory, ".nomedia").exists() || File(directory, ".nekovideo").exists()

        if (isSecure) {
            val videoCount = directory.listFiles()?.count { file ->
                file.isFile && file.extension.lowercase() in videoExtensions
            } ?: 0

            if (videoCount > 0) {
                folderMap[directory.absolutePath] = FolderInfo(
                    path = directory.absolutePath,
                    hasVideos = true,
                    videoCount = videoCount,
                    isSecure = true,
                    lastModified = directory.lastModified()
                )
            }
        }

        // Continue recursion
        directory.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                // Sempre entra em pastas normais, e em pastas "." se a pai é segura OU para verificar se ela é segura
                if (!subDir.name.startsWith(".") || isSecure || File(subDir, ".nomedia").exists() || File(subDir, ".nekovideo").exists()) {
                    scanSecureFoldersRecursive(subDir, folderMap)
                }
            }
        }
    }

    private fun updateFolderInfo(
        folderMap: ConcurrentHashMap<String, FolderInfo>,
        folderPath: String,
        lastModified: Long,
        isSecure: Boolean
    ) {
        folderMap.compute(folderPath) { _, existing ->
            if (existing == null) {
                FolderInfo(
                    path = folderPath,
                    hasVideos = true,
                    videoCount = 1,
                    isSecure = isSecure,
                    lastModified = maxOf(lastModified, existing?.lastModified ?: 0L)
                )
            } else {
                existing.copy(
                    videoCount = existing.videoCount + 1,
                    lastModified = maxOf(lastModified, existing.lastModified)
                )
            }
        }

        // Update parent folders recursively
        val parentPath = File(folderPath).parent
        if (parentPath != null && parentPath != folderPath) {
            folderMap.compute(parentPath) { _, existing ->
                if (existing == null) {
                    FolderInfo(
                        path = parentPath,
                        hasVideos = true,
                        videoCount = 0,
                        isSecure = false,
                        lastModified = lastModified
                    )
                } else {
                    existing.copy(lastModified = maxOf(lastModified, existing.lastModified))
                }
            }
            updateFolderInfo(folderMap, parentPath, lastModified, false)
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