package com.nkls.nekovideo.components.layout

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.ShuffleProgressDialog
import com.nkls.nekovideo.components.helpers.ShuffleProgress
import java.io.File
import java.text.NumberFormat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShuffleProgressDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsLivePercentageAndKeepsTheSheetOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val progress = MutableStateFlow(ShuffleProgress(ShuffleProgress.Stage.PRIVATE_ACCESS, 25))
        compose.setContent { MaterialTheme { ShuffleProgressDialog(progress) } }
        compose.onNodeWithText(NumberFormat.getPercentInstance().format(.25)).assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(.25f, 0f..1f))).assertExists()
        compose.runOnIdle { progress.value = ShuffleProgress(ShuffleProgress.Stage.BUILDING, 75) }
        compose.onNodeWithText(NumberFormat.getPercentInstance().format(.75)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.shuffle_progress_building)).assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(.75f, 0f..1f))).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_shuffle_play)).performTouchInput { swipeDown() }
        compose.onNodeWithText(NumberFormat.getPercentInstance().format(.75)).assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "shuffle-progress-75.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.runOnIdle { progress.value = ShuffleProgress(ShuffleProgress.Stage.BUILDING, 100) }
        compose.onNodeWithText(NumberFormat.getPercentInstance().format(1.0)).assertIsDisplayed()
    }
}
