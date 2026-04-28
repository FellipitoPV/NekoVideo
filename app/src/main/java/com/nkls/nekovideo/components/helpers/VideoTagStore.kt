package com.nkls.nekovideo.components.helpers

import android.content.Context
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
import androidx.sqlite.db.SupportSQLiteDatabase

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("DELETE FROM video_tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Query("SELECT r.tagId FROM video_tag_refs r INNER JOIN video_tags t ON t.id = r.tagId WHERE r.videoPath IN (:videoPaths) AND t.scope = :scope GROUP BY r.tagId HAVING COUNT(DISTINCT r.videoPath) = :videoCount")
    suspend fun getCommonTagIds(videoPaths: List<String>, videoCount: Int, scope: TagScope): List<Long>

    @Query("SELECT DISTINCT r.videoPath FROM video_tag_refs r INNER JOIN video_tags t ON t.id = r.tagId WHERE r.tagId IN (:tagIds) AND t.scope = :scope")
    suspend fun getVideoPathsForAnyTagIds(tagIds: List<Long>, scope: TagScope): List<String>

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
}

@Database(entities = [TagEntity::class, VideoTagCrossRef::class], version = 2, exportSchema = false)
abstract class VideoTagDatabase : RoomDatabase() {
    abstract fun videoTagDao(): VideoTagDao
}

object VideoTagStore {
    @Volatile
    private var database: VideoTagDatabase? = null

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
        return Result.success(TagEntity(id = id, name = name, scope = scope))
    }

    suspend fun deleteTag(context: Context, tagId: Long) {
        getDatabase(context).videoTagDao().deleteTag(tagId)
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
    }

    suspend fun getVideoPathsForAnyTagIds(context: Context, tagIds: Set<Long>, scope: TagScope): Set<String> {
        if (tagIds.isEmpty()) return emptySet()
        return getDatabase(context)
            .videoTagDao()
            .getVideoPathsForAnyTagIds(tagIds.toList(), scope)
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
    }

    suspend fun resetTagsForPathTree(context: Context, path: String) {
        val childPattern = "$path/%"
        getDatabase(context).videoTagDao().deleteVideoTagRefsForTree(path, childPattern)
    }

    suspend fun getTagCountForVideoPath(context: Context, videoPath: String): Int {
        return getDatabase(context).videoTagDao().getTagCountForVideoPath(videoPath)
    }
}
