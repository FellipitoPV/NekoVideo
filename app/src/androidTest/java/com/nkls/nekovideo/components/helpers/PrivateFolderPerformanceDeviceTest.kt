package com.nkls.nekovideo.components.helpers

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.components.SortType
import com.nkls.nekovideo.components.loadFolderContent
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, read-only timings on the connected user's vault. Never migrates, exports or edits it. */
@RunWith(AndroidJUnit4::class)
class PrivateFolderPerformanceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
    private fun <T> timed(label: String, action: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return action().also { report("$label=${SystemClock.elapsedRealtime() - start}ms") }
    }

    @After fun clear() { LockedPlaybackSession.clear() }

    @Test fun measureExistingVaultWithoutChangingFiles() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" && args.getString("measurePrivateFolder") == "true")
        val password = checkNotNull(args.getString("privatePassword"))
        assertTrue(FilesManager.SecureStorage.verifyPassword(context, password))
        val root = File("/storage/emulated/0/NekoVideo")
        assertTrue(root.isDirectory)
        val folders = listOf(root) + root.listFiles().orEmpty().filter {
            it.isDirectory && FolderLockManager.isLocked(it.path)
        }.sortedByDescending { it.listFiles().orEmpty().size }.take(3)
        val metadataBefore = folders.flatMap { folder ->
            listOf(PortablePrivateFolders.INDEX, PortablePrivateFolders.MARKER).map { File(folder, it) }
        }.filter { it.isFile }.associateWith(PortableVideoStore::sha256)
        val summaries = PrivateFolderSummaryCache()
        folders.forEachIndexed { index, folder ->
            LockedPlaybackSession.clear()
            LockedPlaybackSession.authorize(password)
            report("folder[$index] children=${folder.listFiles().orEmpty().size}")
            val manifest = checkNotNull(timed("manifest[$index]") { FolderLockManager.readManifest(folder.path, password) })
            val salt = checkNotNull(timed("salt[$index]") { FolderLockManager.getSalt(folder.path) })
            val key = timed("legacyKey[$index]") { FolderLockManager.deriveXorKey(password, salt) }
            timed("session[$index]") { LockedPlaybackSession.start(key, manifest, folder.path, password) }
            key.fill(0)
            report("folder[$index] videos=${manifest.files.size}")
            repeat(2) { attempt ->
                val items = timed("content[$index][$attempt]") {
                    loadFolderContent(context, folder.path, SortType.NAME_ASC, true, false, true, summaries)
                }
                assertTrue(items.size >= manifest.files.size)
                report("items[$index][$attempt]=${items.size}")
            }
        }
        metadataBefore.forEach { (file, hash) -> assertEquals(hash, PortableVideoStore.sha256(file)) }
    }
}
