package com.nkls.nekovideo.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.pages.mainscreen.MoveDestinationDialog
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveDestinationGestureDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "move-gestures-").toFile()
    private val titleText = context.getString(R.string.move_destination_title)
    private val title get() = compose.onNodeWithText(titleText)
    private val handle get() = compose.onNodeWithTag("bottom_sheet_handle", useUnmergedTree = true)
    private val grid get() = compose.onNodeWithTag("move_destination_grid")

    @After fun cleanup() { directory.deleteRecursively() }

    private fun openPicker() {
        val video = File(directory, "selected.mp4").apply { createNewFile() }
        repeat(36) { check(File(directory, "Destination ${it + 100}").mkdir()) }
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    MoveDestinationDialog(
                        startPath = directory.path,
                        itemsToMove = listOf(video.path),
                        showPrivateFolders = false,
                        hasPrivateAccess = false,
                        refreshToken = 0,
                        isSecureFolder = { true },
                        onCreateFolder = { error("Drag triggered folder creation") },
                        onDismiss = { visible.value = false },
                        onMoveTo = { error("Drag triggered a move") }
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Destination 100").fetchSemanticsNodes().isNotEmpty()
        }
        title.assertIsDisplayed()
    }

    @Test fun scrollingPastTheTopAndDraggingBesideTheHandleKeepThePickerOpen() {
        openPicker()
        val initialTop = title.fetchSemanticsNode().boundsInWindow.top
        grid.performScrollToIndex(6)
        grid.performTouchInput {
            val start = Offset(center.x, 12.dp.toPx())
            val travel = height - 24.dp.toPx()
            down(start)
            repeat(24) { step -> moveTo(start + Offset(0f, travel * (step + 1) / 24), 16L) }
            // Release the native overscroll stretch before waiting for Compose to be idle.
            up()
        }
        compose.onNodeWithText("Destination 100").assertIsDisplayed()
        assertEquals(initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)

        // Repeat at the top, including fast pulls. Neither the list nor the header sides dismiss.
        repeat(3) {
            grid.performTouchInput {
                swipe(Offset(center.x, 20.dp.toPx()), Offset(center.x, height * 0.8f), 140L)
            }
            handle.performTouchInput {
                val beside = Offset(right + 28.dp.toPx(), center.y)
                swipe(beside, beside + Offset(0f, 180.dp.toPx()), 160L)
            }
            title.assertIsDisplayed()
            assertEquals(initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)
        }
    }

    @Test fun aShortFastPullStaysOpenAndALongerSlowPullCloses() {
        openPicker()
        val initialTop = title.fetchSemanticsNode().boundsInWindow.top
        handle.performTouchInput { click() }
        title.assertIsDisplayed()
        handle.performTouchInput { swipe(center, center + Offset(0f, 35.dp.toPx()), 64L) }
        title.assertIsDisplayed()
        assertEquals(initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)

        handle.performTouchInput {
            val start = center
            val travel = 92.dp.toPx()
            down(start)
            repeat(28) { step -> moveTo(start + Offset(0f, travel * (step + 1) / 28), 25L) }
        }
        assertTrue("The picker should follow a pull on the handle", title.fetchSemanticsNode().boundsInWindow.top > initialTop)
        handle.performTouchInput { up() }
        title.assertDoesNotExist()
    }
}
