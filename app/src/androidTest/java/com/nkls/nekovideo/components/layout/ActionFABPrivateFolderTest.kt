package com.nkls.nekovideo.components.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.PortablePrivateFolders
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionFABPrivateFolderTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "fab-private-test-${UUID.randomUUID()}").apply { check(mkdir()) }
    private val selected = mutableStateOf<List<String>>(emptyList())
    private val privatize get() = context.getString(R.string.action_lock_folder)
    private val unprivatize get() = context.getString(R.string.action_unlock_folder)

    @After fun cleanup() { root.deleteRecursively() }

    private fun showFab() {
        compose.setContent {
            MaterialTheme {
                ActionFAB(
                    hasSelectedItems = selected.value.isNotEmpty(),
                    isMoveMode = false,
                    isSecureMode = false,
                    selectedItems = selected.value,
                    onActionClick = {}
                )
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(context.getString(R.string.actions_description)).performClick()
    }

    @Test fun portableFolderStatusRefreshesWhenMenuReopens() {
        val folder = File(root, "new-folder").apply { check(mkdir()) }
        selected.value = listOf(folder.absolutePath)
        showFab()
        openMenu()
        compose.onNodeWithText(privatize).assertExists()
        compose.onNodeWithText(unprivatize).assertDoesNotExist()
        compose.onNodeWithText(privatize).performClick()

        // Same selection: the filesystem changes after the previous menu was closed.
        compose.runOnIdle { File(folder, PortablePrivateFolders.MARKER).writeText("test-marker") }
        openMenu()
        compose.onNodeWithText(unprivatize).assertExists()
        compose.onNodeWithText(privatize).assertDoesNotExist()
    }

    @Test fun legacyAndMixedSelectionsOfferAppropriateActions() {
        val legacy = File(root, "legacy").apply {
            check(mkdir())
            File(this, ".neko_locked").writeText("test-marker")
        }
        val portable = File(root, "portable").apply {
            check(mkdir())
            File(this, PortablePrivateFolders.MARKER).writeText("test-marker")
        }
        val ordinary = File(root, "ordinary").apply { check(mkdir()) }
        selected.value = listOf(legacy.absolutePath, portable.absolutePath)
        showFab()
        openMenu()
        compose.onNodeWithText(unprivatize).assertExists()
        compose.onNodeWithText(privatize).assertDoesNotExist()
        compose.onNodeWithText(unprivatize).performClick()

        compose.runOnIdle { selected.value = selected.value + ordinary.absolutePath }
        openMenu()
        compose.onNodeWithText(unprivatize).assertExists()
        compose.onNodeWithText(privatize).assertExists()
    }
}
