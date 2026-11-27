package com.nkls.nekovideo.components

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.TagEntity
import com.nkls.nekovideo.components.helpers.TagScope
import com.nkls.nekovideo.components.helpers.TagSyncBridge
import com.nkls.nekovideo.components.helpers.VideoTagStore
import com.nkls.nekovideo.components.pages.mainscreen.MainScreen
import com.nkls.nekovideo.services.FolderVideoScanner
import com.nkls.nekovideo.theme.ThemeManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickVideoTagsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("nekovideo_settings", Context.MODE_PRIVATE)
    private lateinit var root: File
    private lateinit var alpha: File
    private lateinit var beta: File
    private lateinit var tagged: File
    private lateinit var tag: TagEntity
    private var previousPrefs = emptyMap<String, Any?>()
    private var previousLocalTestMode = false

    @Before fun setup() = runBlocking<Unit> {
        check(context.packageName == "com.nkls.nekovideo.debug")
        previousLocalTestMode = TagSyncBridge.localTestMode
        TagSyncBridge.localTestMode = true
        previousPrefs = listOf("folder_sort_type", "show_durations", "show_file_sizes",
            "shuffle_tags_hint_shown", "pull_to_refresh_tutorial_shown").associateWith { prefs.all[it] }
        prefs.edit().putString("folder_sort_type", SortType.NAME_ASC.name)
            .putBoolean("show_durations", false).putBoolean("show_file_sizes", false)
            .putBoolean("shuffle_tags_hint_shown", true).putBoolean("pull_to_refresh_tutorial_shown", true).commit()
        root = File(context.cacheDir, "quick-tags-${UUID.randomUUID()}").apply { check(mkdir()) }
        alpha = video(root, "Alpha.mp4")
        beta = video(root, "Beta.mp4")
        tagged = video(root, "Tagged.mp4")
        video(File(root, "Subfolder").apply { check(mkdir()) }, "Child.mp4")
        tag = VideoTagStore.createTag(context, "Quick TAG ${UUID.randomUUID()}", TagScope.NORMAL).getOrThrow()
        VideoTagStore.addTagsToVideos(context, listOf(tagged.path), setOf(tag.id))
        FolderVideoScanner.reconcileFolderEntry(context, root.path)
    }

    @After fun cleanup() = runBlocking<Unit> {
        compose.runOnIdle { compose.activity.finish() }
        if (::root.isInitialized) {
            VideoTagStore.resetTagsForPathTree(context, root.path)
            require(root.parentFile == context.cacheDir && root.name.matches(Regex("quick-tags-[a-f0-9-]{36}")))
            check(root.deleteRecursively())
            FolderVideoScanner.reconcileFolderEntry(context, root.path)
        }
        if (::tag.isInitialized) VideoTagStore.deleteTag(context, tag.id)
        prefs.edit().also { editor -> previousPrefs.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        } }.commit()
        TagSyncBridge.localTestMode = previousLocalTestMode
    }

    private fun video(folder: File, name: String) = File(folder, name).also { file ->
        instrumentation.context.assets.open("embedded-covers/plain.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private fun editDescription(file: File) = "${context.getString(R.string.video_tags_edit)}: ${file.name}"
    private fun toggleUntaggedFilter() {
        if (compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasText(context.getString(R.string.sort_name_asc)) and !hasAnyAncestor(isPopup()))
                .performClick()
        }
        compose.onNodeWithText(context.getString(R.string.filter_no_tags)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun quickEditTargetsOnlyTappedVideoPreservesSelectionAndUpdatesFilter() {
        val theme = ThemeManager(context)
        compose.setContent { MaterialTheme {
            MainScreen(compose.activity, intent = null, themeManager = theme, openFolderPath = root.path)
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(alpha.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(editDescription(alpha)).assertDoesNotExist()
        toggleUntaggedFilter()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(editDescription(beta)).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(tagged.name).assertDoesNotExist()
        compose.onNodeWithContentDescription("${context.getString(R.string.video_tags_edit)}: Subfolder").assertDoesNotExist()

        compose.onNodeWithText(alpha.name).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Preview do video").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.actions_description)).assertIsDisplayed()
        compose.onNodeWithContentDescription(editDescription(beta)).performClick()
        compose.onNodeWithText(context.getString(R.string.video_tags_title)).assertIsDisplayed()
        compose.onNode(hasText(beta.name) and hasAnyAncestor(isDialog())).assertIsDisplayed()

        // The remaining preview plays normally, stays open at the end, and is only inside Tags.
        compose.onNodeWithText("Preview").performClick()
        compose.waitUntil(8_000) {
            val timeline = compose.onNodeWithContentDescription(context.getString(R.string.video_tags_preview_timeline))
                .fetchSemanticsNode().config
            timeline[SemanticsProperties.ProgressBarRangeInfo].current >= 0.95f
        }
        compose.onNodeWithText(context.getString(R.string.close)).assertIsDisplayed().performClick()
        compose.onNodeWithText(tag.name).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.video_tags_save)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(editDescription(beta)).fetchSemanticsNodes().isEmpty() }
        runBlocking {
            assertEquals(0, VideoTagStore.getTagCountForVideoPath(context, alpha.path))
            assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, beta.path))
            assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, tagged.path))
        }
        compose.onNodeWithContentDescription(context.getString(R.string.actions_description)).assertIsDisplayed()

        // Batch/FAB editing still uses the selection after a quick edit.
        compose.onNodeWithContentDescription(context.getString(R.string.actions_description)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_tags)).performClick()
        compose.onNode(hasText(alpha.name) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(tag.name).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.video_tags_save)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(editDescription(alpha)).fetchSemanticsNodes().isEmpty() }
        runBlocking { assertEquals(1, VideoTagStore.getTagCountForVideoPath(context, alpha.path)) }
        compose.onNodeWithContentDescription(context.getString(R.string.options_description)).assertIsDisplayed()
        toggleUntaggedFilter()
        compose.onNodeWithText(beta.name).assertIsDisplayed()
        compose.onNodeWithContentDescription(editDescription(beta)).assertDoesNotExist()
    }

    @Test fun shortcutIsOnlyForVideosInUntaggedFilterAndIsHiddenDuringMove() {
        val selected = mutableStateListOf<String>()
        val moving = mutableStateOf(false)
        val tapped = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            FolderScreen(root.path, { _, _, _ -> }, selectedItems = selected,
                onSelectionChange = { }, renameTrigger = 0, isMoveMode = moving.value,
                onEditVideoTags = { tapped += it })
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(alpha.name).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { selected += alpha.path }
        compose.onNodeWithContentDescription("Preview do video").assertDoesNotExist()
        compose.onNodeWithContentDescription(editDescription(beta)).assertDoesNotExist()
        compose.runOnIdle { selected.clear() }
        toggleUntaggedFilter()
        compose.onNodeWithContentDescription(editDescription(beta)).performClick()
        compose.runOnIdle { assertEquals(listOf(beta.path), tapped); assertTrue(selected.isEmpty()) }
        compose.runOnIdle { moving.value = true }
        compose.onNodeWithContentDescription(editDescription(beta)).assertDoesNotExist()
        compose.runOnIdle { moving.value = false }
        compose.onNodeWithContentDescription(editDescription(beta)).assertIsDisplayed()
    }
}
