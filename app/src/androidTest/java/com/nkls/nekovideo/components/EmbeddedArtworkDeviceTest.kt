package com.nkls.nekovideo.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.components.helpers.LockedFileEntry
import com.nkls.nekovideo.components.helpers.LockedFolderManifest
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedArtworkDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun embeddedCoversArePreferredAndPersistedInTheExistingCache() = withFolder { folder ->
        for (name in listOf("cover.mkv", "cover.mp4")) {
            val video = copyAsset(folder, name)
            val originalDigest = digest(video)
            val thumbnail = checkNotNull(OptimizedThumbnailManager.generateThumbnailSync(context, video.path))
            assertColor(thumbnail, Color.RED)
            val targetSize = OptimizedThumbnailManager.getCurrentThumbnailSize(context)
            assertEquals(targetSize, thumbnail.width)
            assertEquals(targetSize, thumbnail.height)
            assertSame(thumbnail, OptimizedThumbnailManager.generateThumbnailSync(context, video.path))
            OptimizedThumbnailManager.thumbnailCache.remove(video.path.hashCode().toString())
            assertColor(checkNotNull(OptimizedThumbnailManager.loadThumbnailFromDiskSync(context, video.path)), Color.RED)
            assertArrayEquals(originalDigest, digest(video))
        }
    }

    @Test fun fallsBackToAFrameWhenTheCoverIsAbsentOrInvalid() = withFolder { folder ->
        for (name in listOf("plain.mp4", "plain.mkv", "broken-cover.mkv")) {
            val video = copyAsset(folder, name)
            assertColor(checkNotNull(OptimizedThumbnailManager.generateThumbnailSync(context, video.path)), Color.BLUE)
        }
        val malformed = File(folder, "malformed.mkv").apply { writeText("invalid container") }
        assertNull(OptimizedThumbnailManager.generateThumbnailSync(context, malformed.path))
    }

    @Test fun pngAttachmentIsUsedWhenTheMainJpegIsInvalid() = withFolder { folder ->
        val video = copyAsset(folder, "alternate-cover.mkv")
        assertColor(checkNotNull(OptimizedThumbnailManager.generateThumbnailSync(context, video.path)), Color.GREEN)
    }

    @Test fun lockedVideosUseEmbeddedArtworkThroughTheExistingXorDataSource() = withFolder { folder ->
        val key = ByteArray(32) { (it + 11).toByte() }
        val bytes = instrumentation.context.assets.open("embedded-covers/cover.mkv").use { it.readBytes() }
        for (index in 0 until minOf(8_192, bytes.size)) {
            bytes[index] = (bytes[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
        val video = File(folder, "obfuscated").apply { writeBytes(bytes) }
        File(folder, ".neko_locked").createNewFile()
        val originalDigest = digest(video)
        assertNull(OptimizedThumbnailManager.generateThumbnailSync(context, "locked://${video.path}"))
        val manifest = LockedFolderManifest(
            folder.path, folder.name, listOf(LockedFileEntry(video.name, "clip.mkv", video.length())),
            salt = "", lockedAt = 1
        )
        LockedPlaybackSession.start(key, manifest, folder.path)
        try {
            val thumbnail = OptimizedThumbnailManager.generateThumbnailSync(context, "locked://${video.path}")
            assertNotNull(thumbnail)
            assertColor(checkNotNull(thumbnail), Color.RED)
            assertColor(checkNotNull(OptimizedThumbnailManager.loadThumbnailFromDiskSync(context, "locked://${video.path}")), Color.RED)
            assertArrayEquals(originalDigest, digest(video))
        } finally {
            LockedPlaybackSession.removeSession(folder.path)
            key.fill(0)
        }
    }

    private fun copyAsset(folder: File, name: String) = File(folder, name).also { video ->
        instrumentation.context.assets.open("embedded-covers/$name").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun assertColor(bitmap: Bitmap, expected: Int) {
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val dominant = when (expected) {
            Color.RED -> Color.red(pixel) - maxOf(Color.green(pixel), Color.blue(pixel))
            Color.GREEN -> Color.green(pixel) - maxOf(Color.red(pixel), Color.blue(pixel))
            else -> Color.blue(pixel) - maxOf(Color.red(pixel), Color.green(pixel))
        }
        assertTrue("Unexpected image color: $pixel", dominant > 60)
    }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun withFolder(test: (File) -> Unit) {
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "embedded-artwork-").toFile()
        try {
            test(folder)
        } finally {
            check(folder.parentFile == context.cacheDir && folder.name.startsWith("embedded-artwork-"))
            folder.listFiles().orEmpty().filter { it.isFile }.forEach {
                OptimizedThumbnailManager.clearCacheForPath(context, it.path)
            }
            folder.deleteRecursively()
        }
    }
}
