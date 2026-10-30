package com.nkls.nekovideo.components.helpers

import android.os.FileObserver
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.FolderContentCache
import com.nkls.nekovideo.components.FolderScreen
import com.nkls.nekovideo.components.SortType
import com.nkls.nekovideo.components.loadFolderContent
import com.nkls.nekovideo.services.FolderVideoScanner
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** All destructive operations are confined to fresh NekoIncrementalTest-* copies. */
@RunWith(AndroidJUnit4::class)
class IncrementalFolderDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password get() = checkNotNull(InstrumentationRegistry.getArguments().getString("privatePassword"))
    private lateinit var root: File
    private val prefs get() = context.getSharedPreferences("nekovideo_settings", 0)
    private var previousPreferences = emptyMap<String, Any?>()
    private val observers = mutableListOf<FileObserver>()
    private val thumbnails = mutableListOf<String>()

    @Before fun setup() {
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" && BuildConfig.PORTABLE_PRIVATE_WRITES)
        assumeTrue(InstrumentationRegistry.getArguments().containsKey("privatePassword"))
        assertTrue(FilesManager.SecureStorage.verifyPassword(context, password))
        TagSyncBridge.localTestMode = true
        LockedPlaybackSession.clear()
        root = File("/storage/emulated/0/Memes", "NekoIncrementalTest-${UUID.randomUUID()}").canonicalFile
        assertEquals(File("/storage/emulated/0/Memes").canonicalFile, root.parentFile)
        check(root.mkdir())
        previousPreferences = listOf("show_thumbnails", "show_durations", "show_file_sizes", "folder_sort_type")
            .associateWith { prefs.all[it] }
        prefs.edit().putBoolean("show_thumbnails", false).putBoolean("show_durations", false)
            .putBoolean("show_file_sizes", false).putString("folder_sort_type", "NAME_ASC").commit()
    }

    @After fun cleanup() {
        observers.forEach { it.stopWatching() }
        thumbnails.forEach { com.nkls.nekovideo.components.OptimizedThumbnailManager.thumbnailCache.remove(it.hashCode().toString()) }
        if (::root.isInitialized) {
            require(root.parentFile == File("/storage/emulated/0/Memes").canonicalFile &&
                root.name.matches(Regex("NekoIncrementalTest-[0-9a-f-]{36}")))
            FolderLockManager.getAllLockedFolders(context).filter { File(it.folderPath).toPath().startsWith(root.toPath()) }
                .forEach { FolderLockManager.removeFromRegistry(context, it.folderPath) }
            PinnedFoldersStore.entries.value.filter { File(it.path).toPath().startsWith(root.toPath()) }
                .forEach { PinnedFoldersStore.unpin(context, it.path) }
            check(root.deleteRecursively())
        }
        LockedPlaybackSession.clear()
        val editor = prefs.edit()
        previousPreferences.forEach { (key, value) -> when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
        } }
        editor.commit()
        TagSyncBridge.localTestMode = false
    }

    private fun folder(name: String, count: Int, private: Boolean): File {
        var folder = File(root, name).apply { check(mkdir()) }
        File(folder, ".nekovideo").writeText("")
        val input = File(checkNotNull(context.getExternalFilesDir(null)), "portable-integration/input")
            .listFiles().orEmpty().filter { it.extension == "mp4" }.minBy { it.length() }
        repeat(count) { input.copyTo(File(folder, "Video%03d.mp4".format(it))) }
        if (private) FolderLockManager.lockFolder(context, folder.path, password,
            onError = { error(it) }, onResolvedPath = { folder = File(it) })
        LockedPlaybackSession.authorize(password)
        return folder
    }
    private fun load(folder: File, private: Boolean, reuse: Boolean = false) =
        loadFolderContent(context, folder.path, SortType.NAME_ASC, private, false, true, reuseLoadedContent = reuse)
    private fun refresh(vararg folders: File) = runBlocking {
        FolderVideoScanner.refreshLocalPaths(context, folders.map { it.path }).join()
    }

    private inner class Opens(private val folder: File, private val names: Set<String>) :
        FileObserver(folder.path, OPEN or MOVED_TO) {
        init { observers += this }
        val media = AtomicInteger()
        val indices = AtomicInteger()
        private val barrier = CountDownLatch(1)
        override fun onEvent(event: Int, path: String?) {
            if (event and OPEN != 0 && path in names) media.incrementAndGet()
            if (event and MOVED_TO != 0 && path == PortablePrivateFolders.INDEX) indices.incrementAndGet()
            if (path == ".neko_test_barrier") barrier.countDown()
        }
        fun finish() {
            File(folder, ".neko_test_barrier").writeText("barrier")
            assertTrue("Observer must drain its events", barrier.await(3, TimeUnit.SECONDS))
            stopWatching()
        }
    }

    @Test fun privateBatchDeleteRenameCreateAndMoveDoNotReadUnchangedVideos() {
        val folder = folder("private", 36, true)
        val first = load(folder, true).filterNot { it.isFolder }
        val deleted = first.take(8)
        val renamed = first[8]
        val moved = first[9]
        val unchanged = first.drop(10).associate { File(it.path).name to PortableVideoStore.sha256(File(it.path)) }
        val observer = Opens(folder, unchanged.keys).apply { startWatching() }
        runBlocking {
            LockedFolderOperations.deleteLockedItems(context, deleted.map { it.path }, folder.path, password,
                onError = { error(it) })
        }
        refresh(folder)
        assertEquals(28, load(folder, true, true).count { !it.isFolder })
        // Atomically publishing the index once for the whole batch, rather than once per video.
        compose.waitUntil(3_000) { observer.indices.get() > 0 }
        val batchObserver = observer.indices.get()
        assertEquals(1, batchObserver)
        val manifest = checkNotNull(FolderLockManager.renameFileInManifest(folder.path, File(renamed.path).name, "Renamed.mp4", password))
        LockedPlaybackSession.updateManifest(folder.path, manifest)
        refresh(folder)
        assertTrue(load(folder, true, true).any { it.displayName == "Renamed.mp4" })
        val child = File(folder, "Child")
        assertTrue(FolderLockManager.createEmptyLockedFolder(context, child.path, password))
        assertNotNull(FolderLockManager.addSubfolderToLockedFolder(context, folder.path, child.path, password))
        refresh(folder)
        assertEquals(1, load(folder, true, true).count { it.isFolder })
        val destination = File(root, "destination").apply { check(mkdir()); File(this, ".nekovideo").writeText("") }
        runBlocking {
            assertTrue(LockedFolderOperations.pasteItems(context, listOf(moved.path), destination.path,
                folder.path, password, false, onError = { error(it) }))
        }
        refresh(folder, destination)
        assertEquals(27, load(folder, true, true).count { !it.isFolder })
        assertEquals(1, load(destination, false).count { !it.isFolder })
        observer.finish()
        assertEquals("Local operations must not open any unaffected video", 0, observer.media.get())
        unchanged.forEach { (name, hash) -> assertEquals(hash, PortableVideoStore.sha256(File(folder, name))) }
    }

    @Test fun privacyChangesPublishBeforeQueuedScanAndKeepSiblingVideosUntouched() = runBlocking<Unit> {
        val target = folder("change-privacy", 2, false)
        val sibling = folder("unaffected", 12, false)
        val child = File(target, "nested").apply { check(mkdir()) }
        File(sibling, "Video000.mp4").copyTo(File(child, "nested.mp4"))
        refresh(root)
        load(root, false)
        val observer = Opens(sibling, sibling.listFiles().orEmpty().mapTo(HashSet()) { it.name }).apply { startWatching() }
        val visible = AtomicReference<List<String>>(emptyList())
        compose.setContent { MaterialTheme {
            FolderScreen(root.path, { _, _, _ -> }, selectedItems = mutableStateListOf(), onSelectionChange = {},
                onVisibleItemsChange = { visible.set(it) }, renameTrigger = 0, showPrivateFolders = true)
        } }
        compose.waitUntil(10_000) { target.path in visible.get() }
        val dispatcher = object : CoroutineDispatcher() {
            val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
            var paused = true
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                synchronized(queued) { if (paused) { queued += context to block; return } }
                Dispatchers.IO.dispatch(context, block)
            }
            fun release() {
                val pending = synchronized(queued) { paused = false; queued.toList().also { queued.clear() } }
                pending.forEach { (context, block) -> Dispatchers.IO.dispatch(context, block) }
            }
        }
        val scanScope = CoroutineScope(SupervisorJob() + dispatcher)
        val previousVisibility = FilesManager.SecureFoldersVisibility.areSecureFoldersVisible(context)
        FolderVideoScanner.startScan(context, scanScope, forceRefresh = true, dispatcher = dispatcher)
        try {
            var lockedPath: String? = null
            LockedFolderOperations.lockFolders(context, listOf(target.path), password,
                onError = { error(it) }, onFolderLocked = {
                    lockedPath = FolderLockManager.getAllLockedFolders(context)
                        .single { File(it.folderPath).parent == root.path && it.originalFolderName == target.name }.folderPath
                    assertTrue(FolderVideoScanner.cache.value[lockedPath]?.isLocked == true)
                }, onStateChange = { active -> if (!active) assertNotNull(lockedPath) })
            val locked = checkNotNull(lockedPath)
            assertFalse(FolderVideoScanner.cache.value.containsKey(target.path))
            assertTrue(FolderVideoScanner.cache.value.keys.any { it.startsWith("$locked/") })
            compose.waitUntil(10_000) { locked in visible.get() && target.path !in visible.get() }
            assertTrue("Privacy publication must not wait for the held global scan", FolderVideoScanner.isScanning.value)
            val restored = LockedFolderOperations.unlockFolders(context, listOf(locked), password,
                onError = { error(it) }, onFolderUnlocked = {
                    assertTrue(FolderVideoScanner.cache.value[target.path]?.isLocked == false)
                })
            assertEquals(listOf(target.path), restored)
            assertFalse(FolderVideoScanner.cache.value.containsKey(locked))
            assertNotNull(FolderVideoScanner.cache.value[child.path])
            assertEquals(1, FolderVideoScanner.cache.value[child.path]?.videos?.size)
            assertEquals(File(child, "nested.mp4").path, FolderVideoScanner.cache.value[child.path]?.videos?.single()?.path)
            compose.waitUntil(10_000) { target.path in visible.get() && locked !in visible.get() }
            assertTrue(FolderVideoScanner.isScanning.value)
            observer.finish()
            assertEquals("Other folders must not reopen their videos", 0, observer.media.get())
        } finally {
            LockedPlaybackSession.clear()
            FilesManager.SecureFoldersVisibility.hideSecureFolders(context)
            dispatcher.release()
            withTimeout(60_000) { FolderVideoScanner.isScanning.first { !it } }
            FolderVideoScanner.refreshLocalPaths(context, listOf(root.path)).join()
            scanScope.cancel()
            FilesManager.SecureFoldersVisibility.setSecureFoldersVisible(context, previousVisibility)
        }
    }

    @Test fun normalDeleteRenameCreateAndFailedPrivateBatchStayConsistent() {
        val normal = folder("normal", 12, false)
        val initial = load(normal, false)
        val observer = Opens(normal, initial.drop(2).mapTo(HashSet()) { File(it.path).name }).apply { startWatching() }
        runBlocking { FilesManager.deleteSelectedItems(context, listOf(initial.first().path), onError = { error(it) }) }
        refresh(normal)
        assertEquals(11, load(normal, false, true).size)
        val old = File(initial[1].path)
        assertTrue(old.renameTo(File(normal, "Renamed.mp4")))
        assertTrue(FilesManager.createFolderWithMarker(context, normal.path, "New folder"))
        refresh(normal)
        val updated = load(normal, false, true)
        assertEquals(11, updated.count { !it.isFolder })
        assertTrue(updated.any { it.name == "Renamed.mp4" })
        assertTrue(updated.any { it.isFolder && it.name == "New folder" })
        observer.finish()
        assertEquals(0, observer.media.get())
        val private = folder("partial", 3, true)
        val videos = load(private, true).filterNot { it.isFolder }
        var failed = false
        runBlocking { LockedFolderOperations.deleteLockedItems(context, listOf(videos[0].path, File(private, "missing").path),
            private.path, password, onError = { failed = true }) }
        assertTrue(failed)
        refresh(private)
        assertEquals(2, load(private, true, true).size)
        assertEquals(2, FolderLockManager.readManifest(private.path, password)?.files?.size)
    }

    @Test fun searchSortPinsAndUnrelatedChangesDoNotReopenTheFolder() {
        val folder = folder("ui", 12, true)
        // Drain initial external discovery before measuring purely visual interactions.
        runBlocking { FolderVideoScanner.reconcileFolderEntry(context, folder.path) }
        val items = load(folder, true)
        val names = items.mapTo(HashSet()) { File(it.path).name }
        items.forEach {
            com.nkls.nekovideo.components.OptimizedThumbnailManager.thumbnailCache.put(it.path.hashCode().toString(),
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
            thumbnails += it.path
        }
        val visible = AtomicReference<List<String>>(emptyList())
        val selected = mutableStateListOf<String>()
        compose.setContent { MaterialTheme {
            FolderScreen(folder.path, { _, _, _ -> }, selectedItems = selected, onSelectionChange = {},
                onVisibleItemsChange = { visible.set(it) }, renameTrigger = 0, isSecureMode = true, showPrivateFolders = true)
        } }
        compose.waitUntil(15_000) { visible.get().size == 12 }
        val observer = Opens(folder, names).apply { startWatching() }
        compose.onNodeWithContentDescription("Buscar").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Video003")
        compose.waitUntil(5_000) { visible.get().size == 1 }
        compose.onNodeWithContentDescription("Fechar busca").performClick()
        compose.waitUntil(5_000) { visible.get().size == 12 }
        compose.onNodeWithText(context.getString(R.string.sort_name_asc)).performClick()
        compose.onNodeWithText(context.getString(R.string.sort_name_desc)).performClick()
        compose.waitUntil(5_000) { visible.get().firstOrNull() == items.last().path }
        PinnedFoldersStore.pin(context, folder.path)
        LocalFolderUpdates.mark(listOf(File(root, "unrelated").path))
        compose.waitForIdle()
        observer.finish()
        assertEquals("Visual changes must not read media records", 0, observer.media.get())
    }

    @Test fun partialMoveUpdatesConfirmedPathsWithoutReplacingConflicts() {
        val source = folder("source", 8, false)
        val target = folder("target", 1, false)
        val originals = load(source, false)
        load(target, false)
        val conflict = File(target, File(originals[0].path).name)
        conflict.writeText("pre-existing content must remain")
        val conflictHash = PortableVideoStore.sha256(conflict)
        val observer = Opens(source, originals.drop(2).mapTo(HashSet()) { File(it.path).name }).apply { startWatching() }
        var failures = 0
        runBlocking {
            assertFalse(LockedFolderOperations.pasteItems(context, originals.take(2).map { it.path }, target.path,
                null, password, false, onError = { failures++ }))
        }
        assertTrue(failures > 0)
        refresh(source, target)
        assertEquals(7, load(source, false, true).count { !it.isFolder })
        assertEquals(2, load(target, false, true).count { !it.isFolder })
        observer.finish()
        assertEquals(0, observer.media.get())
        assertEquals(conflictHash, PortableVideoStore.sha256(conflict))
        assertTrue(File(originals[0].path).exists())
        assertFalse(File(originals[1].path).exists())
    }

    @Test fun externalRenameInvalidatesTheCacheAndRequiresARecordRead() {
        val folder = folder("external", 3, true)
        val original = load(folder, true).first()
        val moved = File(folder, "externally-renamed")
        assertTrue(File(original.path).renameTo(moved))
        assertTrue(FolderContentCache.externallyChanged(folder.path))
        val observer = Opens(folder, setOf(moved.name)).apply { startWatching() }
        assertTrue(load(folder, true, true).any { it.path == moved.path && it.displayName == original.displayName })
        observer.finish()
        assertTrue("An external change must still validate its embedded record", observer.media.get() > 0)
    }

    @Test fun cachedPrivateContentDoesNotSurviveSessionLock() {
        val folder = folder("lock", 3, true)
        assertEquals(3, load(folder, true).size)
        assertNotNull(FolderContentCache.reusable(folder.path, true, true))
        LockedPlaybackSession.clear()
        assertNull(FolderContentCache.loadedItems(folder.path))
        assertTrue(load(folder, true, true).isEmpty())
        LockedPlaybackSession.authorize(password)
        assertEquals(3, load(folder, true, true).size)
    }

    @Test fun addingPrivateSubfolderAndCachedShuffleDoNotReadSiblingVideos() {
        val parent = folder("shuffle", 8, true)
        val parentItems = load(parent, true)
        val parentObserver = Opens(parent, parentItems.mapTo(HashSet()) { File(it.path).name }).apply { startWatching() }
        var child = File(parent, "Child").apply { check(mkdir()) }
        val sample = File(checkNotNull(context.getExternalFilesDir(null)), "portable-integration/input")
            .listFiles().orEmpty().filter { it.extension == "mp4" }.minBy { it.length() }
        repeat(2) { sample.copyTo(File(child, "Child$it.mp4")) }
        FolderLockManager.lockFolder(context, child.path, password, onError = { error(it) }, onResolvedPath = { child = File(it) })
        child = File(checkNotNull(FolderLockManager.addSubfolderToLockedFolder(context, parent.path, child.path, password)))
        refresh(parent)
        assertEquals(9, load(parent, true, true).size)
        val childItems = load(child, true)
        val childObserver = Opens(child, childItems.mapTo(HashSet()) { File(it.path).name }).apply { startWatching() }
        val recursive = runBlocking { FilesManager.getVideosRecursive(context, parent.path, true, true, sessionPassword = password) }
        assertEquals(10, recursive.size)
        assertTrue(recursive.all { it.startsWith("locked://") })
        assertEquals(9, load(parent, true, true).size)
        parentObserver.finish()
        childObserver.finish()
        assertEquals(0, parentObserver.media.get())
        assertEquals(0, childObserver.media.get())
    }
}
