package com.nkls.nekovideo.components.helpers

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.services.FolderVideoScanner
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans existing metadata without authentication, recovery, TAG mutations or video writes. */
@RunWith(AndroidJUnit4::class)
class InitialScanDeviceTest {
    @Test fun measuresInitialScanWithoutUnlockingOrChangingFiles() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" && args.getString("scanBenchmark") == "true")
        assertTrue(Environment.isExternalStorageManager())
        LockedPlaybackSession.clear()
        assertNull(LockedPlaybackSession.sessionPassword)
        FolderVideoScanner.loadCacheFromDisk(context)
        FolderVideoScanner.startScan(context, forceRefresh = true)
        withTimeout(240_000) { FolderVideoScanner.isScanning.first { !it } }
        val folders = FolderVideoScanner.cache.value
        val signatureInput = folders.toSortedMap().entries.joinToString("\n") { (path, info) ->
            "$path:${info.videoCount}:${info.isLocked}:${info.isSecure}:${info.hasVideos}:" +
                info.videos.map { it.path }.sorted().joinToString("|")
        }
        val signature = MessageDigest.getInstance("SHA-256").digest(signatureInput.toByteArray())
            .joinToString("") { "%02x".format(it) }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Initial scan: ms=${FolderVideoScanner.lastScanTimings}, folders=${folders.size}, videos=${folders.values.sumOf { it.videoCount }}, signature=$signature\n")
        })
        args.getString("expectedSignature")?.let { assertEquals(it, signature) }
        assertNull(LockedPlaybackSession.sessionPassword)
    }

    @Test fun cachedPrivateCountAvoidsReadsAndExternalAdditionIsStillDetected() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" && args.getString("scanFixtures") == "true")
        val password = checkNotNull(args.getString("privatePassword"))
        assertTrue(FilesManager.SecureStorage.verifyPassword(context, password))
        val root = java.io.File("/storage/emulated/0/Memes", "NekoScanTest-${java.util.UUID.randomUUID()}")
        check(root.mkdir())
        var folder = java.io.File(root, "private").apply { check(mkdir()) }
        val input = java.io.File(context.getExternalFilesDir(null), "portable-integration/input")
            .listFiles().orEmpty().filter { it.extension == "mp4" }.minBy { it.length() }
        var observer: android.os.FileObserver? = null
        try {
            repeat(3) { input.copyTo(java.io.File(folder, "video$it.mp4")) }
            FolderLockManager.lockFolder(context, folder.path, password,
                onError = { error(it) }, onResolvedPath = { folder = java.io.File(it) })
            FolderLockManager.removeFromRegistry(context, folder.path)
            suspend fun scan() {
                LockedPlaybackSession.clear()
                FolderVideoScanner.startScan(context, forceRefresh = true)
                withTimeout(240_000) { FolderVideoScanner.isScanning.first { !it } }
            }
            scan()
            assertEquals(3, FolderVideoScanner.cache.value[folder.path]?.videoCount)
            val protected = folder.listFiles().orEmpty().filter { !it.name.startsWith(".") && it.isFile }
            val names = protected.mapTo(HashSet()) { it.name }
            val opens = java.util.concurrent.atomic.AtomicInteger()
            observer = object : android.os.FileObserver(folder.path, android.os.FileObserver.OPEN) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path in names) opens.incrementAndGet()
                }
            }.apply { startWatching() }
            scan()
            assertEquals(3, FolderVideoScanner.cache.value[folder.path]?.videoCount)
            delay(100)
            assertEquals("Unchanged private videos must not be opened to recount them", 0, opens.get())
            val stamp = folder.lastModified()
            protected.first().copyTo(java.io.File(folder, java.util.UUID.randomUUID().toString().replace("-", "")))
            if (folder.lastModified() == stamp) check(folder.setLastModified(stamp + 2000))
            scan()
            assertEquals(4, FolderVideoScanner.cache.value[folder.path]?.videoCount)
            assertTrue(opens.get() > 0)
        } finally {
            observer?.stopWatching()
            FolderLockManager.getAllLockedFolders(context).filter {
                it.folderPath.startsWith(root.path + "/")
            }.forEach { FolderLockManager.removeFromRegistry(context, it.folderPath) }
            require(root.parentFile == java.io.File("/storage/emulated/0/Memes") &&
                root.name.matches(Regex("NekoScanTest-[0-9a-f-]{36}")))
            check(root.deleteRecursively())
            LockedPlaybackSession.clear()
            FolderVideoScanner.refreshLocalPaths(context, listOf(root.path)).join()
        }
    }
}
