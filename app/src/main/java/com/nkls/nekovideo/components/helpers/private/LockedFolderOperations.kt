package com.nkls.nekovideo.components.helpers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

object LockedFolderOperations {

    suspend fun lockFolders(
        context: Context,
        folderPaths: List<String>,
        password: String,
        onStateChange: (Boolean) -> Unit = {},
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        onFolderLocked: () -> Unit = {}
    ) {
        notifyState(onStateChange, true)
        try {
            withContext(Dispatchers.IO) {
                folderPaths.forEach { folderPath ->
                    FolderLockManager.lockFolder(
                        context = context,
                        folderPath = folderPath,
                        password = password,
                        onProgress = { current, total, _ ->
                            runBlocking { notifyProgress(onProgress, current, total) }
                        },
                        onError = { message ->
                            runBlocking { notifyError(onError, message) }
                        },
                        onSuccess = {
                            runBlocking { notifySuccess(onFolderLocked) }
                        }
                    )
                }
            }
        } finally {
            notifyState(onStateChange, false)
        }
    }

    suspend fun unlockFolders(
        context: Context,
        folderPaths: List<String>,
        password: String,
        onStateChange: (Boolean) -> Unit = {},
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        onFolderUnlocked: () -> Unit = {}
    ): List<String> {
        notifyState(onStateChange, true)
        val unlockedPaths = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) {
                folderPaths.forEach { folderPath ->
                    FolderLockManager.unlockFolder(
                        context = context,
                        folderPath = folderPath,
                        password = password,
                        onProgress = { current, total, _ ->
                            runBlocking { notifyProgress(onProgress, current, total) }
                        },
                        onError = { message ->
                            runBlocking { notifyError(onError, message) }
                        },
                        onSuccess = { restoredPath ->
                            unlockedPaths += restoredPath
                            runBlocking { notifySuccess(onFolderUnlocked) }
                        }
                    )
                }
            }
        } finally {
            notifyState(onStateChange, false)
        }

        return unlockedPaths.distinct()
    }

    suspend fun secureItems(
        context: Context,
        itemsToSecure: List<String>,
        securePath: String,
        password: String,
        onMoveStateChange: (Boolean) -> Unit = {},
        onMoveProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onLockStateChange: (Boolean) -> Unit = {},
        onLockProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        if (!FolderLockManager.isLocked(securePath)) {
            notifyState(onLockStateChange, true)
            try {
                withContext(Dispatchers.IO) {
                    FolderLockManager.lockFolder(
                        context = context,
                        folderPath = securePath,
                        password = password,
                        onProgress = { current, total, _ ->
                            runBlocking { notifyProgress(onLockProgress, current, total) }
                        },
                        onError = { message ->
                            runBlocking { notifyError(onError, message) }
                        }
                    )
                }
            } finally {
                notifyState(onLockStateChange, false)
                notifyProgress(onLockProgress, 0, 0)
            }
        }

        notifyState(onMoveStateChange, true)
        try {
            notifyProgress(onMoveProgress, 0, itemsToSecure.size)
            FilesManager.moveSelectedItems(
                context = context,
                selectedItems = itemsToSecure,
                destinationPath = securePath,
                onProgress = onMoveProgress,
                onError = onError,
                onSuccess = {}
            )
        } finally {
            notifyState(onMoveStateChange, false)
            notifyProgress(onMoveProgress, 0, 0)
        }

        lockMovedItemsIntoFolder(
            context = context,
            destinationPath = securePath,
            originalItemPaths = itemsToSecure,
            password = password,
            onLockStateChange = onLockStateChange,
            onLockProgress = onLockProgress,
            onError = onError
        )

        refreshLockedPlaybackSessionIfNeeded(securePath, password)
        notifySuccess(onSuccess)
    }

    suspend fun pasteItems(
        context: Context,
        movedItems: List<String>,
        destinationPath: String,
        sourceLockedFolder: String?,
        password: String?,
        destinationIsLocked: Boolean,
        onMoveStateChange: (Boolean) -> Unit = {},
        onMoveProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onLockStateChange: (Boolean) -> Unit = {},
        onLockProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ): Boolean {
        notifyState(onMoveStateChange, true)
        try {
            notifyProgress(onMoveProgress, 0, movedItems.size)

            val actualItemsToMove = withContext(Dispatchers.IO) {
                if (sourceLockedFolder != null && password != null) {
                    val obfuscatedNames = movedItems.map { File(it).name }
                    val sourceManifest = FolderLockManager.readManifestForLocalEdit(sourceLockedFolder, password)
                        ?: error("Invalid password or unavailable private metadata")

                    val fileObfNames = obfuscatedNames.filter { name ->
                        sourceManifest.files.any { it.obfuscatedName == name }
                    }
                    val subfolderObfNames = obfuscatedNames.filter { name ->
                        sourceManifest.subfolders.any { it.obfuscatedName == name }
                    }

                    val restoredFilePaths = if (fileObfNames.isNotEmpty()) {
                        val (files, updatedManifest) = FolderLockManager.extractFilesFromLockedFolder(
                            context,
                            sourceLockedFolder,
                            fileObfNames,
                            password
                        )
                        if (updatedManifest != null) {
                            LockedPlaybackSession.updateManifest(sourceLockedFolder, updatedManifest)
                        }
                        files.map { it.absolutePath }
                    } else {
                        emptyList()
                    }

                    val restoredFolderPaths = subfolderObfNames.mapNotNull { obfName ->
                        FolderLockManager.extractSubfolderFromLockedParent(
                            context,
                            sourceLockedFolder,
                            obfName,
                            password
                        )?.let { directory ->
                            var restoredPath = directory.absolutePath
                            FolderLockManager.unlockFolderRecursive(context, directory.absolutePath, password,
                                onError = { error(it) }, onResolvedPath = { restoredPath = it })
                            restoredPath
                        }
                    }

                    val untracked = movedItems.filter { path ->
                        val file = File(path)
                        file.name !in fileObfNames && file.name !in subfolderObfNames &&
                            (file.isDirectory || (file.isFile && file.extension.lowercase() in supportedVideoExtensions &&
                                !PortableVideoStore.hasRecoveryRecord(file)))
                    }.map { path ->
                        var restoredPath = path
                        if (File(path).isDirectory) FolderLockManager.unlockFolderRecursive(context, path, password,
                            onError = { error(it) }, onResolvedPath = { restoredPath = it })
                        restoredPath
                    }

                    restoredFilePaths + restoredFolderPaths + untracked
                } else {
                    movedItems
                }
            }

            if (actualItemsToMove.isEmpty()) {
                notifyError(onError, "Error extracting files")
                return false
            }

            var operationFailed = false
            FilesManager.moveSelectedItems(
                context = context,
                selectedItems = actualItemsToMove,
                destinationPath = destinationPath,
                onProgress = onMoveProgress,
                onError = { operationFailed = true; onError(it) },
                onSuccess = {}
            )

            if (destinationIsLocked && password != null) {
                lockMovedItemsIntoFolder(
                    context = context,
                    destinationPath = destinationPath,
                    originalItemPaths = actualItemsToMove,
                    password = password,
                    onLockStateChange = onLockStateChange,
                    onLockProgress = onLockProgress,
                    onError = { operationFailed = true; onError(it) }
                )
                refreshLockedPlaybackSessionIfNeeded(destinationPath, password)
            }

            if (!operationFailed) notifySuccess(onSuccess)
            return !operationFailed
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            notifyError(onError, failure.message ?: "Could not move private files")
            return false
        } finally {
            notifyState(onMoveStateChange, false)
            notifyProgress(onMoveProgress, 0, 0)
        }
    }

    suspend fun deleteLockedItems(
        context: Context,
        selectedItems: List<String>,
        parentLockedFolder: String,
        password: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        try { withContext(Dispatchers.IO) {
            val totalItems = selectedItems.size
            checkNotNull(FolderLockManager.readManifestForLocalEdit(parentLockedFolder, password)) {
                "Invalid password or unavailable private metadata"
            }
            val parent = File(parentLockedFolder).canonicalFile
            require(selectedItems.all { File(it).canonicalFile.parentFile == parent }) { "Selection is outside the private folder" }
            val removed = mutableListOf<String>()
            try { selectedItems.forEachIndexed { index, itemPath ->
                val file = File(itemPath)
                if (file.isDirectory) {
                    if (FolderLockManager.isLocked(itemPath)) {
                        LockedPlaybackSession.removeSession(itemPath)
                    }
                    check(file.deleteRecursively()) { "Could not delete folder" }
                } else {
                    check(file.delete()) { "Could not delete video" }
                }
                removed += file.name
                VideoTagStore.resetTagsForPathTree(context, itemPath)
                notifyProgress(onProgress, index + 1, totalItems)
            } } finally {
                // Commit confirmed removals together, including when a later deletion fails.
                if (removed.isNotEmpty()) {
                    val updated = FolderLockManager.removeItemsFromManifest(context, parentLockedFolder, removed, password)
                    checkNotNull(updated) { "Could not update private folder metadata" }
                    LockedPlaybackSession.updateManifest(parentLockedFolder, updated)
                }
            }
        } } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            notifyError(onError, failure.message ?: "Could not delete private files")
            return
        }

        notifySuccess(onSuccess)
    }

    private suspend fun lockMovedItemsIntoFolder(
        context: Context,
        destinationPath: String,
        originalItemPaths: List<String>,
        password: String,
        onLockStateChange: (Boolean) -> Unit,
        onLockProgress: (current: Int, total: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val movedItems = originalItemPaths.map { File(destinationPath, File(it).name) }
        val movedFiles = movedItems.filter { it.isFile }
        val movedFolders = movedItems.filter { it.isDirectory }

        if (movedFiles.isNotEmpty()) {
            notifyState(onLockStateChange, true)
            try {
                withContext(Dispatchers.IO) {
                    FolderLockManager.addFilesToLockedFolder(
                        context = context,
                        folderPath = destinationPath,
                        password = password,
                        newFiles = movedFiles,
                        onProgress = { current, total, _ ->
                            runBlocking { notifyProgress(onLockProgress, current, total) }
                        },
                        onError = { message ->
                            runBlocking { notifyError(onError, message) }
                        }
                    )
                }
            } finally {
                notifyState(onLockStateChange, false)
                notifyProgress(onLockProgress, 0, 0)
            }
        }

        movedFolders.forEach { folder ->
            if (!folder.exists()) return@forEach

            notifyState(onLockStateChange, true)
            try {
                withContext(Dispatchers.IO) {
                    FolderLockManager.lockFolderRecursive(
                        context = context,
                        folderPath = folder.absolutePath,
                        password = password,
                        onProgress = { current, total, _ ->
                            runBlocking { notifyProgress(onLockProgress, current, total) }
                        },
                        onError = { message ->
                            runBlocking { notifyError(onError, message) }
                        }
                    )
                    FolderLockManager.addSubfolderToLockedFolder(
                        context,
                        destinationPath,
                        folder.absolutePath,
                        password
                    )
                }
            } finally {
                notifyState(onLockStateChange, false)
                notifyProgress(onLockProgress, 0, 0)
            }
        }
    }

    private suspend fun refreshLockedPlaybackSessionIfNeeded(folderPath: String, password: String) {
        withContext(Dispatchers.IO) {
            if (!LockedPlaybackSession.isActive || !LockedPlaybackSession.hasSessionForFolder(folderPath)) {
                return@withContext
            }

            val updatedManifest = FolderLockManager.readManifestForLocalEdit(folderPath, password)
            val salt = FolderLockManager.getSalt(folderPath)
            if (updatedManifest != null && salt != null) {
                val xorKey = FolderLockManager.deriveXorKey(password, salt)
                LockedPlaybackSession.start(xorKey, updatedManifest, folderPath, password)
            }
        }
    }

    private suspend fun notifyState(onStateChange: (Boolean) -> Unit, value: Boolean) {
        withContext(Dispatchers.Main) {
            onStateChange(value)
        }
    }

    private suspend fun notifyProgress(onProgress: (current: Int, total: Int) -> Unit, current: Int, total: Int) {
        withContext(Dispatchers.Main) {
            onProgress(current, total)
        }
    }

    private suspend fun notifyError(onError: (String) -> Unit, message: String) {
        withContext(Dispatchers.Main) {
            onError(message)
        }
    }

    private suspend fun notifySuccess(onSuccess: () -> Unit) {
        withContext(Dispatchers.Main) {
            onSuccess()
        }
    }
}
