package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.util.Log
import java.io.File

/** App-private, versioned discovery state. Never stores credentials or decrypted video metadata. */
internal class PrivateMigrationHistory(context: Context, name: String = "private_video_migration") {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    companion object {
        private const val VERSION = 1
        private const val DISCOVERED = "discovery_version"
        private const val COMPLETE = "completed_version"
        private const val PENDING = "pending_folders"
        private const val CONFIRMED = "confirmed_private_folders"
        private val lock = Any()
    }

    fun isComplete(): Boolean = synchronized(lock) { prefs.getInt(COMPLETE, 0) == VERSION }
    fun hasDiscovery(): Boolean = synchronized(lock) { prefs.getInt(DISCOVERED, 0) == VERSION }
    fun pendingPaths(): Set<String> = synchronized(lock) { prefs.getStringSet(PENDING, emptySet()).orEmpty().toSet() }

    /** Observe only paths the library already visited; no recursive traversal or manifest reads. */
    fun observe(paths: Collection<String>, fullScan: Boolean): Unit = synchronized(lock) {
        val previous = prefs.getStringSet(PENDING, emptySet()).orEmpty().toSet()
        // Older versions also recorded unreadable library ancestors (/, /storage, ...).
        // Access restrictions alone are not evidence that a directory contains private videos.
        val confirmed = prefs.getStringSet(CONFIRMED, emptySet()).orEmpty().toMutableSet()
        val registered = FolderLockManager.getAllLockedFolders(appContext).mapTo(HashSet()) {
            runCatching { File(it.folderPath).canonicalPath }.getOrDefault(it.folderPath)
        }
        val pending = if (fullScan) mutableSetOf() else previous.toMutableSet()
        val observed = (if (fullScan) previous + paths else paths.toSet()).map { path ->
            runCatching { File(path).canonicalPath }.getOrDefault(path)
        }.toSet()
        observed.forEach { path ->
            val folder = File(path)
            val candidate = runCatching { PrivateVideoMigration.isCandidate(path) }.getOrDefault(false)
            val unresolved = (path in confirmed || path in registered) && folder.exists() && !folder.canRead()
            if (candidate || unresolved) {
                pending += path
                confirmed += path
            } else {
                pending -= path
                confirmed -= path
            }
        }
        val discovered = fullScan || prefs.getInt(DISCOVERED, 0) == VERSION
        val complete = discovered && pending.isEmpty()
        if (previous == pending && prefs.getStringSet(CONFIRMED, emptySet()).orEmpty() == confirmed &&
            prefs.getInt(DISCOVERED, 0) == (if (discovered) VERSION else 0) &&
            prefs.getInt(COMPLETE, 0) == (if (complete) VERSION else 0)) return@synchronized
        val saved = prefs.edit().putStringSet(PENDING, pending)
            .putStringSet(CONFIRMED, confirmed)
            .putInt(DISCOVERED, if (discovered) VERSION else 0)
            .putInt(COMPLETE, if (complete) VERSION else 0).commit()
        if (!saved) Log.w("PrivateMigrationHistory", "Could not persist migration discovery")
    }

    /** Once complete, authentication has no migration filesystem work. Otherwise inspect only pending folders. */
    fun plan(password: String, inspect: (Collection<String>, String) -> PrivateVideoMigration.Plan = PrivateVideoMigration::plan): PrivateVideoMigration.Plan {
        if (isComplete()) return PrivateVideoMigration.Plan(emptyList(), 0, 0)
        val pending = pendingPaths()
        val plan = inspect(pending, password)
        observe(pending, fullScan = false)
        return plan.copy(unavailableFolders = maxOf(plan.unavailableFolders, pendingPaths().size - plan.folders.size))
    }
}
