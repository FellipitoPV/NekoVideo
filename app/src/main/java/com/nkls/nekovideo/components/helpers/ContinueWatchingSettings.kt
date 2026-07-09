package com.nkls.nekovideo.components.helpers

import android.content.Context

object ContinueWatchingSettings {
    private const val PREFS_NAME = "nekovideo_settings"
    private const val KEY_ENABLED = "continue_watching_enabled"
    private const val KEY_MIN_DURATION_MINUTES = "continue_watching_min_duration_minutes"
    private const val KEY_INCLUDE_PRIVATE = "continue_watching_include_private"

    private const val DEFAULT_ENABLED = true
    private const val DEFAULT_MIN_DURATION_MINUTES = 5
    private const val DEFAULT_INCLUDE_PRIVATE = false

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun getMinDurationMs(context: Context): Long {
        return prefs(context)
            .getInt(KEY_MIN_DURATION_MINUTES, DEFAULT_MIN_DURATION_MINUTES)
            .coerceAtLeast(1)
            .toLong() * 60_000L
    }

    fun shouldIncludePrivateVideos(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_INCLUDE_PRIVATE, DEFAULT_INCLUDE_PRIVATE)
    }

    fun isEligibleVideo(context: Context, originalPath: String, normalizedPath: String, durationMs: Long): Boolean {
        if (!isEnabled(context)) return false
        if (durationMs < getMinDurationMs(context)) return false
        if (!shouldIncludePrivateVideos(context) && isPrivateVideoPath(context, originalPath, normalizedPath)) {
            return false
        }
        return true
    }

    fun isEligibleForRootCard(context: Context, originalPath: String, normalizedPath: String, durationMs: Long): Boolean {
        if (!isEnabled(context)) return false
        if (durationMs < getMinDurationMs(context)) return false
        if (isPrivateVideoPath(context, originalPath, normalizedPath)) return false
        return true
    }

    fun isPrivateVideoPath(context: Context, originalPath: String, normalizedPath: String): Boolean {
        if (originalPath.startsWith("locked://")) return true

        val secureFolderPath = FilesManager.SecureStorage.getSecureFolderPath(context)
        val nekoPrivatePath = FilesManager.SecureStorage.getNekoPrivateFolderPath()
        val file = java.io.File(normalizedPath)
        val parentPath = file.parent ?: return false

        return normalizedPath.startsWith(secureFolderPath) ||
            normalizedPath.startsWith(nekoPrivatePath) ||
            normalizedPath.contains("/.private/") ||
            normalizedPath.contains("/secure/") ||
            normalizedPath.contains(".secure_videos") ||
            parentPath.endsWith(".secure_videos") ||
            FolderLockManager.isLocked(parentPath) ||
            java.io.File(parentPath, ".secure").exists()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
