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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.security.MessageDigest
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
        sharedRoot.walkTopDown().filter { it.isDirectory }.forEach {
            LockedPlaybackSession.removeSession(it.path)
            if (FolderLockManager.isLocked(it.path)) FolderLockManager.removeFromRegistry(context, it.path)
        }
        val prefs = context.getSharedPreferences(NekoTrimConnection.PREFS, 0)
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if ((key.startsWith("video.") || key.startsWith("output.")) && value.toString().contains(root.name)) {
                if (key.startsWith("output.")) {
                    runCatching {
                        val json = org.json.JSONObject(value.toString())
                        File(json.getString("staging")).takeIf { it.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/nekotrim-outputs/") }?.delete()
                    }
                }
                editor.remove(key)
            }
        }
        editor.commit()
        root.deleteRecursively()
        sharedRoot.deleteRecursively()
    }

    private fun showFab(showStatus: Boolean = false, requestedAction: ActionType = ActionType.QUICK_TRIM, request: (() -> Intent)? = null) {
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
                    onActionClick = { if (it == requestedAction && request != null) launcher.launch(request()) }
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
        compose.onNodeWithText(action).assertExists()
        openMenu()
        compose.runOnIdle { privateFolder.value = false; secureFolder.value = true }
        openMenu()
        compose.onNodeWithText(action).assertExists()
        openMenu()
    }

    @Test fun opensOneVideoInTheConnectedEditor() = importVideos(listOf(copyVideo("one.mp4")))

    @Test fun opensMultipleVideosInSelectionOrder() = importVideos(listOf(copyVideo("first.mkv"), copyVideo("second.mp4")))

    @Test fun opensSelectedVideosInFastMkvConversion() {
        selection.value = listOf(copyVideo("first.mp4").path, copyVideo("second.mkv").path)
        showFab(requestedAction = ActionType.QUICK_CONVERT) {
            NekoTrimVideoAccess.createIntent(context, client, selection.value, fastConvert = true)
        }
        openMenu()
        compose.onNodeWithText(context.getString(R.string.action_quick_convert)).performClick()
        try {
            awaitExternal("Convert to MKV")
            awaitExternal("Convert 2 videos")
            awaitExternal("Estimated total")
            tapExternal("Cancel")
            awaitExternal("Settings")
        } finally { shell("am task focus $hostTaskId") }
    }

    @Test fun convertsThreePrivateVideosAndKeepsResultsProtected() {
        check(sharedRoot.mkdir())
        val password = "convert-private-${UUID.randomUUID()}"
        check(PortablePrivateFolders.createEmpty(context, sharedRoot.path, password))
        val staging = File(context.cacheDir, "nekotrim-outputs/${root.name}").apply { check(mkdirs()) }
        val originals = (1..3).map { index ->
            File(staging, "nt-${UUID.randomUUID().toString().take(8)}-$index.mp4").apply {
                instrumentation.context.assets.open("fast-convert/aac-stereo.mp4").use { input -> outputStream().use { input.copyTo(it) } }
            }
        }
        val names = originals.map { "${it.nameWithoutExtension}.mkv" }.toSet()
        PortablePrivateFolders.importFiles(context, sharedRoot.path, password, originals)
        staging.delete()
        val manifest = checkNotNull(LockedPlaybackSession.getManifestForFolder(sharedRoot.path))
        val files = manifest.files.map { File(sharedRoot, it.obfuscatedName) }
        val before = files.associate { it.path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        privateFolder.value = true
        secureFolder.value = true
        selection.value = files.map { it.path }
        File(context.cacheDir, "fast-convert-test-sources.json").delete()
        showFab(showStatus = true, requestedAction = ActionType.QUICK_CONVERT) {
            NekoTrimVideoAccess.createIntent(context, client, selection.value, fastConvert = true).also { request ->
                @Suppress("DEPRECATION") val uris = request.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
                File(context.cacheDir, "fast-convert-test-sources.json").writeText(org.json.JSONArray(uris.map { it.toString() }).toString())
            }
        }
        openMenu()
        compose.onNodeWithText(context.getString(R.string.action_quick_convert)).performClick()
        try {
            awaitExternal("Convert to MKV")
            awaitExternal("Estimated total")
            tapExternal("Convert 3 videos")
            awaitExternal("Settings")
            val keys = files.map(NekoTrimVideoAccess::videoKey).toSet()
            val id = context.getSharedPreferences(NekoTrimConnection.PREFS, 0).getString("$client.id", null)!!
            val deadline = SystemClock.uptimeMillis() + 90_000
            var states = emptyMap<String, String>()
            while (SystemClock.uptimeMillis() < deadline) {
                states = buildMap {
                    context.contentResolver.query(Uri.parse("content://$client.nekovideo.status/status"), null, null, arrayOf(id), null)?.use {
                        while (it.moveToNext()) if (it.getString(0) in keys) put(it.getString(0), it.getString(1))
                    }
                }
                if (states.values.any { it == "failed" }) error("Private conversion failed: ${describe(instrumentation.uiAutomation.rootInActiveWindow)}")
                if (keys.all { states[it] == "done" }) break
                SystemClock.sleep(150)
            }
            assertTrue("Private conversion did not finish: $states", keys.all { states[it] == "done" })
            val parent = checkNotNull(FolderLockManager.readManifest(sharedRoot.path, password))
            val directory = File(sharedRoot, parent.subfolders.single { it.originalName == "NekoTrim" }.obfuscatedName)
            val outputs = checkNotNull(FolderLockManager.readManifest(directory.path, password)).files
            assertEquals(names, outputs.map { it.originalName }.toSet())
            outputs.forEach { entry ->
                val file = File(directory, entry.obfuscatedName)
                assertFalse(file.name.endsWith(".mkv"))
                val key = checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.path))
                XorMediaDataSource(file, key).use { input ->
                    android.media.MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(input)
                        assertEquals("yes", reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                        assertTrue(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
                    }
                }
                val cover = com.nkls.nekovideo.components.EmbeddedVideoArtwork.fromMatroska(file, 120, key)
                org.junit.Assert.assertNotNull("Converted video lost its embedded cover", cover)
                cover?.recycle()
            }
            files.forEach { assertEquals(before[it.path], MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()) }
            names.forEach { assertFalse(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NekoTrim/$it").exists()) }
        } finally { shell("am task focus $hostTaskId") }
    }

    @Test fun exportStaysInNekoTrimAndPublishesACompletedBadgeAcrossApps() {
        val stem = "nt-${UUID.randomUUID().toString().take(8)}"
        check(sharedRoot.mkdir())
        // Production selections come from shared folders, outside Android's private app cache.
        val file = copyVideo("$stem.mp4").copyTo(File(sharedRoot, "$stem.mp4"))
        val before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        exportAndAwait(listOf(file))
        assertTrue(File(sharedRoot, "NekoTrim/$stem.mkv").isFile)
        assertFalse(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NekoTrim/$stem.mkv").exists())
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    }

    @Test fun multipleSourcesExportBesideTheFirstSource() {
        check(sharedRoot.mkdir())
        val files = (1..3).map { index ->
            val name = "nt-${UUID.randomUUID().toString().take(8)}.mp4"
            copyVideo(name).copyTo(File(sharedRoot, name))
        }
        exportAndAwait(files)
        assertTrue(File(sharedRoot, "NekoTrim/${files.first().nameWithoutExtension}.mkv").isFile)
    }

    @Test fun portablePrivateSourceExportsIntoAProtectedSubfolder() = exportPrivate(portable = true)
    @Test fun legacyPrivateSourceExportsIntoAProtectedSubfolder() = exportPrivate(portable = false)
    @Test fun standalonePrivateSourceAlsoKeepsItsOutputProtected() = exportPrivate(portable = true, standalone = true)

    private fun exportPrivate(portable: Boolean, standalone: Boolean = false) {
        check(sharedRoot.mkdir())
        val password = "NekoTrim-private-test-${UUID.randomUUID()}"
        val original = copyVideo("nt-${UUID.randomUUID().toString().take(8)}.mp4")
        val plain = original.readBytes()
        val source: File
        if (portable) {
            check(PortablePrivateFolders.createEmpty(context, sharedRoot.path, password))
            val staging = File(context.cacheDir, "nekotrim-outputs/${root.name}").apply { check(mkdirs()) }
            PortablePrivateFolders.importFiles(context, sharedRoot.path, password, listOf(original.copyTo(File(staging, original.name))))
            staging.delete()
            val manifest = checkNotNull(LockedPlaybackSession.getManifestForFolder(sharedRoot.path))
            source = File(sharedRoot, manifest.files.single().obfuscatedName)
        } else {
            val salt = ByteArray(16) { (it + 17).toByte() }
            val key = FolderLockManager.deriveXorKey(password, salt)
            val encrypted = plain.copyOf()
            for (i in 0 until minOf(8192, encrypted.size)) encrypted[i] = (encrypted[i].toInt() xor key[i % key.size].toInt()).toByte()
            source = File(sharedRoot, UUID.randomUUID().toString().replace("-", "")).apply { writeBytes(encrypted) }
            val encodedSalt = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
            val manifest = LockedFolderManifest(sharedRoot.path, sharedRoot.name,
                listOf(LockedFileEntry(source.name, original.name, plain.size.toLong())), salt = encodedSalt, lockedAt = 1)
            File(sharedRoot, ".neko_locked").writeText(encodedSalt)
            val encrypt = FolderLockManager::class.java.getDeclaredMethod("encryptManifest", LockedFolderManifest::class.java, String::class.java, ByteArray::class.java)
                .apply { isAccessible = true }
            // Reuse an existing legacy NekoTrim child as well as reading a legacy source.
            val child = File(sharedRoot, UUID.randomUUID().toString().replace("-", "")).apply { check(mkdir()) }
            val childSalt = ByteArray(16) { (it + 29).toByte() }
            val childEncodedSalt = android.util.Base64.encodeToString(childSalt, android.util.Base64.NO_WRAP)
            val childManifest = LockedFolderManifest(child.path, "NekoTrim", emptyList(), salt = childEncodedSalt, lockedAt = 1)
            File(child, ".neko_locked").writeText(childEncodedSalt)
            File(child, ".neko_manifest.enc").writeBytes(encrypt.invoke(FolderLockManager, childManifest, password, childSalt) as ByteArray)
            File(child, ".nomedia").createNewFile()
            val parent = manifest.copy(subfolders = listOf(LockedSubfolderEntry(child.name, "NekoTrim")))
            File(sharedRoot, ".neko_manifest.enc").writeBytes(encrypt.invoke(FolderLockManager, parent, password, salt) as ByteArray)
            File(sharedRoot, ".nomedia").createNewFile()
            LockedPlaybackSession.start(key, parent, sharedRoot.path, password)
            key.fill(0)
        }
        if (standalone) {
            // Losing the folder index must not lose privacy carried by the video's own record.
            sharedRoot.listFiles().orEmpty().filter { it != source }.forEach { it.deleteRecursively() }
            assertFalse(FolderLockManager.isLocked(sharedRoot.path))
        }
        val before = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        val request = NekoTrimVideoAccess.createIntent(context, client, listOf(source.path))
        @Suppress("DEPRECATION") val uri = request.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.single()
        // Verify the decrypted proxy as bytes before exercising native metadata and encoding.
        assertArrayEquals(plain, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        privateFolder.value = true
        secureFolder.value = true
        exportAndAwait(listOf(source))
        val folder = if (standalone) File(sharedRoot, "NekoTrim") else {
            val parent = checkNotNull(FolderLockManager.readManifest(sharedRoot.path, password))
            val child = parent.subfolders.single { it.originalName == "NekoTrim" }
            File(sharedRoot, child.obfuscatedName)
        }
        assertTrue(FolderLockManager.isLocked(folder.path))
        val output = checkNotNull(FolderLockManager.readManifest(folder.path, password)).files.single()
        assertEquals("${original.nameWithoutExtension}.mkv", output.originalName)
        val protected = File(folder, output.obfuscatedName)
        assertFalse(protected.name.endsWith(".mkv"))
        val key = checkNotNull(LockedPlaybackSession.getXorKeyForFile(protected.path))
        XorMediaDataSource(protected, key).use { input ->
            val header = ByteArray(4)
            assertEquals(4, input.readAt(0, header, 0, 4))
            assertArrayEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()), header)
        }
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        assertFalse(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NekoTrim/${original.nameWithoutExtension}.mkv").exists())
    }

    private fun exportAndAwait(files: List<File>) {
        selection.value = files.map { it.path }
        showFab(showStatus = true) { NekoTrimVideoAccess.createIntent(context, client, selection.value) }
        openMenu()
        compose.onNodeWithText(action).performClick()
        try {
            files.forEachIndexed { index, _ ->
                tapExternal("Select whole video ${index + 1}")
                tapExternal("+ Add")
            }
            tapExternal("Review ${files.size} trim")
            tapExternal("Quality & export")
            tapExternal("Export 1 video")
            awaitExternal("Settings")
            awaitExternal("NekoTrim")
            val key = NekoTrimVideoAccess.videoKey(files.first())
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
