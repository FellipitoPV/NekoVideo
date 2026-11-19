package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.gson.Gson
import java.io.File
import java.util.UUID

/** Output rights are scoped to the source explicitly handed to the connected app. */
internal object NekoTrimOutputAccess {
    private data class Output(val id: String, val anchor: String, val client: String, val connectionId: String,
        val folder: String, val staging: String, val name: String, val size: Long, val tag: String,
        val locked: Boolean, val privateVideo: Boolean, val publishing: Boolean = false, val published: String? = null)
    private val gson = Gson()
    private fun prefs(context: Context) = context.getSharedPreferences(NekoTrimConnection.PREFS, 0)
    private fun key(id: String) = "output.$id"
    private fun save(context: Context, output: Output) { check(prefs(context).edit().putString(key(output.id), gson.toJson(output)).commit()) }
    private fun load(context: Context, id: String): Output = gson.fromJson(checkNotNull(prefs(context).getString(key(id), null)), Output::class.java)
    private fun uri(context: Context, output: Output) = Uri.parse("content://${context.packageName}.nekotrim.videos/output/${output.client}/${output.id}")
    private fun authorize(context: Context, client: String, uid: Int, id: String) {
        if (!context.packageManager.getPackagesForUid(uid).orEmpty().contains(client) || !NekoTrimConnection.authorized(context, client, id))
            throw SecurityException("Output access requires the authorized connection")
    }
    private fun source(context: Context, client: String, uid: Int, anchor: Uri): NekoTrimVideoAccess.Source {
        require(anchor.authority == "${context.packageName}.nekotrim.videos")
        val source = NekoTrimVideoAccess.source(context, anchor)
        require(source.client == client)
        authorize(context, client, uid, source.connectionId)
        return source
    }
    private fun output(context: Context, client: String, uid: Int, uri: Uri): Output {
        require(uri.authority == "${context.packageName}.nekotrim.videos" && uri.pathSegments.size == 3 && uri.pathSegments[0] == "output")
        val output = load(context, uri.pathSegments[2])
        require(output.client == client && uri.pathSegments[1] == client)
        authorize(context, client, uid, output.connectionId)
        return output
    }
    private fun requireUnlocked(source: NekoTrimVideoAccess.Source) {
        if (source.locked) checkNotNull(LockedPlaybackSession.getXorKeyForFile(source.path)) { "Unlock the source folder in NekoVideo" }
    }

    private fun folder(context: Context, source: NekoTrimVideoAccess.Source): File {
        val parent = checkNotNull(File(source.path).parentFile)
        require(parent.isDirectory)
        requireUnlocked(source)
        if (!source.locked) {
            check(!FolderLockManager.isLocked(parent.path))
            return File(parent, "NekoTrim").canonicalFile.also {
                require(it.parentFile == parent.canonicalFile)
                check(it.isDirectory || it.mkdir())
                if (source.privateVideo) File(it, ".nomedia").createNewFile()
            }
        }
        val password = checkNotNull(LockedPlaybackSession.sessionPassword)
        // A standalone protected video does not make its ordinary parent directory private.
        if (!FolderLockManager.isLocked(parent.path)) {
            val child = File(parent, "NekoTrim")
            if (!child.exists()) check(PortablePrivateFolders.createEmpty(context, child.path, password))
            require(FolderLockManager.isLocked(child.path)) { "The existing NekoTrim folder must be protected first" }
            LockedPlaybackSession.setCurrentFolder(parent.path)
            return child
        }
        val manifest = checkNotNull(FolderLockManager.readManifestForLocalEdit(parent.path, password))
        manifest.subfolders.firstOrNull { it.originalName == "NekoTrim" }?.let {
            val child = File(parent, it.obfuscatedName).canonicalFile
            require(child.parentFile == parent.canonicalFile && FolderLockManager.isLocked(child.path))
            return child
        }
        val child = File(parent, "NekoTrim")
        require(!child.exists()) { "An unprotected NekoTrim folder already exists" }
        check(PortablePrivateFolders.createEmpty(context, child.path, password))
        val path = checkNotNull(FolderLockManager.addSubfolderToLockedFolder(context, parent.path, child.path, password))
        FolderLockManager.readManifestForLocalEdit(parent.path, password)?.let { LockedPlaybackSession.updateManifest(parent.path, it) }
        LockedPlaybackSession.setCurrentFolder(parent.path)
        return File(path)
    }
    private fun entries(context: Context): List<Output> = prefs(context).all.filterKeys { it.startsWith("output.") }
        .values.mapNotNull { runCatching { gson.fromJson(it as String, Output::class.java) }.getOrNull() }

    @Synchronized fun call(context: Context, client: String, uid: Int, method: String, arg: String?, extras: Bundle?): Bundle {
        val anchor = Uri.parse(checkNotNull(arg))
        val source = source(context, client, uid, anchor)
        val args = checkNotNull(extras)
        return when (method) {
            "trim_stage" -> {
                val folder = folder(context, source)
                val requested = checkNotNull(args.getString("name"))
                require(requested == File(requested).name && requested !in listOf(".", "..") && File(requested).extension.lowercase() in setOf("mp4", "mkv", "webm"))
                val size = args.getLong("size"); require(size in 1..(Long.MAX_VALUE - 65_536))
                check(folder.usableSpace >= size + 65_536 && (!source.privateVideo || context.cacheDir.usableSpace >= size + 65_536)) { "Not enough space in the output folder" }
                val tag = checkNotNull(args.getString("tag")); require(tag.startsWith("NekoTrim:") && tag.length < 100)
                val taken = entries(context).filter { it.folder == folder.path }.mapTo(HashSet()) { it.name }
                if (source.locked) {
                    val password = checkNotNull(LockedPlaybackSession.sessionPassword)
                    checkNotNull(FolderLockManager.readManifestForLocalEdit(folder.path, password)).files.forEach { taken.add(it.originalName) }
                }
                var name = requested; var number = 1
                while (name in taken || File(folder, name).exists()) {
                    name = "${requested.substringBeforeLast('.')} (${number++}).${requested.substringAfterLast('.')}"
                }
                val id = UUID.randomUUID().toString()
                val staging = if (source.privateVideo) File(File(context.cacheDir, "nekotrim-outputs/$id").apply { check(mkdirs()) }, name)
                    else File(folder, ".nekotrim-$id.part")
                check(staging.createNewFile())
                val output = Output(id, anchor.toString(), client, source.connectionId, folder.path, staging.path, name, size, tag, source.locked, source.privateVideo)
                try { save(context, output) } catch (error: Exception) { staging.delete(); throw error }
                bundle(context, output)
            }
            "trim_list" -> Bundle().apply {
                putParcelableArrayList("outputs", ArrayList(entries(context).filter { it.client == client && it.anchor == arg && it.tag == args.getString("tag") }
                    .map { bundle(context, it) }))
            }
            "trim_verify", "trim_publish", "trim_delete" -> {
                var output = output(context, client, uid, Uri.parse(checkNotNull(args.getString("uri"))))
                require(output.anchor == arg)
                if (method == "trim_delete") {
                    // Publication recovery only discards uncommitted stages; published results stay intact.
                    check(output.published == null) { "A published output cannot be discarded" }
                    File(output.staging).delete()
                    prefs(context).edit().remove(key(output.id)).commit()
                } else {
                    requireUnlocked(source)
                    val staging = File(output.staging)
                    if (output.published == null && output.publishing && !staging.exists()) {
                        val existing = if (output.locked) {
                            val password = checkNotNull(LockedPlaybackSession.sessionPassword)
                            FolderLockManager.readManifestForLocalEdit(output.folder, password)?.files
                                ?.firstOrNull { it.originalName == output.name && it.originalSize == output.size }?.let { File(output.folder, it.obfuscatedName) }
                        } else File(output.folder, output.name).takeIf { it.isFile && it.length() == output.size }
                        checkNotNull(existing) { "Output publication needs recovery" }
                        output = output.copy(published = existing.path); save(context, output)
                    }
                    val complete = output.published?.let { path ->
                        val file = File(path)
                        file.isFile && if (!output.locked || !PortablePrivateFolders.isPortable(output.folder)) file.length() == output.size
                        else file.length() > output.size && PortableVideoStore.hasRecoveryRecord(file)
                    } ?: (staging.isFile && staging.length() == output.size)
                    check(complete) { "The output is incomplete" }
                    if (method == "trim_publish" && output.published == null) {
                        output = output.copy(publishing = true); save(context, output)
                        val destination = if (output.locked) {
                            FolderLockManager.importTrimOutput(context, output.folder, checkNotNull(LockedPlaybackSession.sessionPassword), staging).also {
                                LockedPlaybackSession.setCurrentFolder(File(source.path).parent!!)
                            }
                        } else {
                            val target = File(output.folder, output.name)
                            require(!target.exists())
                            java.nio.file.Files.move(staging.toPath(), target.toPath())
                            target
                        }
                        output = output.copy(published = destination.path); save(context, output)
                        if (!output.privateVideo) MediaScannerConnection.scanFile(context, arrayOf(destination.path), null, null)
                        com.nkls.nekovideo.services.FolderVideoScanner.refreshLocalPaths(context, listOf(File(source.path).parent!!, output.folder))
                    }
                }
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("Unsupported output operation")
        }
    }
    private fun bundle(context: Context, output: Output) = Bundle().apply {
        putString("uri", uri(context, output).toString()); putString("name", output.name); putLong("size", output.size)
    }
    fun query(context: Context, client: String, uid: Int, uri: Uri, projection: Array<out String>?): Cursor {
        val output = output(context, client, uid, uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) output.name else output.size }) }
    }
    fun open(context: Context, client: String, uid: Int, uri: Uri, mode: String): ParcelFileDescriptor {
        val output = output(context, client, uid, uri)
        check(output.published == null)
        requireUnlocked(source(context, client, uid, Uri.parse(output.anchor)))
        require(mode in listOf("r", "w", "wt"))
        return ParcelFileDescriptor.open(File(output.staging), if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
            else ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    fun revoke(context: Context, client: String) {
        entries(context).filter { it.client == client && it.published == null }.forEach {
            File(it.staging).delete(); prefs(context).edit().remove(key(it.id)).apply()
        }
    }
}
