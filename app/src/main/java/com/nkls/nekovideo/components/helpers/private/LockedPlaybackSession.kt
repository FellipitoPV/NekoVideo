package com.nkls.nekovideo.components.helpers

import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton session state for locked folder playback.
 * Supports multiple locked folders simultaneously for cross-folder playlist playback.
 */
object LockedPlaybackSession {

    private data class FolderSession(
        val xorKey: ByteArray,
        val manifest: LockedFolderManifest,
        val entries: Map<String, LockedFileEntry>,
        val fileKeys: Map<String, ByteArray>
    ) {
        fun clearKeys() { xorKey.fill(0); fileKeys.values.forEach { it.fill(0) } }
    }

    /** Map of folder path -> session data (supports multiple locked folders) */
    private val sessions = ConcurrentHashMap<String, FolderSession>()

    /** Path of the "primary" locked folder (the one the user is currently browsing) */
    var currentFolderPath: String? = null
        private set

    /** Session password (needed to re-encrypt manifests on delete) */
    @Volatile var sessionPassword: String? = null
        private set
    @Volatile var credentialVersion: Long = 0L
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

    /** Keep an already verified credential available to the scanner before any folder is opened. */
    fun authorize(password: String) {
        if (sessionPassword != password) credentialVersion++
        sessionPassword = password
    }

    /**
     * Start/add a locked playback session for a folder.
     * Sets this folder as the "primary" (current) folder.
     */
    fun start(xorKey: ByteArray, manifest: LockedFolderManifest, folderPath: String, password: String? = null) {
        val entries = manifest.files.associateBy { it.obfuscatedName }
        val keys = entries.mapNotNull { (name, entry) ->
            entry.portableMetadata?.let { name to Base64.getDecoder().decode(it.contentKey) }
        }.toMap()
        sessions.put(folderPath, FolderSession(xorKey.copyOf(), manifest, entries, keys))?.clearKeys()
        currentFolderPath = folderPath
        if (password != null) {
            authorize(password)
        }
    }

    /**
     * Clear all sessions, securely zeroing the key bytes in memory.
     */
    fun clear() {
        sessions.values.forEach { session ->
            session.clearKeys()
        }
        sessions.clear()
        currentFolderPath = null
        sessionPassword = null
        credentialVersion++
        PortablePrivateFolders.clearSession()
    }

    /**
     * Get the XOR key for a specific file based on its parent folder.
     * Used by HybridDataSource for multi-folder playlist playback.
     */
    fun getXorKeyForFile(filePath: String): ByteArray? {
        val file = File(filePath)
        val parent = file.parent ?: return null
        val session = sessions[parent] ?: return null
        if (file.name !in session.entries) return null
        return session.fileKeys[file.name] ?: session.xorKey
    }

    fun getPayloadSize(filePath: String): Long? {
        val file = File(filePath)
        val parent = file.parent ?: return null
        return sessions[parent]?.entries?.get(file.name)?.portableMetadata?.payloadSize
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
        val activeFolder = currentFolderPath
        start(existing.xorKey, manifest, folderPath, sessionPassword)
        currentFolderPath = activeFolder
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
     * Move a session from oldPath to newPath (e.g. when a folder directory is renamed/obfuscated).
     */
    fun renameSession(oldPath: String, newPath: String) {
        val session = sessions[oldPath] ?: return
        sessions[newPath] = session
        sessions.remove(oldPath)
        if (currentFolderPath == oldPath) {
            currentFolderPath = newPath
        }
    }

    /**
     * Move all active sessions under a folder subtree when the folder path changes.
     */
    fun renameSessionsUnderPath(oldRootPath: String, newRootPath: String) {
        val affected = sessions.keys
            .filter { it == oldRootPath || it.startsWith("$oldRootPath/") }
            .sortedByDescending { it.length }

        affected.forEach { oldPath ->
            val session = sessions.remove(oldPath) ?: return@forEach
            val suffix = oldPath.removePrefix(oldRootPath)
            val newPath = newRootPath + suffix
            sessions[newPath] = session
            if (currentFolderPath == oldPath) {
                currentFolderPath = newPath
            }
        }
    }

    /**
     * Remove a session for a specific folder (e.g. when deleting a locked subfolder).
     */
    fun removeSession(folderPath: String) {
        sessions.remove(folderPath)?.clearKeys()
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
            session.entries[obfuscatedName]?.originalName?.let { return it }
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

    /**
     * Get stored duration for a locked file when available in the manifest.
     */
    fun getDurationForFile(filePath: String): String? {
        val parentPath = java.io.File(filePath).parent ?: return null
        val obfuscatedName = java.io.File(filePath).name
        return sessions[parentPath]?.entries?.get(obfuscatedName)?.duration
    }
}
