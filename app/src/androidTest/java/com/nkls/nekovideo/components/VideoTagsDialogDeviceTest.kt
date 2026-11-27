package com.nkls.nekovideo.components

import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.TagEntity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class VideoTagsDialogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun landscapePreviewKeepsActionsOnScreenAndTagsUsable() =
        exercisePreview("embedded-covers/plain.mp4")

    @Test fun portraitPreviewKeepsActionsOnScreenAndTagsUsable() =
        exercisePreview("tag-preview/portrait.mp4")

    @Test fun portraitPreviewInLandscapeWindowKeepsActionsOnScreenAndTagsUsable() =
        exercisePreview("tag-preview/portrait.mp4", landscapeWindow = true)

    @Test fun tallPortraitWithLargeTagLabelsFitsWithoutScrollingTheVideo() =
        exercisePreview("tag-preview/portrait-tall.mp4", largeTagLabels = true)

    @Test fun previewWithoutTagsStillFitsInLandscapeWindow() =
        exercisePreview("tag-preview/portrait.mp4", landscapeWindow = true, emptyTags = true)

    @Test fun onlyDraggingTheHandleCanMoveAndDismissTheDialog() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    VideoTagsDialog(
                        selectedVideoCount = 1,
                        tags = List(60) { TagEntity(id = it.toLong(), name = "Tag $it") },
                        initialSelectedTagIds = emptySet(),
                        onDismiss = { visible.value = false },
                        onManageTags = {},
                        onSave = { Result.success(Unit) }
                    )
                }
            }
        }
        val title = compose.onNodeWithText(context.getString(R.string.video_tags_title))
        val initialTop = title.fetchSemanticsNode().boundsInWindow.top
        compose.onNodeWithText("Tag 0").performTouchInput {
            swipe(center, center + Offset(0f, 120.dp.toPx()))
        }
        assertEquals(initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)
        val handle = compose.onNodeWithTag("bottom_sheet_handle", useUnmergedTree = true)
        handle.performTouchInput { swipe(center, center + Offset(0f, 24.dp.toPx())) }
        assertEquals(initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)
        handle.performTouchInput { swipe(center, center + Offset(0f, 180.dp.toPx())) }
        title.assertDoesNotExist()
    }

    private fun exercisePreview(
        asset: String, landscapeWindow: Boolean = false, largeTagLabels: Boolean = false, emptyTags: Boolean = false
    ) {
        val initialOrientation = compose.activity.requestedOrientation
        compose.runOnUiThread {
            compose.activity.requestedOrientation = if (landscapeWindow) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation ==
                if (landscapeWindow) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        val video = File(context.cacheDir, "tags-preview-layout-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open(asset).use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val visible = mutableStateOf(true)
        val tagCount = mutableStateOf(3)
        try {
            compose.setContent {
                val typography = MaterialTheme.typography
                MaterialTheme(typography = if (largeTagLabels) typography.copy(
                    labelSmall = typography.labelSmall.copy(
                        fontSize = typography.labelSmall.fontSize * 3,
                        lineHeight = typography.labelSmall.lineHeight * 3
                    )
                ) else typography) {
                    if (visible.value) {
                        VideoTagsDialog(
                            selectedVideoCount = 1,
                            tags = List(tagCount.value) { TagEntity(id = it.toLong(), name = "Tag $it") },
                            initialSelectedTagIds = emptySet(),
                            previewVideoTitle = "Preview layout test",
                            previewVideoUri = Uri.fromFile(video),
                            onDismiss = { visible.value = false },
                            onManageTags = {},
                            onSave = { Result.success(Unit) }
                        )
                    }
                }
            }
            val dialogId = compose.onNode(isDialog()).fetchSemanticsNode().id
            repeat(10) {
                compose.runOnIdle { tagCount.value = if (emptyTags) 0 else if (it % 2 == 0) 3 else 60 }
                compose.onNodeWithText("Preview").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) {
                    !compose.onNodeWithContentDescription(context.getString(R.string.video_tags_preview_timeline))
                        .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
                }
                // Wait for the decoder's aspect ratio, including the asynchronous height change.
                compose.waitUntil(10_000) {
                    val bounds = compose.onNodeWithTag("video_tags_preview").fetchSemanticsNode().boundsInWindow
                    if (asset.contains("portrait")) bounds.height > bounds.width else bounds.width > bounds.height
                }
                compose.waitForIdle()
                assertEquals("Starting preview recreated the dialog", dialogId,
                    compose.onNode(isDialog()).fetchSemanticsNode().id)
                assertActionsInsideWindow()
                assertEquals("Only the tags may scroll", if (emptyTags) 0 else 1,
                    compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size)
                val previewBounds = actualBounds("video_tags_preview")
                val controlsBounds = actualBounds("video_tags_preview_toggle")
                val tagsBounds = actualBounds(if (emptyTags) "video_tags_empty" else "video_tags_list")
                val window = compose.onNode(isDialog()).fetchSemanticsNode().boundsInWindow
                assertTrue("Preview extends outside the dialog", previewBounds.top >= window.top &&
                    previewBounds.bottom <= controlsBounds.top && previewBounds.left >= window.left &&
                    previewBounds.right <= window.right)
                assertTrue("Tags overlap the preview controls", controlsBounds.bottom <= tagsBounds.top)
                if (!emptyTags) assertVisibleTagRows(tagCount.value,
                    if (tagCount.value == 60 && !landscapeWindow) 4 else null)
                val timeline = compose.onNodeWithContentDescription(context.getString(R.string.video_tags_preview_timeline))
                val mute = compose.onNode(
                    hasContentDescription(context.getString(R.string.video_tags_preview_mute)) or
                        hasContentDescription(context.getString(R.string.video_tags_preview_unmute))
                )
                listOf(timeline, mute).forEach { control ->
                    control.assertIsDisplayed()
                    val bounds = actualBounds(control)
                    assertTrue("Playback control extends beyond the preview: $bounds / $previewBounds",
                        bounds.top >= previewBounds.top && bounds.bottom <= previewBounds.bottom)
                }
                if (it <= 1) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(context.cacheDir, "tags-${File(asset).nameWithoutExtension}-$landscapeWindow-$it.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                if (!emptyTags) compose.onNodeWithText("Tag ${tagCount.value - 1}").performScrollTo().performClick()
                assertEquals("Scrolling tags moved the video", previewBounds, actualBounds("video_tags_preview"))
                assertEquals("Scrolling tags moved the close action", controlsBounds, actualBounds("video_tags_preview_toggle"))
                assertActionsInsideWindow()
                compose.onNodeWithText(context.getString(R.string.close)).assertIsDisplayed().performClick()
                assertActionsInsideWindow()
            }
            compose.onNodeWithText(context.getString(R.string.video_tags_save)).performClick()
            compose.onNodeWithText(context.getString(R.string.video_tags_title)).assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.runOnUiThread { compose.activity.requestedOrientation = initialOrientation }
            video.delete()
        }
    }

    private fun actualBounds(tag: String) = actualBounds(compose.onNodeWithTag(tag))

    private fun actualBounds(node: SemanticsNodeInteraction): Rect {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val topLeft = coordinates.localToWindow(Offset.Zero)
        val bottomRight = coordinates.localToWindow(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        return Rect(topLeft, bottomRight)
    }

    private fun assertVisibleTagRows(count: Int, expectedRows: Int?) {
        val viewport = actualBounds("video_tags_list")
        val visible = (0 until count).map { actualBounds("video_tag_$it") }
            .filter { it.bottom > viewport.top + 1 && it.top < viewport.bottom - 1 }
        val rows = visible.map { it.top.roundToInt() }.distinct().size
        assertTrue("Expected 1–4 visible tag rows, found $rows", rows in 1..4)
        expectedRows?.let { assertEquals("The tags should fill four rows when there is room", it, rows) }
        visible.forEach {
            assertTrue("A tag row is cut off: $it / $viewport", it.top >= viewport.top && it.bottom <= viewport.bottom)
        }
    }

    private fun assertActionsInsideWindow() {
        val window = compose.onNode(isDialog()).fetchSemanticsNode().boundsInWindow
        listOf(R.string.settings_tags, R.string.cancel, R.string.video_tags_save).forEach { resource ->
            val action = compose.onNodeWithText(context.getString(resource))
            action.assertIsDisplayed()
            val coordinates = action.fetchSemanticsNode().layoutInfo.coordinates
            val top = coordinates.localToWindow(Offset.Zero).y
            val bottom = coordinates.localToWindow(Offset(0f, coordinates.size.height.toFloat())).y
            assertTrue("Action $resource is above the window: $top / $window", top >= window.top)
            assertTrue("Action $resource is below the window: $bottom / $window", bottom <= window.bottom)
        }
    }
}
