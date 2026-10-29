package com.nkls.nekovideo.components.helpers

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** All mutations are confined to the disposable TAG host, never either real app. */
@RunWith(AndroidJUnit4::class)
class TagBackupDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "BackupTest-${UUID.randomUUID()}"
    private val path get() = "/nonexistent/$prefix/video.mp4"
    private val prefs get() = context.getSharedPreferences("nekovideo_settings", 0)
    private val relativePath = "Documents/NekoVideo/Backup/Testing/TagSyncHost/Incremental/"

    @Before fun isolate() {
        assumeTrue(context.packageName == "com.nkls.nekovideo.tagsynchost" &&
            InstrumentationRegistry.getArguments().getString("tagBackupTest") == "true")
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (context.packageName != "com.nkls.nekovideo.tagsynchost") return@runBlocking
        VideoTagStore.localDao(context).getAllTagsAllScopes().filter { it.name.startsWith(prefix) }
            .forEach { VideoTagStore.localDao(context).deleteTag(it.id) }
        prefs.edit().putBoolean("tags_auto_backup_pending", true).commit()
        VideoTagStore.flushCanonicalBackup(context)
    }

    private fun uri(name: String): Uri {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(collection, arrayOf("_id"), "relative_path = ? AND _display_name = ?",
            arrayOf(relativePath, name), null)!!.use {
            check(it.moveToFirst())
            return ContentUris.withAppendedId(collection, it.getLong(0))
        }
    }
    private fun json(name: String) = context.contentResolver.openInputStream(uri(name))!!.use {
        JsonParser.parseString(it.bufferedReader().readText()).asJsonObject
    }
    private fun savedLinks(): Set<Pair<String, String>> =
        checkNotNull(TagBackupJournal(context, relativePath).load()).data.refs.map { it.videoPath to it.tagName }.toSet()

    @Test fun editsAreBackedUpBeforeReturningIncludingConcurrentAndPrivateChanges() = runBlocking<Unit> {
        val normal = VideoTagStore.createTag(context, "$prefix-normal", TagScope.NORMAL).getOrThrow()
        val private = VideoTagStore.createTag(context, "$prefix-private", TagScope.PRIVATE).getOrThrow()
        VideoTagStore.addTagsToVideos(context, listOf(path), setOf(normal.id, private.id))
        assertTrue(savedLinks().containsAll(setOf(path to normal.name, path to private.name)))
        // A second edit in the same five-minute interval must already be on disk.
        VideoTagStore.syncCommonTagsForVideos(context, listOf(path), setOf(normal.id, private.id), setOf(private.id))
        assertFalse(savedLinks().contains(path to normal.name))
        assertTrue(savedLinks().contains(path to private.name))
        coroutineScope { (1..12).map { n -> async(Dispatchers.IO) {
            VideoTagStore.addTagsToVideos(context, listOf("$path-$n"), setOf(private.id))
        } }.awaitAll() }
        val saved = savedLinks()
        (1..12).forEach { assertTrue(saved.contains("$path-$it" to private.name)) }
        assertFalse(prefs.getBoolean("tags_auto_backup_pending", true))
        assertFalse(VideoTagStore.shouldOfferAutomaticImport(context))
    }

    @Test fun changingOneVideoWritesOnlyItsSmallRecord() = runBlocking<Unit> {
        val first = VideoTagStore.createTag(context, "$prefix-first", TagScope.PRIVATE).getOrThrow()
        val second = VideoTagStore.createTag(context, "$prefix-second", TagScope.PRIVATE).getOrThrow()
        val videos = (1..2000).map { "$path-$it" }
        VideoTagStore.addTagsToVideos(context, videos, setOf(first.id))
        // Consolidate once so that the base contains the 2,000 unchanged references.
        VideoTagStore.createTag(context, "$prefix-checkpoint", TagScope.NORMAL).getOrThrow()
        val before = checkNotNull(TagBackupJournal(context, relativePath).load())
        val checkpoint = json(before.checkpointName).toString()
        val start = android.os.SystemClock.elapsedRealtime()
        VideoTagStore.syncCommonTagsForVideos(context, listOf(videos.first()), setOf(first.id), setOf(second.id))
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        val after = checkNotNull(TagBackupJournal(context, relativePath).load())
        assertEquals(before.revision + 1, after.revision)
        assertEquals(checkpoint, json(before.checkpointName).toString())
        val change = json("change-${after.revision}.json").toString()
        assertTrue("One video must not rewrite 2,000 references", change.length < checkpoint.length / 20)
        assertEquals(2000, after.data.refs.count { it.videoPath in videos })
        assertTrue(savedLinks().contains(videos.first() to second.name))
        assertFalse(savedLinks().contains(videos.first() to first.name))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Partial backup: baseBytes=${checkpoint.length}, changeBytes=${change.length}, elapsedMs=$elapsed\n")
        })
    }

    @Test fun incompleteGenerationCannotReplaceTheDatabase() = runBlocking<Unit> {
        val tag = VideoTagStore.createTag(context, "$prefix-private", TagScope.PRIVATE).getOrThrow()
        VideoTagStore.addTagsToVideos(context, listOf(path), setOf(tag.id))
        val latest = checkNotNull(TagBackupJournal(context, relativePath).load())
        val name = "change-${latest.revision}.json"
        val corrupt = json(name)
        corrupt.addProperty("sha256", "broken")
        context.contentResolver.openOutputStream(uri(name), "wt")!!.use {
            it.write(corrupt.toString().toByteArray())
        }
        assertTrue(VideoTagStore.importLatestAutomaticBackup(context).isFailure)
        assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, path))
        // A subsequent complete checkpoint repairs the backup using the intact database.
        VideoTagStore.createTag(context, "$prefix-repair", TagScope.NORMAL).getOrThrow()
        assertTrue(savedLinks().contains(path to tag.name))
    }

    @Test fun restoreKeepsACopyOfTheDatabaseItReplaces() = runBlocking<Unit> {
        val first = VideoTagStore.createTag(context, "$prefix-first", TagScope.PRIVATE).getOrThrow()
        VideoTagStore.addTagsToVideos(context, listOf(path), setOf(first.id))
        val export = File(context.cacheDir, "$prefix.json")
        try {
            VideoTagStore.exportBackupToUri(context, Uri.fromFile(export)).getOrThrow()
            val second = VideoTagStore.createTag(context, "$prefix-second", TagScope.PRIVATE).getOrThrow()
            VideoTagStore.addTagsToVideos(context, listOf(path), setOf(second.id))
            val folder = File(context.filesDir, "tag-restore-recovery")
            val previous = folder.listFiles().orEmpty().mapTo(HashSet()) { it.name }
            VideoTagStore.importBackupFromUri(context, Uri.fromFile(export)).getOrThrow()
            assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, path))
            val recovery = folder.listFiles().orEmpty().single { it.name !in previous }
            val data = JsonParser.parseString(recovery.readText()).asJsonObject
            assertTrue(data.getAsJsonArray("refs").any {
                it.asJsonObject["videoPath"].asString == path && it.asJsonObject["tagName"].asString == second.name
            })
        } finally { export.delete() }
    }

    @Test fun automaticImportReplaysRemovalMoveRenameAndBothScopes() = runBlocking<Unit> {
        val normal = VideoTagStore.createTag(context, "$prefix-normal", TagScope.NORMAL).getOrThrow()
        val private = VideoTagStore.createTag(context, "$prefix-private", TagScope.PRIVATE).getOrThrow()
        VideoTagStore.addTagsToVideos(context, listOf(path, "$path-other", "$path-deleted"), setOf(normal.id))
        VideoTagStore.addTagsToVideos(context, listOf(path), setOf(private.id))
        VideoTagStore.moveTagsForPath(context, path, "$path-moved")
        VideoTagStore.syncCommonTagsForVideos(context, listOf("$path-deleted"), setOf(normal.id), emptySet())
        VideoTagStore.renameTag(context, normal.id, "$prefix-renamed", TagScope.NORMAL).getOrThrow()
        VideoTagStore.syncCommonTagsForVideos(context, listOf("$path-other"), setOf(normal.id), setOf(private.id))
        val expected = savedLinks()
        // Only the disposable database is cleared, leaving the saved backup to reconstruct it.
        VideoTagStore.localDao(context).deleteAllTags()
        VideoTagStore.importLatestAutomaticBackup(context).getOrThrow()
        assertEquals(expected, savedLinks())
        assertEquals(0, VideoTagStore.getTagCountForVideoPath(context, path))
        assertEquals(0, VideoTagStore.getTagCountForVideoPath(context, "$path-deleted"))
        assertEquals(2, VideoTagStore.getTagCountForVideoPath(context, "$path-moved"))
        assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, "$path-other"))
        assertTrue(VideoTagStore.getAllTags(context, TagScope.NORMAL).any { it.name == "$prefix-renamed" })
    }

    @Test fun consolidationAndAnUnpublishedFileDoNotLoseAcknowledgedEdits() = runBlocking<Unit> {
        val tag = VideoTagStore.createTag(context, "$prefix-private", TagScope.PRIVATE).getOrThrow()
        repeat(70) { n -> VideoTagStore.addTagsToVideos(context, listOf("$path-$n"), setOf(tag.id)) }
        val saved = checkNotNull(TagBackupJournal(context, relativePath).load())
        assertEquals(70, saved.data.refs.count { it.videoPath.startsWith(path) })
        assertTrue(saved.checkpointRevision > 1)
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = context.contentResolver.insert(collection, android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "change-${saved.revision + 1}.json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            context.contentResolver.openOutputStream(pending, "wt")!!.use { it.write("{interrupted".toByteArray()) }
            assertEquals(saved, TagBackupJournal(context, relativePath).load())
        } finally { context.contentResolver.delete(pending, null, null) }
    }

    @Test fun pendingBackupOnDiskIsRetriedOnTheNextStartup() = runBlocking<Unit> {
        val dao = VideoTagStore.localDao(context)
        val id = dao.insertTag(TagEntity(name = "$prefix-pending", scope = TagScope.PRIVATE))
        dao.insertVideoTagRefs(listOf(VideoTagCrossRef(path, id)))
        VideoTagStore.stopLocalAutomaticBackup()
        assertTrue(prefs.edit().putBoolean("tags_auto_backup_pending", true).commit())
        VideoTagStore.resumePendingAutomaticBackup(context)
        withTimeout(10_000) {
            while (prefs.getBoolean("tags_auto_backup_pending", true)) delay(20)
        }
        assertTrue(savedLinks().contains(path to "$prefix-pending"))
    }

    @Test fun aFailedBackupCannotLoseEarlierPathsWhenTheNextEditSucceeds() = runBlocking<Unit> {
        val tag = VideoTagStore.createTag(context, "$prefix-private", TagScope.PRIVATE).getOrThrow()
        val dao = VideoTagStore.localDao(context)
        // An invalid fixture forces backup validation to fail, without any permission changes.
        val invalid = dao.insertTag(TagEntity(name = "", scope = TagScope.NORMAL))
        try {
            VideoTagStore.addTagsToVideos(context, listOf(path), setOf(tag.id))
            assertTrue(prefs.getBoolean("tags_auto_backup_pending", false))
            assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, path))
        } finally { dao.deleteTag(invalid) }
        VideoTagStore.addTagsToVideos(context, listOf("$path-next"), setOf(tag.id))
        assertFalse(prefs.getBoolean("tags_auto_backup_pending", true))
        assertTrue(savedLinks().containsAll(setOf(path to tag.name, "$path-next" to tag.name)))
    }
}
