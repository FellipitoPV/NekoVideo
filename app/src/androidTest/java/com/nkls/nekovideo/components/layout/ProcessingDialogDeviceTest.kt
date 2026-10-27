package com.nkls.nekovideo.components.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.ProcessingDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises only the progress UI. No scan, password, video or migration is started. */
@RunWith(AndroidJUnit4::class)
class ProcessingDialogDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val title get() = context.getString(R.string.private_migration_title)

    @Test fun downwardDragsCannotMoveOrDismissProgress() {
        compose.setContent { MaterialTheme { ProcessingDialog(title, "1/4000") } }
        compose.waitForIdle()
        val sheet = compose.onNode(isDialog())
        val window = sheet.fetchSemanticsNode().boundsInWindow
        val initial = compose.onNodeWithText(title).fetchSemanticsNode().boundsInWindow
        val body = compose.onNodeWithText("1/4000").fetchSemanticsNode().boundsInWindow
        listOf(initial.center.y, body.center.y).forEach { startY ->
            val start = Offset(initial.center.x - window.left, startY - window.top)
            val end = Offset(start.x, window.height - 4f)
            sheet.performTouchInput {
                down(start)
                repeat(20) { step ->
                    moveTo(Offset(start.x, start.y + (end.y - start.y) * (step + 1) / 20f), 16L)
                }
            }
            compose.waitForIdle()
            assertEquals("The progress sheet moved while the finger was dragging", initial.top,
                compose.onNodeWithText(title).fetchSemanticsNode().boundsInWindow.top, 1f)
            sheet.performTouchInput { up() }
            compose.waitForIdle()
            compose.onNodeWithText(title).assertIsDisplayed()
            assertEquals(initial.top, compose.onNodeWithText(title).fetchSemanticsNode().boundsInWindow.top, 1f)
        }
    }

    @Test fun outsideTapsAndBackCannotDismissAndProgressStillUpdates() {
        val progress = mutableStateOf("1/4000")
        compose.setContent { MaterialTheme { ProcessingDialog(title, progress.value) } }
        compose.waitForIdle()
        val sheet = compose.onNode(isDialog())
        val window = sheet.fetchSemanticsNode().boundsInWindow
        val initial = compose.onNodeWithText(title).fetchSemanticsNode().boundsInWindow
        repeat(3) {
            sheet.performTouchInput { click(Offset(window.width / 2f, (initial.top - window.top) / 2f)) }
            compose.waitForIdle()
            // Shell input avoids Xiaomi's restriction on Espresso injecting global Back events.
            val back = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
            ParcelFileDescriptor.AutoCloseInputStream(back).use { it.readBytes() }
            compose.waitForIdle()
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        compose.runOnIdle { progress.value = "2/4000" }
        compose.onNodeWithText("2/4000").assertIsDisplayed()
    }
}
