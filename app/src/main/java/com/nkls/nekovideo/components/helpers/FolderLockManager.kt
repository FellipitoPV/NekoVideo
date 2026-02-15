package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Data classes for locked file tracking
data class LockedFileEntry(
    val obfuscatedName: String,
    val originalName: String,
    val originalSize: Long
)

data class LockedFolderManifest(
    val folderPath: String,
    val originalFolderName: String,
    val files: List<LockedFileEntry>,
    val salt: String, // Base64 encoded salt
    val lockedAt: Long
)

data class LockedFolderRegistryEntry(
    val folderPath: String,
    val originalFolderName: String,
    val fileCount: Int,
    val lockedAt: Long
)

data class LockedFoldersRegistry(
    val folders: MutableList<LockedFolderRegistryEntry> = mutableListOf()
)

object FolderLockManager {
    private const val TAG = "FolderLockManager"
    private const val HEADER_SIZE = 8192 // 8KB XOR header
    private const val LOCKED_MARKER = ".neko_locked"
    private const val LOCK_IN_PROGRESS_MARKER = ".neko_lock_in_progress"
    private const val MANIFEST_FILE = ".neko_manifest.enc"
    private val THUMB_XOR_KEY = byteArrayOf(0x4E, 0x45, 0x4B, 0x4F) // "NEKO" as XOR key for thumbs
    private const val THUMBS_DIR = ".neko_thumbs"
    private const val REGISTRY_PREFS = "neko_locked_folders"
    private const val REGISTRY_KEY = "registry"
    private const val PBKDF2_ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_SIZE = 16
    private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"

    private val gson = Gson()
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "m4v", "3gp", "flv")

    // Derive XOR key from password + salt using PBKDF2
    fun deriveXorKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // Derive AES key for manifest encryption (different derivation to avoid key reuse)
    private fun deriveAesKey(password: String, salt: ByteArray): SecretKeySpec {
        val manifestSalt = salt.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val spec = PBEKeySpec(password.toCharArray(), manifestSalt, PBKDF2_ITERATIONS, 128)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // XOR the first 8KB of a file with the derived key
    private fun xorFileHeader(file: File, xorKey: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            val headerSize = minOf(HEADER_SIZE.toLong(), raf.length()).toInt()
            val header = ByteArray(headerSize)
            raf.seek(0)
            raf.readFully(header)

            for (i in header.indices) {
                header[i] = (header[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
            }

            raf.seek(0)
            raf.write(header)
        }
    }

    // Encrypt manifest JSON with AES
    private fun encryptManifest(manifest: LockedFolderManifest, password: String, salt: ByteArray): ByteArray {
        val json = gson.toJson(manifest)
        val aesKey = deriveAesKey(password, salt)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        // Prepend IV to encrypted data
        return iv + encrypted
    }

    // Decrypt manifest JSON from AES
    fun decryptManifest(encryptedData: ByteArray, password: String, salt: ByteArray): LockedFolderManifest? {
        return try {
            val iv = encryptedData.copyOfRange(0, 16)
            val encrypted = encryptedData.copyOfRange(16, encryptedData.size)
            val aesKey = deriveAesKey(password, salt)
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
            val json = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            gson.fromJson(json, LockedFolderManifest::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt manifest: ${e.message}")
            null
        }
    }

    // Read manifest from a locked folder (requires password)
    fun readManifest(folderPath: String, password: String): LockedFolderManifest? {
        val manifestFile = File(folderPath, MANIFEST_FILE)
        if (!manifestFile.exists()) return null

        val markerFile = File(folderPath, LOCKED_MARKER)
        if (!markerFile.exists()) return null

        val saltBase64 = markerFile.readText().trim()
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)

        val encryptedData = manifestFile.readBytes()
        return decryptManifest(encryptedData, password, salt)
    }

    // Get salt from a locked folder (does not require password)
    fun getSalt(folderPath: String): ByteArray? {
        val markerFile = File(folderPath, LOCKED_MARKER)
        if (!markerFile.exists()) return null
        return try {
            Base64.decode(markerFile.readText().trim(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Lock a folder: rename videos to UUIDs, XOR headers, create encrypted manifest
     */
    fun lockFolder(
        context: Context,
        folderPath: String,
        password: String,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            onError("Folder not found: $folderPath")
            return
        }

        // Check if already locked
        if (isLocked(folderPath)) {
            onError("Folder is already locked")
            return
        }

        // Find all video files
        val videoFiles = folder.listFiles()?.filter { file ->
            file.isFile && file.extension.lowercase() in videoExtensions
        } ?: emptyList()

        if (videoFiles.isEmpty()) {
            onError("No video files found in folder")
            return
        }

        // Create in-progress marker for crash safety
        val inProgressMarker = File(folder, LOCK_IN_PROGRESS_MARKER)
        inProgressMarker.createNewFile()

        try {
            // Generate random salt for this folder
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val xorKey = deriveXorKey(password, salt)

            // Create thumbs directory for thumbnail preservation
            val thumbsDir = File(folder, THUMBS_DIR)
            thumbsDir.mkdirs()

            val entries = mutableListOf<LockedFileEntry>()
            val totalFiles = videoFiles.size

            videoFiles.forEachIndexed { index, videoFile ->
                val originalName = videoFile.name
                val originalSize = videoFile.length()
                val obfuscatedName = UUID.randomUUID().toString().replace("-", "")

                onProgress(index + 1, totalFiles, originalName)

                // Save thumbnail BEFORE XOR-ing (headers still intact)
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(videoFile.absolutePath)
                    val bitmap = retriever.getFrameAtTime(
                        1_000_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (bitmap != null) {
                        // Save as obfuscated file (no extension, XOR'd data)
                        val thumbFile = File(thumbsDir, obfuscatedName)
                        val baos = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        val thumbBytes = baos.toByteArray()
                        // XOR thumbnail bytes to make them unrecognizable
                        for (i in thumbBytes.indices) {
                            thumbBytes[i] = (thumbBytes[i].toInt() xor THUMB_XOR_KEY[i % THUMB_XOR_KEY.size].toInt()).toByte()
                        }
                        thumbFile.writeBytes(thumbBytes)
                        bitmap.recycle()
                    }
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save thumbnail for: $originalName", e)
                }

                // XOR the first 8KB
                xorFileHeader(videoFile, xorKey)

                // Rename to UUID (no extension)
                val renamedFile = File(folder, obfuscatedName)
                if (!videoFile.renameTo(renamedFile)) {
                    // Revert XOR if rename fails
                    xorFileHeader(videoFile, xorKey)
                    onError("Failed to rename: $originalName")
                    return
                }

                entries.add(LockedFileEntry(obfuscatedName, originalName, originalSize))
            }

            // Create manifest
            val manifest = LockedFolderManifest(
                folderPath = folderPath,
                originalFolderName = folder.name,
                files = entries,
                salt = Base64.encodeToString(salt, Base64.NO_WRAP),
                lockedAt = System.currentTimeMillis()
            )

            // Encrypt and save manifest
            val encryptedManifest = encryptManifest(manifest, password, salt)
            File(folder, MANIFEST_FILE).writeBytes(encryptedManifest)

            // Write locked marker (contains salt for key derivation)
            File(folder, LOCKED_MARKER).writeText(Base64.encodeToString(salt, Base64.NO_WRAP))

            // Add .nomedia to prevent gallery indexing
            File(folder, ".nomedia").createNewFile()

            // Update registry
            addToRegistry(context, folderPath, folder.name, entries.size)

            // Remove in-progress marker
            inProgressMarker.delete()

            onSuccess()

        } catch (e: Exception) {
            Log.e(TAG, "Error locking folder: ${e.message}", e)
            inProgressMarker.delete()
            onError("Error: ${e.message}")
        }
    }

    /**
     * Unlock a folder: read manifest, revert XOR, restore original names
     */
    fun unlockFolder(
        context: Context,
        folderPath: String,
        password: String,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            onError("Folder not found")
            return
        }

        if (!isLocked(folderPath)) {
            onError("Folder is not locked")
            return
        }

        // Read and decrypt manifest
        val manifest = readManifest(folderPath, password)
        if (manifest == null) {
            onError("Invalid password or corrupted manifest")
            return
        }

        val salt = Base64.decode(manifest.salt, Base64.NO_WRAP)
        val xorKey = deriveXorKey(password, salt)

        try {
            val totalFiles = manifest.files.size

            manifest.files.forEachIndexed { index, entry ->
                val obfuscatedFile = File(folder, entry.obfuscatedName)
                if (!obfuscatedFile.exists()) {
                    Log.w(TAG, "Obfuscated file not found: ${entry.obfuscatedName}")
                    return@forEachIndexed
                }

                onProgress(index + 1, totalFiles, entry.originalName)

                // Revert XOR on header
                xorFileHeader(obfuscatedFile, xorKey)

                // Restore original name
                val restoredFile = File(folder, entry.originalName)
                if (!obfuscatedFile.renameTo(restoredFile)) {
                    // Revert the XOR back if rename fails
                    xorFileHeader(obfuscatedFile, xorKey)
                    onError("Failed to restore: ${entry.originalName}")
                    return
                }
            }

            // Clean up marker files and thumbs
            File(folder, LOCKED_MARKER).delete()
            File(folder, MANIFEST_FILE).delete()
            File(folder, LOCK_IN_PROGRESS_MARKER).delete()
            // Clean up any legacy recovery_hint.txt
            File(folder, "recovery_hint.txt").also { if (it.exists()) it.delete() }
            val thumbsDir = File(folder, THUMBS_DIR)
            if (thumbsDir.exists()) {
                thumbsDir.listFiles()?.forEach { it.delete() }
                thumbsDir.delete()
            }
            // Keep .nomedia if the folder name starts with "." (it's also private)
            if (!folder.name.startsWith(".")) {
                File(folder, ".nomedia").delete()
            }

            // Remove from registry
            removeFromRegistry(context, folderPath)

            onSuccess()

        } catch (e: Exception) {
            Log.e(TAG, "Error unlocking folder: ${e.message}", e)
            onError("Error: ${e.message}")
        }
    }

    /**
     * Check if a folder is locked
     */
    fun isLocked(folderPath: String): Boolean {
        return File(folderPath, LOCKED_MARKER).exists()
    }

    /**
     * Check if a lock operation was interrupted (crash safety)
     */
    fun isLockInProgress(folderPath: String): Boolean {
        return File(folderPath, LOCK_IN_PROGRESS_MARKER).exists()
    }

    // Registry management (stored in app's internal storage, survives without password)
    private fun loadRegistry(context: Context): LockedFoldersRegistry {
        return try {
            val prefs = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(REGISTRY_KEY, null)
            if (json != null) {
                gson.fromJson(json, LockedFoldersRegistry::class.java)
            } else {
                LockedFoldersRegistry()
            }
        } catch (e: Exception) {
            LockedFoldersRegistry()
        }
    }

    private fun saveRegistry(context: Context, registry: LockedFoldersRegistry) {
        val prefs = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(REGISTRY_KEY, gson.toJson(registry)).apply()
    }

    private fun addToRegistry(context: Context, folderPath: String, originalName: String, fileCount: Int) {
        val registry = loadRegistry(context)
        registry.folders.removeAll { it.folderPath == folderPath }
        registry.folders.add(
            LockedFolderRegistryEntry(
                folderPath = folderPath,
                originalFolderName = originalName,
                fileCount = fileCount,
                lockedAt = System.currentTimeMillis()
            )
        )
        saveRegistry(context, registry)
    }

    private fun removeFromRegistry(context: Context, folderPath: String) {
        val registry = loadRegistry(context)
        registry.folders.removeAll { it.folderPath == folderPath }
        saveRegistry(context, registry)
    }

    /**
     * Get registry entry for a locked folder (does NOT require password)
     */
    fun getRegistryEntry(context: Context, folderPath: String): LockedFolderRegistryEntry? {
        val registry = loadRegistry(context)
        return registry.folders.find { it.folderPath == folderPath }
    }

    /**
     * Get all locked folders from registry
     */
    fun getAllLockedFolders(context: Context): List<LockedFolderRegistryEntry> {
        return loadRegistry(context).folders
    }

    /**
     * Verify if a password is correct for a locked folder (tries to decrypt manifest)
     */
    fun verifyPassword(folderPath: String, password: String): Boolean {
        return readManifest(folderPath, password) != null
    }

    /**
     * Add new files to an already-locked folder.
     * XORs headers, renames to UUIDs, saves thumbnails, and updates the encrypted manifest.
     */
    fun addFilesToLockedFolder(
        context: Context,
        folderPath: String,
        password: String,
        newFiles: List<File>,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        if (!isLocked(folderPath)) {
            onError("Folder is not locked")
            return
        }

        val manifest = readManifest(folderPath, password)
        if (manifest == null) {
            onError("Invalid password or corrupted manifest")
            return
        }

        val salt = getSalt(folderPath)
        if (salt == null) {
            onError("Cannot read salt")
            return
        }

        val xorKey = deriveXorKey(password, salt)
        val folder = File(folderPath)
        val thumbsDir = File(folder, THUMBS_DIR)
        thumbsDir.mkdirs()

        val videoFiles = newFiles.filter { file ->
            file.isFile && file.extension.lowercase() in videoExtensions && file.parentFile?.absolutePath == folderPath
        }

        if (videoFiles.isEmpty()) {
            onSuccess() // Nothing to lock, but not an error
            return
        }

        try {
            val newEntries = mutableListOf<LockedFileEntry>()
            val totalFiles = videoFiles.size

            videoFiles.forEachIndexed { index, videoFile ->
                val originalName = videoFile.name
                val originalSize = videoFile.length()
                val obfuscatedName = UUID.randomUUID().toString().replace("-", "")

                onProgress(index + 1, totalFiles, originalName)

                // Save thumbnail BEFORE XOR
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(videoFile.absolutePath)
                    val bitmap = retriever.getFrameAtTime(
                        1_000_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (bitmap != null) {
                        // Save as obfuscated file (no extension, XOR'd data)
                        val thumbFile = File(thumbsDir, obfuscatedName)
                        val baos = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        val thumbBytes = baos.toByteArray()
                        // XOR thumbnail bytes to make them unrecognizable
                        for (i in thumbBytes.indices) {
                            thumbBytes[i] = (thumbBytes[i].toInt() xor THUMB_XOR_KEY[i % THUMB_XOR_KEY.size].toInt()).toByte()
                        }
                        thumbFile.writeBytes(thumbBytes)
                        bitmap.recycle()
                    }
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save thumbnail for: $originalName", e)
                }

                // XOR header
                xorFileHeader(videoFile, xorKey)

                // Rename to UUID
                val renamedFile = File(folder, obfuscatedName)
                if (!videoFile.renameTo(renamedFile)) {
                    xorFileHeader(videoFile, xorKey) // Revert
                    onError("Failed to rename: $originalName")
                    return
                }

                newEntries.add(LockedFileEntry(obfuscatedName, originalName, originalSize))
            }

            // Update manifest with new entries
            val updatedManifest = manifest.copy(
                files = manifest.files + newEntries
            )
            val encryptedManifest = encryptManifest(updatedManifest, password, salt)
            File(folder, MANIFEST_FILE).writeBytes(encryptedManifest)

            // Update registry file count
            addToRegistry(context, folderPath, manifest.originalFolderName, updatedManifest.files.size)

            onSuccess()

        } catch (e: Exception) {
            Log.e(TAG, "Error adding files to locked folder: ${e.message}", e)
            onError("Error: ${e.message}")
        }
    }

    /**
     * Get saved thumbnail for a locked video file.
     * Reads obfuscated thumbnail (XOR'd, no extension), reverses XOR, decodes to Bitmap.
     */
    fun getLockedThumbnail(videoPath: String): Bitmap? {
        val file = File(videoPath)
        val folder = file.parentFile ?: return null
        val thumbsDir = File(folder, THUMBS_DIR)
        // Try obfuscated name first (no extension), fallback to legacy .jpg
        val thumbFile = File(thumbsDir, file.name)
        val legacyThumbFile = File(thumbsDir, "${file.name}.jpg")
        return when {
            thumbFile.exists() -> {
                try {
                    val thumbBytes = thumbFile.readBytes()
                    // Reverse XOR to restore JPEG data
                    for (i in thumbBytes.indices) {
                        thumbBytes[i] = (thumbBytes[i].toInt() xor THUMB_XOR_KEY[i % THUMB_XOR_KEY.size].toInt()).toByte()
                    }
                    BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                } catch (e: Exception) {
                    null
                }
            }
            legacyThumbFile.exists() -> {
                // Legacy format: plain JPEG with .jpg extension
                try {
                    BitmapFactory.decodeFile(legacyThumbFile.absolutePath)
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
    }
}
