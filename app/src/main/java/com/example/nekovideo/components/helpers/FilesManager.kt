package com.example.nekovideo.components.helpers

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object FilesManager {

    // Existing renameSelectedItems function (unchanged)
    fun renameSelectedItems(
        context: Context,
        selectedItems: List<String>,
        baseName: String,
        startNumber: Int,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (selectedItems.isEmpty()) {
            Toast.makeText(context, "No items selected", Toast.LENGTH_SHORT).show()
            return
        }

        val parentDir = File(selectedItems.first()).parentFile ?: return
        val existingFiles = parentDir.listFiles()?.map { it.name }?.toMutableSet() ?: mutableSetOf()
        var currentNumber = startNumber
        var renamedCount = 0
        val totalItems = selectedItems.size

        selectedItems.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) return@forEachIndexed

            val extension = if (file.isFile) ".${file.extension}" else ""
            var newName: String

            while (true) {
                newName = if (currentNumber == 0) baseName else "$baseName $currentNumber"
                val fullNewName = "$newName$extension"
                if (fullNewName !in existingFiles && !File(parentDir, fullNewName).exists()) {
                    break
                }
                currentNumber++
            }

            val newFile = File(parentDir, "$newName$extension")
            if (file.renameTo(newFile)) {
                existingFiles.add("$newName$extension")
                renamedCount++
            } else {
                Toast.makeText(context, "Failed to rename ${file.name}", Toast.LENGTH_SHORT).show()
            }
            currentNumber++
            onProgress(index + 1, totalItems)
        }

        if (renamedCount == 0) {
            Toast.makeText(context, "No files were renamed", Toast.LENGTH_SHORT).show()
        }
    }

    // Existing moveSelectedItems function (unchanged)
    fun moveSelectedItems(
        context: Context,
        selectedItems: List<String>,
        destinationPath: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> }
    ) {
        if (selectedItems.isEmpty()) {
            onError("No items selected")
            return
        }

        val destinationDir = File(destinationPath)
        if (!destinationDir.exists() || !destinationDir.isDirectory) {
            // Attempt to create the destination directory if it doesn't exist
            if (!destinationDir.mkdirs()) {
                onError("Failed to create destination folder")
                return
            }
        }

        var movedCount = 0
        val totalItems = selectedItems.size

        selectedItems.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) return@forEachIndexed

            val newFile = File(destinationDir, file.name)

            if (newFile.exists()) {
                onError("${file.name} already exists in destination")
                return@forEachIndexed
            }

            if (file.renameTo(newFile)) {
                movedCount++
            } else {
                try {
                    file.copyTo(newFile)
                    file.delete()
                    movedCount++
                } catch (e: Exception) {
                    onError("Failed to move ${file.name}")
                }
            }

            onProgress(index + 1, totalItems)
        }

        if (movedCount == 0) {
            onError("No files were moved")
        } else {
            onSuccess("Moved $movedCount items")
        }
    }

    fun createFolderWithMarker(context: Context, path: String, folderName: String): Boolean {
        val newFolder = File(path, folderName)
        if (newFolder.mkdirs()) {
            File(newFolder, ".nekovideo").createNewFile()
            return true
        }
        return false
    }

    // New helper function to ensure the unlocked folder exists
    fun ensureUnlockedFolderExists(): Boolean {
        val unlockedFolder = File("/storage/emulated/0/DCIM/Unlooked")
        return try {
            if (!unlockedFolder.exists()) {
                unlockedFolder.mkdirs()
            }
            unlockedFolder.exists() && unlockedFolder.isDirectory
        } catch (e: Exception) {
            false
        }
    }

    fun deleteSelectedItems(
        context: Context,
        selectedItems: List<String>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> }
    ) {
        if (selectedItems.isEmpty()) {
            onError("No items selected")
            return
        }

        var deletedCount = 0
        val totalItems = selectedItems.size

        selectedItems.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) {
                onError("Item not found: ${file.name}")
                return@forEachIndexed
            }

            try {
                if (file.deleteRecursively()) {
                    deletedCount++
                } else {
                    onError("Failed to delete ${file.name}")
                }
            } catch (e: Exception) {
                onError("Error deleting ${file.name}: ${e.message}")
            }

            onProgress(index + 1, totalItems)
        }

        if (deletedCount == 0) {
            onError("No items were deleted")
        } else {
            onSuccess("Deleted $deletedCount items")
        }
    }

    fun deleteSecureSelectedItems(
        context: Context,
        selectedItems: List<String>,
        secureFolderPath: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> }
    ) {
        if (selectedItems.isEmpty()) {
            onError("No items selected")
            return
        }

        // Verifica se todos os itens estão dentro da pasta segura
        val invalidItems = selectedItems.filterNot { it.startsWith(secureFolderPath) }
        if (invalidItems.isNotEmpty()) {
            onError("Some items are not in the secure folder")
            return
        }

        var deletedCount = 0
        val totalItems = selectedItems.size

        selectedItems.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) {
                onError("Item not found: ${file.name}")
                return@forEachIndexed
            }

            try {
                if (file.deleteRecursively()) {
                    deletedCount++
                } else {
                    onError("Failed to delete secure item ${file.name}")
                }
            } catch (e: Exception) {
                onError("Error deleting secure item ${file.name}: ${e.message}")
            }

            onProgress(index + 1, totalItems)
        }

        if (deletedCount == 0) {
            onError("No secure items were deleted")
        } else {
            onSuccess("Deleted $deletedCount secure items")
        }
    }

    object SecureStorage {
        private const val SECURE_FOLDER_NAME = ".secure_videos"
        private const val NOMEDIA_FILE = ".nomedia"
        private const val PREFS_NAME = "NekoVideoPrefs"
        private const val PREF_PASSWORD_KEY = "secure_password"

        fun getSecureFolderPath(context: Context): String {
            return File("/storage/emulated/0", SECURE_FOLDER_NAME).absolutePath
        }

        fun getSecureVideosRecursively(context: Context, folderPath: String): List<String> {
            val videoPaths = mutableListOf<String>()
            val folder = File(folderPath)

            if (!folder.exists() || !folder.isDirectory) return emptyList()

            folder.listFiles()?.forEach { file ->
                if (file.name == ".nomedia") return@forEach // Ignorar .nomedia
                if (file.isDirectory) {
                    videoPaths.addAll(getSecureVideosRecursively(context, file.absolutePath))
                } else if (file.isFile && file.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "wmv")) {
                    videoPaths.add(file.absolutePath)
                }
            }

            return videoPaths
        }

        fun ensureSecureFolderExists(context: Context): Boolean {
            return try {
                val secureFolder = File(getSecureFolderPath(context))
                if (!secureFolder.exists()) {
                    secureFolder.mkdirs()
                    File(secureFolder, NOMEDIA_FILE).createNewFile()
                }
                secureFolder.exists() && secureFolder.isDirectory
            } catch (e: Exception) {
                false
            }
        }

        fun savePassword(context: Context, password: String): Boolean {
            return try {
                val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = sharedPrefs.edit()
                val encryptedPassword = encryptPassword(password)
                editor.putString(PREF_PASSWORD_KEY, encryptedPassword)
                editor.apply()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun verifyPassword(context: Context, inputPassword: String): Boolean {
            return try {
                val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val storedPassword = sharedPrefs.getString(PREF_PASSWORD_KEY, null)
                if (storedPassword == null) {
                    // No password set yet, allow setting new password
                    return true
                }
                val decryptedStoredPassword = decryptPassword(storedPassword)
                inputPassword == decryptedStoredPassword
            } catch (e: Exception) {
                false
            }
        }

        fun hasPassword(context: Context): Boolean {
            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return sharedPrefs.contains(PREF_PASSWORD_KEY)
        }

        private fun encryptPassword(password: String): String {
            val key = generateKey()
            val cipher = Cipher.getInstance("AES")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedBytes = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        }

        private fun decryptPassword(encryptedPassword: String): String {
            val key = generateKey()
            val cipher = Cipher.getInstance("AES")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decodedBytes = Base64.decode(encryptedPassword, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        }

        private fun generateKey(): ByteArray {
            val keyString = "nekovideo_secure_key_1234567890"
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(keyString.toByteArray(Charsets.UTF_8)).copyOf(16)
        }
    }
}