package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.util.Log
import java.io.File

/** App-private, versioned discovery state. Never stores credentials or decrypted video metadata. */
internal class PrivateMigrationHistory(context: Context, name: String = "private_video_migration") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    companion object {
        private const val VERSION = 1
        private const val DISCOVERED = "discovery_version"
        private const val COMPLETE = "completed_version"
        private const val PENDING = "pending_folders"
        private val lock = Any()
    }

    fun isComplete(): Boolean = synchronized(lock) { prefs.getInt(COMPLETE, 0) == VERSION }
    fun hasDiscovery(): Boolean = synchronized(lock) { prefs.getInt(DISCOVERED, 0) == VERSION }
    fun pendingPaths(): Set<String> = synchronized(lock) { prefs.getStringSet(PENDING, emptySet()).orEmpty().toSet() }

    /** Observe only paths the library already visited; no recursive traversal or manifest reads. */
    fun observe(paths: Collection<String>, fullScan: Boolean): Unit = synchronized(lock) {
        val previous = prefs.getStringSet(PENDING, emptySet()).orEmpty().toSet()
        val pending = if (fullScan) mutableSetOf() else previous.toMutableSet()
        val observed = (if (fullScan) previous + paths else paths.toSet()).map { path ->
            runCatching { File(path).canonicalPath }.getOrDefault(path)
        }.toSet()
        observed.forEach { path ->
            val folder = File(path)
            val unresolved = folder.exists() && !folder.canRead()
            if (unresolved || runCatching { PrivateVideoMigration.isCandidate(path) }.getOrDefault(true)) pending += path
            else pending -= path
        }
        val discovered = fullScan || prefs.getInt(DISCOVERED, 0) == VERSION
        val complete = discovered && pending.isEmpty()
        if (previous == pending && prefs.getInt(DISCOVERED, 0) == (if (discovered) VERSION else 0) &&
            prefs.getInt(COMPLETE, 0) == (if (complete) VERSION else 0)) return@synchronized
        val saved = prefs.edit().putStringSet(PENDING, pending)
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
