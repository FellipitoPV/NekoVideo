package com.nkls.nekovideo.components.helpers

import android.os.FileObserver
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.components.FolderScreen
import com.nkls.nekovideo.components.SortType
import com.nkls.nekovideo.components.loadFolderContent
import com.nkls.nekovideo.services.FolderVideoScanner
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

/** Ordinary copies only; never edits the real Downloads, vault or canonical TAG database. */
class FolderExternalRefreshDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var input: File
    private val prefs get() = context.getSharedPreferences("nekovideo_settings", 0)
    private var previousPrefs = emptyMap<String, Any?>()

    @Before fun setup() {
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" &&
            InstrumentationRegistry.getArguments().getString("externalRefreshTest") == "true")
        TagSyncBridge.localTestMode = true
        LockedPlaybackSession.clear()
        root = File("/storage/emulated/0/Memes", "NekoRefreshTest-${UUID.randomUUID()}").canonicalFile
        require(root.parentFile == File("/storage/emulated/0/Memes").canonicalFile)
        check(root.mkdir())
        input = File(context.getExternalFilesDir(null), "portable-integration/input").listFiles().orEmpty()
            .filter { it.extension == "mp4" }.minBy { it.length() }
        previousPrefs = listOf("show_thumbnails", "show_durations", "show_file_sizes").associateWith { prefs.all[it] }
        prefs.edit().putBoolean("show_thumbnails", false).putBoolean("show_durations", false)
            .putBoolean("show_file_sizes", false).commit()
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (::root.isInitialized) {
            require(root.parentFile == File("/storage/emulated/0/Memes").canonicalFile &&
                root.name.matches(Regex("NekoRefreshTest-[0-9a-f-]{36}")))
            check(root.deleteRecursively())
            FolderVideoScanner.reconcileFolderEntry(context, root.path)
        }
        prefs.edit().also { editor -> previousPrefs.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putBoolean(key, value as Boolean)
        } }.commit()
        TagSyncBridge.localTestMode = false
    }

    private fun load(folder: File, rootLevel: Boolean = false) = loadFolderContent(context, folder.path,
        SortType.NAME_ASC, false, rootLevel, false)
    private fun video(folder: File, name: String): File {
        val stamp = folder.lastModified()
        return input.copyTo(File(folder, name)).also {
            if (stamp == folder.lastModified()) check(folder.setLastModified(stamp + 2000))
        }
    }

    @Test fun changedEmptyChildBecomesVisibleWithoutReadingUnchangedSiblingVideos() = runBlocking<Unit> {
        val received = File(root, "received").apply { check(mkdir()) }
        val sibling = File(root, "untouched").apply { check(mkdir()) }
        repeat(16) { input.copyTo(File(sibling, "video$it.mp4")) }
        assertTrue(FolderVideoScanner.reconcileFolderEntry(context, root.path))
        assertFalse(load(root).any { it.path == received.path })
        val fullScan = FolderVideoScanner.fullScanVersion.value
        val parentStamp = root.lastModified()
        val opened = AtomicInteger()
        val barrier = CountDownLatch(1)
        val observer = object : FileObserver(sibling.path, OPEN) {
            override fun onEvent(event: Int, path: String?) {
                if (path == ".barrier") barrier.countDown()
                else if (path?.endsWith(".mp4") == true) opened.incrementAndGet()
            }
        }.apply { startWatching() }
        try {
            video(received, "downloaded.mp4")
            assertEquals(parentStamp, root.lastModified())
            assertTrue(FolderVideoScanner.reconcileFolderEntry(context, root.path))
            assertEquals(1, load(root).single { it.path == received.path }.videoCount)
            assertFalse(FolderVideoScanner.reconcileFolderEntry(context, root.path))
            assertEquals(fullScan, FolderVideoScanner.fullScanVersion.value)
            FilesManager.deleteSelectedItems(context, listOf(File(received, "downloaded.mp4").path), onError = { error(it) })
            FolderVideoScanner.refreshLocalPaths(context, listOf(received.path)).join()
            assertFalse("Known app edits must not trigger another validation", FolderVideoScanner.reconcileFolderEntry(context, root.path))
            assertFalse(load(root).any { it.path == received.path })
            File(sibling, ".barrier").writeText("drain")
            assertTrue(barrier.await(3, TimeUnit.SECONDS))
            assertEquals(0, opened.get())
        } finally { observer.stopWatching() }
    }

    @Test fun newNestedFolderAndDirectDownloadAppearOnEntry() = runBlocking<Unit> {
        assertTrue(FolderVideoScanner.reconcileFolderEntry(context, root.path))
        assertTrue(load(root).isEmpty())
        val nested = File(root, "new-folder/nested").apply { check(mkdirs()) }
        video(nested, "new.mp4")
        assertTrue(FolderVideoScanner.reconcileFolderEntry(context, root.path))
        assertTrue(load(root).any { it.path == nested.parent })
        assertTrue(load(nested).any { !it.isFolder && it.name == "new.mp4" })
        video(root, "direct.mp4")
        assertTrue(FolderVideoScanner.reconcileFolderEntry(context, root.path))
        assertTrue(load(root).any { !it.isFolder && it.name == "direct.mp4" })
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle get() = registry
    }

    @Test fun enteringDownloadsFindsANewVideoWithoutLeavingTheApp() = runBlocking<Unit> {
        val downloads = File(root, "Download").apply { check(mkdir()) }
        val received = File(downloads, "received").apply { check(mkdir()) }
        FolderVideoScanner.reconcileFolderEntry(context, root.path)
        val currentPath = mutableStateOf(root.path)
        compose.setContent { MaterialTheme {
            FolderScreen(currentPath.value, { path, _, _ -> currentPath.value = path },
                selectedItems = mutableStateListOf(), onSelectionChange = {}, renameTrigger = 0,
                isRootLevel = currentPath.value == root.path)
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Download").fetchSemanticsNodes().isNotEmpty() }
        val fullScan = FolderVideoScanner.fullScanVersion.value
        video(received, "downloaded-while-open.mp4")
        compose.onNodeWithText("Download").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("received").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(fullScan, FolderVideoScanner.fullScanVersion.value)
        assertFalse(FolderVideoScanner.isScanning.value)
    }

    @Test fun rootScreenUpdatesSilentlyOnResumeWhenAnExistingChildReceivesVideo() = runBlocking<Unit> {
        val received = File(root, "received").apply { check(mkdir()) }
        val existing = File(root, "existing").apply { check(mkdir()) }
        video(existing, "baseline.mp4")
        FolderVideoScanner.reconcileFolderEntry(context, root.path)
        val owner = Owner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            FolderScreen(root.path, { _, _, _ -> }, selectedItems = mutableStateListOf(),
                onSelectionChange = {}, renameTrigger = 0, isRootLevel = true)
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("existing").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("received").fetchSemanticsNodes().isEmpty())
        val fullScan = FolderVideoScanner.fullScanVersion.value
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        video(received, "background-download.mp4")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("received").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(fullScan, FolderVideoScanner.fullScanVersion.value)
        assertFalse(FolderVideoScanner.isScanning.value)
    }
}
