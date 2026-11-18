package com.nkls.nekovideo.components.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.*
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NekoTrimVideoBadgeDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun badgesFollowStatusAndStayHiddenWithoutMatchingHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "badge-${UUID.randomUUID()}.mp4").apply { writeText("fixture") }
        val statuses = mutableStateOf<Map<String, NekoTrimVideoState>>(emptyMap())
        val key = NekoTrimVideoAccess.videoKey(file)
        try {
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalNekoTrimVideoStatuses provides statuses.value) { NekoTrimVideoBadge(file.path) }
                }
            }
            val states = listOf(
                NekoTrimVideoState.QUEUED to R.string.nekotrim_video_queued,
                NekoTrimVideoState.PROCESSING to R.string.nekotrim_video_processing,
                NekoTrimVideoState.DONE to R.string.nekotrim_video_done,
                NekoTrimVideoState.FAILED to R.string.nekotrim_video_failed
            )
            states.forEach { (_, label) -> compose.onNodeWithContentDescription("NekoTrim: ${context.getString(label)}").assertDoesNotExist() }
            states.forEach { (status, label) ->
                compose.runOnIdle { statuses.value = mapOf(key to status) }
                val description = "NekoTrim: ${context.getString(label)}"
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription(description).assertExists(); true }.getOrDefault(false)
                }
                compose.onNodeWithContentDescription(description).assertIsDisplayed()
            }
            compose.runOnIdle { statuses.value = emptyMap() }
            states.forEach { (_, label) -> compose.onNodeWithContentDescription("NekoTrim: ${context.getString(label)}").assertDoesNotExist() }
        } finally { file.delete() }
    }
}
