package com.nkls.nekovideo.components.helpers

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Revisions for known app operations. External changes never acquire this trusted fast path. */
internal object LocalFolderUpdates {
    private val versions = mutableMapOf<String, Long>()
    private val _version = MutableStateFlow(0L)
    val version = _version.asStateFlow()

    @Synchronized fun mark(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val next = _version.value + 1
        paths.forEach { path ->
            var folder: File? = File(path).absoluteFile
            while (folder != null) {
                versions[folder.path] = next
                folder = folder.parentFile
            }
        }
        _version.value = next
    }

    @Synchronized fun revision(path: String): Long = versions[path] ?: 0L
    @Synchronized fun changedSince(version: Long): Set<String> = versions.filterValues { it > version }.keys.toSet()
}
