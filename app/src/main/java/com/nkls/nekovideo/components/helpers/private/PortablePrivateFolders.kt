package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/** Folder integration for new private files. Legacy folders retain their existing implementation. */
internal object PortablePrivateFolders {
    const val MARKER = ".neko_recovery"
    const val INDEX = PortableVideoStore.INDEX_NAME
    const val PASSWORD_BACKUP = ".neko_recovery_password_backup.enc"
    private const val IN_PROGRESS = ".neko_lock_in_progress"
    private data class Detection(val modified: Long, val found: Boolean)
    private val detection = object : LinkedHashMap<String, Detection>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Detection>?) = size > 256
    }
    private var keyring: PortableVideoStore.Keyring? = null
    private var keyringPassword: String? = null

    private data class Snapshot(
        val entries: List<PortableVideoStore.Entry>,
        val directories: List<PortableVideoStore.Directory>,
        val name: String,
        val salt: ByteArray,
        val managed: Boolean = true
    )

    @Synchronized fun clearSession() {
        keyring?.close()
        keyring = null
        keyringPassword = null
    }

    @Synchronized private fun <T> unlocked(password: String, action: (PortableVideoStore.Keyring) -> T): T {
        if (keyring == null || keyringPassword != password) {
            clearSession()
            keyring = PortableVideoStore.Keyring(password.toCharArray())
            keyringPassword = password
        }
        return action(checkNotNull(keyring))
    }

    fun isPortable(folderPath: String): Boolean {
        val folder = File(folderPath)
        // A legacy folder keeps its own format even when new Debug writes are enabled.
        if (File(folder, ".neko_locked").exists()) return false
        if (File(folder, MARKER).exists() || File(folder, INDEX).exists() || File(folder, PASSWORD_BACKUP).exists()) return true
        // An embedded record belongs to the video, not to its new parent directory.
        return false
    }

    private fun hasPrivateBoundary(context: Context, folder: File, password: String?): Boolean {
        val privateRoots = setOf(
            File(FilesManager.SecureStorage.getNekoPrivateFolderPath()).canonicalFile,
            File(FilesManager.SecureStorage.getSecureFolderPath(context)).canonicalFile
        )
        var current: File? = folder
        while (current != null) {
            val directory = current
            if (directory in privateRoots || directory.name.startsWith(".") ||
                listOf(".neko_locked", MARKER, PASSWORD_BACKUP, IN_PROGRESS, ".nomedia", ".secure")
                    .any { File(directory, it).exists() }) return true
            val index = File(directory, INDEX)
            if (index.exists()) {
                // Older Debug builds could cache arrivals in an explicitly unmanaged index.
                val managed = if (password == null) null else runCatching {
                    unlocked(password) { keys -> PortableVideoStore(directory).readIndexOrNull(index, keys)?.privateFolder }
                }.getOrNull()
                if (managed != false) return true
            }
            current = directory.parentFile
        }
        return false
    }

    /** Queues externally moved records during the existing library scan, even before authentication. */
    internal fun hasExternalArrivals(context: Context, folderPath: String): Boolean = runCatching {
        val folder = File(folderPath).canonicalFile
        folder.isDirectory && !hasPrivateBoundary(context, folder, LockedPlaybackSession.sessionPassword) && containsRecords(folder)
    }.getOrDefault(false)

    /** The initial walk already established the ancestor boundary and listed this directory. */
    internal fun hasExternalArrivalsForScan(folder: File, entries: List<File>, privateBoundary: Boolean): Boolean =
        !privateBoundary && containsRecords(folder, entries)

    internal fun recoveryRecordsForScan(entries: List<File>): List<File> = candidates(entries)
        .filter(PortableVideoStore::hasRecoveryRecord)

    /** Called on IO while browsing/scanning. No credential, no plaintext publication. */
    @Synchronized fun restoreExternalArrivals(context: Context, folderPath: String,
                                             password: String? = LockedPlaybackSession.sessionPassword): List<File> {
        if (password == null) return emptyList()
        val folder = File(folderPath).canonicalFile
        if (!folder.isDirectory || hasPrivateBoundary(context, folder, password) || !containsRecords(folder)) return emptyList()
        val restored = mutableListOf<File>()
        unlocked(password) { keys ->
            val store = PortableVideoStore(folder)
            val indexFile = File(folder, INDEX)
            val index = store.readIndexOrNull(indexFile, keys)
            candidates(folder).filter(PortableVideoStore::hasRecoveryRecord).forEach { source ->
                try {
                    val metadata = store.resolve(source, index, keys).metadata
                    var target = store.checked(File(folder, metadata.originalName))
                    var suffix = 1
                    while (target != source && target.exists() &&
                        !(target.isFile && PortableVideoStore.sha256(target) == metadata.originalSha256)) {
                        val original = File(metadata.originalName)
                        val extension = original.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
                        target = store.checked(File(folder, "${original.nameWithoutExtension} (${suffix++})$extension"))
                    }
                    // restoreEntry publishes only after both ciphertext and plaintext hashes match.
                    val output = restoreEntry(folder, source.name, metadata.copy(originalName = target.name))
                    if (output != source) {
                        check(store.matchesPayload(source, metadata)) { "Protected video changed during recovery" }
                        check(source.delete()) { "Recovered video verified; protected copy could not be removed" }
                    }
                    restored += output
                    runCatching { kotlinx.coroutines.runBlocking {
                        VideoTagStore.moveTagsForPath(context, source.absolutePath, output.absolutePath)
                    } }
                } catch (failure: Exception) {
                    // Wrong password, corruption or unavailable space: retain the protected input.
                    Log.w("PortablePrivateFolders", "Could not restore external video ${source.name}", failure)
                }
            }
            if (index?.privateFolder == false && !candidates(folder).any(PortableVideoStore::hasRecoveryRecord)) {
                check(indexFile.delete())
            }
        }
        if (restored.isNotEmpty()) {
            invalidate(folder)
            LockedPlaybackSession.removeSession(folder.absolutePath)
            FolderLockManager.removeFromRegistry(context, folder.absolutePath)
            MediaScannerConnection.scanFile(context, restored.map { it.absolutePath }.toTypedArray(), null, null)
        }
        return restored
    }

    private fun candidates(folder: File): List<File> = candidates(folder.listFiles().orEmpty().toList())

    private fun candidates(entries: List<File>): List<File> = entries.filter {
        it.isFile && !it.name.startsWith(".neko_") && !it.name.endsWith(".part") && it.name != ".nomedia" && it.name != ".nekovideo"
    }

    private fun containsRecords(folder: File, entries: List<File>? = null): Boolean {
        if (!folder.isDirectory) return false
        val stamp = folder.lastModified()
        synchronized(detection) { detection[folder.absolutePath]?.takeIf { it.modified == stamp }?.let { return it.found } }
        val found = (entries?.let(::candidates) ?: candidates(folder)).any(PortableVideoStore::hasRecoveryRecord)
        synchronized(detection) { detection[folder.absolutePath] = Detection(stamp, found) }
        return found
    }

    private fun invalidate(folder: File) { synchronized(detection) { detection.remove(folder.absolutePath) } }

    fun fileCount(folderPath: String): Int = candidates(File(folderPath)).count(PortableVideoStore::hasRecoveryRecord)

    fun salt(folderPath: String): ByteArray? = runCatching {
        val folder = File(folderPath)
        if (!folder.isDirectory) return@runCatching null
        val store = PortableVideoStore(folder)
        for (name in listOf(INDEX, PASSWORD_BACKUP)) {
            runCatching { store.readSalt(File(folder, name), index = true) }.getOrNull()?.let { return it }
        }
        runCatching { Base64.getDecoder().decode(File(folder, MARKER).readText().trim()) }
            .getOrNull()?.takeIf { it.size == 16 }?.let { return it }
        candidates(folder).firstOrNull(PortableVideoStore::hasRecoveryRecord)?.let { store.readSalt(it) }
    }.getOrNull()

    private fun read(folder: File, password: String, verifyKnownRecords: Boolean = true): Snapshot? = unlocked(password) { keys ->
        if (!folder.isDirectory) return@unlocked null
        val store = PortableVideoStore(folder)
        val indexFile = File(folder, INDEX)
        val backup = File(folder, PASSWORD_BACKUP)
        val index = store.readIndexOrNull(indexFile, keys) ?: store.readIndexOrNull(backup, keys)
        val known = index?.entries.orEmpty().associateBy { it.diskName }
        val children = folder.listFiles().orEmpty()
        val entries = children.mapNotNull { file ->
            if (file.name.startsWith(".neko_") || file.name.endsWith(".part") ||
                file.name == ".nomedia" || file.name == ".nekovideo") return@mapNotNull null
            val cached = known[file.name]
            // Local edits use the authenticated index for unchanged entries. Unknown arrivals
            // still recover from their record; navigation/manual refresh verifies every record.
            if (!verifyKnownRecords && cached != null) return@mapNotNull cached
            if (!file.isFile) return@mapNotNull null
            // A protected file is longer than its payload. Check possible plaintext restoration
            // only at that exact length; inspectEntry validates every other record in one read.
            if (cached != null && file.length() == cached.metadata.payloadSize &&
                PortableVideoStore.sha256(file) == cached.metadata.originalSha256) return@mapNotNull null
            runCatching { store.inspectEntry(file, index, keys, allowManifestRecovery = true) }
                .getOrNull() ?: cached // Keep an authenticated damaged entry visible; export will reject damage.
        }
        if (index == null && entries.isEmpty()) return@unlocked null
        val directoryNames = index?.directories.orEmpty().associateBy { it.diskName }
        val videoNames = entries.mapTo(HashSet()) { it.diskName }
        val directories = children.filter { it.name !in videoNames && it.name != ".neko_thumbs" && it.isDirectory }
            .map { directoryNames[it.name] ?: PortableVideoStore.Directory(it.name, it.name) }
        val recoveredName = if (folder.name.matches(Regex("[0-9a-f]{32}|[0-9a-f-]{36}"))) {
            entries.firstOrNull()?.metadata?.folderName
        } else folder.name
        Snapshot(entries, directories, index?.folderName ?: recoveredName ?: folder.name,
            salt(folder.absolutePath) ?: entries.firstOrNull()?.let { store.readSalt(File(folder, it.diskName)) } ?: return@unlocked null,
            index?.privateFolder ?: File(folder, MARKER).exists())
    }

    private fun Snapshot.manifest(folder: File) = LockedFolderManifest(
        folder.absolutePath, name,
        entries.map { LockedFileEntry(it.diskName, it.metadata.originalName, it.metadata.payloadSize,
            duration = it.metadata.duration, portableMetadata = it.metadata) },
        directories.map { LockedSubfolderEntry(it.diskName, it.originalName) },
        Base64.getEncoder().encodeToString(salt), File(folder, INDEX).lastModified()
    )

    fun manifest(folderPath: String, password: String): LockedFolderManifest? = runCatching {
        val folder = File(folderPath)
        read(folder, password)?.manifest(folder)
    }.getOrNull()

    internal fun manifestForLocalEdit(folderPath: String, password: String): LockedFolderManifest? = runCatching {
        val folder = File(folderPath)
        read(folder, password, verifyKnownRecords = false)?.manifest(folder)
    }.getOrNull()

    fun mergeLegacy(folderPath: String, password: String, legacy: LockedFolderManifest): LockedFolderManifest = unlocked(password) { keys ->
        val folder = File(folderPath)
        val store = PortableVideoStore(folder)
        val known = legacy.files.associateBy { it.obfuscatedName }
        // Unchanged legacy payloads need no record lookup. Migrated files have an appended record,
        // allowing recovery after replacement even if the process stopped before the new index.
        val recovered = folder.listFiles().orEmpty().filter {
            (it.name !in known || known[it.name]?.portableMetadata != null ||
                (it.length() != known[it.name]?.originalSize && PortableVideoStore.hasRecoveryRecord(it))) &&
                !it.name.startsWith(".neko_") && !it.name.endsWith(".part") && it.isFile
        }.mapNotNull { file ->
            val cached = known[file.name]?.portableMetadata
            val metadata = runCatching { store.resolve(file, null, keys).metadata }.getOrNull()
                ?: cached?.takeIf { runCatching { store.matchesPayload(file, it) }.getOrDefault(false) }
                ?: return@mapNotNull null
            LockedFileEntry(file.name, metadata.originalName, metadata.payloadSize, metadata.duration, metadata)
        }.associateBy { it.obfuscatedName }
        val surviving = legacy.files.filter { entry ->
            val metadata = entry.portableMetadata ?: return@filter true
            val file = File(folder, entry.obfuscatedName)
            file.isFile && !(file.name == metadata.originalName && !PortableVideoStore.hasRecoveryRecord(file) &&
                file.length() == metadata.payloadSize && PortableVideoStore.sha256(file) == metadata.originalSha256)
        }
        legacy.copy(files = surviving.map { recovered[it.obfuscatedName] ?: it } +
            recovered.values.filter { it.obfuscatedName !in known })
    }

    private fun persist(folder: File, snapshot: Snapshot, password: String) = unlocked(password) { keys ->
        if (!snapshot.managed && snapshot.entries.isEmpty()) {
            val index = File(folder, INDEX)
            if (index.exists()) check(index.delete())
            invalidate(folder)
            return@unlocked
        }
        // The embedded records remain recoverable if the app stops between these two commits.
        if (snapshot.managed) {
            val marker = AtomicFile(File(folder, MARKER))
            val stream = marker.startWrite()
            try {
                stream.write(Base64.getEncoder().encode(snapshot.salt))
                marker.finishWrite(stream)
            } catch (failure: Exception) { marker.failWrite(stream); throw failure }
        }
        PortableVideoStore(folder).writeIndex(File(folder, INDEX), snapshot.entries, keys, snapshot.salt,
            snapshot.name, snapshot.directories, snapshot.managed)
        if (snapshot.managed) File(folder, ".nomedia").createNewFile()
        invalidate(folder)
    }

    private fun nameFromParent(folder: File, password: String): String {
        val parent = folder.parentFile ?: return folder.name
        if (!FolderLockManager.isLocked(parent.absolutePath)) return folder.name
        return FolderLockManager.readManifestForLocalEdit(parent.absolutePath, password)?.subfolders
            ?.firstOrNull { it.obfuscatedName == folder.name }?.originalName ?: folder.name
    }

    fun lock(context: Context, folderPath: String, password: String,
             onProgress: (Int, Int, String) -> Unit, onError: (String) -> Unit, onSuccess: () -> Unit,
             onResolvedPath: (String) -> Unit) {
        val folder = File(folderPath)
        try {
            require(folder.isDirectory && !FolderLockManager.isLocked(folderPath)) { "Folder is already private or unavailable" }
            val base = Snapshot(emptyList(), emptyList(), nameFromParent(folder, password), PortableVideoStore(folder).newSalt())
            protectFiles(context, folder, password, base,
                candidates(folder).filter { it.extension.lowercase() in supportedVideoExtensions }, true, onProgress)
            onResolvedPath(FolderLockManager.obfuscateLockedFolderNameIfNeeded(context, folderPath))
            onSuccess()
        } catch (failure: Exception) { onError(failure.message ?: "Could not protect folder") }
    }

    fun createEmpty(context: Context, folderPath: String, password: String): Boolean = runCatching {
        val folder = File(folderPath)
        require(folder.isDirectory || folder.mkdirs())
        require(!File(folder, ".neko_locked").exists() && !File(folder, INDEX).exists())
        val snapshot = Snapshot(emptyList(), emptyList(), folder.name, PortableVideoStore(folder).newSalt())
        persist(folder, snapshot, password)
        FolderLockManager.addToRegistry(context, folderPath, snapshot.name, 0)
        LockedPlaybackSession.start(FolderLockManager.deriveXorKey(password, snapshot.salt), snapshot.manifest(folder), folderPath, password)
        true
    }.getOrDefault(false)

    fun addFiles(context: Context, folderPath: String, password: String, files: List<File>,
                 onProgress: (Int, Int, String) -> Unit, onError: (String) -> Unit, onSuccess: () -> Unit) {
        try {
            val folder = File(folderPath)
            val base = checkNotNull(read(folder, password, false)) { "Invalid password or unavailable recovery metadata" }
            protectFiles(context, folder, password, base, files.filter {
                it.isFile && it.parentFile?.absolutePath == folder.absolutePath &&
                    it.extension.lowercase() in supportedVideoExtensions && !PortableVideoStore.hasRecoveryRecord(it)
            }, false, onProgress)
            onSuccess()
        } catch (failure: Exception) { onError(failure.message ?: "Could not protect files") }
    }

    private fun protectFiles(context: Context, folder: File, password: String, base: Snapshot,
                             sources: List<File>, renameDirectories: Boolean, progress: (Int, Int, String) -> Unit) {
        val store = PortableVideoStore(folder)
        val created = mutableListOf<PortableVideoStore.Entry>()
        val pendingSources = mutableListOf<Pair<File, PortableVideoStore.Entry>>()
        val movedDirectories = mutableListOf<Pair<File, File>>()
        val flag = File(folder, IN_PROGRESS)
        flag.createNewFile()
        var committed = false
        try {
            unlocked(password) { keys ->
                sources.forEachIndexed { i, source ->
                    progress(i + 1, sources.size, source.name)
                    val existing = base.entries.firstOrNull { it.metadata.originalName == source.name }
                    val entry = if (existing != null) {
                        require(PortableVideoStore.sha256(source) == existing.metadata.originalSha256) { "A private video already uses this name" }
                        existing
                    } else {
                        val output = File(folder, UUID.randomUUID().toString().replace("-", ""))
                        store.protectCopy(source, output, keys, base.salt, base.name,
                            FolderLockManager.getFormattedVideoDuration(source)).also { created += it }
                    }
                    pendingSources += source to entry
                }
            }
            val dirs = if (renameDirectories) folder.listFiles().orEmpty()
                .filter { it.isDirectory && it.name != ".neko_thumbs" }.map { directory ->
                    val target = File(folder, UUID.randomUUID().toString().replace("-", ""))
                    check(directory.renameTo(target)) { "Could not protect subfolder name" }
                    movedDirectories += directory to target
                    PortableVideoStore.Directory(target.name, directory.name)
                } else base.directories
            val snapshot = base.copy(entries = base.entries + created, directories = dirs)
            persist(folder, snapshot, password)
            committed = true
            FolderLockManager.addToRegistry(context, folder.absolutePath, snapshot.name, snapshot.entries.size)
            movedDirectories.forEach { (old, new) -> FolderLockManager.onLockedFolderMoved(context, old.absolutePath, new.absolutePath) }
            pendingSources.forEach { (source, entry) ->
                require(PortableVideoStore.sha256(source) == entry.metadata.originalSha256) { "Source changed; original has been preserved" }
                check(source.delete()) { "Private copy created; original could not be removed" }
                kotlinx.coroutines.runBlocking { VideoTagStore.moveTagsForPath(context, source.absolutePath, File(folder, entry.diskName).absolutePath) }
            }
        } catch (failure: Exception) {
            if (!committed) {
                created.forEach { store.checked(File(folder, it.diskName)).delete() }
                movedDirectories.asReversed().forEach { (old, new) -> new.renameTo(old) }
                if (!File(folder, INDEX).exists()) File(folder, MARKER).delete()
            }
            throw failure
        } finally { flag.delete(); invalidate(folder) }
    }

    fun extract(context: Context, folderPath: String, names: List<String>, password: String,
                progress: (Int, Int, String) -> Unit = { _, _, _ -> }): Pair<List<File>, LockedFolderManifest?> {
        val folder = File(folderPath)
        val snapshot = checkNotNull(read(folder, password, false)) { "Invalid password or unavailable recovery metadata" }
        val store = PortableVideoStore(folder)
        val restored = mutableListOf<File>()
        val selected = snapshot.entries.filter { it.diskName in names }
        require(selected.map { it.metadata.originalName }.distinct().size == selected.size) {
            "Videos have the same original name; move them separately"
        }
        selected.forEachIndexed { i, entry ->
            progress(i + 1, selected.size, entry.metadata.originalName)
            restored += restoreEntry(folder, entry.diskName, entry.metadata)
        }
        val next = snapshot.copy(entries = snapshot.entries.filter { it.diskName !in names })
        persist(folder, next, password)
        selected.forEachIndexed { i, entry ->
            val source = store.checked(File(folder, entry.diskName))
            val output = restored[i]
            if (source != output) check(source.delete()) { "Export verified; protected copy could not be removed" }
            kotlinx.coroutines.runBlocking { VideoTagStore.moveTagsForPath(context, source.absolutePath, output.absolutePath) }
            File(folder, ".neko_thumbs/${entry.diskName}").delete()
            File(folder, ".neko_thumbs/${entry.diskName}.jpg").delete()
        }
        FolderLockManager.addToRegistry(context, folderPath, next.name, next.entries.size)
        return restored to next.manifest(folder)
    }

    internal fun restoreEntry(folder: File, diskName: String, metadata: PortableVideoStore.Metadata): File {
        val store = PortableVideoStore(folder)
        val source = store.checked(File(folder, diskName))
        val output = store.checked(File(folder, metadata.originalName))
        if (source == output) {
            val staged = store.checked(File(folder, ".neko_export_${UUID.randomUUID()}"))
            try {
                store.restoreKnownCopy(source, staged, metadata)
                Files.move(staged.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { if (staged.exists()) staged.delete() }
        } else if (output.exists()) {
            require(output.isFile && PortableVideoStore.sha256(output) == metadata.originalSha256) {
                "A different file already exists with the original name"
            }
        } else store.restoreKnownCopy(source, output, metadata)
        return output
    }

    fun remove(context: Context, folderPath: String, name: String, password: String): LockedFolderManifest? {
        return removeMany(context, folderPath, listOf(name), password)
    }

    fun removeMany(context: Context, folderPath: String, names: Collection<String>, password: String): LockedFolderManifest? {
        val folder = File(folderPath)
        val snapshot = read(folder, password, false) ?: return null
        val next = snapshot.copy(entries = snapshot.entries.filter { it.diskName !in names },
            directories = snapshot.directories.filter { it.diskName !in names })
        persist(folder, next, password)
        names.forEach { name ->
            requireValidName(name)
            File(folder, ".neko_thumbs/$name").delete()
            File(folder, ".neko_thumbs/$name.jpg").delete()
        }
        FolderLockManager.addToRegistry(context, folderPath, next.name, next.entries.size)
        return next.manifest(folder)
    }

    internal fun replaceRecord(folder: File, source: File, metadata: PortableVideoStore.Metadata,
                              keys: PortableVideoStore.Keyring, salt: ByteArray): PortableVideoStore.Entry {
        val store = PortableVideoStore(folder)
        val staged = store.checked(File(folder, ".neko_record_${UUID.randomUUID()}"))
        try {
            val entry = store.rewrapKnownCopy(source, staged, metadata, keys, salt)
            Files.move(staged.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return entry.copy(diskName = source.name)
        } finally { if (staged.exists()) staged.delete() }
    }

    fun renameFile(folderPath: String, name: String, newName: String, password: String): LockedFolderManifest? {
        val folder = File(folderPath)
        val snapshot = read(folder, password, false) ?: return null
        requireValidName(newName)
        require(snapshot.entries.none { it.diskName != name && it.metadata.originalName == newName }) { "Name already in use" }
        val old = snapshot.entries.firstOrNull { it.diskName == name } ?: return null
        val updated = unlocked(password) { keys ->
            replaceRecord(folder, File(folder, name), old.metadata.copy(originalName = newName), keys, snapshot.salt)
        }
        val next = snapshot.copy(entries = snapshot.entries.map { if (it.diskName == name) updated else it })
        persist(folder, next, password)
        return next.manifest(folder)
    }

    fun addDirectory(context: Context, folderPath: String, childPath: String, password: String): String? {
        val folder = File(folderPath)
        val snapshot = read(folder, password, false) ?: return null
        val child = PortableVideoStore(folder).checked(File(childPath))
        require(child.parentFile == folder.canonicalFile && child.isDirectory)
        val name = FolderLockManager.readManifestForLocalEdit(childPath, password)?.originalFolderName ?: child.name
        val target = File(folder, UUID.randomUUID().toString().replace("-", ""))
        check(child.renameTo(target))
        try { persist(folder, snapshot.copy(directories = snapshot.directories + PortableVideoStore.Directory(target.name, name)), password) }
        catch (failure: Exception) { target.renameTo(child); throw failure }
        FolderLockManager.onLockedFolderMoved(context, childPath, target.absolutePath)
        return target.absolutePath
    }

    fun extractDirectory(context: Context, folderPath: String, name: String, password: String): File? {
        val folder = File(folderPath)
        val snapshot = read(folder, password, false) ?: return null
        val item = snapshot.directories.firstOrNull { it.diskName == name } ?: return null
        val source = File(folder, name)
        val target = File(folder, item.originalName)
        require(!target.exists() || target == source) { "A directory already exists with the original name" }
        if (source != target) check(source.renameTo(target))
        try { persist(folder, snapshot.copy(directories = snapshot.directories.filter { it.diskName != name }), password) }
        catch (failure: Exception) { if (source != target) target.renameTo(source); throw failure }
        FolderLockManager.onLockedFolderMoved(context, source.absolutePath, target.absolutePath)
        return target
    }

    fun renameDirectory(context: Context, folderPath: String, name: String, newName: String, password: String): LockedFolderManifest? {
        requireValidName(newName)
        val folder = File(folderPath)
        val snapshot = read(folder, password, false) ?: return null
        require(snapshot.directories.none { it.diskName != name && it.originalName == newName })
        val next = snapshot.copy(directories = snapshot.directories.map { if (it.diskName == name) it.copy(originalName = newName) else it })
        val child = File(folder, name)
        if (isPortable(child.absolutePath)) read(child, password, false)?.let { persist(child, it.copy(name = newName), password) }
        persist(folder, next, password)
        if (FolderLockManager.isLocked(child.absolutePath)) FolderLockManager.addToRegistry(context, child.absolutePath, newName,
            FolderLockManager.readManifestForLocalEdit(child.absolutePath, password)?.files?.size ?: 0)
        return next.manifest(folder)
    }

    fun renameFolder(context: Context, folderPath: String, newName: String, password: String) {
        requireValidName(newName)
        val folder = File(folderPath)
        val snapshot = checkNotNull(read(folder, password, false))
        persist(folder, snapshot.copy(name = newName), password)
        FolderLockManager.addToRegistry(context, folderPath, newName, snapshot.entries.size)
    }

    fun unlock(context: Context, folderPath: String, password: String, progress: (Int, Int, String) -> Unit): String {
        val folder = File(folderPath)
        val snapshot = checkNotNull(read(folder, password)) { "Invalid password or unavailable recovery metadata" }
        extract(context, folderPath, snapshot.entries.map { it.diskName }, password, progress)
        snapshot.directories.forEach { extractDirectory(context, folderPath, it.diskName, password) }
        listOf(INDEX, MARKER, PASSWORD_BACKUP, IN_PROGRESS).forEach { name ->
            val file = File(folder, name)
            if (file.exists()) check(file.delete())
        }
        if (!folder.name.startsWith(".")) File(folder, ".nomedia").delete()
        invalidate(folder)
        val restored = FolderLockManager.restoreUnlockedFolderNameIfNeeded(context, folderPath, snapshot.name)
        FolderLockManager.removeFromRegistry(context, restored)
        LockedPlaybackSession.removeSession(folderPath)
        LockedPlaybackSession.removeSession(restored)
        return restored
    }

    fun rekey(folderPath: String, oldPassword: String, newPassword: String, progress: (Int, Int) -> Unit) {
        val folder = File(folderPath)
        val snapshot = checkNotNull(read(folder, oldPassword)) { "Invalid old password" }
        if (!snapshot.managed && snapshot.entries.isEmpty()) { persist(folder, snapshot, oldPassword); return }
        val backup = File(folder, PASSWORD_BACKUP)
        if (!backup.exists()) {
            persist(folder, snapshot, oldPassword)
            Files.copy(File(folder, INDEX).toPath(), backup.toPath())
            RandomAccessFile(backup, "rw").use { it.fd.sync() }
        }
        val newSalt = PortableVideoStore(folder).newSalt()
        PortableVideoStore.Keyring(newPassword.toCharArray()).use { keys ->
            val entries = snapshot.entries.mapIndexed { i, entry ->
                progress(i + 1, snapshot.entries.size)
                replaceRecord(folder, File(folder, entry.diskName), entry.metadata, keys, newSalt)
            }
            persist(folder, snapshot.copy(entries = entries, salt = newSalt), newPassword)
        }
        clearSession()
        // Backup is retained until the application password has been durably saved.
    }

    fun finalizePasswordChange(folderPath: String, password: String) {
        val folder = File(folderPath)
        val backup = File(folder, PASSWORD_BACKUP)
        if (!backup.exists()) return
        val store = PortableVideoStore(folder)
        PortableVideoStore.Keyring(password.toCharArray()).use { keys ->
            val index = store.readIndexOrNull(File(folder, INDEX), keys) ?: return
            index.entries.forEach { store.resolve(File(folder, it.diskName), null, keys) }
            check(backup.delete())
        }
    }

    private fun requireValidName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.length <= 1024 &&
            '/' !in name && '\\' !in name && '\u0000' !in name) { "Invalid name" }
    }
}
