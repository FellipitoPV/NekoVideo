package com.nkls.nekovideo.components.helpers

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/** Durable, read-only references to the videos explicitly selected for a connected client. */
internal object NekoTrimVideoAccess {
    const val SOURCE_KEY = "nekotrim_source_key"
    fun videoKey(file: File): String {
        val entry = file.parent?.let { LockedPlaybackSession.getManifestForFolder(it) }?.files?.firstOrNull { it.obfuscatedName == file.name }
        val identity = if (entry == null) "${file.canonicalPath}|${file.length()}|${file.lastModified()}"
            else "${file.canonicalPath}|${entry.originalSize}|${entry.portableMetadata?.originalSha256 ?: file.name}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    data class Source(
        val path: String,
        val client: String,
        val connectionId: String,
        val name: String,
        val size: Long,
        val locked: Boolean = false,
        val privateVideo: Boolean = false
    )
    private val gson = Gson()
    private fun preferences(context: Context) = context.getSharedPreferences(NekoTrimConnection.PREFS, Context.MODE_PRIVATE)
    private fun key(client: String, id: String) = "video.$client/$id"

    fun createIntent(context: Context, client: String, paths: List<String>, privateContext: Boolean = false, fastConvert: Boolean = false): Intent {
        check(NekoTrimConnection.authorized(context, client))
        require(paths.isNotEmpty() && paths.all { File(it).isFile })
        val connectionId = checkNotNull(preferences(context).getString("$client.id", null))
        val intent = Intent("com.nkls.nekotrim.OPEN_VIDEOS")
            .setClassName(client, "com.nkls.nekotrim.integration.NekoVideoImportActivity")
            .setType("video/*")
            .putExtra(NekoTrimConnection.HOST_PACKAGE, context.packageName)
            .putExtra(NekoTrimConnection.CONNECTION_ID, connectionId)
            .putExtra(NekoTrimConnection.PROTOCOL, NekoTrimConnection.PROTOCOL_VERSION)
        val activity = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.GET_META_DATA)?.activityInfo
        check((activity?.metaData?.getInt("com.nkls.nekotrim.TRIM_IO_VERSION", 0) ?: 0) >= 2)
        if (fastConvert) check((activity?.metaData?.getInt("com.nkls.nekotrim.FAST_CONVERT_VERSION", 0) ?: 0) >= 1)
        intent.putExtra("operation", if (fastConvert) "convert_mkv" else "trim")
        intent.putExtra("trim_io_version", 2)
        val editor = preferences(context).edit()
        var privateSelection: Boolean? = null
        var lockedSelection: Boolean? = null
        val uris = paths.distinct().map { path ->
            val file = File(path).canonicalFile
            val locked = FolderLockManager.isLocked(checkNotNull(file.parent)) ||
                LockedPlaybackSession.getXorKeyForFile(file.path) != null || PortableVideoStore.hasRecoveryRecord(file)
            require(lockedSelection == null || lockedSelection == locked) { "Send protected and unprotected videos separately" }
            lockedSelection = locked
            val entry = if (locked) {
                checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.path)) { "Unlock the source folder first" }
                checkNotNull(LockedPlaybackSession.getManifestForFolder(file.parent!!)?.files?.firstOrNull { it.obfuscatedName == file.name })
            } else null
            val privateVideo = privateContext || locked || generateSequence(file.parentFile) { it.parentFile }
                .any { it.name.startsWith(".") || File(it, ".nomedia").isFile || File(it, ".secure").exists() }
            require(privateSelection == null || privateSelection == privateVideo) { "Send private and ordinary videos separately" }
            privateSelection = privateVideo
            val source = Source(file.path, client, connectionId, entry?.originalName ?: file.name,
                entry?.originalSize ?: file.length(), locked, privateVideo)
            val id = MessageDigest.getInstance("SHA-256")
                .digest("$connectionId:${file.path}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            editor.putString(key(client, id), gson.toJson(source))
            Uri.Builder().scheme("content").authority("${context.packageName}.nekotrim.videos")
                .appendPath("video").appendPath(client).appendPath(id).build()
        }
        check(editor.commit())
        intent.putExtra("private_import", privateSelection == true)
        return intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).apply {
            clipData = ClipData("Videos", arrayOf("video/*"), ClipData.Item(uris.first())).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
    }

    fun source(context: Context, uri: Uri): Source {
        val segments = uri.pathSegments
        require(segments.size == 3 && segments[0] == "video" && segments[1] in NekoTrimConnection.clients)
        val json = checkNotNull(preferences(context).getString(key(segments[1], segments[2]), null))
        return gson.fromJson(json, Source::class.java)
    }

    fun revoke(context: Context, client: String) {
        val prefs = preferences(context)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("video.$client/") }.forEach(editor::remove)
        editor.apply()
    }
}
