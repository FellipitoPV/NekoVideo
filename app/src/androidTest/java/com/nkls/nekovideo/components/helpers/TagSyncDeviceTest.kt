package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Remote writes are hard-limited to the disposable .tagsynchost install, never the release. */
@RunWith(AndroidJUnit4::class)
class TagSyncDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val prefix get() = "TagSyncTest-${checkNotNull(args.getString("tagSyncToken"))}"
    private val path get() = "/storage/emulated/0/Memes/$prefix/video.mp4"
    private var wasEnabled = false
    @Before fun isolate() {
        assumeTrue(context.packageName == TagSyncBridge.CLIENT && args.getString("tagSyncTest") == "true")
        require(args.getString("tagSyncToken").orEmpty().matches(Regex("[a-z0-9-]{1,50}")))
        TagSyncBridge.localTestMode = false
        wasEnabled = TagSyncBridge.enabled(context)
        TagSyncBridge.disconnect(context)
        TagSyncBridge.testHost = "com.nkls.nekovideo.tagsynchost"
        assertEquals("com.nkls.nekovideo.tagsynchost.tagsync", TagSyncBridge.uri().authority)
    }
    @After fun restore() {
        TagSyncBridge.testHost = null
        TagSyncBridge.disconnect(context)
        if (wasEnabled) context.getSharedPreferences("tag_sync_connection", 0).edit().putBoolean("enabled", true).commit()
        TagSyncBridge.attach(context)
    }
    private fun approve() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.startActivityForResult(TagSyncBridge.authorizationIntent(), 741) }
            if (args.getString("externalApproval") == "true") {
                runBlocking { withTimeout(60_000) {
                    while (true) {
                        try { TagSyncBridge.read(context, "change", TagRpc(), TagRemoteChange::class.java); break }
                        catch (_: SecurityException) { delay(100) }
                    }
                } }
                return@use
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var clicked = false
            while (!clicked && android.os.SystemClock.elapsedRealtime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val nodes = listOf(context.getString(R.string.tag_sync_allow), "Permitir conexão", "Allow connection", "Permitir conexión")
                    .flatMap { root?.findAccessibilityNodeInfosByText(it).orEmpty() }
                for (node in nodes) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if (target != null) {
                        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            val bounds = android.graphics.Rect()
                            target.getBoundsInScreen(bounds)
                            instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                                .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
                        }
                        clicked = true; break
                    }
                }
                if (!clicked) Thread.sleep(100)
            }
            assertTrue("The real host consent sheet must be approved", clicked)
            runBlocking { withTimeout(5_000) {
                while (true) {
                    try { TagSyncBridge.read(context, "change", TagRpc(), TagRemoteChange::class.java); break }
                    catch (_: SecurityException) { delay(50) }
                }
            } }
            instrumentation.waitForIdleSync()
        }
    }
    @Test fun authorizeAndUseCanonicalDatabaseWithoutImportingDebugTags() = runBlocking<Unit> {
        val local = VideoTagStore.localDao(context)
        val sentinel = local.insertTag(TagEntity(name = "$prefix-debug-local", scope = TagScope.NORMAL))
        try {
            if (args.getString("alreadyAuthorized") != "true") {
                try {
                    TagSyncBridge.read(context, "tags", TagRpc(), TagEntity::class.java)
                    fail("Unapproved client must be rejected")
                } catch (_: SecurityException) { }
                approve()
            }
            withTimeout(5_000) {
                while (true) {
                    try { TagSyncBridge.connect(context); break }
                    catch (_: SecurityException) { delay(50) }
                }
            }
            assertTrue(VideoTagStore.getAllTags(context, TagScope.NORMAL).none { it.name == "$prefix-debug-local" })
            val normal = VideoTagStore.createTag(context, "$prefix-normal", TagScope.NORMAL).getOrThrow()
            val private = VideoTagStore.createTag(context, "$prefix-private", TagScope.PRIVATE).getOrThrow()
            VideoTagStore.addTagsToVideos(context, listOf(path), setOf(normal.id))
            VideoTagStore.addTagsToVideos(context, listOf("$path-private"), setOf(private.id))
            val bulk = (0 until 1200).map { "/storage/emulated/0/Memes/$prefix/bulk$it.mp4" }
            VideoTagStore.addTagsToVideos(context, bulk, setOf(normal.id))
            assertEquals(setOf(normal.id), VideoTagStore.getCommonTagIds(context, bulk, TagScope.NORMAL))
            assertTrue(VideoTagStore.getTagCountsForVideoPaths(context, bulk).values.all { it == 1 })
            assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, path))
            val tagged = VideoTagStore.getVideoPathsForAnyTagIds(context, setOf(normal.id), TagScope.NORMAL)
            assertEquals(1201, tagged.size)
            assertTrue(tagged.contains(path) && tagged.containsAll(bulk))
            assertTrue(local.getAllTagsAllScopes().none { it.id == normal.id && it.name == normal.name })
            assertTrue(VideoTagStore.renameTag(context, normal.id, "$prefix-debug-renamed", TagScope.NORMAL).isSuccess)
            assertEquals(normal.id, VideoTagStore.getAllTags(context, TagScope.NORMAL).single { it.name == "$prefix-debug-renamed" }.id)
            assertTrue(VideoTagStore.hasAutomaticBackup(context))
            assertTrue(VideoTagStore.getLastAutomaticBackupAt(context) > 0L)
            val backup = context.cacheDir.resolve("tag-sync-$prefix.json").apply { writeText("{}") }
            try {
                VideoTagStore.exportBackupToUri(context, android.net.Uri.fromFile(backup)).getOrThrow()
                val rejected = VideoTagStore.importBackupFromUri(context, android.net.Uri.fromFile(backup))
                assertEquals(context.getString(R.string.tag_sync_restore_in_normal), rejected.exceptionOrNull()?.message)
            }
            finally { backup.delete() }
        } finally { local.deleteTag(sentinel) }
    }
    @Test fun observesNormalChangesAndWritesBackToTheSameIds() = runBlocking<Unit> {
        if (args.getString("waitForHostChange") == "true") withTimeout(60_000) {
            while (true) {
                val version = VideoTagStore.tagChangeEvent.value
                val tags = VideoTagStore.getAllTags(context, TagScope.NORMAL)
                if (tags.any { it.name == "$prefix-host-created" }) break
                VideoTagStore.tagChangeEvent.first { it > version }
            }
        }
        val normal = VideoTagStore.getAllTags(context, TagScope.NORMAL).single { it.name == "$prefix-host-renamed" }
        assertTrue(VideoTagStore.getAllTags(context, TagScope.NORMAL).any { it.name == "$prefix-host-created" })
        val private = VideoTagStore.getAllTags(context, TagScope.PRIVATE).single { it.name == "$prefix-private" }
        assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, "$path-private"))
        VideoTagStore.syncCommonTagsForVideos(context, listOf(path), setOf(normal.id), setOf(normal.id, private.id))
        assertEquals(2, VideoTagStore.getTagCountsForVideoPaths(context, listOf(path))[path])
        VideoTagStore.moveTagsForPath(context, path, "$path-moved")
        assertEquals(0, VideoTagStore.getTagCountForVideoPath(context, path))
        assertEquals(2, VideoTagStore.getTagCountForVideoPath(context, "$path-moved"))
    }
    @Test fun revokedHostRejectsReadsAndNeverUsesLocalDatabase() = runBlocking<Unit> {
        val before = VideoTagStore.localDao(context).getAllTagsAllScopes()
        try {
            VideoTagStore.getAllTags(context, TagScope.NORMAL)
            fail("Revoked connection must fail closed")
        } catch (_: CancellationException) { }
        assertEquals(before, VideoTagStore.localDao(context).getAllTagsAllScopes())
    }
}

@RunWith(AndroidJUnit4::class)
class TagSyncHostDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val prefix get() = "TagSyncTest-${checkNotNull(args.getString("tagSyncToken"))}"
    private val path get() = "/storage/emulated/0/Memes/$prefix/video.mp4"
    @Before fun requireDisposableHost() {
        assumeTrue(context.packageName == "com.nkls.nekovideo.tagsynchost" && args.getString("tagSyncTest") == "true")
        require(args.getString("tagSyncToken").orEmpty().matches(Regex("[a-z0-9-]{1,50}")))
    }
    @Test fun normalSideSeesDebugEditsAndPublishesItsOwnEdits() = runBlocking<Unit> {
        val tags = VideoTagStore.getAllTags(context, TagScope.NORMAL)
        assertTrue(tags.none { it.name == "$prefix-debug-local" })
        val normal = tags.single { it.name == "$prefix-debug-renamed" }
        assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, path))
        VideoTagStore.renameTag(context, normal.id, "$prefix-host-renamed", TagScope.NORMAL).getOrThrow()
        VideoTagStore.createTag(context, "$prefix-host-created", TagScope.NORMAL).getOrThrow()
    }
    @Test fun revokeDisposableAuthorization() = runBlocking<Unit> {
        val dao = VideoTagStore.localDao(context)
        dao.getAllTagsAllScopes().filter { it.name.startsWith(prefix) }.forEach { dao.deleteTag(it.id) }
        TagSyncBridge.revoke(context)
    }
    @Test fun authorizeDisposableClient() { TagSyncBridge.authorize(context) }
    @Test fun confirmsBothScopesAndMovesThenRevokesConnection() = runBlocking<Unit> {
        assertEquals(2, VideoTagStore.getTagCountForVideoPath(context, "$path-moved"))
        assertEquals(0, VideoTagStore.getTagCountForVideoPath(context, path))
        assertTrue(VideoTagStore.getAllTags(context, TagScope.PRIVATE).any { it.name == "$prefix-private" })
        assertTrue(VideoTagStore.hasAutomaticBackup(context))
        TagSyncBridge.revoke(context)
    }
}

/** Explicit production verification: queries only. No mutation, backup flush, restore or test override. */
@RunWith(AndroidJUnit4::class)
class ProductionTagConnectionDeviceTest {
    @Test fun readsCanonicalTagsWithoutChangingThem() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == TagSyncBridge.CLIENT &&
            InstrumentationRegistry.getArguments().getString("verifyProductionTags") == "true")
        assertNull(TagSyncBridge.testHost)
        TagSyncBridge.localTestMode = false
        assertEquals(TagSyncBridge.HOST, TagSyncBridge.hostPackage())
        assertTrue("Connect and approve using the real apps first", TagSyncBridge.enabled(context))
        val normal = VideoTagStore.getAllTags(context, TagScope.NORMAL)
        val private = VideoTagStore.getAllTags(context, TagScope.PRIVATE)
        val normalCounts = VideoTagStore.getVideoCountsByTag(context, TagScope.NORMAL)
        val privateCounts = VideoTagStore.getVideoCountsByTag(context, TagScope.PRIVATE)
        val refs = TagSyncBridge.remoteDao(context).getAllVideoTagRefs()
        val ids = (normal + private).mapTo(HashSet()) { it.id }
        assertTrue(refs.all { it.tagId in ids })
        assertEquals(refs.size, normalCounts.values.sum() + privateCounts.values.sum())
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Canonical normal database: normalTags=${normal.size}, privateTags=${private.size}, links=${refs.size}\n")
        })
    }
}
