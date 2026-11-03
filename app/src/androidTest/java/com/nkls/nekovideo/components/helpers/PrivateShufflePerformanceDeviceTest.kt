package com.nkls.nekovideo.components.helpers

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit read-only measurements: no playback, exports, migration or tag edits on the real vault. */
@RunWith(AndroidJUnit4::class)
class PrivateShufflePerformanceDeviceTest {
    @Test fun measureAvailablePasswordProviders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrumentation.targetContext.packageName == "com.nkls.nekovideo.debug" &&
            InstrumentationRegistry.getArguments().getString("measurePrivateShuffle") == "true")
        val algorithm = "PBKDF2WithHmacSHA256"
        val default = javax.crypto.SecretKeyFactory.getInstance(algorithm)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream",
            "KDF default=${default.provider.name} processors=${Runtime.getRuntime().availableProcessors()}\n") })
        var expected: ByteArray? = null
        java.security.Security.getProviders().forEach { provider ->
            val factory = runCatching { javax.crypto.SecretKeyFactory.getInstance(algorithm, provider) }.getOrNull() ?: return@forEach
            val spec = javax.crypto.spec.PBEKeySpec("benchmark-not-a-user-password".toCharArray(), ByteArray(16) { it.toByte() }, 210000, 256)
            val start = SystemClock.elapsedRealtime()
            val result = try { factory.generateSecret(spec).encoded } finally { spec.clearPassword() }
            expected?.let { assertArrayEquals(it, result) } ?: run { expected = result }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "KDF ${provider.name}=${SystemClock.elapsedRealtime() - start}ms\n") })
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val start = SystemClock.elapsedRealtime()
        mac.init(javax.crypto.spec.SecretKeySpec("benchmark-not-a-user-password".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        var u = mac.doFinal(ByteArray(16) { it.toByte() } + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(209999) {
            u = mac.doFinal(u)
            for (index in result.indices) result[index] = (result[index].toInt() xor u[index].toInt()).toByte()
        }
        assertArrayEquals(expected, result)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "KDF via Mac ${mac.provider.name}=${SystemClock.elapsedRealtime() - start}ms\n") })
    }

    @Test fun measureExistingVaultShuffle() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" && args.getString("measurePrivateShuffle") == "true")
        val password = checkNotNull(args.getString("privatePassword"))
        assertTrue(FilesManager.SecureStorage.verifyPassword(context, password))
        val root = File("/storage/emulated/0/NekoVideo")
        assertTrue(root.isDirectory && FolderLockManager.isLocked(root.path))
        // MainActivity restores this discovery cache before exposing the library.
        com.nkls.nekovideo.services.FolderVideoScanner.loadCacheFromDisk(context)
        val metadata = root.walkTopDown().filter { it.isFile && it.name in setOf(
            PortablePrivateFolders.INDEX, PortablePrivateFolders.MARKER, ".neko_manifest.enc", ".neko_locked")
        }.associateWith(PortableVideoStore::sha256)
        val previousVisibility = FilesManager.SecureFoldersVisibility.areSecureFoldersVisible(context)
        try {
            FilesManager.SecureFoldersVisibility.showSecureFolders(context)
            LockedPlaybackSession.clear()
            LockedPlaybackSession.authorize(password)
            val tags = VideoTagStore.getAllTags(context, TagScope.PRIVATE)
            val tag = tags.firstOrNull { it.name.equals("Skincolored", true) } ?: tags.firstOrNull()
            for ((label, required) in listOf("cold" to emptySet(), "warm" to emptySet(), "tagged" to setOfNotNull(tag?.id),
                "tagged-cold" to setOfNotNull(tag?.id))) {
                if (label == "tagged-cold") { LockedPlaybackSession.clear(); LockedPlaybackSession.authorize(password) }
                val start = SystemClock.elapsedRealtime()
                var stage: ShuffleProgress.Stage? = null
                var stageStart = start
                val playlist = ShufflePlaylistBuilder.build(context, root.path, true, true, password = password,
                    tagScope = TagScope.PRIVATE, filter = com.nkls.nekovideo.components.ShuffleTagFilter(required, emptySet(), emptySet()),
                    onProgress = { progress ->
                        if (progress.stage != stage) {
                            val now = SystemClock.elapsedRealtime()
                            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                                "stage[$label][$stage]=${now - stageStart}ms; next=${progress.stage} work=${progress.completed}/${progress.total}\n") })
                            stage = progress.stage; stageStart = now
                        }
                    })
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "stage[$label][$stage]=${SystemClock.elapsedRealtime() - stageStart}ms\n") })
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "shuffle[$label]=${SystemClock.elapsedRealtime() - start}ms playlist=${playlist.size}\n") })
                if (required.isEmpty()) assertTrue(playlist.isNotEmpty())
            }
            metadata.forEach { (file, hash) -> assertEquals(hash, PortableVideoStore.sha256(file)) }
        } finally {
            LockedPlaybackSession.clear()
            FilesManager.SecureFoldersVisibility.setSecureFoldersVisible(context, previousVisibility)
        }
    }
}
