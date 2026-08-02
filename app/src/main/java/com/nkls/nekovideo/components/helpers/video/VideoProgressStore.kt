package com.nkls.nekovideo.components.helpers

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoProgressEntry(
    val videoPath: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long
)

object VideoProgressStore {
    private const val PREFS_NAME = "video_progress"
    private const val KEY_POSITION_PREFIX = "position_"
    private const val KEY_DURATION_PREFIX = "duration_"
    private const val KEY_TITLE_PREFIX = "title_"

    private const val COMPLETION_THRESHOLD = 0.95

    private val _changeVersion = MutableStateFlow(0)
    val changeVersion: StateFlow<Int> = _changeVersion.asStateFlow()

    fun get(context: Context, videoPath: String): VideoProgressEntry? {
        val normalizedPath = normalizePath(videoPath).takeIf { it.isNotBlank() } ?: return null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val positionMs = prefs.getLong(positionKey(normalizedPath), -1L)
        if (positionMs < 0L) return null

        val durationMs = prefs.getLong(durationKey(normalizedPath), 0L)
        val title = prefs.getString(titleKey(normalizedPath), null)
            ?.takeIf { it.isNotBlank() }
            ?: File(normalizedPath).nameWithoutExtension

        if (shouldClear(context, videoPath, normalizedPath, positionMs, durationMs) || !File(normalizedPath).exists()) {
            clear(context, normalizedPath)
            return null
        }

        return VideoProgressEntry(
            videoPath = normalizedPath,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    fun save(
        context: Context,
        videoPath: String,
        title: String,
        positionMs: Long,
        durationMs: Long = 0L
    ) {
        val normalizedPath = normalizePath(videoPath).takeIf { it.isNotBlank() } ?: return

        if (shouldClear(context, videoPath, normalizedPath, positionMs, durationMs) || !File(normalizedPath).exists()) {
            clear(context, normalizedPath)
            return
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(positionKey(normalizedPath), positionMs)
            .putLong(durationKey(normalizedPath), durationMs)
            .putString(titleKey(normalizedPath), title.ifBlank { File(normalizedPath).nameWithoutExtension })
            .apply()

        bumpChangeVersion()
    }

    fun clear(context: Context, videoPath: String) {
        val normalizedPath = normalizePath(videoPath).takeIf { it.isNotBlank() } ?: return

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(positionKey(normalizedPath))
            .remove(durationKey(normalizedPath))
            .remove(titleKey(normalizedPath))
            .apply()

        bumpChangeVersion()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        bumpChangeVersion()
    }

    private fun positionKey(videoPath: String): String = "$KEY_POSITION_PREFIX$videoPath"

    private fun durationKey(videoPath: String): String = "$KEY_DURATION_PREFIX$videoPath"

    private fun titleKey(videoPath: String): String = "$KEY_TITLE_PREFIX$videoPath"

    private fun bumpChangeVersion() {
        _changeVersion.value = _changeVersion.value + 1
    }

    private fun normalizePath(videoPath: String): String {
        return when {
            videoPath.startsWith("locked://") -> videoPath.removePrefix("locked://")
            videoPath.startsWith("file://") -> videoPath.removePrefix("file://")
            else -> videoPath
        }
    }

    private fun shouldClear(
        context: Context,
        originalPath: String,
        normalizedPath: String,
        positionMs: Long,
        durationMs: Long
    ): Boolean {
        if (positionMs < 0L) return true
        if (!ContinueWatchingSettings.isEligibleVideo(context, originalPath, normalizedPath, durationMs)) return true
        return positionMs >= (durationMs * COMPLETION_THRESHOLD).toLong()
    }
}
