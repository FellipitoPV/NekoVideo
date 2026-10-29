package com.nkls.nekovideo.components.helpers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking

/** Only an explicitly authorized, certificate-pinned Debug install can access this endpoint. */
class TagSyncProvider : ContentProvider() {
    override fun onCreate(): Boolean { TagSyncBridge.attach(checkNotNull(context)); return true }
    private fun authorize() {
        val ctx = checkNotNull(context)
        val caller = callingPackage
        if (caller != TagSyncBridge.CLIENT || !ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            .orEmpty().contains(caller)) throw SecurityException("Unsupported TAG client")
        if (!TagSyncBridge.authorized(ctx)) throw SecurityException("TAG connection has not been authorized")
    }
    private fun request(json: String?): TagRpc {
        require(json != null && json.length <= 256_000)
        val request = TagSyncBridge.gson.fromJson(json, TagRpc::class.java)
        require(request.paths.size <= 1000 && request.ids.size <= 1000 && request.refs.size <= 1000)
        return request
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        authorize()
        val args = request(selection)
        val dao = VideoTagStore.localDao(checkNotNull(context))
        val rows: List<Any> = runBlocking { when (uri.lastPathSegment) {
            "change" -> listOf(TagSyncBridge.currentChange())
            "backup" -> listOf(TagRemoteBackup(VideoTagStore.hasAutomaticBackup(checkNotNull(context)), VideoTagStore.getLastAutomaticBackupAt(checkNotNull(context))))
            "tags" -> dao.getAllTags(args.scope)
            "tagCounts" -> dao.getVideoCountsByTag(args.scope)
            "exists" -> listOf(dao.tagExists(args.name, args.scope))
            "existsOther" -> listOf(dao.tagExistsForOther(args.id, args.name, args.scope))
            "common" -> dao.getCommonTagIds(args.paths, args.count, args.scope)
            "any" -> dao.getVideoPathsForAnyTagIds(args.ids, args.scope)
            "all" -> dao.getVideoPathsForAllTagIds(args.ids, args.count, args.scope)
            "tree" -> dao.getTaggedPathsForTree(args.path, args.pattern)
            "count" -> listOf(dao.getTagCountForVideoPath(args.path))
            "allTags" -> dao.getAllTagsAllScopes()
            "allRefs" -> dao.getAllVideoTagRefs()
            "refsForPaths" -> dao.getVideoTagRefsForPaths(args.paths)
            "paths" -> dao.getDistinctTaggedVideoPaths()
            "counts" -> dao.getTagCountsForVideoPaths(args.paths)
            else -> error("Unsupported TAG query")
        } }
        return MatrixCursor(arrayOf("json"), rows.size).apply {
            rows.forEach { addRow(arrayOf(TagSyncBridge.gson.toJson(it))) }
            setNotificationUri(checkNotNull(context).contentResolver, Uri.parse("content://${checkNotNull(context).packageName}.tagsync/tags"))
        }
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        require(method == "mutate")
        val args = request(extras?.getString("request"))
        val ctx = checkNotNull(context)
        return try {
            val result: Any = runBlocking {
                val db = VideoTagStore.localDatabase(ctx)
                val dao = db.videoTagDao()
                val resetPaths = if (arg == "resetTree") dao.getTaggedPathsForTree(args.path, args.pattern).toSet() else null
                val result: Any = if (arg == "flushBackup") {
                    VideoTagStore.flushCanonicalBackup(ctx); true
                } else if (arg == "moveTree") {
                    VideoTagStore.moveTagsForPath(ctx, args.path, args.newPath)
                    VideoTagStore.flushCanonicalBackup(ctx); true
                } else db.withTransaction { when (arg) {
                    "insertTag" -> {
                        val tag = checkNotNull(args.tag)
                        require(tag.name.isNotBlank() && tag.name.length <= 1024)
                        check(!dao.tagExists(tag.name.trim(), tag.scope)) { "exists" }
                        dao.insertTag(tag.copy(id = 0, name = tag.name.trim()))
                    }
                    "deleteTag" -> { dao.deleteTag(args.id); true }
                    "rename" -> {
                        val tag = checkNotNull(dao.getAllTagsAllScopes().firstOrNull { it.id == args.id })
                        require(args.name.isNotBlank() && args.name.length <= 1024)
                        check(!dao.tagExistsForOther(args.id, args.name.trim(), tag.scope)) { "exists" }
                        dao.updateTagName(args.id, args.name.trim()); true
                    }
                    "addRefs" -> { dao.insertVideoTagRefs(args.refs); true }
                    "removeRefs" -> { dao.deleteVideoTagRefs(args.paths, args.ids); true }
                    "moveRef" -> { dao.updateVideoPath(args.path, args.newPath); true }
                    "resetTree" -> { dao.deleteVideoTagRefsForTree(args.path, args.pattern); true }
                    "resetPaths" -> dao.deleteVideoTagRefsForPaths(args.paths)
                    else -> error("Unsupported TAG mutation")
                } }
                if (arg != "moveTree" && arg != "flushBackup") {
                    val affected = when (arg) {
                        "insertTag", "rename" -> emptySet()
                        "addRefs" -> args.refs.mapTo(HashSet()) { it.videoPath }
                        "removeRefs", "resetPaths" -> args.paths.toSet()
                        "moveRef" -> setOf(args.path, args.newPath)
                        "resetTree" -> resetPaths
                        else -> null
                    }
                    VideoTagStore.changedByRemoteClient(ctx, affected)
                }
                result
            }
            Bundle().apply { putString("json", TagSyncBridge.gson.toJson(result)) }
        } catch (failure: Exception) {
            Bundle().apply { putString("error", failure.message ?: "TAG operation failed") }
        }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.nekovideo.tags"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Use the authorized TAG protocol")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw SecurityException("Use the authorized TAG protocol")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw SecurityException("Use the authorized TAG protocol")
}
