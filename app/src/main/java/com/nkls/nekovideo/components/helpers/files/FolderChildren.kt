package com.nkls.nekovideo.components.helpers

import java.io.File

internal data class FolderChildren(val entries: List<File>, val subfolders: List<File>)

internal fun readFolderChildren(folder: File, cachedVideoPaths: Set<String>): FolderChildren {
    val entries = folder.listFiles()?.toList().orEmpty()
    // Cached videos are already known to be files; avoid a filesystem stat for each of them.
    val subfolders = entries.filter { it.absolutePath !in cachedVideoPaths && it.isDirectory }
    return FolderChildren(entries, subfolders)
}
