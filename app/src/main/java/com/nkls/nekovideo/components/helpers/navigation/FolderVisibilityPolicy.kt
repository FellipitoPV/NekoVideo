package com.nkls.nekovideo.components.helpers

import android.content.Context
import com.nkls.nekovideo.services.FolderInfo
import java.io.File

internal const val SHOW_NOMEDIA_FOLDERS_KEY = "show_nomedia_folders"
internal const val NOMEDIA_REQUIRE_AUTH_KEY = "nomedia_require_authentication"

internal data class NomediaVisibilitySettings(val enabled: Boolean = true, val requireAuthentication: Boolean = true) {
    companion object {
        fun read(context: Context): NomediaVisibilitySettings {
            val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            return NomediaVisibilitySettings(prefs.getBoolean(SHOW_NOMEDIA_FOLDERS_KEY, true),
                prefs.getBoolean(NOMEDIA_REQUIRE_AUTH_KEY, true))
        }
    }
}

/** A per-listing policy; ancestor checks are shared by folders, counts and pinned shortcuts. */
internal class FolderVisibilityPolicy(context: Context, private val privateVisible: Boolean,
                                      val settings: NomediaVisibilitySettings = NomediaVisibilitySettings.read(context)) {
    private val authenticated = privateVisible && LockedPlaybackSession.sessionPassword != null
    private val privateRoots = listOf(FilesManager.SecureStorage.getSecureFolderPath(context),
        FilesManager.SecureStorage.getNekoPrivateFolderPath())
    private val boundaries = mutableMapOf<String, Pair<Boolean, Boolean>>()

    private fun boundary(folder: File): Pair<Boolean, Boolean> = boundaries.getOrPut(folder.absolutePath) {
        val parent = folder.parentFile?.let(::boundary) ?: (false to false)
        val privateBoundary = parent.first || privateRoots.any { folder.path == it || folder.path.startsWith("$it/") } ||
            folder.name in setOf(".secure_videos", ".private", "secure") ||
            File(folder, ".secure").exists() || FolderLockManager.isLocked(folder.path)
        privateBoundary to (parent.second || File(folder, ".nomedia").exists())
    }

    fun isPlainNomedia(folder: File): Boolean = boundary(folder).let { !it.first && it.second }

    fun canAccess(folder: File): Boolean {
        val (privateBoundary, nomedia) = boundary(folder)
        return when {
            privateBoundary -> privateVisible
            nomedia -> settings.enabled && (!settings.requireAuthentication || authenticated)
            else -> true
        }
    }

    fun hasVisibleNomediaDescendant(folder: File, cache: Map<String, FolderInfo>): Boolean =
        cache.values.any { info -> info.isSecure && !info.isLocked && info.path.startsWith("${folder.path}/") &&
            File(info.path, ".nomedia").exists() && isPlainNomedia(File(info.path)) && canAccess(File(info.path)) }
}
