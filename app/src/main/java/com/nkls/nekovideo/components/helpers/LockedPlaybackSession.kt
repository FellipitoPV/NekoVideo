package com.nkls.nekovideo.components.helpers

/**
 * Singleton session state for locked folder playback.
 * Supports multiple locked folders simultaneously for cross-folder playlist playback.
 */
object LockedPlaybackSession {

    private data class FolderSession(
        val xorKey: ByteArray,
        val manifest: LockedFolderManifest
    )

    /** Map of folder path -> session data (supports multiple locked folders) */
    private val sessions = mutableMapOf<String, FolderSession>()

    /** Path of the "primary" locked folder (the one the user is currently browsing) */
    var currentFolderPath: String? = null
        private set

    /** Session password (needed to re-encrypt manifests on delete) */
    var sessionPassword: String? = null
        private set

    /** Active XOR key for the primary folder */
    val currentXorKey: ByteArray?
        get() = currentFolderPath?.let { sessions[it]?.xorKey }

    /** Decrypted manifest of the primary folder */
    val currentManifest: LockedFolderManifest?
        get() = currentFolderPath?.let { sessions[it]?.manifest }

    /** Whether any locked session is currently active */
    val isActive: Boolean
        get() = sessions.isNotEmpty()

    /**
     * Start/add a locked playback session for a folder.
     * Sets this folder as the "primary" (current) folder.
     */
    fun start(xorKey: ByteArray, manifest: LockedFolderManifest, folderPath: String, password: String? = null) {
        sessions[folderPath] = FolderSession(xorKey.copyOf(), manifest)
        currentFolderPath = folderPath
        if (password != null) {
            sessionPassword = password
        }
    }

    /**
     * Clear all sessions, securely zeroing the key bytes in memory.
     */
    fun clear() {
        sessions.values.forEach { session ->
            session.xorKey.fill(0)
        }
        sessions.clear()
        currentFolderPath = null
        sessionPassword = null
    }

    /**
     * Get the XOR key for a specific file based on its parent folder.
     * Used by HybridDataSource for multi-folder playlist playback.
     */
    fun getXorKeyForFile(filePath: String): ByteArray? {
        val parentPath = java.io.File(filePath).parent ?: return null
        return sessions[parentPath]?.xorKey
    }

    /**
     * Check if a specific folder has an active session.
     */
    fun hasSessionForFolder(folderPath: String): Boolean {
        return sessions.containsKey(folderPath)
    }

    /**
     * Update the manifest for a specific folder (e.g. after deleting a video).
     */
    fun updateManifest(folderPath: String, manifest: LockedFolderManifest) {
        val existing = sessions[folderPath] ?: return
        sessions[folderPath] = existing.copy(manifest = manifest)
    }

    /**
     * Get the manifest for a specific folder (not just the primary/current one).
     */
    fun getManifestForFolder(folderPath: String): LockedFolderManifest? {
        return sessions[folderPath]?.manifest
    }

    /**
     * Update the current folder path (e.g. when navigating into/out of subfolders).
     */
    fun setCurrentFolder(folderPath: String) {
        currentFolderPath = folderPath
    }

    /**
     * Remove a session for a specific folder (e.g. when deleting a locked subfolder).
     */
    fun removeSession(folderPath: String) {
        sessions[folderPath]?.xorKey?.fill(0)
        sessions.remove(folderPath)
        if (currentFolderPath == folderPath) {
            currentFolderPath = null
        }
    }

    /**
     * Get the original file name for an obfuscated file name.
     * Searches across ALL active sessions.
     */
    fun getOriginalName(obfuscatedName: String): String? {
        for (session in sessions.values) {
            session.manifest.files.find { it.obfuscatedName == obfuscatedName }?.originalName?.let { return it }
        }
        return null
    }

    /**
     * Get the obfuscated file name for an original file name.
     * Searches across ALL active sessions.
     */
    fun getObfuscatedName(originalName: String): String? {
        for (session in sessions.values) {
            session.manifest.files.find { it.originalName == originalName }?.obfuscatedName?.let { return it }
        }
        return null
    }
}
