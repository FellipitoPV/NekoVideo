package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import kotlin.coroutines.resume

object FilesManager {

    suspend fun renameSelectedItems(
        context: Context,
        selectedItems: List<String>,
        baseName: String,
        startNumber: Int,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
    ) {
        if (selectedItems.isEmpty()) {
            showToast(context, "No items selected")
            return
        }

        withContext(Dispatchers.IO) {
            val parentDir = File(selectedItems.first()).parentFile ?: return@withContext
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
                    newName = if (currentNumber == 0) baseName else "${baseName} $currentNumber"
                    val fullNewName = "$newName$extension"
                    if (fullNewName !in existingFiles && !File(parentDir, fullNewName).exists()) {
                        break
                    }
                    currentNumber++
                }

                val newFile = File(parentDir, "$newName$extension")
                if (file.renameTo(newFile)) {
                    VideoTagStore.moveTagsForPath(context, file.absolutePath, newFile.absolutePath)
                    existingFiles.add("$newName$extension")
                    renamedCount++
                } else {
                    showToast(context, "Failed to rename ${file.name}")
                }
                currentNumber++
                notifyProgress(onProgress, index + 1, totalItems)
            }

            if (renamedCount == 0) {
                showToast(context, "No files were renamed")
            } else {
                // ✅ NOVO: Chama refresh se fornecido
                notifyRefresh(onRefresh)
            }
        }
    }

    suspend fun moveSelectedItems(
        context: Context,
        selectedItems: List<String>,
        destinationPath: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> },
        onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
    ) {
        if (selectedItems.isEmpty()) {
            notifyMessage(onError, "No items selected")
            return
        }

        withContext(Dispatchers.IO) {
            val destinationDir = File(destinationPath)
            if (!destinationDir.exists() || !destinationDir.isDirectory) {
                if (!destinationDir.mkdirs()) {
                    notifyMessage(onError, "Failed to create destination folder")
                    return@withContext
                }
            }

            var movedCount = 0
            val totalItems = selectedItems.size

            selectedItems.forEachIndexed { index, path ->
                val file = File(path)
                if (!file.exists()) return@forEachIndexed

                val newFile = File(destinationDir, file.name)
                val wasLockedFolder = file.isDirectory && FolderLockManager.isLocked(file.absolutePath)

                if (newFile.exists()) {
                    notifyMessage(onError, "${file.name} already exists in destination")
                    return@forEachIndexed
                }

                if (file.renameTo(newFile)) {
                    VideoTagStore.moveTagsForPath(context, file.absolutePath, newFile.absolutePath)
                    if (wasLockedFolder) {
                        FolderLockManager.onLockedFolderMoved(context, file.absolutePath, newFile.absolutePath)
                    }
                    movedCount++
                } else {
                    try {
                        if (file.isDirectory) {
                            file.copyRecursively(newFile, overwrite = false)
                            file.deleteRecursively()
                        } else {
                            file.copyTo(newFile)
                            file.delete()
                        }
                        VideoTagStore.moveTagsForPath(context, file.absolutePath, newFile.absolutePath)
                        if (wasLockedFolder) {
                            FolderLockManager.onLockedFolderMoved(context, file.absolutePath, newFile.absolutePath)
                        }
                        movedCount++
                    } catch (e: Exception) {
                        notifyMessage(onError, "Failed to move ${file.name}")
                    }
                }

                notifyProgress(onProgress, index + 1, totalItems)
            }

            if (movedCount == 0) {
                notifyMessage(onError, "No files were moved")
            } else {
                notifyMessage(onSuccess, "Moved $movedCount items")
                // ✅ NOVO: Chama refresh se fornecido
                notifyRefresh(onRefresh)
            }
        }
    }

    fun createFolderWithMarker(
        context: Context,
        path: String,
        folderName: String,
        onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
    ): Boolean {
        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        val appOnlyFolders = prefs.getBoolean("app_only_folders", false)

        val finalFolderName = if (appOnlyFolders && !folderName.startsWith(".")) {
            ".$folderName"
        } else {
            folderName
        }

        val newFolder = File(path, finalFolderName)
        val success = if (newFolder.mkdirs()) {
            File(newFolder, ".nekovideo").createNewFile()

            if (appOnlyFolders) {
                File(newFolder, ".nomedia").createNewFile()
            }
            true
        } else {
            false
        }

        // ✅ NOVO: Chama refresh se fornecido e bem-sucedido
        if (success) {
            onRefresh?.invoke()
        }

        return success
    }

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

    suspend fun deleteSelectedItems(
        context: Context,
        selectedItems: List<String>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> },
        onConfirmRequired: (onConfirm: () -> Unit) -> Unit = { it() },
        onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
    ) {
        if (selectedItems.isEmpty()) {
            onError("No items selected")
            return
        }

        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        val confirmDelete = prefs.getBoolean("confirm_delete", true)

        if (confirmDelete) {
            awaitConfirmation(onConfirmRequired)
        }

        performDelete(context, selectedItems, onProgress, onError) { message ->
            notifyMessage(onSuccess, message)
            // ✅ NOVO: Chama refresh após delete bem-sucedido
            notifyRefresh(onRefresh)
        }
    }

    private suspend fun performDelete(
        context: Context,
        selectedItems: List<String>,
        onProgress: (current: Int, total: Int) -> Unit,
        onError: (message: String) -> Unit,
        onSuccess: suspend (message: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var deletedCount = 0
        val totalItems = selectedItems.size

        selectedItems.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) {
                notifyMessage(onError, "Item not found: ${file.name}")
                return@forEachIndexed
            }

            try {
                VideoTagStore.resetTagsForPathTree(context, file.absolutePath)
                if (file.deleteRecursively()) {
                    deletedCount++
                } else {
                    notifyMessage(onError, "Failed to delete ${file.name}")
                }
            } catch (e: Exception) {
                notifyMessage(onError, "Error deleting ${file.name}: ${e.message}")
            }

            notifyProgress(onProgress, index + 1, totalItems)
        }

        if (deletedCount == 0) {
            notifyMessage(onError, "No items were deleted")
        } else {
            onSuccess("Deleted $deletedCount items")
        }
    }

    suspend fun deleteSecureSelectedItems(
        context: Context,
        selectedItems: List<String>,
        secureFolderPath: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> },
        onConfirmRequired: (onConfirm: () -> Unit) -> Unit = { it() },
        onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
    ) {
        if (selectedItems.isEmpty()) {
            notifyMessage(onError, "No items selected")
            return
        }

        val invalidItems = selectedItems.filterNot { it.startsWith(secureFolderPath) }
        if (invalidItems.isNotEmpty()) {
            notifyMessage(onError, "Some items are not in the secure folder")
            return
        }

        val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
        val confirmDelete = prefs.getBoolean("confirm_delete", true)

        if (confirmDelete) {
            awaitConfirmation(onConfirmRequired)
        }

        performSecureDelete(context, selectedItems, onProgress, onError) { message ->
            notifyMessage(onSuccess, message)
            // ✅ NOVO: Chama refresh após delete seguro
            notifyRefresh(onRefresh)
        }
    }

    private suspend fun performSecureDelete(
        context: Context,
        selectedItems: List<String>,
        onProgress: (current: Int, total: Int) -> Unit,
        onError: (message: String) -> Unit,
        onSuccess: suspend (message: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var deletedCount = 0
        val totalItems = selectedItems.size

        selectedItems.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) {
                notifyMessage(onError, "Item not found: ${file.name}")
                return@forEachIndexed
            }

            try {
                VideoTagStore.resetTagsForPathTree(context, file.absolutePath)
                if (file.deleteRecursively()) {
                    deletedCount++
                } else {
                    notifyMessage(onError, "Failed to delete secure item ${file.name}")
                }
            } catch (e: Exception) {
                notifyMessage(onError, "Error deleting secure item ${file.name}: ${e.message}")
            }

            notifyProgress(onProgress, index + 1, totalItems)
        }

        if (deletedCount == 0) {
            notifyMessage(onError, "No secure items were deleted")
        } else {
            onSuccess("Deleted $deletedCount secure items")
        }
    }

    private suspend fun showToast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun notifyProgress(onProgress: (current: Int, total: Int) -> Unit, current: Int, total: Int) {
        withContext(Dispatchers.Main) {
            onProgress(current, total)
        }
    }

    private suspend fun notifyMessage(callback: (message: String) -> Unit, message: String) {
        withContext(Dispatchers.Main) {
            callback(message)
        }
    }

    private suspend fun notifyRefresh(onRefresh: (() -> Unit)?) {
        withContext(Dispatchers.Main) {
            onRefresh?.invoke()
        }
    }

    private suspend fun awaitConfirmation(onConfirmRequired: (onConfirm: () -> Unit) -> Unit) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                onConfirmRequired {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
    }

    fun privatizeFolders(
        context: Context,
        selectedFolders: List<String>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> },
        onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
    ) {
        if (selectedFolders.isEmpty()) {
            onError("Nenhuma pasta selecionada")
            return
        }

        val foldersToPrivatize = selectedFolders.filter { path ->
            val folder = File(path)
            folder.isDirectory && !folder.name.startsWith(".")
        }

        if (foldersToPrivatize.isEmpty()) {
            onError("Nenhuma pasta válida para privar")
            return
        }

        var privatizedCount = 0
        val totalFolders = foldersToPrivatize.size

        foldersToPrivatize.forEachIndexed { index, folderPath ->
            val originalFolder = File(folderPath)
            if (!originalFolder.exists() || !originalFolder.isDirectory) {
                onError("Pasta não encontrada: ${originalFolder.name}")
                return@forEachIndexed
            }

            try {
                kotlinx.coroutines.runBlocking {
                    VideoTagStore.resetTagsForPathTree(context, originalFolder.absolutePath)
                }
                val parentDir = originalFolder.parentFile
                val newName = ".${originalFolder.name}"
                val newFolder = File(parentDir, newName)

                if (newFolder.exists()) {
                    onError("Já existe uma pasta privada com o nome: $newName")
                    return@forEachIndexed
                }

                if (originalFolder.renameTo(newFolder)) {
                    try {
                        File(newFolder, ".nomedia").createNewFile()
                        privatizedCount++
                    } catch (e: Exception) {
                        newFolder.renameTo(originalFolder)
                        onError("Erro ao criar .nomedia em ${originalFolder.name}")
                    }
                } else {
                    onError("Falha ao privar pasta: ${originalFolder.name}")
                }
            } catch (e: Exception) {
                onError("Erro ao privar ${originalFolder.name}: ${e.message}")
            }

            onProgress(index + 1, totalFolders)
        }

        if (privatizedCount == 0) {
            onError("Nenhuma pasta foi privada")
        } else {
            onSuccess("$privatizedCount pasta(s) privada(s) com sucesso")
            // ✅ NOVO: Chama refresh após privatizar
            onRefresh?.invoke()
        }
    }

    fun unprivatizeFolders(
        context: Context,
        selectedFolders: List<String>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (message: String) -> Unit = { _ -> },
        onSuccess: (message: String) -> Unit = { _ -> },
        onRefresh: (() -> Unit)? = null // ✅ NOVO: Callback de refresh
    ) {
        if (selectedFolders.isEmpty()) {
            onError("Nenhuma pasta selecionada")
            return
        }

        val foldersToUnprivatize = selectedFolders.filter { path ->
            val folder = File(path)
            folder.isDirectory && folder.name.startsWith(".") && folder.name != ".nomedia" && folder.name != ".nekovideo"
        }

        if (foldersToUnprivatize.isEmpty()) {
            onError("Nenhuma pasta privada selecionada")
            return
        }

        var unprivatizedCount = 0
        val totalFolders = foldersToUnprivatize.size

        foldersToUnprivatize.forEachIndexed { index, folderPath ->
            val privateFolder = File(folderPath)
            if (!privateFolder.exists() || !privateFolder.isDirectory) {
                onError("Pasta não encontrada: ${privateFolder.name}")
                return@forEachIndexed
            }

            try {
                kotlinx.coroutines.runBlocking {
                    VideoTagStore.resetTagsForPathTree(context, privateFolder.absolutePath)
                }
                val parentDir = privateFolder.parentFile
                val newName = privateFolder.name.substring(1)
                val newFolder = File(parentDir, newName)

                if (newFolder.exists()) {
                    onError("Já existe uma pasta com o nome: $newName")
                    return@forEachIndexed
                }

                val nomediaFile = File(privateFolder, ".nomedia")
                if (nomediaFile.exists()) {
                    nomediaFile.delete()
                }

                if (privateFolder.renameTo(newFolder)) {
                    unprivatizedCount++
                } else {
                    onError("Falha ao desprivar pasta: ${privateFolder.name}")
                }
            } catch (e: Exception) {
                onError("Erro ao desprivar ${privateFolder.name}: ${e.message}")
            }

            onProgress(index + 1, totalFolders)
        }

        if (unprivatizedCount == 0) {
            onError("Nenhuma pasta foi desprivada")
        } else {
            onSuccess("$unprivatizedCount pasta(s) desprivada(s) com sucesso")
            // ✅ NOVO: Chama refresh após desprivatizar
            onRefresh?.invoke()
        }
    }

    fun isFolderPrivate(folderPath: String): Boolean {
        val folder = File(folderPath)
        return folder.isDirectory && folder.name.startsWith(".") &&
                folder.name != ".nomedia" && folder.name != ".nekovideo"
    }

    fun canFolderBePrivatized(folderPath: String): Boolean {
        val folder = File(folderPath)
        return folder.isDirectory && !folder.name.startsWith(".")
    }

    fun isFolderLocked(folderPath: String): Boolean {
        return File(folderPath, ".neko_locked").exists()
    }

    suspend fun getVideosRecursive(
        context: Context,
        folderPath: String,
        isSecureMode: Boolean,
        showPrivateFolders: Boolean,
        selectedItems: List<String> = emptyList(),
        sessionPassword: String? = null
    ): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {

        fun isSecureFolder(folderPath: String): Boolean {
            return folderPath.contains("/.private/") ||
                    folderPath.contains("/secure/") ||
                    folderPath.contains(".secure_videos") ||
                    folderPath.endsWith(".secure_videos") ||
                    File(folderPath, ".secure").exists() ||
                    File(folderPath, ".nomedia").exists()
        }

        fun getLockedVideos(path: String, password: String): List<String> {
            val manifest = FolderLockManager.readManifest(path, password) ?: return emptyList()
            return manifest.files.map { entry ->
                "locked://${File(path, entry.obfuscatedName).absolutePath}"
            }
        }

        fun scanFolder(path: String, isSecure: Boolean): List<String> {
            val videos = mutableListOf<String>()
            val folder = File(path)

            if (!folder.exists() || !folder.isDirectory) return videos

            // If this folder is locked, get videos from manifest and continue scanning subfolders
            if (FolderLockManager.isLocked(path) && sessionPassword != null) {
                videos.addAll(getLockedVideos(path, sessionPassword))
                // Continue into subdirectories for recursive locked subfolder support
                File(path).listFiles()?.forEach { file ->
                    if (file.isDirectory && file.name !in listOf(".neko_thumbs")) {
                        videos.addAll(scanFolder(file.absolutePath, true))
                    }
                }
                return videos
            }

            try {
                folder.listFiles()?.forEach { file ->
                    when {
                        file.isFile && file.extension.lowercase() in listOf(
                            "mp4", "mkv", "webm", "avi", "mov", "wmv", "m4v", "3gp", "flv"
                        ) -> {
                            videos.add("file://${file.absolutePath}")
                        }
                        file.isDirectory && file.name !in listOf(".neko_thumbs") -> {
                            val shouldEnter = if (file.name.startsWith(".")) {
                                showPrivateFolders || isSecure
                            } else {
                                true
                            }

                            if (shouldEnter) {
                                val subFolderSecure = isSecureFolder(file.absolutePath)
                                videos.addAll(scanFolder(file.absolutePath, subFolderSecure))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FilesManager", "Erro ao escanear: $path", e)
            }

            return videos
        }

        if (selectedItems.isNotEmpty()) {
            val allVideos = mutableListOf<String>()

            selectedItems.forEach { selectedPath ->
                val file = File(selectedPath)

                when {
                    file.isDirectory -> {
                        val isSelectedSecure = isSecureFolder(selectedPath)
                        allVideos.addAll(scanFolder(selectedPath, isSelectedSecure))
                    }
                    file.isFile -> {
                        allVideos.add("file://$selectedPath")
                    }
                }
            }

            allVideos
        } else {
            scanFolder(folderPath, isSecureMode)
        }
    }

    object SecureStorage {
        private const val SECURE_FOLDER_NAME = ".secure_videos"
        private const val NOMEDIA_FILE = ".nomedia"
        private const val PREFS_NAME = "NekoVideoPrefs"
        private const val PREF_PASSWORD_KEY = "secure_password"

        private const val PREF_CUSTOM_SECURE_PATH = "custom_secure_folder_path"

        fun setCustomSecureFolderPath(context: Context, folderPath: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_CUSTOM_SECURE_PATH, folderPath).apply()
        }

        fun getCustomSecureFolderPath(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREF_CUSTOM_SECURE_PATH, null)
        }

        fun hasCustomSecureFolder(context: Context): Boolean {
            return getCustomSecureFolderPath(context) != null
        }

        fun getSecureFolderPath(context: Context): String {
            // Primeiro tenta pegar o caminho customizado
            val customPath = getCustomSecureFolderPath(context)
            if (customPath != null) {
                return customPath
            }
            // Se não tiver, retorna o padrão
            return File("/storage/emulated/0", SECURE_FOLDER_NAME).absolutePath
        }

        fun getSecureVideosRecursively(context: Context, folderPath: String): List<String> {
            val videoPaths = mutableListOf<String>()
            val folder = File(folderPath)

            if (!folder.exists() || !folder.isDirectory) return emptyList()

            folder.listFiles()?.forEach { file ->
                if (file.name == ".nomedia") return@forEach
                if (file.isDirectory) {
                    videoPaths.addAll(getSecureVideosRecursively(context, file.absolutePath))
                } else if (file.isFile && file.extension.lowercase() in supportedVideoExtensions) {
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

        // Nova pasta privada real — usa o sistema .nekovideo do app
        private const val NEKO_PRIVATE_FOLDER_NAME = "NekoVideo"

        fun getNekoPrivateFolderPath(): String {
            return File("/storage/emulated/0", NEKO_PRIVATE_FOLDER_NAME).absolutePath
        }

        fun ensureNekoPrivateFolderExists(): Boolean {
            return try {
                val nekoFolder = File(getNekoPrivateFolderPath())
                if (!nekoFolder.exists()) {
                    nekoFolder.mkdirs()
                    File(nekoFolder, ".nekovideo").createNewFile()
                }
                nekoFolder.exists() && nekoFolder.isDirectory
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


    object SecureFoldersVisibility {
        private const val PREF_SHOW_SECURE_FOLDERS = "show_secure_folders"

        /**
         * Mostra/esconde as pastas seguras
         */
        fun setSecureFoldersVisible(context: Context, visible: Boolean) {
            val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_SHOW_SECURE_FOLDERS, visible).apply()
        }

        /**
         * Verifica se as pastas seguras estão visíveis
         */
        fun areSecureFoldersVisible(context: Context): Boolean {
            val prefs = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_SHOW_SECURE_FOLDERS, false)
        }

        /**
         * Alterna a visibilidade das pastas seguras
         */
        fun toggleSecureFoldersVisibility(context: Context): Boolean {
            val currentState = areSecureFoldersVisible(context)
            val newState = !currentState
            setSecureFoldersVisible(context, newState)
            return newState
        }

        /**
         * Esconde as pastas seguras
         */
        fun hideSecureFolders(context: Context) {
            setSecureFoldersVisible(context, false)
        }

        /**
         * Mostra as pastas seguras (após validação de senha)
         */
        fun showSecureFolders(context: Context) {
            setSecureFoldersVisible(context, true)
        }

        fun resetOnAppStart(context: Context) {
            setSecureFoldersVisible(context, false)
        }
    }
}
