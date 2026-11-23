package com.nkls.nekovideo.components.helpers

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.*
import com.nkls.nekovideo.components.settings.DisplaySettingsScreen
import com.nkls.nekovideo.components.settings.SHOW_ALL_FOLDERS_KEY
import com.nkls.nekovideo.services.FolderVideoScanner
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NomediaFolderVisibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("nekovideo_settings", 0)
    private val password = "nomedia-test-${UUID.randomUUID()}"
    private lateinit var root: File
    private lateinit var nomedia: File
    private lateinit var deep: File
    private lateinit var protectedFolder: File
    private lateinit var clip: File
    private var previous = emptyMap<String, Any?>()
    private val addedPins = mutableListOf<String>()

    @Before fun setup() = runBlocking<Unit> {
        check(context.packageName == "com.nkls.nekovideo.debug")
        TagSyncBridge.localTestMode = true
        LockedPlaybackSession.clear()
        FolderContentCache.clear()
        previous = listOf(SHOW_ALL_FOLDERS_KEY, SHOW_NOMEDIA_FOLDERS_KEY, NOMEDIA_REQUIRE_AUTH_KEY,
            "show_thumbnails", "show_durations", "show_file_sizes").associateWith { prefs.all[it] }
        prefs.edit().putBoolean(SHOW_ALL_FOLDERS_KEY, false)
            .remove(SHOW_NOMEDIA_FOLDERS_KEY).remove(NOMEDIA_REQUIRE_AUTH_KEY)
            .putBoolean("show_thumbnails", false).putBoolean("show_durations", false).putBoolean("show_file_sizes", false).commit()
        root = File(context.cacheDir, "nomedia-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val ordinary = File(root, "Ordinary").apply { check(mkdir()) }
        video(ordinary)
        File(root, "Empty").apply { check(mkdir()) }
        nomedia = File(root, "NoMedia").apply { check(mkdir()); File(this, ".nomedia").writeText("") }
        clip = video(nomedia)
        video(File(nomedia, "Child").apply { check(mkdir()) })
        deep = File(root, "Container/Deep").apply { check(mkdirs()); File(this, ".nomedia").writeText("") }
        video(deep)
        video(File(root, ".Hidden").apply { check(mkdir()) })
        protectedFolder = File(nomedia, "Protected").apply { check(mkdir()) }
        check(PortablePrivateFolders.createEmpty(context, protectedFolder.path, password))
        val staging = File(context.cacheDir, "nekotrim-outputs/${root.name}").apply { check(mkdirs()) }
        val privateInput = video(staging)
        PortablePrivateFolders.importFiles(context, protectedFolder.path, password, listOf(privateInput))
        check(staging.delete())
        LockedPlaybackSession.clear()
        FolderVideoScanner.reconcileFolderEntry(context, root.path)
    }

    private fun video(folder: File): File = File(folder, "clip.mp4").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("fast-convert/aac-stereo.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    private fun configure(enabled: Boolean = true, auth: Boolean = true) {
        check(prefs.edit().putBoolean(SHOW_NOMEDIA_FOLDERS_KEY, enabled).putBoolean(NOMEDIA_REQUIRE_AUTH_KEY, auth).commit())
    }
    private fun load(folder: File = root, privateVisible: Boolean = false, all: Boolean = false, reuse: Boolean = false) =
        loadFolderContent(context, folder.path, SortType.NAME_ASC, false, folder == root, privateVisible,
            showAllFolders = all, reuseLoadedContent = reuse)
    private fun pin(folder: File) {
        assertEquals(PinFolderResult.Success, PinnedFoldersStore.pin(context, folder.path))
        addedPins += folder.path
    }

    @After fun cleanup() = runBlocking<Unit> {
        addedPins.forEach { PinnedFoldersStore.unpin(context, it) }
        if (::root.isInitialized) {
            require(root.parentFile == context.cacheDir && root.name.matches(Regex("nomedia-test-[a-f0-9-]{36}")))
            FolderLockManager.getAllLockedFolders(context).filter { it.folderPath.startsWith(root.path + "/") }
                .forEach { FolderLockManager.removeFromRegistry(context, it.folderPath) }
            check(root.deleteRecursively())
            FolderVideoScanner.reconcileFolderEntry(context, root.path)
        }
        LockedPlaybackSession.clear()
        prefs.edit().also { editor -> previous.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putBoolean(key, value as Boolean)
        } }.commit()
        TagSyncBridge.localTestMode = false
    }

    @Test fun discoversNomediaWithoutAllFoldersAndKeepsOtherFilters() {
        assertEquals(NomediaVisibilitySettings(true, true), NomediaVisibilitySettings.read(context))
        assertFalse(load().any { it.path == nomedia.path })
        assertFalse(load(all = true).any { it.path == nomedia.path })
        configure(auth = false)
        val items = load()
        assertTrue(items.any { it.path == nomedia.path })
        assertTrue(items.any { it.path == File(root, "Container").path })
        assertFalse(items.any { it.path == File(root, "Empty").path || it.path == File(root, ".Hidden").path })
        assertTrue(load(File(root, "Container")).any { it.path == deep.path })
        assertTrue(load(nomedia).any { it.path == clip.path })
        assertTrue(load(File(nomedia, "Child")).any { !it.isFolder })
        assertTrue(load(all = true).any { it.path == File(root, "Empty").path })
    }

    @Test fun discoversEmptyAndDotNamedNomediaFoldersWithoutShowingOrdinaryEmptyFolders() = runBlocking<Unit> {
        val empty = File(root, "OnlyMarked/EmptyMarked").apply { check(mkdirs()); File(this, ".nomedia").writeText("") }
        val markedDot = File(root, ".Marked").apply { check(mkdir()); File(this, ".nomedia").writeText("") }
        FolderVideoScanner.reconcileFolderEntry(context, root.path)
        configure(auth = false)
        assertTrue(load().any { it.path == empty.parent })
        assertTrue(load().any { it.path == markedDot.path })
        assertTrue(load(checkNotNull(empty.parentFile)).any { it.path == empty.path })
        assertTrue(load(empty).isEmpty())
        assertFalse(load().any { it.path == File(root, "Empty").path })
        configure(auth = true)
        assertFalse(load().any { it.path == markedDot.path })
    }

    @Test fun authenticationAndMasterSwitchInvalidateCachedListingsAndGuardDirectPaths() {
        configure(auth = false)
        assertTrue(load(reuse = true).any { it.path == nomedia.path })
        assertTrue(load(nomedia, reuse = true).any { it.path == clip.path })
        configure(auth = true)
        assertFalse(load(reuse = true).any { it.path == nomedia.path })
        assertTrue(load(nomedia, reuse = true).isEmpty())
        assertTrue(load(File(nomedia, "Child"), reuse = true).isEmpty())
        // The visibility flag alone is insufficient without an authenticated session.
        assertFalse(load(privateVisible = true).any { it.path == nomedia.path })
        LockedPlaybackSession.authorize(password)
        assertTrue(load(privateVisible = true, reuse = true).any { it.path == nomedia.path })
        assertTrue(load(nomedia, privateVisible = true).any { it.path == clip.path })
        configure(enabled = false)
        assertFalse(load(privateVisible = true, all = true, reuse = true).any { it.path == nomedia.path })
        assertTrue(load(nomedia, privateVisible = true).isEmpty())
        // The master switch controls plain .nomedia folders, not genuine protected folders.
        assertTrue(load(protectedFolder, privateVisible = true).any { !it.isFolder })
    }

    @Test fun pinnedPathsAndCountsCannotBypassAuthenticationOrPrivateProtection() {
        org.junit.Assume.assumeTrue(PinnedFoldersStore.entries.value.size <= 8)
        pin(deep)
        pin(protectedFolder)
        assertFalse(load().any { it.path == deep.path || it.path == protectedFolder.path })
        configure(auth = false)
        val items = load()
        assertTrue(items.any { it.path == deep.path && it.showPinnedPathHint })
        assertFalse(items.any { it.path == protectedFolder.path })
        assertEquals(1, items.single { it.path == nomedia.path }.subfolderCount)
        assertFalse(load(nomedia).any { it.path == protectedFolder.path })
        assertTrue(load(protectedFolder).isEmpty())
        configure(auth = true)
        assertFalse(load(reuse = true).any { it.path == deep.path || it.path == nomedia.path })
    }

    @Test fun settingsChildFollowsParentAndPersistsItsOwnChoice() {
        compose.setContent { MaterialTheme { DisplaySettingsScreen() } }
        val parent = context.getString(R.string.display_show_nomedia_folders)
        val child = context.getString(R.string.display_nomedia_require_auth)
        compose.onNodeWithText(child).performScrollTo().performClick()
        compose.waitUntil { !NomediaVisibilitySettings.read(context).requireAuthentication }
        compose.onNodeWithText(parent).performScrollTo().performClick()
        compose.waitUntil { !NomediaVisibilitySettings.read(context).enabled }
        compose.onNodeWithText(child).assertDoesNotExist()
        compose.onNodeWithText(parent).performScrollTo().performClick()
        compose.waitUntil { NomediaVisibilitySettings.read(context).enabled }
        compose.onNodeWithText(child).performScrollTo().assertExists()
        assertFalse(NomediaVisibilitySettings.read(context).requireAuthentication)
    }

    @Test fun visibleScreenUpdatesOnSettingsChangesAndOnAuthentication() {
        val currentPath = mutableStateOf(root.path)
        val privateVisible = mutableStateOf(false)
        val visible = AtomicReference<List<String>>(emptyList())
        compose.setContent { MaterialTheme {
            FolderScreen(currentPath.value, { path, _, folder -> if (folder) currentPath.value = path },
                selectedItems = mutableStateListOf(), onSelectionChange = {}, renameTrigger = 0,
                isRootLevel = currentPath.value == root.path, showPrivateFolders = privateVisible.value,
                onVisibleItemsChange = { visible.set(it) })
        } }
        compose.waitUntil(10_000) { File(root, "Ordinary").path in visible.get() }
        assertFalse(nomedia.path in visible.get())
        configure(auth = false)
        compose.waitUntil(10_000) { nomedia.path in visible.get() }
        compose.onNodeWithText(nomedia.name).performClick()
        compose.waitUntil(10_000) { clip.path in visible.get() }
        assertFalse(protectedFolder.path in visible.get())
        configure(auth = true)
        compose.waitUntil(10_000) { visible.get().isEmpty() }
        compose.runOnIdle { LockedPlaybackSession.authorize(password); privateVisible.value = true }
        compose.waitUntil(10_000) { clip.path in visible.get() }
        compose.runOnIdle { LockedPlaybackSession.clear(); privateVisible.value = false }
        compose.waitUntil(10_000) { visible.get().isEmpty() }
    }

    @Test fun shuffleAndExplicitSelectionsFollowNomediaVisibility() = runBlocking<Unit> {
        fun containsNomedia(paths: List<String>) = paths.any { it.contains(nomedia.path) || it.contains(deep.path) }
        suspend fun videos(selected: List<String> = emptyList()) = FilesManager.getVideosRecursive(context,
            root.path, false, false, selectedItems = selected, preparePlayback = false)
        assertFalse(containsNomedia(videos()))
        assertTrue(videos(listOf(clip.path)).isEmpty())
        configure(auth = false)
        val found = videos()
        assertTrue(containsNomedia(found))
        assertTrue(found.any { it == Uri.fromFile(clip).toString() })
        assertFalse(found.any { it.contains(protectedFolder.path) })
        assertEquals(listOf(Uri.fromFile(clip).toString()), videos(listOf(clip.path)))
        configure(enabled = false, auth = false)
        assertFalse(containsNomedia(videos()))
        assertTrue(videos(listOf(clip.path)).isEmpty())
    }
}
