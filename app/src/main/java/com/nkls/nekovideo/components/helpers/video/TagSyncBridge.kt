package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TagRpc(val scope: TagScope = TagScope.NORMAL, val id: Long = 0, val name: String = "",
    val paths: List<String> = emptyList(), val ids: List<Long> = emptyList(), val refs: List<VideoTagCrossRef> = emptyList(),
    val path: String = "", val pattern: String = "", val newPath: String = "", val count: Int = 0,
    val tag: TagEntity? = null)
internal data class TagRemoteChange(val epoch: String, val version: Long, val paths: Set<String>?)
internal data class TagRemoteBackup(val exists: Boolean, val updatedAt: Long)

/** The normal package owns the only live database. No database file is shared or copied. */
internal object TagSyncBridge {
    const val CLIENT = "com.nkls.nekovideo.debug"
    const val HOST = "com.nkls.nekovideo"
    private const val PREFS = "tag_sync_connection"
    val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private var observer: ContentObserver? = null
    private var lastChange: TagRemoteChange? = null
    private val epoch = UUID.randomUUID().toString()
    private var hostChange = TagRemoteChange(epoch, 0L, null)
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    @Volatile internal var testHost: String? = null
    @Volatile internal var localTestMode = false
    fun isClient(context: Context) = context.packageName == CLIENT
    fun hostPackage() = testHost ?: HOST
    fun uri() = Uri.parse("content://${hostPackage()}.tagsync/tags")
    fun enabled(context: Context) = isClient(context) && !localTestMode &&
        (testHost != null || context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false))
    fun routingToHost() = app?.let(::enabled) == true

    fun attach(context: Context) {
        app = context.applicationContext
        if (!enabled(context) || observer != null) return
        synchronized(this) {
            if (observer == null) {
                observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { scope.launch { refreshChange(context.applicationContext) } }
                }.also { context.contentResolver.registerContentObserver(uri(), true, it) }
                scope.launch { refreshChange(context.applicationContext) }
            }
        }
    }
    suspend fun connect(context: Context) = withContext(Dispatchers.IO) {
        read<TagRemoteChange>(context, "change", TagRpc(), TagRemoteChange::class.java).single()
        check(context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", true).commit())
        VideoTagStore.stopLocalAutomaticBackup()
        attach(context)
        lastChange = null
        refreshChange(context)
        _revision.value++
    }
    fun disconnect(context: Context) {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        lastChange = null
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", false).commit()
        VideoTagStore.receiveSyncedTagChange(null)
        _revision.value++
    }
    fun authorizationIntent() = Intent().setClassName(hostPackage(), TagSyncAuthorizationActivity::class.java.name)
    fun authorized(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getString("client_certificate", null)
        ?.let { it == certificate(context, CLIENT) } == true
    fun authorize(context: Context) {
        val certificate = checkNotNull(certificate(context, CLIENT))
        check(context.getSharedPreferences(PREFS, 0).edit().putString("client_certificate", certificate).commit())
        _revision.value++
    }
    fun revoke(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().remove("client_certificate").commit()
        context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.tagsync/tags"), null)
        _revision.value++
    }
    private fun certificate(context: Context, pkg: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        checkNotNull(info.signingInfo).apkContentsSigners.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(":")
    }.getOrNull()
    fun localChange(version: Long, paths: Set<String>?) {
        val context = app ?: return
        if (context.packageName != HOST && !context.packageName.endsWith(".tagsynchost")) return
        hostChange = TagRemoteChange(epoch, version, paths)
        context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.tagsync/tags"), null)
    }
    fun currentChange() = hostChange
    private suspend fun refreshChange(context: Context) {
        if (!enabled(context)) return
        runCatching {
            val current = read(context, "change", TagRpc(), TagRemoteChange::class.java).single()
            val previous = lastChange
            if (previous == current) return@runCatching
            lastChange = current
            VideoTagStore.receiveSyncedTagChange(if (previous?.epoch == current.epoch && current.version == previous.version + 1)
                current.paths else null)
        }.onFailure { VideoTagStore.receiveSyncedTagChange(null) }
    }
    suspend fun <T> read(context: Context, method: String, request: TagRpc, type: Class<T>): List<T> = withContext(Dispatchers.IO) {
        val result = context.contentResolver.query(Uri.withAppendedPath(uri(), method), arrayOf("json"),
            gson.toJson(request), null, null) ?: error(context.getString(com.nkls.nekovideo.R.string.tag_sync_unavailable))
        result.use { cursor -> buildList { while (cursor.moveToNext()) add(gson.fromJson(cursor.getString(0), type)) } }
    }
    suspend fun <T> write(context: Context, method: String, request: TagRpc, type: Class<T>): T = withContext(Dispatchers.IO) {
        val response = checkNotNull(context.contentResolver.call(uri(), "mutate", method,
            Bundle().apply { putString("request", gson.toJson(request)) }))
        response.getString("error")?.let { throw IllegalStateException(it) }
        gson.fromJson(response.getString("json"), type)
    }
    internal fun remoteDao(context: Context): VideoTagDao { attach(context); return RemoteTagDao(context.applicationContext) }
    internal suspend fun <T> checked(context: Context, action: suspend () -> T): T = try { action() }
    catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, context.getString(com.nkls.nekovideo.R.string.tag_sync_unavailable), android.widget.Toast.LENGTH_LONG).show()
        }
        // Abort this operation. Never silently edit Debug's old local database.
        throw CancellationException(context.getString(com.nkls.nekovideo.R.string.tag_sync_unavailable), failure)
    }
}

private class RemoteTagDao(private val context: Context) : VideoTagDao {
    private suspend fun <T> read(op: String, arg: TagRpc, type: Class<T>) = TagSyncBridge.checked(context) { TagSyncBridge.read(context, op, arg, type) }
    private suspend fun <T> write(op: String, arg: TagRpc, type: Class<T>) = TagSyncBridge.checked(context) { TagSyncBridge.write(context, op, arg, type) }
    private suspend fun change(op: String, arg: TagRpc) { write(op, arg, java.lang.Boolean::class.java) }
    override suspend fun getAllTags(scope: TagScope) = read("tags", TagRpc(scope = scope), TagEntity::class.java)
    override suspend fun getVideoCountsByTag(scope: TagScope) = read("tagCounts", TagRpc(scope = scope), TagVideoCount::class.java)
    override suspend fun tagExists(name: String, scope: TagScope) = read("exists", TagRpc(scope, name = name), Boolean::class.javaObjectType).single()
    override suspend fun tagExistsForOther(tagId: Long, name: String, scope: TagScope) = read("existsOther", TagRpc(scope, tagId, name), Boolean::class.javaObjectType).single()
    override suspend fun insertTag(tag: TagEntity) = write("insertTag", TagRpc(tag = tag), Long::class.javaObjectType)
    override suspend fun deleteTag(tagId: Long) = change("deleteTag", TagRpc(id = tagId))
    override suspend fun deleteAllTags(): Unit = error(context.getString(com.nkls.nekovideo.R.string.tag_sync_restore_in_normal))
    override suspend fun updateTagName(tagId: Long, name: String) = change("rename", TagRpc(id = tagId, name = name))
    override suspend fun getCommonTagIds(videoPaths: List<String>, videoCount: Int, scope: TagScope): List<Long> {
        var common: Set<Long>? = null
        videoPaths.distinct().chunked(250).forEach { paths ->
            val batch = read("common", TagRpc(scope, paths = paths, count = paths.size), Long::class.javaObjectType).toSet()
            common = common?.intersect(batch) ?: batch
        }
        return common.orEmpty().toList()
    }
    override suspend fun getVideoPathsForAnyTagIds(tagIds: List<Long>, scope: TagScope): List<String> {
        val paths = mutableSetOf<String>()
        tagIds.distinct().chunked(250).forEach { paths += read("any", TagRpc(scope, ids = it), String::class.java) }
        return paths.toList()
    }
    override suspend fun getVideoPathsForAllTagIds(tagIds: List<Long>, tagCount: Int, scope: TagScope): List<String> {
        var common: Set<String>? = null
        tagIds.distinct().chunked(250).forEach {
            val batch = read("all", TagRpc(scope, ids = it, count = it.size), String::class.java).toSet()
            common = common?.intersect(batch) ?: batch
        }
        return common.orEmpty().toList()
    }
    override suspend fun insertVideoTagRefs(refs: List<VideoTagCrossRef>) { refs.chunked(250).forEach { change("addRefs", TagRpc(refs = it)) } }
    override suspend fun deleteVideoTagRefs(videoPaths: List<String>, tagIds: List<Long>) {
        videoPaths.chunked(250).forEach { paths -> tagIds.chunked(250).forEach { ids -> change("removeRefs", TagRpc(paths = paths, ids = ids)) } }
    }
    override suspend fun getTaggedPathsForTree(path: String, childPattern: String) = read("tree", TagRpc(path = path, pattern = childPattern), String::class.java)
    override suspend fun updateVideoPath(oldPath: String, newPath: String) = change("moveRef", TagRpc(path = oldPath, newPath = newPath))
    override suspend fun deleteVideoTagRefsForTree(path: String, childPattern: String) = change("resetTree", TagRpc(path = path, pattern = childPattern))
    override suspend fun getTagCountForVideoPath(videoPath: String) = read("count", TagRpc(path = videoPath), Int::class.javaObjectType).single()
    override suspend fun getAllTagsAllScopes() = read("allTags", TagRpc(), TagEntity::class.java)
    override suspend fun getAllVideoTagRefs() = read("allRefs", TagRpc(), VideoTagCrossRef::class.java)
    override suspend fun getDistinctTaggedVideoPaths() = read("paths", TagRpc(), String::class.java)
    override suspend fun getTagCountsForVideoPaths(videoPaths: List<String>) = read("counts", TagRpc(paths = videoPaths), VideoTagCount::class.java)
    override suspend fun deleteVideoTagRefsForPaths(videoPaths: List<String>): Int {
        var removed = 0
        videoPaths.chunked(250).forEach { removed += write("resetPaths", TagRpc(paths = it), Int::class.javaObjectType) }
        return removed
    }
}
