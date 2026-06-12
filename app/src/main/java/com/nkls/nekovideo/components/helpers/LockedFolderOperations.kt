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
    ) {
        notifyState(onStateChange, true)
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
                        onSuccess = {
                            runBlocking { notifySuccess(onFolderUnlocked) }
                        }
                    )
                }
            }
        } finally {
            notifyState(onStateChange, false)
        }
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
                    val sourceManifest = FolderLockManager.readManifest(sourceLockedFolder, password)

                    val fileObfNames = obfuscatedNames.filter { name ->
                        sourceManifest?.files?.any { it.obfuscatedName == name } == true
                    }
                    val subfolderObfNames = obfuscatedNames.filter { name ->
                        sourceManifest?.subfolders?.any { it.obfuscatedName == name } == true
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
                        )?.absolutePath
                    }

                    restoredFilePaths + restoredFolderPaths
                } else {
                    movedItems
                }
            }

            if (actualItemsToMove.isEmpty()) {
                notifyError(onError, "Error extracting files")
                return false
            }

            FilesManager.moveSelectedItems(
                context = context,
                selectedItems = actualItemsToMove,
                destinationPath = destinationPath,
                onProgress = onMoveProgress,
                onError = onError,
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
                    onError = onError
                )
                refreshLockedPlaybackSessionIfNeeded(destinationPath, password)
            }

            notifySuccess(onSuccess)
            return true
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
        withContext(Dispatchers.IO) {
            val totalItems = selectedItems.size

            selectedItems.forEachIndexed { index, itemPath ->
                val file = File(itemPath)
                if (file.isDirectory) {
                    if (FolderLockManager.isLocked(itemPath)) {
                        LockedPlaybackSession.removeSession(itemPath)
                    }
                    file.deleteRecursively()
                } else {
                    val obfuscatedName = file.name
                    file.delete()
                    val updatedManifest = FolderLockManager.removeFileFromManifest(
                        context,
                        parentLockedFolder,
                        obfuscatedName,
                        password
                    )
                    if (updatedManifest != null) {
                        LockedPlaybackSession.updateManifest(parentLockedFolder, updatedManifest)
                    }
                }

                notifyProgress(onProgress, index + 1, totalItems)
            }
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

            val updatedManifest = FolderLockManager.readManifest(folderPath, password)
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
