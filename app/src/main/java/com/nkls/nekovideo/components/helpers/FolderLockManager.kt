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
    val originalSize: Long,
    val duration: String? = null
)

data class LockedSubfolderEntry(
    val obfuscatedName: String,
    val originalName: String
)

data class LockedFolderManifest(
    val folderPath: String,
    val originalFolderName: String,
    val files: List<LockedFileEntry>,
    val subfolders: List<LockedSubfolderEntry> = emptyList(),
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

    private fun shouldObfuscateLockedFolderName(folderPath: String): Boolean {
        if (folderPath == FilesManager.SecureStorage.getNekoPrivateFolderPath()) {
            return false
        }

        val parentPath = File(folderPath).parent ?: return false
        return !isLocked(parentPath)
    }

    private fun obfuscateLockedFolderNameIfNeeded(context: Context, folderPath: String): String {
        if (!shouldObfuscateLockedFolderName(folderPath)) return folderPath

        val folder = File(folderPath)
        val parent = folder.parentFile ?: return folderPath
        var renamedFolder: File
        do {
            renamedFolder = File(parent, UUID.randomUUID().toString().replace("-", ""))
        } while (renamedFolder.exists())

        if (!folder.renameTo(renamedFolder)) {
            Log.w(TAG, "Failed to obfuscate locked folder name: $folderPath")
            return folderPath
        }

        onLockedFolderMoved(context, folderPath, renamedFolder.absolutePath)

        if (FilesManager.SecureStorage.getCustomSecureFolderPath(context) == folderPath) {
            FilesManager.SecureStorage.setCustomSecureFolderPath(context, renamedFolder.absolutePath)
        }

        return renamedFolder.absolutePath
    }

    private fun restoreUnlockedFolderNameIfNeeded(context: Context, folderPath: String, originalFolderName: String): String {
        if (folderPath == FilesManager.SecureStorage.getNekoPrivateFolderPath()) return folderPath

        val folder = File(folderPath)
        val parent = folder.parentFile ?: return folderPath
        if (isLocked(parent.absolutePath) || folder.name == originalFolderName) return folderPath

        val restoredFolder = File(parent, originalFolderName)
        if (restoredFolder.exists()) {
            Log.w(TAG, "Cannot restore unlocked folder name, target already exists: ${restoredFolder.absolutePath}")
            return folderPath
        }

        if (!folder.renameTo(restoredFolder)) {
            Log.w(TAG, "Failed to restore unlocked folder name: $folderPath")
            return folderPath
        }

        if (FilesManager.SecureStorage.getCustomSecureFolderPath(context) == folderPath) {
            FilesManager.SecureStorage.setCustomSecureFolderPath(context, restoredFolder.absolutePath)
        }

        return restoredFolder.absolutePath
    }

    // Used to track completed operations so they can be rolled back on failure
    private data class PendingFileEntry(
        val renamedFile: File,       // UUID-named file (current on-disk state)
        val originalName: String,    // original filename, used to rename back on rollback
        val thumbFile: File?,        // thumbnail file (now at UUID name in .neko_thumbs)
        val thumbWasRenamed: Boolean // true = thumb was renamed from existing; false = newly generated
    )

    /**
     * Rollback all entries that were already XOR'd and renamed.
     * Reverts XOR and renames UUID files back to their original names.
     * Called when an error occurs mid-operation so no files are left in limbo.
     */
    private fun rollbackPendingFiles(entries: List<PendingFileEntry>, folder: File, xorKey: ByteArray) {
        entries.reversed().forEach { entry ->
            try {
                xorFileHeader(entry.renamedFile, xorKey) // revert XOR
                entry.renamedFile.renameTo(File(folder, entry.originalName)) // restore original name
                if (entry.thumbFile != null) {
                    if (entry.thumbWasRenamed) {
                        // Restore thumb to its original ThumbnailManager name (name without extension)
                        val originalThumbName = File(entry.originalName).nameWithoutExtension
                        entry.thumbFile.renameTo(File(entry.thumbFile.parentFile, originalThumbName))
                    } else {
                        entry.thumbFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rollback failed for: ${entry.originalName}", e)
            }
        }
    }

    /**
     * Provides a thumbnail for a video file about to be locked.
     * Priority: reuse existing ThumbnailManager thumbnail (already XOR'd on disk, middle frame).
     * Fallback: generate a new thumbnail from the middle frame.
     * Returns the saved thumb File at [thumbsDir]/[obfuscatedName], or null on failure.
     * [thumbWasRenamed] is set to true if an existing thumbnail was reused.
     */
    private fun saveOrGenerateThumbnailForLock(
        videoFile: File,
        obfuscatedName: String,
        thumbsDir: File
    ): Pair<File?, Boolean> {
        val destThumb = File(thumbsDir, obfuscatedName)

        // If ThumbnailManager already saved a thumbnail for this file, reuse it
        val existingThumb = File(thumbsDir, videoFile.nameWithoutExtension)
        if (existingThumb.exists()) {
            return if (existingThumb.renameTo(destThumb)) {
                Pair(destThumb, true)
            } else {
                Pair(null, false)
            }
        }

        // No existing thumbnail — generate from the middle frame
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val middleTimeUs = (durationMs * 1000L) / 2
            val bitmap = retriever.getFrameAtTime(middleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()

            if (bitmap != null) {
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val thumbBytes = baos.toByteArray()
                for (i in thumbBytes.indices) {
                    thumbBytes[i] = (thumbBytes[i].toInt() xor THUMB_XOR_KEY[i % THUMB_XOR_KEY.size].toInt()).toByte()
                }
                destThumb.writeBytes(thumbBytes)
                bitmap.recycle()
                Pair(destThumb, false)
            } else {
                Pair(null, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save thumbnail for: ${videoFile.name}", e)
            Pair(null, false)
        }
    }

    private fun getFormattedVideoDuration(videoFile: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            durationMs?.let {
                val minutes = it / 1000L / 60L
                val seconds = (it / 1000L) % 60L
                String.format("%02d:%02d", minutes, seconds)
            }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

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

        kotlinx.coroutines.runBlocking {
            VideoTagStore.resetTagsForPathTree(context, folderPath)
        }

        // Find all video files
        val videoFiles = folder.listFiles()?.filter { file ->
            file.isFile && file.extension.lowercase() in videoExtensions
        } ?: emptyList()

        // Create in-progress marker for crash safety
        val inProgressMarker = File(folder, LOCK_IN_PROGRESS_MARKER)
        inProgressMarker.createNewFile()

        // Declared outside try so the catch block can rollback on unexpected errors
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val xorKey = deriveXorKey(password, salt)
        val pendingEntries = mutableListOf<PendingFileEntry>()

        try {
            // Create thumbs directory for thumbnail preservation
            val thumbsDir = File(folder, THUMBS_DIR)
            thumbsDir.mkdirs()

            val entries = mutableListOf<LockedFileEntry>()
            val totalFiles = videoFiles.size

            videoFiles.forEachIndexed { index, videoFile ->
                val originalName = videoFile.name
                val originalSize = videoFile.length()
                val duration = getFormattedVideoDuration(videoFile)
                val obfuscatedName = UUID.randomUUID().toString().replace("-", "")

                onProgress(index + 1, totalFiles, originalName)

                // Reuse existing ThumbnailManager thumbnail or generate from middle frame
                // MUST be done BEFORE XOR-ing (video headers must still be intact for MediaMetadataRetriever)
                val (savedThumbFile, thumbWasRenamed) = saveOrGenerateThumbnailForLock(videoFile, obfuscatedName, thumbsDir)

                // XOR the first 8KB
                xorFileHeader(videoFile, xorKey)

                // Rename to UUID (no extension)
                val renamedFile = File(folder, obfuscatedName)
                if (!videoFile.renameTo(renamedFile)) {
                    // Revert XOR on this file, then rollback all previously processed files
                    xorFileHeader(videoFile, xorKey)
                    if (savedThumbFile != null && thumbWasRenamed) {
                        savedThumbFile.renameTo(File(thumbsDir, videoFile.nameWithoutExtension))
                    } else savedThumbFile?.delete()
                    rollbackPendingFiles(pendingEntries, folder, xorKey)
                    inProgressMarker.delete()
                    onError("Failed to rename: $originalName")
                    return
                }

                pendingEntries.add(PendingFileEntry(renamedFile, originalName, savedThumbFile, thumbWasRenamed))
                entries.add(LockedFileEntry(obfuscatedName, originalName, originalSize, duration))
            }

            // Rename direct subdirectories to UUIDs
            val subfolderEntries = mutableListOf<LockedSubfolderEntry>()
            folder.listFiles()?.filter { f -> f.isDirectory && f.name != THUMBS_DIR }?.forEach { subdir ->
                val originalName = subdir.name
                val obfuscatedName = UUID.randomUUID().toString().replace("-", "")
                val renamedDir = File(folder, obfuscatedName)
                if (subdir.renameTo(renamedDir)) {
                    subfolderEntries.add(LockedSubfolderEntry(obfuscatedName, originalName))
                    if (isLocked(renamedDir.absolutePath)) {
                        onLockedFolderMoved(context, subdir.absolutePath, renamedDir.absolutePath)
                    }
                }
            }

            // Create manifest
            val manifest = LockedFolderManifest(
                folderPath = folderPath,
                originalFolderName = folder.name,
                files = entries,
                subfolders = subfolderEntries,
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

            // Remove in-progress marker
            inProgressMarker.delete()

            // Update registry
            addToRegistry(context, folderPath, folder.name, entries.size)

            obfuscateLockedFolderNameIfNeeded(context, folderPath)

            onSuccess()

        } catch (e: Exception) {
            Log.e(TAG, "Error locking folder: ${e.message}", e)
            rollbackPendingFiles(pendingEntries, folder, xorKey)
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

        kotlinx.coroutines.runBlocking {
            VideoTagStore.resetTagsForPathTree(context, folderPath)
        }

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

            // Restore subdirectory names
            manifest.subfolders?.forEach { entry ->
                val obfuscatedDir = File(folder, entry.obfuscatedName)
                if (obfuscatedDir.exists() && obfuscatedDir.isDirectory) {
                    val restoredDir = File(folder, entry.originalName)
                    if (obfuscatedDir.renameTo(restoredDir)) {
                        if (isLocked(restoredDir.absolutePath)) {
                            onLockedFolderMoved(context, obfuscatedDir.absolutePath, restoredDir.absolutePath)
                        }
                    }
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

            val restoredFolderPath = restoreUnlockedFolderNameIfNeeded(context, folderPath, manifest.originalFolderName)

            // Remove from registry
            removeFromRegistry(context, folderPath)

            LockedPlaybackSession.renameSessionsUnderPath(folderPath, restoredFolderPath)

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

    private fun updateRegistryPath(context: Context, oldPath: String, newPath: String) {
        val registry = loadRegistry(context)
        val existing = registry.folders.find { it.folderPath == oldPath } ?: return
        registry.folders.removeAll { it.folderPath == oldPath }
        registry.folders.add(existing.copy(folderPath = newPath))
        saveRegistry(context, registry)
    }

    private fun updateRegistryPathsUnderPath(context: Context, oldRootPath: String, newRootPath: String) {
        val registry = loadRegistry(context)
        var changed = false
        registry.folders.replaceAll { entry ->
            if (entry.folderPath == oldRootPath || entry.folderPath.startsWith("$oldRootPath/")) {
                changed = true
                entry.copy(folderPath = newRootPath + entry.folderPath.removePrefix(oldRootPath))
            } else {
                entry
            }
        }
        if (changed) {
            saveRegistry(context, registry)
        }
    }

    fun onLockedFolderMoved(context: Context, oldPath: String, newPath: String) {
        updateRegistryPathsUnderPath(context, oldPath, newPath)
        LockedPlaybackSession.renameSessionsUnderPath(oldPath, newPath)

        if (FilesManager.SecureStorage.getCustomSecureFolderPath(context) == oldPath) {
            FilesManager.SecureStorage.setCustomSecureFolderPath(context, newPath)
        }
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
     * Re-keys a single locked folder: swaps XOR key on all 8KB headers and
     * re-encrypts the manifest with the new password + a freshly generated salt.
     * File UUIDs and structure are left unchanged.
     */
    fun reKeyFolder(
        folderPath: String,
        oldPassword: String,
        newPassword: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) { onError("Folder not found"); return }
        if (!isLocked(folderPath)) { onSuccess(); return } // nothing to re-key

        val manifest = readManifest(folderPath, oldPassword)
        if (manifest == null) { onError("Invalid password"); return }

        val oldSalt = Base64.decode(manifest.salt, Base64.NO_WRAP)
        val oldXorKey = deriveXorKey(oldPassword, oldSalt)
        val newSalt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val newXorKey = deriveXorKey(newPassword, newSalt)
        val total = manifest.files.size

        try {
            manifest.files.forEachIndexed { index, entry ->
                val file = File(folder, entry.obfuscatedName)
                if (!file.exists()) return@forEachIndexed
                onProgress(index + 1, total)
                xorFileHeader(file, oldXorKey) // restore original 8KB
                xorFileHeader(file, newXorKey) // apply new 8KB XOR
            }

            val newManifest = manifest.copy(salt = Base64.encodeToString(newSalt, Base64.NO_WRAP))
            File(folder, MANIFEST_FILE).writeBytes(encryptManifest(newManifest, newPassword, newSalt))
            File(folder, LOCKED_MARKER).writeText(Base64.encodeToString(newSalt, Base64.NO_WRAP))
            onSuccess()
        } catch (e: Exception) {
            onError(e.message ?: "Re-key failed")
        }
    }

    /**
     * Re-keys all folders in the registry plus the NekoVideo private vault folder.
     * Stops and calls onError immediately on the first failure.
     */
    fun reKeyAllFolders(
        context: Context,
        oldPassword: String,
        newPassword: String,
        onProgress: (foldersDone: Int, foldersTotal: Int, filesDone: Int, filesTotal: Int) -> Unit = { _, _, _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        // Collect all folder paths to re-key (registry + private vault if locked)
        val registryFolders = getAllLockedFolders(context).map { it.folderPath }
        val vaultPath = FilesManager.SecureStorage.getNekoPrivateFolderPath()
        val allPaths = (registryFolders + vaultPath)
            .distinct()
            .filter { isLocked(it) }

        val total = allPaths.size

        for ((index, path) in allPaths.withIndex()) {
            var failed = false
            reKeyFolder(
                folderPath = path,
                oldPassword = oldPassword,
                newPassword = newPassword,
                onProgress = { current, fileTotal ->
                    onProgress(index + 1, total, current, fileTotal)
                },
                onError = { msg ->
                    failed = true
                    onError(msg)
                }
            )
            if (failed) return
        }

        onSuccess()
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

        // Declared outside try so the catch block can rollback on unexpected errors
        val pendingEntries = mutableListOf<PendingFileEntry>()

        try {
            val newEntries = mutableListOf<LockedFileEntry>()
            val totalFiles = videoFiles.size

            videoFiles.forEachIndexed { index, videoFile ->
                val originalName = videoFile.name
                val originalSize = videoFile.length()
                val duration = getFormattedVideoDuration(videoFile)
                val obfuscatedName = UUID.randomUUID().toString().replace("-", "")

                onProgress(index + 1, totalFiles, originalName)

                // Reuse existing ThumbnailManager thumbnail or generate from middle frame
                // MUST be done BEFORE XOR-ing (video headers must still be intact for MediaMetadataRetriever)
                val (savedThumbFile, thumbWasRenamed) = saveOrGenerateThumbnailForLock(videoFile, obfuscatedName, thumbsDir)

                // XOR header
                xorFileHeader(videoFile, xorKey)

                // Rename to UUID
                val renamedFile = File(folder, obfuscatedName)
                if (!videoFile.renameTo(renamedFile)) {
                    // Revert XOR on this file, then rollback all previously processed files
                    xorFileHeader(videoFile, xorKey)
                    if (savedThumbFile != null && thumbWasRenamed) {
                        savedThumbFile.renameTo(File(thumbsDir, videoFile.nameWithoutExtension))
                    } else savedThumbFile?.delete()
                    rollbackPendingFiles(pendingEntries, folder, xorKey)
                    onError("Failed to rename: $originalName")
                    return
                }

                kotlinx.coroutines.runBlocking {
                    VideoTagStore.moveTagsForPath(context, videoFile.absolutePath, renamedFile.absolutePath)
                }

                pendingEntries.add(PendingFileEntry(renamedFile, originalName, savedThumbFile, thumbWasRenamed))
                newEntries.add(LockedFileEntry(obfuscatedName, originalName, originalSize, duration))
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
            // Rollback all files processed before the crash — no files left in limbo
            rollbackPendingFiles(pendingEntries, folder, xorKey)
            onError("Error: ${e.message}")
        }
    }

    /**
     * Recursively lock a folder and all its subfolders.
     * Each level: encrypts direct videos + obfuscates direct subfolders, then recurses into them.
     * Uses the same password for every level.
     */
    fun lockFolderRecursive(
        context: Context,
        folderPath: String,
        password: String,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        if (!isLocked(folderPath)) {
            var didError = false
            lockFolder(
                context = context,
                folderPath = folderPath,
                password = password,
                onProgress = onProgress,
                onError = { msg -> didError = true; onError(msg) },
                onSuccess = {}
            )
            if (didError) return
        }

        // Recurse into subfolders (already renamed to UUIDs in manifest after lockFolder)
        val manifest = readManifest(folderPath, password) ?: run { onSuccess(); return }
        manifest.subfolders?.forEach { subEntry ->
            val subPath = File(folderPath, subEntry.obfuscatedName).absolutePath
            if (File(subPath).exists()) {
                lockFolderRecursive(
                    context = context,
                    folderPath = subPath,
                    password = password,
                    onProgress = onProgress,
                    onError = { /* continue with other subfolders */ },
                    onSuccess = {}
                )
            }
        }

        onSuccess()
    }

    /**
     * Extract a subfolder from a locked parent: restore its original name and remove it from the
     * parent manifest. The subfolder is NOT unlocked here — call unlockFolderRecursive afterwards
     * if needed. Returns the restored directory File, or null on failure.
     */
    fun extractSubfolderFromLockedParent(
        context: Context,
        parentFolderPath: String,
        obfuscatedName: String,
        password: String
    ): File? {
        val manifest = readManifest(parentFolderPath, password) ?: return null
        val salt = getSalt(parentFolderPath) ?: return null

        val subEntry = (manifest.subfolders ?: emptyList()).find { it.obfuscatedName == obfuscatedName }
            ?: return null
        val obfuscatedDir = File(parentFolderPath, obfuscatedName)
        if (!obfuscatedDir.exists() || !obfuscatedDir.isDirectory) return null

        val restoredDir = File(parentFolderPath, subEntry.originalName)
        if (!obfuscatedDir.renameTo(restoredDir)) return null

        if (isLocked(restoredDir.absolutePath)) {
            onLockedFolderMoved(context, obfuscatedDir.absolutePath, restoredDir.absolutePath)
        }

        val updatedManifest = manifest.copy(
            subfolders = (manifest.subfolders ?: emptyList()).filter { it.obfuscatedName != obfuscatedName }
        )
        val encryptedManifest = encryptManifest(updatedManifest, password, salt)
        File(parentFolderPath, MANIFEST_FILE).writeBytes(encryptedManifest)

        if (LockedPlaybackSession.hasSessionForFolder(parentFolderPath)) {
            LockedPlaybackSession.updateManifest(parentFolderPath, updatedManifest)
        }

        return restoredDir
    }

    /**
     * Recursively unlock a folder and all its locked subfolders.
     * Each level: read subfolder names, unlock current folder, then recurse into them.
     * Uses the same password for every level.
     */
    fun unlockFolderRecursive(
        context: Context,
        folderPath: String,
        password: String,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        if (!isLocked(folderPath)) {
            // Not locked — still recurse into any locked subfolders
            File(folderPath).listFiles()?.filter { it.isDirectory && isLocked(it.absolutePath) }
                ?.forEach { subdir ->
                    unlockFolderRecursive(context, subdir.absolutePath, password, onProgress, {}, {})
                }
            onSuccess()
            return
        }

        // Read subfolder original names BEFORE unlocking (unlockFolder renames them back)
        val subOriginalNames = readManifest(folderPath, password)?.subfolders
            ?.map { it.originalName } ?: emptyList()

        var didError = false
        unlockFolder(
            context = context,
            folderPath = folderPath,
            password = password,
            onProgress = onProgress,
            onError = { msg -> didError = true; onError(msg) },
            onSuccess = {}
        )
        if (didError) return

        // After unlock, subfolders have original names again — recurse into locked ones
        subOriginalNames.forEach { originalName ->
            val subPath = File(folderPath, originalName).absolutePath
            if (File(subPath).exists()) {
                unlockFolderRecursive(context, subPath, password, onProgress, {}, {})
            }
        }

        onSuccess()
    }

    /**
     * Rename a subfolder inside a locked parent to a UUID, tracking it in the parent manifest.
     * Call this after moving/locking a subfolder into an already-locked parent.
     * Returns the new obfuscated path, or null on failure.
     */
    fun addSubfolderToLockedFolder(
        context: Context,
        parentFolderPath: String,
        subfolderPath: String,
        password: String
    ): String? {
        if (!isLocked(parentFolderPath)) return null
        val manifest = readManifest(parentFolderPath, password) ?: return null
        val salt = getSalt(parentFolderPath) ?: return null

        val subdir = File(subfolderPath)
        if (!subdir.exists() || !subdir.isDirectory) return null

        val originalName = subdir.name
        val obfuscatedName = UUID.randomUUID().toString().replace("-", "")
        val renamedDir = File(parentFolderPath, obfuscatedName)

        if (!subdir.renameTo(renamedDir)) return null

        if (isLocked(renamedDir.absolutePath)) {
            onLockedFolderMoved(context, subfolderPath, renamedDir.absolutePath)
        }

        val updatedManifest = manifest.copy(
            subfolders = (manifest.subfolders ?: emptyList()) + LockedSubfolderEntry(obfuscatedName, originalName)
        )
        val encryptedManifest = encryptManifest(updatedManifest, password, salt)
        File(parentFolderPath, MANIFEST_FILE).writeBytes(encryptedManifest)

        if (LockedPlaybackSession.hasSessionForFolder(parentFolderPath)) {
            LockedPlaybackSession.updateManifest(parentFolderPath, updatedManifest)
        }

        return renamedDir.absolutePath
    }

    /**
     * Rename a file's original name in the manifest (the obfuscated name on disk stays the same).
     * Returns the updated manifest or null on failure.
     */
    fun renameFileInManifest(
        folderPath: String,
        obfuscatedName: String,
        newOriginalName: String,
        password: String
    ): LockedFolderManifest? {
        val manifest = readManifest(folderPath, password) ?: return null
        val salt = getSalt(folderPath) ?: return null

        val updatedFiles = manifest.files.map { entry ->
            if (entry.obfuscatedName == obfuscatedName) {
                entry.copy(originalName = newOriginalName)
            } else {
                entry
            }
        }

        val updatedManifest = manifest.copy(files = updatedFiles)
        val encryptedManifest = encryptManifest(updatedManifest, password, salt)
        File(folderPath, MANIFEST_FILE).writeBytes(encryptedManifest)

        return updatedManifest
    }

    /**
     * Rename a subfolder's display name in the parent manifest without changing the
     * obfuscated directory name on disk. If the child folder is also locked, keep its
     * own manifest/registry display name in sync.
     */
    fun renameSubfolderInManifest(
        context: Context,
        parentFolderPath: String,
        obfuscatedName: String,
        newOriginalName: String,
        password: String
    ): LockedFolderManifest? {
        val manifest = readManifest(parentFolderPath, password) ?: return null
        val salt = getSalt(parentFolderPath) ?: return null

        val updatedSubfolders = manifest.subfolders.map { entry ->
            if (entry.obfuscatedName == obfuscatedName) {
                entry.copy(originalName = newOriginalName)
            } else {
                entry
            }
        }

        val updatedManifest = manifest.copy(subfolders = updatedSubfolders)
        val encryptedManifest = encryptManifest(updatedManifest, password, salt)
        File(parentFolderPath, MANIFEST_FILE).writeBytes(encryptedManifest)

        val childFolderPath = File(parentFolderPath, obfuscatedName).absolutePath
        if (isLocked(childFolderPath)) {
            val childManifest = readManifest(childFolderPath, password)
            val childSalt = getSalt(childFolderPath)
            if (childManifest != null && childSalt != null) {
                val updatedChildManifest = childManifest.copy(originalFolderName = newOriginalName)
                File(childFolderPath, MANIFEST_FILE).writeBytes(
                    encryptManifest(updatedChildManifest, password, childSalt)
                )
                if (LockedPlaybackSession.hasSessionForFolder(childFolderPath)) {
                    LockedPlaybackSession.updateManifest(childFolderPath, updatedChildManifest)
                }
                addToRegistry(context, childFolderPath, newOriginalName, updatedChildManifest.files.size)
            }
        }

        return updatedManifest
    }

    /**
     * Remove multiple file entries from a locked folder's manifest and persist the change.
     * Also removes thumbnails. Returns the updated manifest or null on failure.
     */
    fun removeFilesFromManifest(
        context: Context,
        folderPath: String,
        obfuscatedNames: List<String>,
        password: String
    ): LockedFolderManifest? {
        val manifest = readManifest(folderPath, password) ?: return null
        val salt = getSalt(folderPath) ?: return null

        val namesToRemove = obfuscatedNames.toSet()
        val updatedFiles = manifest.files.filter { it.obfuscatedName !in namesToRemove }
        if (updatedFiles.size == manifest.files.size) return manifest

        val updatedManifest = manifest.copy(files = updatedFiles)
        val encryptedManifest = encryptManifest(updatedManifest, password, salt)
        File(folderPath, MANIFEST_FILE).writeBytes(encryptedManifest)

        // Delete thumbnails
        obfuscatedNames.forEach { name ->
            File(folderPath, "$THUMBS_DIR/$name").also { if (it.exists()) it.delete() }
            File(folderPath, "$THUMBS_DIR/$name.jpg").also { if (it.exists()) it.delete() }
        }

        // Update registry
        addToRegistry(context, folderPath, updatedManifest.originalFolderName, updatedFiles.size)

        return updatedManifest
    }

    /**
     * Extract files from a locked folder: revert XOR, restore original names, remove from manifest.
     * Returns a list of restored File objects (with original names) and the updated manifest.
     */
    fun extractFilesFromLockedFolder(
        context: Context,
        folderPath: String,
        obfuscatedNames: List<String>,
        password: String
    ): Pair<List<File>, LockedFolderManifest?> {
        val manifest = readManifest(folderPath, password) ?: return Pair(emptyList(), null)
        val salt = getSalt(folderPath) ?: return Pair(emptyList(), null)
        val xorKey = deriveXorKey(password, salt)
        val folder = File(folderPath)

        val restoredFiles = mutableListOf<File>()
        val namesToRemove = obfuscatedNames.toSet()

        manifest.files.filter { it.obfuscatedName in namesToRemove }.forEach { entry ->
            val obfuscatedFile = File(folder, entry.obfuscatedName)
            if (!obfuscatedFile.exists()) return@forEach

            // Revert XOR
            xorFileHeader(obfuscatedFile, xorKey)

            // Restore original name
            val restoredFile = File(folder, entry.originalName)
            if (obfuscatedFile.renameTo(restoredFile)) {
                kotlinx.coroutines.runBlocking {
                    VideoTagStore.moveTagsForPath(context, obfuscatedFile.absolutePath, restoredFile.absolutePath)
                }
                restoredFiles.add(restoredFile)
            } else {
                // Revert XOR back if rename fails
                xorFileHeader(obfuscatedFile, xorKey)
            }

            // Delete thumbnail
            File(folderPath, "$THUMBS_DIR/${entry.obfuscatedName}").also { if (it.exists()) it.delete() }
            File(folderPath, "$THUMBS_DIR/${entry.obfuscatedName}.jpg").also { if (it.exists()) it.delete() }
        }

        // Update manifest
        val updatedFiles = manifest.files.filter { it.obfuscatedName !in namesToRemove }
        val updatedManifest = manifest.copy(files = updatedFiles)
        val encryptedManifest = encryptManifest(updatedManifest, password, salt)
        File(folderPath, MANIFEST_FILE).writeBytes(encryptedManifest)

        addToRegistry(context, folderPath, updatedManifest.originalFolderName, updatedFiles.size)

        return Pair(restoredFiles, updatedManifest)
    }

    /**
     * Remove a file entry from a locked folder's manifest and persist the change.
     * Also deletes the thumbnail. Returns the updated manifest or null on failure.
     */
    fun removeFileFromManifest(
        context: Context,
        folderPath: String,
        obfuscatedName: String,
        password: String
    ): LockedFolderManifest? {
        val manifest = readManifest(folderPath, password) ?: return null
        val salt = getSalt(folderPath) ?: return null

        val updatedFiles = manifest.files.filter { it.obfuscatedName != obfuscatedName }
        if (updatedFiles.size == manifest.files.size) return manifest // not found, no change

        val updatedManifest = manifest.copy(files = updatedFiles)

        // Re-encrypt and save manifest
        val encryptedManifest = encryptManifest(updatedManifest, password, salt)
        File(folderPath, MANIFEST_FILE).writeBytes(encryptedManifest)

        // Delete thumbnail
        val thumbFile = File(folderPath, "$THUMBS_DIR/$obfuscatedName")
        if (thumbFile.exists()) thumbFile.delete()
        // Legacy thumb
        val legacyThumb = File(folderPath, "$THUMBS_DIR/$obfuscatedName.jpg")
        if (legacyThumb.exists()) legacyThumb.delete()

        // Update registry file count
        addToRegistry(context, folderPath, updatedManifest.originalFolderName, updatedFiles.size)

        return updatedManifest
    }

    /**
     * Create an empty locked folder (e.g. a subfolder inside a locked parent).
     * Creates directory, generates salt, creates empty manifest, writes marker files.
     * Also starts a session for the new folder in LockedPlaybackSession.
     */
    fun createEmptyLockedFolder(
        context: Context,
        folderPath: String,
        password: String
    ): Boolean {
        return try {
            val folder = File(folderPath)
            if (!folder.exists()) folder.mkdirs()

            // Generate salt
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)

            // Create empty manifest
            val manifest = LockedFolderManifest(
                folderPath = folderPath,
                originalFolderName = folder.name,
                files = emptyList(),
                salt = saltBase64,
                lockedAt = System.currentTimeMillis()
            )

            // Encrypt and save manifest
            val encryptedManifest = encryptManifest(manifest, password, salt)
            File(folder, MANIFEST_FILE).writeBytes(encryptedManifest)

            // Write locked marker
            File(folder, LOCKED_MARKER).writeText(saltBase64)

            // Add .nomedia
            File(folder, ".nomedia").createNewFile()

            // Create thumbs directory
            File(folder, THUMBS_DIR).mkdirs()

            // Register in SharedPreferences
            addToRegistry(context, folderPath, folder.name, 0)

            // Start session for the new folder
            val xorKey = deriveXorKey(password, salt)
            LockedPlaybackSession.start(xorKey, manifest, folderPath, password)
            // Restore current folder to parent (don't navigate into the new folder)
            val parentPath = folder.parent
            if (parentPath != null) {
                LockedPlaybackSession.setCurrentFolder(parentPath)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating empty locked folder: ${e.message}", e)
            false
        }
    }

    /**
     * Generates and saves a thumbnail for a locked video using the active XOR session.
     * Uses a custom MediaDataSource to reverse XOR on-the-fly without touching the file.
     */
    fun generateAndSaveLockedThumbnail(videoPath: String): Bitmap? {
        val file = File(videoPath)
        val folder = file.parentFile ?: return null
        val xorKey = LockedPlaybackSession.getXorKeyForFile(videoPath) ?: return null

        val thumbsDir = File(folder, THUMBS_DIR)
        if (!thumbsDir.exists()) thumbsDir.mkdirs()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(XorMediaDataSource(file, xorKey))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val middleTimeUs = (durationMs * 1000L) / 2
            val bitmap = retriever.getFrameAtTime(middleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                val thumbFile = File(thumbsDir, file.name)
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val thumbBytes = baos.toByteArray()
                for (i in thumbBytes.indices) {
                    thumbBytes[i] = (thumbBytes[i].toInt() xor THUMB_XOR_KEY[i % THUMB_XOR_KEY.size].toInt()).toByte()
                }
                thumbFile.writeBytes(thumbBytes)
            }
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate locked thumbnail for: ${file.name}", e)
            null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
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

    fun getLockedVideoDuration(videoPath: String): String? {
        val file = File(videoPath)
        val xorKey = LockedPlaybackSession.getXorKeyForFile(videoPath) ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(XorMediaDataSource(file, xorKey))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            durationMs?.let {
                val minutes = it / 1000L / 60L
                val seconds = (it / 1000L) % 60L
                String.format("%02d:%02d", minutes, seconds)
            }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}

private class XorMediaDataSource(
    private val file: File,
    private val xorKey: ByteArray,
    private val headerSize: Int = 8192
) : android.media.MediaDataSource() {
    private val raf = RandomAccessFile(file, "r")

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= raf.length()) return -1
        raf.seek(position)
        val read = raf.read(buffer, offset, size)
        if (read <= 0) return read
        for (i in 0 until read) {
            val filePos = position + i
            if (filePos < headerSize) {
                buffer[offset + i] = (buffer[offset + i].toInt() xor xorKey[(filePos % xorKey.size).toInt()].toInt()).toByte()
            }
        }
        return read
    }

    override fun getSize(): Long = try { raf.length() } catch (e: Exception) { -1 }

    override fun close() {
        try { raf.close() } catch (e: Exception) {}
    }
}
