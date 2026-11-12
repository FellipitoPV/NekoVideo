package com.nkls.nekovideo.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class AppBottomSheetDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contentScrollsButOnlyTheHandleCanDragAndDismissTheSheet() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    AppBottomSheet(
                        onDismissRequest = { visible.value = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        title = "Folder picker"
                    ) {
                        Box(Modifier.fillMaxWidth().height(32.dp).testTag("static_content"))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(224.dp).testTag("folder_list"),
                            state = rememberLazyListState(initialFirstVisibleItemIndex = 1)
                        ) {
                            items(20) { index ->
                                Text("Folder $index", Modifier.fillMaxWidth().height(40.dp))
                            }
                        }
                    }
                }
            }
        }
        val title = compose.onNodeWithText("Folder picker")
        val initialTop = title.fetchSemanticsNode().boundsInWindow.top
        compose.onNodeWithText("Folder 0").assertDoesNotExist()

        val list = compose.onNodeWithTag("folder_list")
        list.performTouchInput {
            val start = Offset(center.x, top + 8.dp.toPx())
            val distance = height - 16.dp.toPx()
            down(start)
            repeat(20) { step -> moveTo(start + Offset(0f, distance * (step + 1) / 20), 16L) }
            up()
        }
        compose.onNodeWithText("Folder 0").assertIsDisplayed()
        assertEquals(initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)

        val staticContent = compose.onNodeWithTag("static_content")
        staticContent.performTouchInput { swipe(center, center + Offset(0f, 180.dp.toPx())) }
        title.assertIsDisplayed()
        assertEquals(initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)

        compose.onNodeWithTag("bottom_sheet_handle", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, 230.dp.toPx()))
        }
        title.assertDoesNotExist()
    }
}
