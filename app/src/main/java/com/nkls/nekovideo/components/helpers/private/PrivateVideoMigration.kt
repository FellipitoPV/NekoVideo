package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException

/** Opt-in conversion. Paths, payload ciphertext, thumbnails and tag references never change. */
internal object PrivateVideoMigration {
    const val JOURNAL = ".neko_migration"
    private const val LEGACY_MARKER = ".neko_locked"
    private const val LEGACY_INDEX = ".neko_manifest.enc"
    data class Plan(val folders: List<String>, val videoCount: Int, val unavailableFolders: Int)
    data class Result(val updatedVideos: Int, val updatedFolders: Int, val failedFolders: Int)
    enum class Stage { COPY_PAYLOAD_WRITTEN, COPY_SYNCED, COPY_READY, SESSION_READY, VIDEO_REPLACED, INDEX_SAVED, NEW_MARKER_SAVED, LEGACY_MARKER_REMOVED }

    fun isCandidate(path: String): Boolean = File(path, LEGACY_MARKER).isFile || File(path, JOURNAL).isFile

    /** Only decrypt directory manifests here; the global scan never rewrites video data. */
    fun plan(paths: Collection<String>, password: String): Plan {
        var videos = 0
        var unavailable = 0
        val folders = paths.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.distinct()
            .filter(::isCandidate).sortedByDescending { it.count { char -> char == File.separatorChar } }
            .filter { path ->
                runCatching {
                    val manifest = manifestForMigration(path, password)
                    validateInputs(File(path), manifest)
                    videos += manifest.files.size
                }.onFailure { unavailable++ }.isSuccess
            }
        return Plan(folders, videos, unavailable)
    }

    private fun manifestForMigration(path: String, password: String): LockedFolderManifest = checkNotNull(
        if (File(path, LEGACY_MARKER).isFile) FolderLockManager.readManifest(path, password)
        else FolderLockManager.readManifest(path, password)?.takeIf { File(path, JOURNAL).isFile }
    ) { "Private folder metadata could not be read" }

    private fun validateInputs(folder: File, manifest: LockedFolderManifest) {
        require(!File(folder, ".neko_lock_in_progress").exists()) { "Previous folder protection has not finished" }
        val store = PortableVideoStore(folder)
        require(manifest.files.map { it.obfuscatedName }.distinct().size == manifest.files.size)
        manifest.files.forEach { entry ->
            val file = store.checked(File(folder, entry.obfuscatedName))
            require(file.isFile && (PortableVideoStore.hasRecoveryRecord(file) || file.length() == entry.originalSize)) {
                "Missing or truncated private video; folder has been preserved"
            }
        }
        val known = manifest.files.mapTo(HashSet()) { it.obfuscatedName }
        require(folder.listFiles().orEmpty().none {
            it.isFile && !it.name.startsWith(".") && it.name !in known &&
                (PortableVideoStore.hasRecoveryRecord(it) || it.name.matches(Regex("[0-9a-f]{32}|[0-9a-f-]{36}")))
        }) { "Unidentified protected files; folder has been preserved" }
    }

    @Synchronized fun migrate(context: Context, plan: Plan, password: String,
                             progress: (Int, Int) -> Unit = { _, _ -> },
                             availableBytes: (File) -> Long = { StatFs(it.absolutePath).availableBytes },
                             checkpoint: (Stage, File) -> Unit = { _, _ -> }): Result {
        var done = 0
        var folders = 0
        var failed = plan.unavailableFolders
        plan.folders.forEach { path ->
            try {
                migrateFolder(context, File(path).canonicalFile, password, availableBytes, checkpoint) {
                    done++
                    progress(done, maxOf(done, plan.videoCount))
                }
                folders++
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failed++
                Log.w("PrivateVideoMigration", "Private conversion left resumable", failure)
            }
        }
        return Result(done, folders, failed)
    }

    private fun migrateFolder(context: Context, folder: File, password: String, availableBytes: (File) -> Long,
                              checkpoint: (Stage, File) -> Unit, completed: () -> Unit) {
        if (!isCandidate(folder.absolutePath)) return
        val manifest = manifestForMigration(folder.absolutePath, password)
        validateInputs(folder, manifest)
        if (!File(folder, LEGACY_MARKER).exists()) {
            // The last process committed the new marker and stopped during cleanup.
            finish(folder)
            FolderLockManager.addToRegistry(context, folder.absolutePath, manifest.originalFolderName, manifest.files.size)
            LockedPlaybackSession.updateManifest(folder.absolutePath, manifest)
            repeat(manifest.files.size) { completed() }
            return
        }
        val salt = checkNotNull(FolderLockManager.getSalt(folder.absolutePath))
        require(salt.contentEquals(Base64.getDecoder().decode(manifest.salt)))
        val legacyKey = FolderLockManager.deriveXorKey(password, salt)
        val store = PortableVideoStore(folder, migrationTemporaryFiles = true) { stage, destination ->
            if (destination.name.startsWith(".neko_migrate_")) checkpoint(
                if (stage == PortableVideoStore.Stage.PAYLOAD_WRITTEN) Stage.COPY_PAYLOAD_WRITTEN else Stage.COPY_SYNCED, folder)
        }
        val entries = mutableListOf<PortableVideoStore.Entry>()
        val activeFiles = if (LockedPlaybackSession.hasSessionForFolder(folder.absolutePath)) manifest.files.toMutableList() else null
        writeAtomic(File(folder, JOURNAL), "1".toByteArray())
        // Only this converter creates these temporary files; an interrupted copy is disposable.
        folder.listFiles().orEmpty().filter { it.name.matches(Regex("\\.neko_migrate_[0-9a-f-]{36}\\.part")) }
            .forEach { check(it.delete()) }
        try {
            PortableVideoStore.Keyring(password.toCharArray()).use { keys ->
                manifest.files.forEachIndexed { fileIndex, old ->
                    val source = store.checked(File(folder, old.obfuscatedName))
                    val embedded = if (PortableVideoStore.hasRecoveryRecord(source)) {
                        runCatching { store.inspectEntry(source, null, keys) }.getOrNull()
                    } else null
                    val migrated = if (embedded != null) {
                        check(store.matchesPayload(source, embedded.metadata)) { "Private payload integrity mismatch" }
                        embedded
                    } else {
                        val originalCipherHash = PortableVideoStore.sha256(source)
                        val identity = MessageDigest.getInstance("SHA-256").digest(source.name.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        val staged = store.checked(File(folder, ".neko_migrate_$identity.ready"))
                        if (!staged.exists()) {
                            check(availableBytes(folder) >= source.length() + 1024 * 1024) { "Insufficient free space" }
                            if (old.portableMetadata != null) {
                                store.rewrapKnownCopy(source, staged, old.portableMetadata, keys, salt)
                            } else {
                                require(source.length() == old.originalSize)
                                store.attachLegacyCopy(source, staged, old.originalName, legacyKey, keys, salt,
                                    manifest.originalFolderName, old.duration)
                            }
                        }
                        val ready = store.inspectEntry(staged, null, keys)
                        require(ready.metadata.originalName == old.originalName && ready.metadata.payloadSize == old.originalSize)
                        check(store.matchesPayload(staged, ready.metadata) && store.matchesPayload(source, ready.metadata)) {
                            "Input changed during conversion; protected source retained"
                        }
                        checkpoint(Stage.COPY_READY, folder)
                        if (activeFiles != null) {
                            activeFiles[fileIndex] = old.copy(portableMetadata = ready.metadata)
                            LockedPlaybackSession.updateManifest(folder.absolutePath, manifest.copy(files = activeFiles.toList()))
                        }
                        checkpoint(Stage.SESSION_READY, folder)
                        check(PortableVideoStore.sha256(source) == originalCipherHash) {
                            "Source changed before commit; source and verified copy retained"
                        }
                        Files.move(staged.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        checkpoint(Stage.VIDEO_REPLACED, folder)
                        ready.copy(diskName = source.name)
                    }
                    entries += migrated
                    completed()
                }
                // Every payload is independently recoverable before the directory switches readers.
                store.writeIndex(File(folder, PortablePrivateFolders.INDEX), entries, keys, salt, manifest.originalFolderName,
                    manifest.subfolders.orEmpty().map { PortableVideoStore.Directory(it.obfuscatedName, it.originalName) }, true)
                val verifiedIndex = checkNotNull(store.readIndexOrNull(File(folder, PortablePrivateFolders.INDEX), keys))
                check(verifiedIndex.entries == entries && verifiedIndex.privateFolder == true &&
                    verifiedIndex.folderName == manifest.originalFolderName && verifiedIndex.directories.orEmpty() ==
                    manifest.subfolders.orEmpty().map { PortableVideoStore.Directory(it.obfuscatedName, it.originalName) })
                checkpoint(Stage.INDEX_SAVED, folder)
                writeAtomic(File(folder, PortablePrivateFolders.MARKER), Base64.getEncoder().encode(salt))
                check(File(folder, PortablePrivateFolders.MARKER).readBytes().contentEquals(Base64.getEncoder().encode(salt)))
                checkpoint(Stage.NEW_MARKER_SAVED, folder)
                check(File(folder, LEGACY_MARKER).delete())
                checkpoint(Stage.LEGACY_MARKER_REMOVED, folder)
                checkNotNull(PortablePrivateFolders.manifest(folder.absolutePath, password))
                finish(folder)
                FolderLockManager.addToRegistry(context, folder.absolutePath, manifest.originalFolderName, entries.size)
                PortablePrivateFolders.manifest(folder.absolutePath, password)?.let {
                    LockedPlaybackSession.updateManifest(folder.absolutePath, it)
                }
            }
        } finally { legacyKey.fill(0) }
    }

    private fun finish(folder: File) {
        listOf(LEGACY_INDEX, "$LEGACY_INDEX.bak", "$LEGACY_INDEX.new").forEach {
            val file = File(folder, it)
            if (file.exists()) check(file.delete())
        }
        check(File(folder, JOURNAL).delete())
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
}
