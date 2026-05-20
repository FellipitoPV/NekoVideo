package com.nkls.nekovideo.components.helpers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale

enum class TagScope {
    NORMAL,
    PRIVATE
}

@Entity(tableName = "video_tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "NORMAL") val scope: TagScope = TagScope.NORMAL,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "video_tag_refs",
    primaryKeys = ["videoPath", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId"), Index("videoPath")]
)
data class VideoTagCrossRef(
    val videoPath: String,
    val tagId: Long
)

@Dao
interface VideoTagDao {
    @Query("SELECT * FROM video_tags WHERE scope = :scope ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllTags(scope: TagScope): List<TagEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM video_tags WHERE LOWER(name) = LOWER(:name) AND scope = :scope)")
    suspend fun tagExists(name: String, scope: TagScope): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM video_tags WHERE LOWER(name) = LOWER(:name) AND scope = :scope AND id != :tagId)")
    suspend fun tagExistsForOther(tagId: Long, name: String, scope: TagScope): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("DELETE FROM video_tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Query("UPDATE video_tags SET name = :name WHERE id = :tagId")
    suspend fun updateTagName(tagId: Long, name: String)

    @Query("SELECT r.tagId FROM video_tag_refs r INNER JOIN video_tags t ON t.id = r.tagId WHERE r.videoPath IN (:videoPaths) AND t.scope = :scope GROUP BY r.tagId HAVING COUNT(DISTINCT r.videoPath) = :videoCount")
    suspend fun getCommonTagIds(videoPaths: List<String>, videoCount: Int, scope: TagScope): List<Long>

    @Query("SELECT DISTINCT r.videoPath FROM video_tag_refs r INNER JOIN video_tags t ON t.id = r.tagId WHERE r.tagId IN (:tagIds) AND t.scope = :scope")
    suspend fun getVideoPathsForAnyTagIds(tagIds: List<Long>, scope: TagScope): List<String>

    @Query("SELECT r.videoPath FROM video_tag_refs r INNER JOIN video_tags t ON t.id = r.tagId WHERE r.tagId IN (:tagIds) AND t.scope = :scope GROUP BY r.videoPath HAVING COUNT(DISTINCT r.tagId) = :tagCount")
    suspend fun getVideoPathsForAllTagIds(tagIds: List<Long>, tagCount: Int, scope: TagScope): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideoTagRefs(refs: List<VideoTagCrossRef>)

    @Query("DELETE FROM video_tag_refs WHERE videoPath IN (:videoPaths) AND tagId IN (:tagIds)")
    suspend fun deleteVideoTagRefs(videoPaths: List<String>, tagIds: List<Long>)

    @Query("SELECT videoPath FROM video_tag_refs WHERE videoPath = :path OR videoPath LIKE :childPattern")
    suspend fun getTaggedPathsForTree(path: String, childPattern: String): List<String>

    @Query("UPDATE video_tag_refs SET videoPath = :newPath WHERE videoPath = :oldPath")
    suspend fun updateVideoPath(oldPath: String, newPath: String)

    @Query("DELETE FROM video_tag_refs WHERE videoPath = :path OR videoPath LIKE :childPattern")
    suspend fun deleteVideoTagRefsForTree(path: String, childPattern: String)

    @Query("SELECT COUNT(*) FROM video_tag_refs WHERE videoPath = :videoPath")
    suspend fun getTagCountForVideoPath(videoPath: String): Int

    @Query("SELECT * FROM video_tags ORDER BY scope ASC, name COLLATE NOCASE ASC")
    suspend fun getAllTagsAllScopes(): List<TagEntity>

    @Query("SELECT * FROM video_tag_refs")
    suspend fun getAllVideoTagRefs(): List<VideoTagCrossRef>
}

@Database(entities = [TagEntity::class, VideoTagCrossRef::class], version = 2, exportSchema = false)
abstract class VideoTagDatabase : RoomDatabase() {
    abstract fun videoTagDao(): VideoTagDao
}

object VideoTagStore {
    private data class TagBackupPayload(
        val version: Int,
        val exportedAt: Long,
        val tags: List<TagBackupTag>,
        val refs: List<TagBackupRef>
    )

    private data class TagBackupTag(
        val name: String,
        val scope: TagScope,
        val createdAt: Long
    )

    private data class TagBackupRef(
        val videoPath: String,
        val tagName: String,
        val scope: TagScope
    )

    private data class AutomaticBackupEntry(
        val uri: Uri,
        val updatedAt: Long
    )

    data class TagBackupImportResult(
        val createdTags: Int,
        val restoredRefs: Int
    )

    @Volatile
    private var database: VideoTagDatabase? = null

    private val gson = Gson()

    private const val BACKUP_VERSION = 1
    private const val SETTINGS_PREFS = "nekovideo_settings"
    private const val KEY_LAST_AUTO_BACKUP_AT = "tags_last_auto_backup_at"
    private const val AUTO_BACKUP_FILE_NAME = "tags-backup.json"
    private const val AUTO_BACKUP_RELATIVE_PATH = "Documents/NekoVideo/Backup/"

    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE video_tags ADD COLUMN scope TEXT NOT NULL DEFAULT 'NORMAL'")
        }
    }

    private fun getDatabase(context: Context): VideoTagDatabase {
        return database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                VideoTagDatabase::class.java,
                "video_tags.db"
            ).addMigrations(migration1To2).build().also { database = it }
        }
    }

    suspend fun getAllTags(context: Context, scope: TagScope): List<TagEntity> {
        return getDatabase(context).videoTagDao().getAllTags(scope)
    }

    suspend fun createTag(context: Context, rawName: String, scope: TagScope): Result<TagEntity> {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("empty"))

        val dao = getDatabase(context).videoTagDao()
        if (dao.tagExists(name, scope)) return Result.failure(IllegalStateException("exists"))

        val id = dao.insertTag(TagEntity(name = name, scope = scope))
        writeAutomaticBackupSafely(context)
        return Result.success(TagEntity(id = id, name = name, scope = scope))
    }

    suspend fun deleteTag(context: Context, tagId: Long) {
        getDatabase(context).videoTagDao().deleteTag(tagId)
        writeAutomaticBackupSafely(context)
    }

    suspend fun renameTag(context: Context, tagId: Long, rawName: String, scope: TagScope): Result<Unit> {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("empty"))

        val dao = getDatabase(context).videoTagDao()
        if (dao.tagExistsForOther(tagId, name, scope)) return Result.failure(IllegalStateException("exists"))

        dao.updateTagName(tagId, name)
        writeAutomaticBackupSafely(context)
        return Result.success(Unit)
    }

    suspend fun getCommonTagIds(context: Context, videoPaths: List<String>, scope: TagScope): Set<Long> {
        if (videoPaths.isEmpty()) return emptySet()
        return getDatabase(context)
            .videoTagDao()
            .getCommonTagIds(videoPaths, videoPaths.distinct().size, scope)
            .toSet()
    }

    suspend fun addTagsToVideos(context: Context, videoPaths: List<String>, tagIds: Set<Long>) {
        if (videoPaths.isEmpty() || tagIds.isEmpty()) return

        val refs = buildList {
            videoPaths.distinct().forEach { path ->
                tagIds.forEach { tagId ->
                    add(VideoTagCrossRef(videoPath = path, tagId = tagId))
                }
            }
        }

        getDatabase(context).videoTagDao().insertVideoTagRefs(refs)
        writeAutomaticBackupSafely(context)
    }

    suspend fun syncCommonTagsForVideos(
        context: Context,
        videoPaths: List<String>,
        initiallyCommonTagIds: Set<Long>,
        selectedTagIds: Set<Long>
    ) {
        val distinctPaths = videoPaths.distinct()
        if (distinctPaths.isEmpty()) return

        val dao = getDatabase(context).videoTagDao()
        val tagsToAdd = selectedTagIds - initiallyCommonTagIds
        val tagsToRemove = initiallyCommonTagIds - selectedTagIds

        if (tagsToAdd.isNotEmpty()) {
            val refs = buildList {
                distinctPaths.forEach { path ->
                    tagsToAdd.forEach { tagId ->
                        add(VideoTagCrossRef(videoPath = path, tagId = tagId))
                    }
                }
            }
            dao.insertVideoTagRefs(refs)
        }

        if (tagsToRemove.isNotEmpty()) {
            dao.deleteVideoTagRefs(distinctPaths, tagsToRemove.toList())
        }

        writeAutomaticBackupSafely(context)
    }

    suspend fun getVideoPathsForAnyTagIds(context: Context, tagIds: Set<Long>, scope: TagScope): Set<String> {
        if (tagIds.isEmpty()) return emptySet()
        return getDatabase(context)
            .videoTagDao()
            .getVideoPathsForAnyTagIds(tagIds.toList(), scope)
            .toSet()
    }

    suspend fun getVideoPathsForAllTagIds(context: Context, tagIds: Set<Long>, scope: TagScope): Set<String> {
        if (tagIds.isEmpty()) return emptySet()
        return getDatabase(context)
            .videoTagDao()
            .getVideoPathsForAllTagIds(tagIds.toList(), tagIds.size, scope)
            .toSet()
    }

    suspend fun moveTagsForPath(context: Context, oldPath: String, newPath: String) {
        if (oldPath == newPath) return

        val dao = getDatabase(context).videoTagDao()
        val childPattern = "$oldPath/%"
        val taggedPaths = dao.getTaggedPathsForTree(oldPath, childPattern)
        if (taggedPaths.isEmpty()) return

        taggedPaths
            .sortedBy { it.length }
            .forEach { taggedPath ->
                val suffix = taggedPath.removePrefix(oldPath)
                dao.updateVideoPath(taggedPath, "$newPath$suffix")
            }

        writeAutomaticBackupSafely(context)
    }

    suspend fun resetTagsForPathTree(context: Context, path: String) {
        val childPattern = "$path/%"
        getDatabase(context).videoTagDao().deleteVideoTagRefsForTree(path, childPattern)
        writeAutomaticBackupSafely(context)
    }

    suspend fun getTagCountForVideoPath(context: Context, videoPath: String): Int {
        return getDatabase(context).videoTagDao().getTagCountForVideoPath(videoPath)
    }

    suspend fun getLastAutomaticBackupAt(context: Context): Long {
        val storedValue = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AUTO_BACKUP_AT, 0L)
        val backupEntry = findAutomaticBackupEntry(context) ?: return 0L
        return maxOf(storedValue, backupEntry.updatedAt)
    }

    suspend fun hasAutomaticBackup(context: Context): Boolean {
        return findAutomaticBackupEntry(context) != null
    }

    suspend fun shouldOfferAutomaticImport(context: Context): Boolean {
        val backupEntry = findAutomaticBackupEntry(context) ?: return false
        val storedSyncAt = getStoredAutomaticBackupSyncAt(context)
        return backupEntry.updatedAt > storedSyncAt
    }

    suspend fun exportBackupToUri(context: Context, uri: Uri): Result<Unit> = runCatching {
        val payload = buildBackupPayload(context)
        writePayloadToUri(context, uri, payload)
    }

    suspend fun importBackupFromUri(context: Context, uri: Uri): Result<TagBackupImportResult> = runCatching {
        val payload = readPayloadFromUri(context, uri)
        val result = importBackupPayload(context, payload)
        writeAutomaticBackupSafely(context)
        result
    }

    suspend fun importLatestAutomaticBackup(context: Context): Result<TagBackupImportResult> = runCatching {
        val entry = findAutomaticBackupEntry(context) ?: error("automatic_backup_not_found")
        val payload = readPayloadFromUri(context, entry.uri)
        val result = importBackupPayload(context, payload)
        writeAutomaticBackupSafely(context)
        result
    }

    private suspend fun writeAutomaticBackupSafely(context: Context) {
        runCatching {
            writeAutomaticBackup(context)
        }
    }

    private suspend fun writeAutomaticBackup(context: Context) {
        val payload = buildBackupPayload(context)
        val uri = ensureAutomaticBackupUri(context)
        writePayloadToUri(context, uri, payload)
        updateStoredAutomaticBackupSyncAt(context, System.currentTimeMillis())
    }

    private suspend fun buildBackupPayload(context: Context): TagBackupPayload {
        val dao = getDatabase(context).videoTagDao()
        val tags = dao.getAllTagsAllScopes()
        val tagIndex = tags.associateBy { it.id }
        val refs = dao.getAllVideoTagRefs()

        return TagBackupPayload(
            version = BACKUP_VERSION,
            exportedAt = System.currentTimeMillis(),
            tags = tags.map {
                TagBackupTag(
                    name = it.name,
                    scope = it.scope,
                    createdAt = it.createdAt
                )
            },
            refs = refs.mapNotNull { ref ->
                val tag = tagIndex[ref.tagId] ?: return@mapNotNull null
                TagBackupRef(
                    videoPath = ref.videoPath,
                    tagName = tag.name,
                    scope = tag.scope
                )
            }
        )
    }

    private suspend fun importBackupPayload(
        context: Context,
        payload: TagBackupPayload
    ): TagBackupImportResult {
        val database = getDatabase(context)
        return database.withTransaction {
            val dao = database.videoTagDao()
            val tagIdByKey = dao.getAllTagsAllScopes()
                .associate { backupTagKey(it.name, it.scope) to it.id }
                .toMutableMap()

            val normalizedTags = buildList {
                payload.tags.forEach { tag ->
                    normalizeBackupTag(tag.name, tag.scope, tag.createdAt)?.let(::add)
                }
                payload.refs.forEach { ref ->
                    normalizeBackupTag(ref.tagName, ref.scope, System.currentTimeMillis())?.let(::add)
                }
            }.distinctBy { backupTagKey(it.name, it.scope) }

            var createdTags = 0
            normalizedTags.forEach { tag ->
                val key = backupTagKey(tag.name, tag.scope)
                if (key !in tagIdByKey) {
                    val id = dao.insertTag(
                        TagEntity(
                            name = tag.name,
                            scope = tag.scope,
                            createdAt = tag.createdAt
                        )
                    )
                    tagIdByKey[key] = id
                    createdTags++
                }
            }

            val refsToInsert = payload.refs.mapNotNull { ref ->
                val videoPath = ref.videoPath.trim()
                if (videoPath.isEmpty()) return@mapNotNull null

                val tagId = tagIdByKey[backupTagKey(ref.tagName, ref.scope)] ?: return@mapNotNull null
                VideoTagCrossRef(videoPath = videoPath, tagId = tagId)
            }.distinctBy { "${it.videoPath}|${it.tagId}" }

            if (refsToInsert.isNotEmpty()) {
                dao.insertVideoTagRefs(refsToInsert)
            }

            TagBackupImportResult(
                createdTags = createdTags,
                restoredRefs = refsToInsert.size
            )
        }
    }

    private fun normalizeBackupTag(name: String, scope: TagScope, createdAt: Long): TagBackupTag? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        return TagBackupTag(trimmedName, scope, createdAt)
    }

    private fun backupTagKey(name: String, scope: TagScope): String {
        return "${scope.name}:${name.trim().lowercase(Locale.ROOT)}"
    }

    private fun writePayloadToUri(context: Context, uri: Uri, payload: TagBackupPayload) {
        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: error("backup_output_unavailable")

        outputStream.use { stream ->
            OutputStreamWriter(stream).use { writer ->
                gson.toJson(payload, writer)
            }
        }
    }

    private fun readPayloadFromUri(context: Context, uri: Uri): TagBackupPayload {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("backup_input_unavailable")

        inputStream.use { stream ->
            InputStreamReader(stream).use { reader ->
                return gson.fromJson(reader, TagBackupPayload::class.java)
                    ?: error("backup_payload_invalid")
            }
        }
    }

    private fun ensureAutomaticBackupUri(context: Context): Uri {
        findAutomaticBackupEntry(context)?.let { return it.uri }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, AUTO_BACKUP_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, AUTO_BACKUP_RELATIVE_PATH)
        }

        return context.contentResolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: error("automatic_backup_create_failed")
    }

    private fun findAutomaticBackupEntry(context: Context): AutomaticBackupEntry? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(AUTO_BACKUP_FILE_NAME, AUTO_BACKUP_RELATIVE_PATH)

        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            if (cursor.moveToFirst()) {
                val updatedAt = cursor.getLong(dateModifiedColumn) * 1000L
                return AutomaticBackupEntry(
                    uri = Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString()),
                    updatedAt = updatedAt
                )
            }
        }

        return null
    }

    private fun getStoredAutomaticBackupSyncAt(context: Context): Long {
        return context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AUTO_BACKUP_AT, 0L)
    }

    private fun updateStoredAutomaticBackupSyncAt(context: Context, timestamp: Long) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit { putLong(KEY_LAST_AUTO_BACKUP_AT, timestamp) }
    }
}
