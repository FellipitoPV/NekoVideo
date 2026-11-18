package com.nkls.nekovideo.components.layout

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.Environment
import androidx.activity.ComponentActivity
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.view.MotionEvent
import android.view.InputDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.*
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickTrimDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = File(context.cacheDir, "quick-trim-${UUID.randomUUID()}").apply { check(mkdir()) }
    private val sharedRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), root.name)
    private val client = "com.nkls.nekotrim.debug"
    private val selection = mutableStateOf<List<String>>(emptyList())
    private val connected = mutableStateOf(true)
    private val privateFolder = mutableStateOf(false)
    private val secureFolder = mutableStateOf(false)
    private val action get() = context.getString(R.string.action_quick_trim)
    private var hostTaskId = -1

    @After fun cleanup() {
        val prefs = context.getSharedPreferences(NekoTrimConnection.PREFS, 0)
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("video.") && value.toString().contains(root.name)) editor.remove(key)
        }
        editor.commit()
        root.deleteRecursively()
        sharedRoot.deleteRecursively()
    }

    private fun showFab(showStatus: Boolean = false, request: (() -> Intent)? = null) {
        hostTaskId = compose.activity.taskId
        compose.setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            val statuses = if (showStatus) rememberNekoTrimVideoStatuses(listOf(client)) else emptyMap()
            MaterialTheme {
                CompositionLocalProvider(LocalNekoTrimVideoStatuses provides statuses) {
                Column {
                ActionFAB(
                    hasSelectedItems = selection.value.isNotEmpty(), isMoveMode = false, isSecureMode = secureFolder.value,
                    selectedItems = selection.value, hasNekoTrimConnection = connected.value,
                    isInsideLockedFolder = privateFolder.value,
                    onActionClick = { if (it == ActionType.QUICK_TRIM && request != null) launcher.launch(request()) }
                )
                if (showStatus) selection.value.firstOrNull()?.let { NekoTrimVideoBadge(it) }
                }
                }
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(context.getString(
            if (selection.value.isEmpty()) R.string.options_description else R.string.actions_description
        )).performClick()
    }

    @Test fun optionRequiresOnlyVideosAndAnActiveConnection() {
        val first = copyVideo("first.mp4")
        val second = copyVideo("second.mp4")
        val folder = File(root, "folder").apply { check(mkdir()) }
        showFab()
        for ((paths, active, visible) in listOf(
            Triple(emptyList(), true, false),
            Triple(listOf(folder.path), true, false),
            Triple(listOf(first.path, folder.path), true, false),
            Triple(listOf(first.path), false, false),
            Triple(listOf(first.path), true, true),
            Triple(listOf(first.path, second.path), true, true)
        )) {
            compose.runOnIdle { selection.value = paths; connected.value = active }
            openMenu()
            if (visible) compose.onNodeWithText(action).assertExists()
            else compose.onNodeWithText(action).assertDoesNotExist()
            openMenu()
        }
        compose.runOnIdle { privateFolder.value = true }
        openMenu()
        compose.onNodeWithText(action).assertDoesNotExist()
        openMenu()
        compose.runOnIdle { privateFolder.value = false; secureFolder.value = true }
        openMenu()
        compose.onNodeWithText(action).assertDoesNotExist()
        openMenu()
    }

    @Test fun opensOneVideoInTheConnectedEditor() = importVideos(listOf(copyVideo("one.mp4")))

    @Test fun opensMultipleVideosInSelectionOrder() = importVideos(listOf(copyVideo("first.mkv"), copyVideo("second.mp4")))

    @Test fun exportStaysInNekoTrimAndPublishesACompletedBadgeAcrossApps() {
        val stem = "nt-${UUID.randomUUID().toString().take(8)}"
        check(sharedRoot.mkdir())
        // Production selections come from shared folders, outside Android's private app cache.
        val file = copyVideo("$stem.mp4").copyTo(File(sharedRoot, "$stem.mp4"))
        selection.value = listOf(file.path)
        showFab(showStatus = true) { NekoTrimVideoAccess.createIntent(context, client, selection.value) }
        openMenu()
        compose.onNodeWithText(action).performClick()
        try {
            tapExternal("Select whole video 1")
            tapExternal("+ Add")
            tapExternal("Review 1 trim")
            tapExternal("Quality & export")
            tapExternal("Export 1 video")
            awaitExternal("Settings")
            awaitExternal("NekoTrim")
            val key = NekoTrimVideoAccess.videoKey(file)
            val id = context.getSharedPreferences(NekoTrimConnection.PREFS, 0).getString("$client.id", null)!!
            val deadline = SystemClock.uptimeMillis() + 45_000
            var status: String? = null
            while (SystemClock.uptimeMillis() < deadline && status != "done") {
                context.contentResolver.query(Uri.parse("content://$client.nekovideo.status/status"), null, null, arrayOf(id), null)?.use {
                    while (it.moveToNext()) if (it.getString(0) == key) status = it.getString(1)
                }
                if (status == "failed") error("NekoTrim export failed: ${describe(instrumentation.uiAutomation.rootInActiveWindow)}")
                SystemClock.sleep(150)
            }
            assertTrue("Completed status did not cross the app boundary: $status", status == "done")
            shell("am task focus $hostTaskId")
            val description = "NekoTrim: ${context.getString(R.string.nekotrim_video_done)}"
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithContentDescription(description).assertExists(); true }.getOrDefault(false)
            }
        } finally {
            shell("am task focus $hostTaskId")
            // Only the uniquely named disposable export created by this test is removed.
            context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Video.Media.DISPLAY_NAME} IN (?, ?) AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                arrayOf("$stem.mkv", "$stem.mp4", "DCIM/NekoTrim/"))
        }
    }

    @Test fun receiverRejectsAStaleConnectionId() {
        val file = copyVideo("rejected.mp4")
        selection.value = listOf(file.path)
        showFab {
            NekoTrimVideoAccess.createIntent(context, client, selection.value)
                .putExtra(NekoTrimConnection.CONNECTION_ID, "stale-connection")
        }
        openMenu()
        compose.onNodeWithContentDescription("NekoTrim", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(action).performClick()
        try { awaitExternal("Could not open the videos.") }
        finally { backToNekoVideo() }
    }

    private fun importVideos(files: List<File>, names: List<String> = files.map { it.name }) {
        assertTrue("Connect NekoTrim Debug to this NekoVideo before this device test", NekoTrimConnection.authorized(context, client))
        selection.value = files.map { it.path }
        showFab { NekoTrimVideoAccess.createIntent(context, client, selection.value) }
        openMenu()
        compose.onNodeWithText(action).performClick()
        try {
            names.forEach { awaitExternal(it) }
            awaitExternal("Select whole video 1")
            if (files.size > 1) awaitExternal("Select whole video 2")
        } finally { backToNekoVideo() }
    }

    private fun backToNekoVideo() {
        if (describe(instrumentation.uiAutomation.rootInActiveWindow).contains("Could not open the videos.")) {
            tapExternal(context.getString(android.R.string.cancel))
        } else tapExternal("Back")
        try {
            // The shortcut must remain in NekoTrim when the editor is closed.
            awaitExternal("Settings")
            awaitExternal("NekoTrim")
        } finally { shell("am task focus $hostTaskId") }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun tapExternal(text: String) {
        awaitExternal(text)
        SystemClock.sleep(300)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            // Compose virtual nodes are traversable but do not implement platform findByText.
            fun matching(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
                if (node == null) return emptyList()
                return buildList {
                    if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) add(node)
                    for (i in 0 until node.childCount) addAll(matching(node.getChild(i)))
                }
            }
            val nodes = matching(root)
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                down.source = InputDevice.SOURCE_TOUCHSCREEN
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    val pressed = instrumentation.uiAutomation.injectInputEvent(down, true)
                    val released = instrumentation.uiAutomation.injectInputEvent(up, true)
                    if (pressed && released) return
                } finally { down.recycle(); up.recycle() }
            }
            SystemClock.sleep(100)
        }
        error("Could not tap '$text'")
    }

    private fun copyVideo(name: String) = File(root, name).apply {
        instrumentation.context.assets.open("embedded-covers/plain.${extension}").use { input -> outputStream().use(input::copyTo) }
    }

    private fun awaitExternal(text: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val node = instrumentation.uiAutomation.rootInActiveWindow
            last = describe(node)
            if (node?.packageName?.toString() == client && last.contains(text)) return
            SystemClock.sleep(150)
        }
        error("NekoTrim did not display '$text'. UI: $last")
    }

    private fun describe(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return buildString {
            append(node.text); append(' '); append(node.contentDescription); append('\n')
            for (i in 0 until node.childCount) append(describe(node.getChild(i)))
        }
    }
}
