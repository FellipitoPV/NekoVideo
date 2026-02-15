package com.nkls.nekovideo.components.helpers

/**
 * Singleton session state for locked folder playback.
 * Holds the active XOR key and decrypted manifest while a locked folder is being accessed.
 */
object LockedPlaybackSession {

    /** Active XOR key for the currently accessed locked folder */
    var currentXorKey: ByteArray? = null
        private set

    /** Decrypted manifest of the currently accessed locked folder */
    var currentManifest: LockedFolderManifest? = null
        private set

    /** Path of the currently accessed locked folder */
    var currentFolderPath: String? = null
        private set

    /** Whether a locked session is currently active */
    val isActive: Boolean
        get() = currentXorKey != null && currentManifest != null

    /**
     * Start a locked playback session with the given key and manifest.
     */
    fun start(xorKey: ByteArray, manifest: LockedFolderManifest, folderPath: String) {
        currentXorKey = xorKey.copyOf()
        currentManifest = manifest
        currentFolderPath = folderPath
    }

    /**
     * Clear the session, securely zeroing the key bytes in memory.
     */
    fun clear() {
        currentXorKey?.let { key ->
            key.fill(0)
        }
        currentXorKey = null
        currentManifest = null
        currentFolderPath = null
    }

    /**
     * Get the original file name for an obfuscated file name.
     */
    fun getOriginalName(obfuscatedName: String): String? {
        return currentManifest?.files?.find { it.obfuscatedName == obfuscatedName }?.originalName
    }

    /**
     * Get the obfuscated file name for an original file name.
     */
    fun getObfuscatedName(originalName: String): String? {
        return currentManifest?.files?.find { it.originalName == originalName }?.obfuscatedName
    }
}
