package com.nkls.nekovideo.components.helpers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
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
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.StringReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.File
import java.io.FileNotFoundException
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

data class TagVideoCount(
    val tagId: Long,
    val videoCount: Int
)

data class VideoTagCount(
    val videoPath: String,
    val tagCount: Int
)

data class VideoTagChange(
    val version: Long = 0L,
    // null invalidates all counts (for example, deleting a tag or importing a backup).
    val affectedVideoPaths: Set<String>? = null
)

data class MissingVideoTagCleanupResult(
    val checkedVideoPaths: Int,
    val removedVideoPaths: Int,
    val removedTagLinks: Int
)

@Dao
interface VideoTagDao {
    @Query("SELECT * FROM video_tags WHERE scope = :scope ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllTags(scope: TagScope): List<TagEntity>

    @Query("SELECT r.tagId AS tagId, COUNT(DISTINCT r.videoPath) AS videoCount FROM video_tag_refs r INNER JOIN video_tags t ON t.id = r.tagId WHERE t.scope = :scope GROUP BY r.tagId")
    suspend fun getVideoCountsByTag(scope: TagScope): List<TagVideoCount>

    @Query("SELECT EXISTS(SELECT 1 FROM video_tags WHERE LOWER(name) = LOWER(:name) AND scope = :scope)")
    suspend fun tagExists(name: String, scope: TagScope): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM video_tags WHERE LOWER(name) = LOWER(:name) AND scope = :scope AND id != :tagId)")
    suspend fun tagExistsForOther(tagId: Long, name: String, scope: TagScope): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("DELETE FROM video_tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Query("DELETE FROM video_tags")
    suspend fun deleteAllTags()

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

    @Query("SELECT * FROM video_tag_refs WHERE videoPath IN (:paths)")
    suspend fun getVideoTagRefsForPaths(paths: List<String>): List<VideoTagCrossRef>

    @Query("SELECT DISTINCT videoPath FROM video_tag_refs")
    suspend fun getDistinctTaggedVideoPaths(): List<String>

    @Query("SELECT videoPath, COUNT(*) AS tagCount FROM video_tag_refs WHERE videoPath IN (:videoPaths) GROUP BY videoPath")
    suspend fun getTagCountsForVideoPaths(videoPaths: List<String>): List<VideoTagCount>

    @Query("DELETE FROM video_tag_refs WHERE videoPath IN (:videoPaths)")
    suspend fun deleteVideoTagRefsForPaths(videoPaths: List<String>): Int
}

@Database(entities = [TagEntity::class, VideoTagCrossRef::class], version = 2, exportSchema = false)
abstract class VideoTagDatabase : RoomDatabase() {
    abstract fun videoTagDao(): VideoTagDao
}

object VideoTagStore {
    private val _tagChangeEvent = MutableStateFlow(0L)
    val tagChangeEvent: StateFlow<Long> = _tagChangeEvent.asStateFlow()
    private val _tagChanges = MutableStateFlow(VideoTagChange())
    val tagChanges: StateFlow<VideoTagChange> = _tagChanges.asStateFlow()

    @Synchronized
    private fun notifyTagChange(affectedVideoPaths: Set<String>? = null) {
        if (TagSyncBridge.routingToHost()) return
        emitTagChange(affectedVideoPaths)
        TagSyncBridge.localChange(_tagChangeEvent.value, affectedVideoPaths)
    }
    @Synchronized private fun emitTagChange(affectedVideoPaths: Set<String>?) {
        val version = _tagChangeEvent.value + 1L
        _tagChanges.value = VideoTagChange(version, affectedVideoPaths)
        _tagChangeEvent.value = version
    }

    private data class AutomaticBackupManifest(
        @SerializedName(value = "version", alternate = ["a"]) val version: Int,
        @SerializedName(value = "exportedAt", alternate = ["b"]) val exportedAt: Long,
        @SerializedName(value = "files", alternate = ["c"]) val files: List<String>
    )

    private data class AutomaticBackupTagsPayload(
        @SerializedName(value = "version", alternate = ["a"]) val version: Int,
        @SerializedName(value = "updatedAt", alternate = ["b"]) val updatedAt: Long,
        @SerializedName(value = "tags", alternate = ["c"]) val tags: List<AutomaticBackupTag>
    )

    private data class AutomaticBackupTag(
        @SerializedName(value = "id", alternate = ["a"]) val id: Long,
        @SerializedName(value = "name", alternate = ["b"]) val name: String,
        @SerializedName(value = "scope", alternate = ["c"]) val scope: TagScope,
        @SerializedName(value = "createdAt", alternate = ["d"]) val createdAt: Long
    )

    private data class AutomaticBackupRefsPayload(
        @SerializedName(value = "version", alternate = ["a"]) val version: Int,
        @SerializedName(value = "updatedAt", alternate = ["b"]) val updatedAt: Long,
        @SerializedName(value = "refs", alternate = ["c"]) val refs: List<AutomaticBackupVideoRef>
    )

    private data class AutomaticBackupVideoRef(
        @SerializedName(value = "videoPath", alternate = ["a"]) val videoPath: String,
        @SerializedName(value = "tagIds", alternate = ["b"]) val tagIds: List<Long>
    )

    internal data class TagBackupPayload(
        @SerializedName(value = "version", alternate = ["a"]) val version: Int,
        @SerializedName(value = "exportedAt", alternate = ["b"]) val exportedAt: Long,
        @SerializedName(value = "tags", alternate = ["c"]) val tags: List<TagBackupTag>,
        @SerializedName(value = "refs", alternate = ["d"]) val refs: List<TagBackupRef>
    )

    internal data class TagBackupTag(
        @SerializedName(value = "name", alternate = ["a"]) val name: String,
        @SerializedName(value = "scope", alternate = ["b"]) val scope: TagScope,
        @SerializedName(value = "createdAt", alternate = ["c"]) val createdAt: Long
    )

    internal data class TagBackupRef(
        @SerializedName(value = "videoPath", alternate = ["a"]) val videoPath: String,
        @SerializedName(value = "tagName", alternate = ["b"]) val tagName: String,
        @SerializedName(value = "scope", alternate = ["c"]) val scope: TagScope
    )

    private data class AutomaticBackupEntry(
        val uri: Uri,
        val updatedAt: Long
    )

    private data class AutomaticBackupFiles(
        val manifest: AutomaticBackupEntry,
        val tags: AutomaticBackupEntry,
        val refs: AutomaticBackupEntry
    ) {
        val updatedAt: Long
            get() = listOf(manifest.updatedAt, tags.updatedAt, refs.updatedAt).maxOrNull() ?: 0L
    }

    data class TagBackupImportResult(
        val importedTags: Int,
        val restoredRefs: Int
    )

    @Volatile
    private var database: VideoTagDatabase? = null

    private val automaticBackupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var automaticBackupJob: Job? = null
    @Volatile private var automaticBackupPending = false

    private val gson = Gson()

    private const val BACKUP_VERSION = 1
    private const val SETTINGS_PREFS = "nekovideo_settings"
    private const val KEY_LAST_AUTO_BACKUP_AT = "tags_last_auto_backup_at"
    private const val KEY_AUTO_BACKUP_PENDING = "tags_auto_backup_pending"
    private const val AUTO_BACKUP_LEGACY_FILE_NAME = "tags-backup.json"
    private const val AUTO_BACKUP_MANIFEST_FILE_NAME = "manifest.json"
    private const val AUTO_BACKUP_TAGS_FILE_NAME = "tags.json"
    private const val AUTO_BACKUP_REFS_FILE_NAME = "video-tag-refs.json"
    private val AUTO_BACKUP_RELATIVE_PATH = when (com.nkls.nekovideo.BuildConfig.APPLICATION_ID) {
        TagSyncBridge.CLIENT -> "Documents/NekoVideo/Backup/Debug/Tags/"
        "com.nkls.nekovideo.tagsynchost" -> "Documents/NekoVideo/Backup/Testing/TagSyncHost/"
        else -> "Documents/NekoVideo/Backup/Tags/"
    }
    private val AUTO_BACKUP_LEGACY_RELATIVE_PATH = when (com.nkls.nekovideo.BuildConfig.APPLICATION_ID) {
        TagSyncBridge.CLIENT -> "Documents/NekoVideo/Backup/Debug/"
        "com.nkls.nekovideo.tagsynchost" -> "Documents/NekoVideo/Backup/Testing/TagSyncHost/Legacy/"
        else -> "Documents/NekoVideo/Backup/"
    }
    private const val AUTO_BACKUP_RETRY_MS = 30_000L

    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE video_tags ADD COLUMN scope TEXT NOT NULL DEFAULT 'NORMAL'")
        }
    }

    internal fun localDatabase(context: Context) = getDatabase(context)
    internal fun localDao(context: Context): VideoTagDao = getDatabase(context).videoTagDao()
    private fun getDao(context: Context): VideoTagDao = if (TagSyncBridge.enabled(context))
        TagSyncBridge.remoteDao(context) else localDao(context)
    internal fun receiveSyncedTagChange(paths: Set<String>?) { emitTagChange(paths) }
    internal suspend fun changedByRemoteClient(context: Context, paths: Set<String>?) {
        writeAutomaticBackupSafely(context, paths)
        notifyTagChange(paths)
    }
    private fun getDatabase(context: Context): VideoTagDatabase {
        TagSyncBridge.attach(context)
        return database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                VideoTagDatabase::class.java,
                "video_tags.db"
            ).addMigrations(migration1To2).build().also { database = it }
        }
    }

    suspend fun getAllTags(context: Context, scope: TagScope): List<TagEntity> {
        return getDao(context).getAllTags(scope)
    }

    suspend fun getVideoCountsByTag(context: Context, scope: TagScope): Map<Long, Int> {
        return getDao(context).getVideoCountsByTag(scope)
            .associate { it.tagId to it.videoCount }
    }

    suspend fun cleanMissingVideoTagRefs(
        context: Context,
        onProgress: suspend (checked: Int, total: Int) -> Unit
    ): MissingVideoTagCleanupResult = withContext(Dispatchers.IO) {
        val dao = getDao(context)
        val paths = dao.getDistinctTaggedVideoPaths()
        val missingPaths = mutableListOf<String>()

        paths.forEachIndexed { index, path ->
            if (videoPathExists(context, path) == false) {
                missingPaths += path
            }
            if ((index + 1) % 25 == 0 || index + 1 == paths.size) {
                onProgress(index + 1, paths.size)
            }
        }

        var removedTagLinks = 0
        missingPaths.chunked(500).forEach { batch ->
            removedTagLinks += dao.deleteVideoTagRefsForPaths(batch)
        }

        if (removedTagLinks > 0) {
            writeAutomaticBackupSafely(context, missingPaths.toSet())
            notifyTagChange(missingPaths.toSet())
        }

        MissingVideoTagCleanupResult(
            checkedVideoPaths = paths.size,
            removedVideoPaths = missingPaths.size,
            removedTagLinks = removedTagLinks
        )
    }

    private fun videoPathExists(context: Context, storedPath: String): Boolean? {
        val path = when {
            storedPath.startsWith("locked://") -> storedPath.removePrefix("locked://")
            storedPath.startsWith("file://") -> storedPath
            else -> storedPath
        }
        val uri = Uri.parse(path)

        if (uri.scheme == "content") {
            return try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: FileNotFoundException) {
                false
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }

        if (uri.scheme != null && uri.scheme != "file") return null
        val filePath = if (uri.scheme == "file") uri.path ?: return null else path
        return File(filePath).exists()
    }

    suspend fun createTag(context: Context, rawName: String, scope: TagScope): Result<TagEntity> {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("empty"))

        val dao = getDao(context)
        if (dao.tagExists(name, scope)) return Result.failure(IllegalStateException("exists"))

        val id = dao.insertTag(TagEntity(name = name, scope = scope))
        writeAutomaticBackupSafely(context)
        notifyTagChange(emptySet())
        return Result.success(TagEntity(id = id, name = name, scope = scope))
    }

    suspend fun deleteTag(context: Context, tagId: Long) {
        getDao(context).deleteTag(tagId)
        writeAutomaticBackupSafely(context)
        notifyTagChange()
    }

    suspend fun renameTag(context: Context, tagId: Long, rawName: String, scope: TagScope): Result<Unit> {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("empty"))

        val dao = getDao(context)
        if (dao.tagExistsForOther(tagId, name, scope)) return Result.failure(IllegalStateException("exists"))

        dao.updateTagName(tagId, name)
        writeAutomaticBackupSafely(context)
        notifyTagChange(emptySet())
        return Result.success(Unit)
    }

    suspend fun getCommonTagIds(context: Context, videoPaths: List<String>, scope: TagScope): Set<Long> {
        if (videoPaths.isEmpty()) return emptySet()
        return getDao(context)
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

        getDao(context).insertVideoTagRefs(refs)
        writeAutomaticBackupSafely(context, videoPaths.toSet())
        notifyTagChange(videoPaths.toSet())
    }

    suspend fun syncCommonTagsForVideos(
        context: Context,
        videoPaths: List<String>,
        initiallyCommonTagIds: Set<Long>,
        selectedTagIds: Set<Long>
    ) {
        val distinctPaths = videoPaths.distinct()
        if (distinctPaths.isEmpty()) return
        if (initiallyCommonTagIds == selectedTagIds) return

        val dao = getDao(context)
        val tagsToAdd = selectedTagIds - initiallyCommonTagIds
        val tagsToRemove = initiallyCommonTagIds - selectedTagIds

        if (tagsToAdd.isEmpty() && tagsToRemove.isEmpty()) return

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

        writeAutomaticBackupSafely(context, distinctPaths.toSet())
        notifyTagChange(distinctPaths.toSet())
    }

    suspend fun getVideoPathsForAnyTagIds(context: Context, tagIds: Set<Long>, scope: TagScope): Set<String> {
        if (tagIds.isEmpty()) return emptySet()
        return getDao(context)
            .getVideoPathsForAnyTagIds(tagIds.toList(), scope)
            .toSet()
    }

    suspend fun getVideoPathsForAllTagIds(context: Context, tagIds: Set<Long>, scope: TagScope): Set<String> {
        if (tagIds.isEmpty()) return emptySet()
        return getDao(context)
            .getVideoPathsForAllTagIds(tagIds.toList(), tagIds.size, scope)
            .toSet()
    }

    suspend fun getTagCountsForVideoPaths(context: Context, videoPaths: List<String>): Map<String, Int> {
        if (videoPaths.isEmpty()) return emptyMap()
        val dao = getDao(context)
        val counts = videoPaths.associateWith { 0 }.toMutableMap()
        videoPaths.distinct().chunked(500).forEach { batch ->
            dao.getTagCountsForVideoPaths(batch).forEach { counts[it.videoPath] = it.tagCount }
        }
        return counts
    }

    suspend fun moveTagsForPath(context: Context, oldPath: String, newPath: String) {
        if (oldPath == newPath) return
        if (TagSyncBridge.enabled(context)) {
            TagSyncBridge.checked(context) {
                TagSyncBridge.write(context, "moveTree", TagRpc(path = oldPath, newPath = newPath), Boolean::class.javaObjectType)
            }
            return
        }
        val dao = getDao(context)
        val childPattern = "$oldPath/%"
        val taggedPaths = dao.getTaggedPathsForTree(oldPath, childPattern)
        if (taggedPaths.isEmpty()) return

        taggedPaths
            .sortedBy { it.length }
            .forEach { taggedPath ->
                val suffix = taggedPath.removePrefix(oldPath)
                dao.updateVideoPath(taggedPath, "$newPath$suffix")
            }

        val affected = (taggedPaths + taggedPaths.map { newPath + it.removePrefix(oldPath) }).toSet()
        writeAutomaticBackupSafely(context, affected)
        notifyTagChange(affected)
    }

    suspend fun resetTagsForPathTree(context: Context, path: String) {
        val childPattern = "$path/%"
        val dao = getDao(context)
        val affected = dao.getTaggedPathsForTree(path, childPattern).toSet()
        if (affected.isEmpty()) return
        dao.deleteVideoTagRefsForTree(path, childPattern)
        writeAutomaticBackupSafely(context, affected)
        notifyTagChange(affected)
    }

    suspend fun getTagCountForVideoPath(context: Context, videoPath: String): Int {
        return getDao(context).getTagCountForVideoPath(videoPath)
    }

    suspend fun getLastAutomaticBackupAt(context: Context): Long {
        if (TagSyncBridge.enabled(context)) return TagSyncBridge.checked(context) {
            TagSyncBridge.read(context, "backup", TagRpc(), TagRemoteBackup::class.java).single().updatedAt
        }
        val backupFiles = runCatching { findLatestUsableAutomaticBackup(context) }.getOrNull() ?: return 0L
        return backupFiles.updatedAt
    }

    suspend fun hasAutomaticBackup(context: Context): Boolean {
        if (TagSyncBridge.enabled(context)) return TagSyncBridge.checked(context) {
            TagSyncBridge.read(context, "backup", TagRpc(), TagRemoteBackup::class.java).single().exists
        }
        return runCatching { findLatestUsableAutomaticBackup(context) }.getOrNull() != null
    }

    suspend fun shouldOfferAutomaticImport(context: Context): Boolean {
        if (TagSyncBridge.enabled(context)) return false
        if (context.getSharedPreferences(SETTINGS_PREFS, 0).getBoolean(KEY_AUTO_BACKUP_PENDING, false)) return false
        val backupEntry = runCatching { findLatestUsableAutomaticBackup(context) }.getOrNull() ?: return false
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
        val incremental = TagBackupJournal(context, AUTO_BACKUP_RELATIVE_PATH + "Incremental/").load()
        val hasMultipart = listOf(AUTO_BACKUP_MANIFEST_FILE_NAME, AUTO_BACKUP_TAGS_FILE_NAME, AUTO_BACKUP_REFS_FILE_NAME)
            .any { findAutomaticBackupFileEntry(context, it, AUTO_BACKUP_RELATIVE_PATH) != null }
        check(incremental != null || !hasMultipart || findUsableAutomaticBackupFiles(context) != null) { "automatic_backup_incomplete_generation" }
        val entry = findLatestUsableAutomaticBackup(context) ?: error("automatic_backup_not_found")
        val payload = when (entry) {
            is AutomaticBackupSource.Incremental -> entry.payload
            is AutomaticBackupSource.MultiFile -> readAutomaticBackupPayload(context, entry.files)
            is AutomaticBackupSource.LegacySingleFile -> readPayloadFromUri(context, entry.entry.uri)
        }
        val result = importBackupPayload(context, payload)
        writeAutomaticBackupSafely(context)
        result
    }

    private val backupStateLock = Any()
    private var backupRevision = 0L
    private var backupFailureReported = false
    private var forceFullBackup = false
    private val automaticBackupMutex = Mutex()

    private fun markBackupPending(context: Context) = synchronized(backupStateLock) {
        if (!automaticBackupPending && context.getSharedPreferences(SETTINGS_PREFS, 0)
                .getBoolean(KEY_AUTO_BACKUP_PENDING, false)) forceFullBackup = true
        backupRevision++
        automaticBackupPending = true
        check(context.getSharedPreferences(SETTINGS_PREFS, 0).edit()
            .putBoolean(KEY_AUTO_BACKUP_PENDING, true).commit())
    }

    /** Each completed edit waits for its backup attempt; there is no five-minute window. */
    private suspend fun writeAutomaticBackupSafely(context: Context, paths: Set<String>? = null): Unit = withContext(NonCancellable + Dispatchers.IO) {
        if (TagSyncBridge.enabled(context)) {
            TagSyncBridge.checked(context) {
                TagSyncBridge.write(context, "flushBackup", TagRpc(), Boolean::class.javaObjectType)
            }
            return@withContext
        }
        val appContext = context.applicationContext
        try {
            markBackupPending(appContext)
            writeAutomaticBackup(appContext, paths)
        } catch (failure: Exception) {
            android.util.Log.e("VideoTagBackup", "Automatic TAG backup failed; database retained", failure)
            val report = synchronized(backupStateLock) {
                (!backupFailureReported).also { backupFailureReported = true }
            }
            if (report) android.os.Handler(android.os.Looper.getMainLooper()).post {
                SortRowMessageCenter.showError(appContext.getString(com.nkls.nekovideo.R.string.tags_backup_save_failed))
            }
            scheduleBackupRetry(appContext)
        }
    }

    private fun scheduleBackupRetry(context: Context): Unit = synchronized(backupStateLock) {
        if (automaticBackupJob?.isActive == true) return@synchronized
        automaticBackupJob = automaticBackupScope.launch {
            delay(AUTO_BACKUP_RETRY_MS)
            synchronized(backupStateLock) { automaticBackupJob = null }
            if (context.getSharedPreferences(SETTINGS_PREFS, 0).getBoolean(KEY_AUTO_BACKUP_PENDING, false)) {
                writeAutomaticBackupSafely(context)
            }
        }
    }

    /** A failed backup remains pending across process death and app updates. */
    fun resumePendingAutomaticBackup(context: Context) {
        if (TagSyncBridge.enabled(context) || !context.getSharedPreferences(SETTINGS_PREFS, 0)
                .getBoolean(KEY_AUTO_BACKUP_PENDING, false)) return
        automaticBackupScope.launch { writeAutomaticBackupSafely(context.applicationContext) }
    }

    fun flushAutomaticBackupNow(context: Context) {
        if (TagSyncBridge.enabled(context)) { stopLocalAutomaticBackup(); return }
        if (!automaticBackupPending && !context.getSharedPreferences(SETTINGS_PREFS, 0)
                .getBoolean(KEY_AUTO_BACKUP_PENDING, false)) return
        runBlocking(Dispatchers.IO) { writeAutomaticBackupSafely(context.applicationContext) }
    }

    private var backupJournal: TagBackupJournal? = null
    private fun journal(context: Context): TagBackupJournal = backupJournal ?: TagBackupJournal(
        context.applicationContext, AUTO_BACKUP_RELATIVE_PATH + "Incremental/"
    ).also { backupJournal = it }

    private suspend fun writeAutomaticBackup(context: Context, paths: Set<String>? = null) = automaticBackupMutex.withLock {
        if (TagSyncBridge.enabled(context)) return@withLock
        try {
            val revision = synchronized(backupStateLock) { backupRevision }
            val target = journal(context)
            val current = runCatching { target.current() }.getOrNull()
            val payload = if (paths != null && !forceFullBackup && current != null && current.changes < 64) {
                val changed = buildBackupPayload(context, paths)
                if (changed.tags.toSet() == current.data.tags.toSet()) {
                    target.savePaths(paths, changed)
                    changed
                } else buildBackupPayload(context).also { target.saveFull(it) }
            } else buildBackupPayload(context).also { target.saveFull(it) }
            synchronized(backupStateLock) {
                if (revision == backupRevision) {
                    check(context.getSharedPreferences(SETTINGS_PREFS, 0).edit()
                        .putLong(KEY_LAST_AUTO_BACKUP_AT, payload.exportedAt)
                        .putBoolean(KEY_AUTO_BACKUP_PENDING, false).commit())
                    automaticBackupPending = false
                    backupFailureReported = false
                }
            }
            forceFullBackup = false
        } catch (failure: Exception) {
            // The next successful attempt must include earlier failed paths as well.
            forceFullBackup = true
            throw failure
        }
    }

    internal suspend fun flushCanonicalBackup(context: Context) = withContext(NonCancellable + Dispatchers.IO) {
        // Remote batches already persisted each changed set; do not rewrite the full base again.
        if (automaticBackupPending || context.getSharedPreferences(SETTINGS_PREFS, 0)
                .getBoolean(KEY_AUTO_BACKUP_PENDING, false)) {
            markBackupPending(context)
            writeAutomaticBackup(context)
        }
    }

    internal fun stopLocalAutomaticBackup() = synchronized(backupStateLock) {
        automaticBackupJob?.cancel()
        automaticBackupJob = null
        automaticBackupPending = false
    }

    private fun readAutomaticBackupPayload(context: Context, files: AutomaticBackupFiles): TagBackupPayload {
        val manifest = readJsonFromUri(context, files.manifest.uri, AutomaticBackupManifest::class.java)
        val tagsPayload = readJsonFromUri(context, files.tags.uri, AutomaticBackupTagsPayload::class.java)
        val refsPayload = readJsonFromUri(context, files.refs.uri, AutomaticBackupRefsPayload::class.java)

        check(manifest.version == BACKUP_VERSION && tagsPayload.version == BACKUP_VERSION &&
            refsPayload.version == BACKUP_VERSION) { "automatic_backup_version_invalid" }
        check(manifest.exportedAt == tagsPayload.updatedAt && manifest.exportedAt == refsPayload.updatedAt) {
            "automatic_backup_incomplete_generation"
        }
        check(tagsPayload.tags.map { it.id }.distinct().size == tagsPayload.tags.size) {
            "automatic_backup_duplicate_tag_ids"
        }
        val knownIds = tagsPayload.tags.mapTo(HashSet()) { it.id }
        check(refsPayload.refs.all { ref -> ref.videoPath.isNotBlank() && ref.tagIds.all { it in knownIds } }) {
            "automatic_backup_unknown_tag_reference"
        }

        if (!manifest.files.contains(AUTO_BACKUP_TAGS_FILE_NAME) || !manifest.files.contains(AUTO_BACKUP_REFS_FILE_NAME)) {
            error("automatic_backup_manifest_invalid")
        }

        val tagsById = tagsPayload.tags.associateBy { it.id }
        return TagBackupPayload(
            version = manifest.version,
            exportedAt = manifest.exportedAt,
            tags = tagsPayload.tags.map {
                TagBackupTag(
                    name = it.name,
                    scope = it.scope,
                    createdAt = it.createdAt
                )
            },
            refs = refsPayload.refs.flatMap { ref ->
                val videoPath = ref.videoPath.trim()
                if (videoPath.isEmpty()) {
                    emptyList()
                } else {
                    ref.tagIds.mapNotNull { tagId ->
                        val tag = tagsById[tagId] ?: return@mapNotNull null
                        TagBackupRef(
                            videoPath = videoPath,
                            tagName = tag.name,
                            scope = tag.scope
                        )
                    }
                }
            }
        )
    }

    private suspend fun buildBackupPayload(context: Context, paths: Set<String>? = null): TagBackupPayload {
        val dao = getDao(context)
        val (tags, refs) = if (TagSyncBridge.enabled(context)) {
            dao.getAllTagsAllScopes() to dao.getAllVideoTagRefs()
        } else getDatabase(context).withTransaction {
            dao.getAllTagsAllScopes() to if (paths == null) dao.getAllVideoTagRefs()
            else paths.toList().chunked(500).flatMap { dao.getVideoTagRefsForPaths(it) }
        }
        val tagIndex = tags.associateBy { it.id }

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
        check(!TagSyncBridge.enabled(context)) { context.getString(com.nkls.nekovideo.R.string.tag_sync_restore_in_normal) }
        val database = getDatabase(context)
        val result = database.withTransaction {
            val dao = database.videoTagDao()

            // Keep the previous database before a restore replaces either scope.
            val before = buildBackupPayload(context)
            val recoveryFolder = File(context.filesDir, "tag-restore-recovery").apply { check(mkdirs() || isDirectory) }
            val recovery = AtomicFile(File(recoveryFolder, "before-import-${java.util.UUID.randomUUID()}.json"))
            val stream = recovery.startWrite()
            try {
                stream.write(gson.toJson(before).toByteArray(Charsets.UTF_8))
                recovery.finishWrite(stream)
            } catch (failure: Exception) {
                recovery.failWrite(stream)
                throw failure
            }

            val normalizedTags = buildList {
                payload.tags.forEach { tag ->
                    normalizeBackupTag(tag.name, tag.scope, tag.createdAt)?.let(::add)
                }
                payload.refs.forEach { ref ->
                    normalizeBackupTag(ref.tagName, ref.scope, System.currentTimeMillis())?.let(::add)
                }
            }.distinctBy { backupTagKey(it.name, it.scope) }

            // Import is a restore: remove existing tags and their refs in both scopes first.
            dao.deleteAllTags()

            val tagIdByKey = mutableMapOf<String, Long>()
            var importedTags = 0
            normalizedTags.forEach { tag ->
                val key = backupTagKey(tag.name, tag.scope)
                val id = dao.insertTag(
                    TagEntity(
                        name = tag.name,
                        scope = tag.scope,
                        createdAt = tag.createdAt
                    )
                )
                tagIdByKey[key] = id
                importedTags++
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
                importedTags = importedTags,
                restoredRefs = refsToInsert.size
            )
        }
        notifyTagChange()
        return result
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
        writeJsonToUri(context, uri, payload)
    }

    private fun readPayloadFromUri(context: Context, uri: Uri): TagBackupPayload {
        return parseBackupPayload(readTextFromUri(context, uri))
    }

    private fun writeJsonToUri(context: Context, uri: Uri, payload: Any) {
        val outputStream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("backup_output_unavailable")

        outputStream.use { stream ->
            OutputStreamWriter(stream).use { writer ->
                gson.toJson(payload, writer)
            }
        }
    }

    private fun readTextFromUri(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("backup_input_unavailable")

        inputStream.use { stream ->
            InputStreamReader(stream).use { reader ->
                return reader.readText()
            }
        }
    }

    private fun <T> readJsonFromUri(context: Context, uri: Uri, type: Class<T>): T {
        val content = readTextFromUri(context, uri).trim()
        if (content.isEmpty()) error("backup_payload_empty")
        return gson.fromJson(content, type) ?: error("backup_payload_invalid")
    }

    private fun parseBackupPayload(rawContent: String): TagBackupPayload {
        val content = rawContent.trim()
        if (content.isEmpty()) error("backup_payload_empty")

        return runCatching { parseBackupPayloadStrict(content) }
            .getOrElse {
                recoverBackupPayload(content) ?: throw it
            }
    }

    private fun parseBackupPayloadStrict(content: String): TagBackupPayload {
        val reader = JsonReader(StringReader(content)).apply {
            isLenient = false
        }
        val payload = gson.fromJson<TagBackupPayload>(reader, TagBackupPayload::class.java)
            ?: error("backup_payload_invalid")
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            error("backup_payload_extra_data")
        }
        return payload
    }

    private fun recoverBackupPayload(content: String): TagBackupPayload? {
        val candidates = mutableListOf<TagBackupPayload>()
        var depth = 0
        var startIndex = -1
        var inString = false
        var escaping = false

        content.forEachIndexed { index, char ->
            if (inString) {
                if (escaping) {
                    escaping = false
                } else if (char == '\\') {
                    escaping = true
                } else if (char == '"') {
                    inString = false
                }
                return@forEachIndexed
            }

            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) startIndex = index
                    depth++
                }
                '}' -> {
                    if (depth == 0) return@forEachIndexed
                    depth--
                    if (depth == 0 && startIndex >= 0) {
                        val candidate = content.substring(startIndex, index + 1)
                        runCatching { parseBackupPayloadStrict(candidate) }
                            .onSuccess { candidates += it }
                        startIndex = -1
                    }
                }
            }
        }

        return candidates.lastOrNull()
    }

    private fun findAutomaticBackupFileEntry(
        context: Context,
        fileName: String,
        relativePath: String
    ): AutomaticBackupEntry? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(fileName, relativePath)

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

    private sealed interface AutomaticBackupSource {
        val updatedAt: Long
        data class Incremental(val payload: TagBackupPayload) : AutomaticBackupSource {
            override val updatedAt: Long = payload.exportedAt
        }

        data class MultiFile(val files: AutomaticBackupFiles) : AutomaticBackupSource {
            override val updatedAt: Long = files.updatedAt
        }

        data class LegacySingleFile(val entry: AutomaticBackupEntry) : AutomaticBackupSource {
            override val updatedAt: Long = entry.updatedAt
        }
    }

    private suspend fun findLatestUsableAutomaticBackup(context: Context): AutomaticBackupSource? {
        TagBackupJournal(context, AUTO_BACKUP_RELATIVE_PATH + "Incremental/").load()?.let {
            return AutomaticBackupSource.Incremental(it.data)
        }
        val candidates = buildList {
            findUsableAutomaticBackupFiles(context)?.let {
                add(AutomaticBackupSource.MultiFile(it))
            }
            findUsableLegacyAutomaticBackupEntry(context)?.let {
                add(AutomaticBackupSource.LegacySingleFile(it))
            }
        }

        return candidates.maxByOrNull { it.updatedAt }
    }

    private suspend fun findUsableAutomaticBackupFiles(context: Context): AutomaticBackupFiles? {
        val manifest = findAutomaticBackupFileEntry(context, AUTO_BACKUP_MANIFEST_FILE_NAME, AUTO_BACKUP_RELATIVE_PATH)
            ?: return null
        val tags = findAutomaticBackupFileEntry(context, AUTO_BACKUP_TAGS_FILE_NAME, AUTO_BACKUP_RELATIVE_PATH)
            ?: return null
        val refs = findAutomaticBackupFileEntry(context, AUTO_BACKUP_REFS_FILE_NAME, AUTO_BACKUP_RELATIVE_PATH)
            ?: return null

        val files = AutomaticBackupFiles(manifest = manifest, tags = tags, refs = refs)
        val payload = runCatching { readAutomaticBackupPayload(context, files) }.getOrNull() ?: return null
        return files.copy(manifest = manifest.copy(updatedAt = payload.exportedAt),
            tags = tags.copy(updatedAt = payload.exportedAt), refs = refs.copy(updatedAt = payload.exportedAt))
    }

    private suspend fun findUsableLegacyAutomaticBackupEntry(context: Context): AutomaticBackupEntry? {
        val entry = findAutomaticBackupFileEntry(
            context,
            AUTO_BACKUP_LEGACY_FILE_NAME,
            AUTO_BACKUP_LEGACY_RELATIVE_PATH
        ) ?: return null

        val payload = runCatching { readPayloadFromUri(context, entry.uri) }.getOrNull() ?: return null
        return entry.copy(updatedAt = payload.exportedAt)
    }

    private fun getStoredAutomaticBackupSyncAt(context: Context): Long {
        return context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AUTO_BACKUP_AT, 0L)
    }

}
