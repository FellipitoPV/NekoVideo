package com.nkls.nekovideo.components.helpers

import android.os.FileObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.components.ShuffleTagFilter
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Copies only, under a validated Memes root, with an unrelated password and local Debug tags. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PrivateShuffleDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "ShuffleTest-${UUID.randomUUID()}"
    private lateinit var root: File
    private lateinit var sample: File
    private var previousVisibility = false
    private val tags = mutableListOf<Long>()
    private val observers = mutableListOf<Opens>()

    @Before fun setup() {
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug")
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("privatePassword"))
        assertTrue(FilesManager.SecureStorage.verifyPassword(context, args.getString("privatePassword")!!))
        TagSyncBridge.localTestMode = true
        previousVisibility = FilesManager.SecureFoldersVisibility.areSecureFoldersVisible(context)
        FilesManager.SecureFoldersVisibility.showSecureFolders(context)
        LockedPlaybackSession.clear()
        LockedPlaybackSession.authorize(password)
        root = File("/storage/emulated/0/Memes", "NekoShuffleTest-${UUID.randomUUID()}").canonicalFile
        assertEquals(File("/storage/emulated/0/Memes").canonicalFile, root.parentFile)
        check(root.mkdir())
        File(root, ".nomedia").createNewFile()
        sample = File(context.getExternalFilesDir(null), "portable-integration/input").listFiles().orEmpty()
            .filter { it.extension == "mp4" }.minBy { it.length() }
    }

    @After fun cleanup() = runBlocking<Unit> {
        observers.forEach { it.stopWatching() }
        try {
            tags.forEach { VideoTagStore.deleteTag(context, it) }
            if (::root.isInitialized) {
                VideoTagStore.resetTagsForPathTree(context, root.path)
                require(root.parentFile == File("/storage/emulated/0/Memes").canonicalFile &&
                    root.name.matches(Regex("NekoShuffleTest-[0-9a-f-]{36}")))
                check(root.deleteRecursively())
            }
        } finally {
            LockedPlaybackSession.clear()
            FilesManager.SecureFoldersVisibility.setSecureFoldersVisible(context, previousVisibility)
            TagSyncBridge.localTestMode = false
        }
    }

    private fun folder(name: String, count: Int): List<File> {
        val folder = File(root, name).apply { check(mkdir()) }
        File(folder, ".nomedia").createNewFile()
        val store = PortableVideoStore(folder)
        val salt = store.newSalt()
        val protected = mutableListOf<File>()
        PortableVideoStore.Keyring(password.toCharArray()).use { keys ->
            val entries = (0 until count).map { index ->
                val plain = sample.copyTo(File(folder, "Video$index.mp4"))
                val output = File(folder, UUID.randomUUID().toString().replace("-", ""))
                store.protectCopy(plain, output, keys, salt, name).also { check(plain.delete()); protected += output }
            }
            store.writeIndex(File(folder, PortablePrivateFolders.INDEX), entries, keys, salt, name, privateFolder = true)
        }
        File(folder, PortablePrivateFolders.MARKER).writeText(Base64.getEncoder().encodeToString(salt))
        File(folder, ".nomedia").createNewFile()
        return protected
    }

    private suspend fun build(filter: ShuffleTagFilter? = null, limit: Int = 1000, selected: List<String> = emptyList(),
                              onProgress: (ShuffleProgress) -> Unit = {}) =
        ShufflePlaylistBuilder.build(context, root.path, true, true, selected, password, TagScope.PRIVATE, filter, limit, onProgress)

    private inner class Opens(folder: File, private val names: Set<String>) : FileObserver(folder.path, OPEN) {
        private val folder = folder
        val videos = AtomicInteger()
        val opened = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val barrier = CountDownLatch(1)
        init { observers += this; startWatching() }
        override fun onEvent(event: Int, path: String?) {
            if (path in names) { videos.incrementAndGet(); opened += File(folder, path!!).path }
            if (path == ".neko_shuffle_barrier") barrier.countDown()
        }
        fun finish() {
            File(folder, ".neko_shuffle_barrier").writeText("barrier")
            assertTrue(barrier.await(3, TimeUnit.SECONDS))
            stopWatching()
        }
    }

    @Test fun limitBeforePreparationAndWarmShuffleBeyondScreenCache() = runBlocking<Unit> {
        val files = (0 until 16).flatMap { folder("folder-$it", 3) }
        LockedPlaybackSession.clear(); LockedPlaybackSession.authorize(password)
        val watched = files.groupBy { it.parentFile!! }.map { (dir, entries) -> Opens(dir, entries.mapTo(HashSet()) { it.name }) }
        val progress = mutableListOf<ShuffleProgress>()
        val limited = build(limit = 7, onProgress = { progress += it })
        assertEquals(0, progress.first().percent)
        assertEquals(100, progress.last().percent)
        assertTrue(progress.zipWithNext().all { (before, after) -> before.percent <= after.percent })
        assertTrue(progress.any { it.stage == ShuffleProgress.Stage.PRIVATE_ACCESS && it.total > 0 && it.percent in 1..99 })
        assertEquals(7, limited.size)
        assertEquals(7, limited.distinct().size)
        limited.forEach { assertNotNull(LockedPlaybackSession.getXorKeyForFile(it.removePrefix("locked://"))) }
        watched.forEach { it.finish() }
        assertEquals("Only records in the capped playlist should be read",
            limited.map { it.removePrefix("locked://") }.toSet(), watched.flatMap { it.opened }.toSet())
        assertEquals(files.map { "locked://${it.path}" }.toSet(), build().toSet())
        val warm = files.groupBy { it.parentFile!! }.map { (dir, entries) -> Opens(dir, entries.mapTo(HashSet()) { it.name }) }
        assertEquals(48, build().size)
        warm.forEach { it.finish() }
        assertEquals("Verified unchanged records must be reused even beyond the 12-folder UI cache", 0, warm.sumOf { it.videos.get() })
        assertEquals(listOf("locked://${files.last().path}"), build(selected = listOf(files.last().path)))
    }

    @Test fun requiredAnyExcludedAndScopesPruneNonMatchingPrivateVideos() = runBlocking<Unit> {
        val first = folder("matches", 5)
        val others = folder("non-matching", 12)
        suspend fun tag(name: String, scope: TagScope = TagScope.PRIVATE): Long = VideoTagStore
            .createTag(context, "Shuffle-${root.name}-$name", scope).getOrThrow().id.also { tags += it }
        val a = tag("A"); val b = tag("B"); val c = tag("C"); val d = tag("D"); val x = tag("X")
        val normal = tag("normal", TagScope.NORMAL)
        suspend fun assign(file: File, vararg ids: Long) = VideoTagStore.addTagsToVideos(context, listOf(file.path), ids.toSet())
        assign(first[0], a, b, c); assign(first[1], a, b, d, x); assign(first[2], a)
        assign(first[3], a, b); assign(first[4], a, b, d)
        VideoTagStore.addTagsToVideos(context, others.map { it.path }, setOf(normal))
        val excluded = (others + first.slice(1..3)).groupBy { it.parentFile!! }
            .map { (dir, entries) -> Opens(dir, entries.mapTo(HashSet()) { it.name }) }
        val playlist = build(ShuffleTagFilter(setOf(a, b), setOf(c, d), setOf(x)))
        assertEquals(setOf(first[0], first[4]).map { "locked://${it.path}" }.toSet(), playlist.toSet())
        excluded.forEach { it.finish() }
        assertEquals(0, excluded.sumOf { it.videos.get() })
        assertTrue(build(ShuffleTagFilter(setOf(normal), emptySet(), emptySet())).isEmpty())
        assertEquals(16, build(ShuffleTagFilter(emptySet(), emptySet(), setOf(x))).size)
    }

    @Test fun hidingDuringPreparationStopsWithoutReportingCompletionAndCanRetry() = runBlocking<Unit> {
        val files = folder("interrupted", 2)
        val before = files.associateWith(PortableVideoStore::sha256)
        val progress = mutableListOf<ShuffleProgress>()
        var hidden = false
        val result = runCatching { build(onProgress = {
            progress += it
            if (!hidden && it.stage == ShuffleProgress.Stage.PRIVATE_ACCESS && it.percent == 10) {
                hidden = true
                FilesManager.SecureFoldersVisibility.hideSecureFolders(context)
            }
        }) }
        assertTrue(hidden)
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(progress.any { it.percent == 100 })
        files.forEach { assertNull(LockedPlaybackSession.getXorKeyForFile(it.path)) }
        before.forEach { (file, hash) -> assertEquals(hash, PortableVideoStore.sha256(file)) }
        FilesManager.SecureFoldersVisibility.showSecureFolders(context)
        assertEquals(2, build().size)
    }

    @Test fun externalRenameMoveReplacementAndMissingIndexStillRecoverKeys() = runBlocking<Unit> {
        val first = folder("source", 2)
        val second = folder("destination", 1)
        build()
        val renamed = File(first[0].parentFile, "externally-renamed.mp4")
        check(first[0].renameTo(renamed))
        val moved = File(second[0].parentFile, "externally-moved")
        check(first[1].renameTo(moved))
        check(File(second[0].parentFile, PortablePrivateFolders.INDEX).delete())
        val replacement = second[0]
        val plain = sample.copyTo(File(replacement.parentFile, "replacement.mp4"))
        check(replacement.delete())
        val store = PortableVideoStore(replacement.parentFile!!)
        PortableVideoStore.Keyring(password.toCharArray()).use { keys -> store.protectCopy(plain, replacement, keys, store.newSalt()) }
        check(plain.delete())
        val playlist = build()
        assertEquals(setOf(renamed, moved, replacement).map { "locked://${it.path}" }.toSet(), playlist.toSet())
        playlist.forEach { uri ->
            val file = File(uri.removePrefix("locked://"))
            val bytes = ByteArray(64)
            XorMediaDataSource(file, checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.path))).use {
                assertEquals(64, it.readAt(0, bytes, 0, bytes.size))
            }
            val expected = sample.inputStream().use { it.readNBytes(64) }
            assertArrayEquals(expected, bytes)
        }
        LockedPlaybackSession.clear()
        assertTrue(runCatching { build() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun inPlaceReplacementInvalidatesVerifiedRecordWithoutDirectoryChanges() = runBlocking<Unit> {
        val file = folder("in-place", 1).single()
        build()
        val oldKey = checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.path)).copyOf()
        val plain = sample.copyTo(File(root, "changed.mp4"))
        java.io.RandomAccessFile(plain, "rw").use { it.seek(64); it.write(37) }
        val replacement = File(root, "replacement")
        val store = PortableVideoStore(root)
        PortableVideoStore.Keyring(password.toCharArray()).use { keys -> store.protectCopy(plain, replacement, keys, store.newSalt()) }
        val directoryStamp = file.parentFile!!.lastModified()
        val previousTime = file.lastModified()
        replacement.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        check(file.setLastModified(previousTime + 2000))
        assertEquals(directoryStamp, file.parentFile!!.lastModified())
        assertEquals(setOf("locked://${file.path}", "file://${plain.path}"), build().toSet())
        val key = checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.path))
        assertFalse(oldKey.contentEquals(key))
        val actual = ByteArray(128)
        XorMediaDataSource(file, key).use { assertEquals(128, it.readAt(0, actual, 0, actual.size)) }
        assertArrayEquals(plain.inputStream().use { it.readNBytes(128) }, actual)
    }

    @Test fun legacyAndPortableFoldersBothPreparePlayableKeys() = runBlocking<Unit> {
        val portable = folder("portable", 1).single()
        val legacy = File(root, "legacy").apply { check(mkdir()) }
        val video = sample.copyTo(File(legacy, "legacy-obfuscated"))
        val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val legacyKey = FolderLockManager.deriveXorKey(password, salt)
        java.io.RandomAccessFile(video, "rw").use { file ->
            val header = ByteArray(minOf(8192L, file.length()).toInt())
            file.readFully(header)
            header.indices.forEach { index -> header[index] = (header[index].toInt() xor legacyKey[index % legacyKey.size].toInt()).toByte() }
            file.seek(0); file.write(header)
        }
        legacyKey.fill(0)
        val manifest = LockedFolderManifest(legacy.path, "legacy", listOf(
            LockedFileEntry(video.name, "original.mp4", sample.length())), salt = Base64.getEncoder().encodeToString(salt), lockedAt = 1L)
        val aesSalt = salt.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), aesSalt, 10000, 128)
        val aes = try { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(aes, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        File(legacy, ".neko_manifest.enc").writeBytes(iv + cipher.doFinal(com.google.gson.Gson().toJson(manifest).toByteArray(Charsets.UTF_8)))
        File(legacy, ".neko_locked").writeText(Base64.getEncoder().encodeToString(salt))
        File(legacy, ".nomedia").createNewFile()
        assertEquals(setOf(portable, video).map { "locked://${it.path}" }.toSet(), build().toSet())
        for (file in listOf(portable, video)) {
            val actual = ByteArray(64)
            val source = HybridDataSource(context)
            try {
                assertEquals(sample.length(), source.open(androidx.media3.datasource.DataSpec(
                    android.net.Uri.parse("locked://${file.path}"))))
                assertEquals(64, source.read(actual, 0, actual.size))
            } finally { source.close() }
            assertArrayEquals(sample.inputStream().use { it.readNBytes(64) }, actual)
        }
    }
}
