package com.nkls.nekovideo.components

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThumbnailCacheCleanupState(
    val isClearing: Boolean = false,
    val remainingBytes: Long = 0L,
    val completedOperations: Long = 0L
)

/**
 * Keeps thumbnail cleanup alive while the app process is running, independently
 * from the settings screen that started it.
 */
object ThumbnailCacheCleaner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ThumbnailCacheCleanupState())
    val state = _state.asStateFlow()

    private var cleanupJob: Job? = null

    @Synchronized
    fun start(context: Context, folderPaths: Collection<String>) {
        if (cleanupJob?.isActive == true) return

        val appContext = context.applicationContext
        val paths = folderPaths.toList()
        cleanupJob = scope.launch {
            val initialBytes = OptimizedThumbnailManager.getDiskCacheSize(appContext, paths)
            _state.value = _state.value.copy(
                isClearing = true,
                remainingBytes = initialBytes
            )

            val progressJob = launch {
                while (isActive) {
                    val remainingBytes = OptimizedThumbnailManager.getDiskCacheSize(appContext, paths)
                    _state.value = _state.value.copy(remainingBytes = remainingBytes)
                    delay(200)
                }
            }

            try {
                OptimizedThumbnailManager.clearCache()
                OptimizedThumbnailManager.clearAllDiskThumbnails(appContext, paths)
                _state.value = ThumbnailCacheCleanupState(
                    isClearing = false,
                    remainingBytes = 0L,
                    completedOperations = _state.value.completedOperations + 1L
                )
            } catch (error: Exception) {
                Log.e("ThumbnailCacheCleaner", "Failed to clear thumbnail cache", error)
                _state.value = _state.value.copy(isClearing = false)
            } finally {
                progressJob.cancel()
            }
        }
    }
}
