package com.nkls.nekovideo.components

import com.nkls.nekovideo.components.helpers.LocalFolderUpdates
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import java.io.File

/** Bounded session cache. Local operations can patch it; external edits require verified reads. */
internal object FolderContentCache {
    data class Stamp(val directory: Long, val index: Long, val indexSize: Long, val legacy: Long, val legacySize: Long)
    data class Entry(val items: List<MediaItem>, val stamp: Stamp, val localRevision: Long,
                     val privateVisible: Boolean, val credential: Long, val secure: Boolean)
    private val entries = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 12
    }
    fun stamp(path: String): Stamp {
        val folder = File(path)
        val index = File(folder, ".neko_recovery_index.enc")
        val legacy = File(folder, ".neko_manifest.enc")
        return Stamp(folder.lastModified(), index.lastModified(), index.length(), legacy.lastModified(), legacy.length())
    }
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun loadedItems(path: String): List<MediaItem>? = entries[path]?.takeIf {
        it.credential == LockedPlaybackSession.credentialVersion
    }?.items
    @Synchronized fun invalidate(paths: Collection<String>) {
        entries.keys.filter { cached -> paths.any { cached == it || cached.startsWith("$it/") } }
            .toList().forEach(entries::remove)
    }
    @Synchronized fun find(path: String, privateVisible: Boolean, secure: Boolean): Entry? = entries[path]?.takeIf {
        it.privateVisible == privateVisible && it.credential == LockedPlaybackSession.credentialVersion && it.secure == secure
    }
    @Synchronized fun save(path: String, items: List<MediaItem>, privateVisible: Boolean, secure: Boolean,
                            credential: Long, revision: Long) {
        if (credential == LockedPlaybackSession.credentialVersion) {
            entries[path] = Entry(items, stamp(path), revision, privateVisible, credential, secure)
        }
    }
    @Synchronized fun externallyChanged(path: String): Boolean = entries[path]?.let {
        LocalFolderUpdates.revision(path) == it.localRevision && stamp(path) != it.stamp
    } ?: true
    fun reusable(path: String, privateVisible: Boolean, secure: Boolean): List<MediaItem>? =
        find(path, privateVisible, secure)?.takeIf {
            it.localRevision == LocalFolderUpdates.revision(path) && it.stamp == stamp(path)
        }?.items
}
