package com.nkls.nekovideo.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nkls.nekovideo.components.helpers.SortRowMessage
import com.nkls.nekovideo.components.helpers.SortRowMessageType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineStatusMessageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visibleMessagesLetTouchesReachTagControlBehindThem() {
        val message = mutableStateOf<SortRowMessage?>(null)
        var tagClicks = 0

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 320.dp, height = 100.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("tagControl")
                            .clickable { tagClicks++ },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("TAG")
                    }
                    InlineStatusMessage(message.value)
                }
            }
        }

        SortRowMessageType.entries.forEachIndexed { index, type ->
            composeRule.runOnIdle {
                message.value = SortRowMessage(type, "Tags atualizadas")
            }
            composeRule.waitForIdle()

            // Inject a physical touch under the banner; semantic performClick bypasses hit testing.
            composeRule.onNodeWithTag("tagControl").performTouchInput { click(center) }
            composeRule.runOnIdle { assertEquals(index + 1, tagClicks) }
        }
    }
}
