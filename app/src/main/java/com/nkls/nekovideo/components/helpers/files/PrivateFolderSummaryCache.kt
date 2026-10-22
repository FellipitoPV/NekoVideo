package com.nkls.nekovideo.components.helpers

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal data class PrivateFolderSummary(
    val videoCount: Int,
    val subfolderCount: Int,
    val sizeInBytes: Long
)

/** Screen-scoped counts only; never stores keys, passwords or decrypted manifests. */
internal class PrivateFolderSummaryCache(private val capacity: Int = 128) {
    private data class Stamp(val modified: Long, val manifestModified: Long, val locked: Boolean)
    private data class Cached(val stamp: Stamp, val summary: PrivateFolderSummary)
    private val entries = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
            size > capacity
    }
    private var generation = 0

    @Synchronized
    fun clear() {
        entries.clear()
        generation++
    }

    fun read(folder: File, locked: Boolean): PrivateFolderSummary {
        val stamp = Stamp(
            folder.lastModified(),
            if (locked) File(folder, ".neko_manifest.enc").lastModified() else 0L,
            locked
        )
        val readGeneration = synchronized(this) {
            entries[folder.absolutePath]?.takeIf { it.stamp == stamp }?.let { return it.summary }
            generation
        }
        val children = folder.listFiles() ?: return PrivateFolderSummary(0, 0, 0)
        var videos = 0
        var subfolders = 0
        var bytes = 0L
        var complete = true
        for (child in children) {
            // One metadata read per entry instead of separate isFile/isDirectory/length calls.
            val attributes = try {
                Files.readAttributes(child.toPath(), BasicFileAttributes::class.java)
            } catch (_: java.io.IOException) {
                complete = false
                continue // Files can disappear while a move/delete is finishing.
            }
            if (attributes.isDirectory) {
                if (child.name != ".neko_thumbs") subfolders++
            } else if (attributes.isRegularFile) {
                if (locked) {
                    if (child.name !in lockedMetadataFiles) videos++
                } else if (child.extension.lowercase() in supportedVideoExtensions) {
                    videos++
                }
                if (!child.name.startsWith(".")) bytes += attributes.size()
            }
        }
        val summary = PrivateFolderSummary(videos, subfolders, bytes)
        // Do not retain a partial snapshot if entries changed during the read.
        if (complete && folder.lastModified() == stamp.modified &&
            (!locked || File(folder, ".neko_manifest.enc").lastModified() == stamp.manifestModified)) {
            synchronized(this) {
                if (generation == readGeneration) entries[folder.absolutePath] = Cached(stamp, summary)
            }
        }
        return summary
    }

    private companion object {
        val lockedMetadataFiles = setOf(
            ".neko_locked", ".neko_manifest.enc", ".neko_lock_in_progress", ".nomedia", ".nekovideo"
        )
    }
}
