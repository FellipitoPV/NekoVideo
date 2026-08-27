package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PinnedFolderEntry(
    val path: String,
    val pinnedAt: Long = System.currentTimeMillis()
)

enum class PinFolderResult {
    Success,
    AlreadyPinned,
    LimitReached,
    Invalid
}

object PinnedFoldersStore {
    private const val PREFS_NAME = "pinned_folders"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_PINS = 10

    private val gson = Gson()
    private val _entries = MutableStateFlow<List<PinnedFolderEntry>>(emptyList())
    val entries: StateFlow<List<PinnedFolderEntry>> = _entries.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ENTRIES, null) ?: return
        val type = object : TypeToken<List<PinnedFolderEntry>>() {}.type
        _entries.value = runCatching { gson.fromJson<List<PinnedFolderEntry>>(json, type) }
            .getOrDefault(emptyList())
            .filter { it.path.isNotBlank() }
    }

    fun isPinned(path: String): Boolean {
        val normalized = File(path).absolutePath
        return _entries.value.any { it.path == normalized }
    }

    fun pin(context: Context, path: String): PinFolderResult {
        val folder = File(path)
        if (!folder.exists() || !folder.isDirectory) return PinFolderResult.Invalid

        val normalized = folder.absolutePath
        if (isPinned(normalized)) return PinFolderResult.AlreadyPinned
        if (_entries.value.size >= MAX_PINS) return PinFolderResult.LimitReached

        val updated = listOf(PinnedFolderEntry(normalized)) + _entries.value.filter { it.path != normalized }
        persist(context, updated)
        return PinFolderResult.Success
    }

    fun unpin(context: Context, path: String) {
        val normalized = File(path).absolutePath
        val updated = _entries.value.filterNot { it.path == normalized }
        if (updated.size != _entries.value.size) {
            persist(context, updated)
        }
    }

    fun pruneMissing(context: Context) {
        val updated = _entries.value.filter { File(it.path).exists() }
        if (updated.size != _entries.value.size) {
            persist(context, updated)
        }
    }

    fun relocatePath(context: Context, oldPath: String, newPath: String) {
        val oldNormalized = File(oldPath).absolutePath
        val newNormalized = File(newPath).absolutePath
        if (oldNormalized == newNormalized) return

        var changed = false
        val updated = _entries.value.mapNotNull { entry ->
            when {
                entry.path == oldNormalized -> {
                    changed = true
                    entry.copy(path = newNormalized)
                }
                entry.path.startsWith("$oldNormalized/") -> {
                    changed = true
                    entry.copy(path = newNormalized + entry.path.removePrefix(oldNormalized))
                }
                else -> entry
            }
        }
        if (changed) {
            persist(context, updated)
        }
    }

    fun resolveDisplayName(context: Context, path: String): String {
        val file = File(path)
        if (!file.exists()) {
            return file.name.ifBlank { path }
        }

        FolderLockManager.getRegistryEntry(context, path)?.originalFolderName?.let { return it }

        if (path == FilesManager.SecureStorage.getNekoPrivateFolderPath()) {
            return context.getString(com.nkls.nekovideo.R.string.neko_private_folder_name)
        }

        val name = file.name
        return if (name.startsWith(".")) name.drop(1) else name
    }

    fun resolveSubtitle(path: String): String {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val relative = path.removePrefix(rootPath).trimStart(File.separatorChar)
        return relative.ifBlank { path }
    }

    private fun persist(context: Context, entries: List<PinnedFolderEntry>) {
        _entries.value = entries
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, gson.toJson(entries))
            .apply()
    }
}
