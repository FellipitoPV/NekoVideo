package com.nkls.nekovideo.components.helpers

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContinueWatchingEntry(
    val videoPath: String,
    val folderPath: String,
    val title: String,
    val positionMs: Long
)

object ContinueWatchingStore {
    private const val PREFS_NAME = "continue_watching"
    private const val KEY_VIDEO_PATH = "video_path"
    private const val KEY_FOLDER_PATH = "folder_path"
    private const val KEY_TITLE = "title"
    private const val KEY_POSITION_MS = "position_ms"

    private const val MIN_POSITION_TO_KEEP_MS = 5_000L
    private const val END_TOLERANCE_MS = 10_000L

    private val _entry = MutableStateFlow<ContinueWatchingEntry?>(null)
    val entry: StateFlow<ContinueWatchingEntry?> = _entry.asStateFlow()

    private val _hasActivePlayback = MutableStateFlow(false)
    val hasActivePlayback: StateFlow<Boolean> = _hasActivePlayback.asStateFlow()

    fun get(context: Context): ContinueWatchingEntry? {
        _entry.value?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val videoPath = prefs.getString(KEY_VIDEO_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val folderPath = prefs.getString(KEY_FOLDER_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val title = prefs.getString(KEY_TITLE, null)?.takeIf { it.isNotBlank() }
            ?: File(videoPath).nameWithoutExtension
        val positionMs = prefs.getLong(KEY_POSITION_MS, 0L)

        if (positionMs < MIN_POSITION_TO_KEEP_MS || !File(videoPath).exists()) {
            clear(context)
            return null
        }

        return ContinueWatchingEntry(
            videoPath = videoPath,
            folderPath = folderPath,
            title = title,
            positionMs = positionMs
        ).also {
            _entry.value = it
        }
    }

    fun save(
        context: Context,
        videoPath: String,
        title: String,
        positionMs: Long,
        durationMs: Long = 0L
    ) {
        val normalizedPath = normalizePath(videoPath).takeIf { it.isNotBlank() } ?: return
        if (isPrivateVideoPath(context, videoPath, normalizedPath)) return

        val folderPath = File(normalizedPath).parent?.takeIf { it.isNotBlank() } ?: return

        if (shouldClear(positionMs, durationMs) || !File(normalizedPath).exists()) {
            clear(context)
            return
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIDEO_PATH, normalizedPath)
            .putString(KEY_FOLDER_PATH, folderPath)
            .putString(KEY_TITLE, title.ifBlank { File(normalizedPath).nameWithoutExtension })
            .putLong(KEY_POSITION_MS, positionMs)
            .apply()

        _entry.value = ContinueWatchingEntry(
            videoPath = normalizedPath,
            folderPath = folderPath,
            title = title.ifBlank { File(normalizedPath).nameWithoutExtension },
            positionMs = positionMs
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        _entry.value = null
    }

    fun setPlaybackActive(active: Boolean) {
        _hasActivePlayback.value = active
    }

    private fun normalizePath(videoPath: String): String {
        return when {
            videoPath.startsWith("locked://") -> videoPath.removePrefix("locked://")
            videoPath.startsWith("file://") -> videoPath.removePrefix("file://")
            else -> videoPath
        }
    }

    private fun isPrivateVideoPath(context: Context, originalPath: String, normalizedPath: String): Boolean {
        if (originalPath.startsWith("locked://")) return true

        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val nekoPrivatePath = FilesManager.SecureStorage.getNekoPrivateFolderPath()
        val file = File(normalizedPath)
        val parentPath = file.parent ?: return false

        return normalizedPath.startsWith(secureFolderPath) ||
            normalizedPath.startsWith(nekoPrivatePath) ||
            normalizedPath.contains("/.private/") ||
            normalizedPath.contains("/secure/") ||
            normalizedPath.contains(".secure_videos") ||
            parentPath.endsWith(".secure_videos") ||
            FolderLockManager.isLocked(parentPath) ||
            File(parentPath, ".secure").exists()
    }

    private fun shouldClear(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_POSITION_TO_KEEP_MS) return true
        if (durationMs <= 0L) return false
        return durationMs - positionMs <= END_TOLERANCE_MS
    }
}
