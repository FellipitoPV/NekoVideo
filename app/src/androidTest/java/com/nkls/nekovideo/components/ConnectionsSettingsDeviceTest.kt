package com.nkls.nekovideo.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.NekoTrimConnection
import com.nkls.nekovideo.components.helpers.TagSyncBridge
import com.nkls.nekovideo.components.settings.ConnectionsSettingsScreen
import com.nkls.nekovideo.components.settings.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionsSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoryAppearsWithConnectionsAndClosesAfterTheLastDisconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(NekoTrimConnection.PREFS, Context.MODE_PRIVATE)
        val previous = prefs.all.mapValues { it.value as String }
        val previousLocalTestMode = TagSyncBridge.localTestMode
        prefs.edit().clear().commit()
        TagSyncBridge.localTestMode = true
        lateinit var navigation: NavHostController
        try {
            compose.setContent {
                MaterialTheme {
                    navigation = rememberNavController()
                    NavHost(navigation, startDestination = "settings") {
                        composable("settings") { SettingsScreen(navigation) }
                        composable("settings/connections") {
                            ConnectionsSettingsScreen(onEmpty = { navigation.popBackStack() })
                        }
                    }
                }
            }
            val title = context.getString(R.string.settings_connections)
            val disconnect = context.getString(R.string.nekotrim_connection_disconnect)
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.runOnIdle {
                NekoTrimConnection.grant(context, "com.nkls.nekotrim")
                NekoTrimConnection.grant(context, "com.nkls.nekotrim.debug")
            }
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.onNodeWithText("NekoTrim").assertIsDisplayed()
            compose.onNodeWithText("NekoTrim Debug").assertIsDisplayed()
            compose.onAllNodesWithText(disconnect)[0].performClick()
            compose.onNodeWithText("NekoTrim").assertDoesNotExist()
            compose.onNodeWithText("NekoTrim Debug").assertIsDisplayed()

            compose.runOnIdle { navigation.popBackStack() }
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.onNodeWithText(disconnect).performClick()
            compose.onNodeWithText(context.getString(R.string.settings_playback)).assertIsDisplayed()
            compose.onNodeWithText(title).assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                val editor = prefs.edit().clear()
                previous.forEach { (key, value) -> editor.putString(key, value) }
                editor.commit()
            }
            TagSyncBridge.localTestMode = previousLocalTestMode
        }
    }
}
