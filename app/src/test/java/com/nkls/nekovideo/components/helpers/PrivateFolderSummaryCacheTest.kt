package com.nkls.nekovideo.components.helpers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateFolderSummaryCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private class CountingFolder(folder: File) : File(folder.absolutePath) {
        var listings = 0
        var modified = 1L
        override fun lastModified() = modified
        override fun listFiles(): Array<File>? {
            listings++
            return super.listFiles()
        }
    }

    @Test fun returningToParentDoesNotRecountAThousandVideos() {
        val folder = CountingFolder(temporary.newFolder())
        repeat(1_000) { File(folder, "$it.mp4").writeBytes(byteArrayOf(1)) }
        val cache = PrivateFolderSummaryCache()

        val first = cache.read(folder, false)
        repeat(10) { assertEquals(first, cache.read(folder, false)) }

        assertEquals(PrivateFolderSummary(1_000, 0, 1_000), first)
        assertEquals(1, folder.listings)
    }

    @Test fun addedAndRemovedFilesInvalidateOnlyChangedFolder() {
        val folder = CountingFolder(temporary.newFolder())
        val unchanged = CountingFolder(temporary.newFolder())
        val cache = PrivateFolderSummaryCache()
        cache.read(folder, false)
        cache.read(unchanged, false)
        val video = File(folder, "new.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        folder.modified++
        assertEquals(PrivateFolderSummary(1, 0, 2), cache.read(folder, false))
        assertTrue(video.delete())
        folder.modified++
        assertEquals(PrivateFolderSummary(0, 0, 0), cache.read(folder, false))
        cache.read(unchanged, false)
        assertEquals(1, unchanged.listings)
        assertEquals(3, folder.listings)
    }

    @Test fun lockedMetadataIsExcludedAndManifestChangesInvalidate() {
        val folder = CountingFolder(temporary.newFolder())
        val manifest = File(folder, ".neko_manifest.enc").apply {
            writeText("encrypted")
            assertTrue(setLastModified(10_000L))
        }
        File(folder, ".neko_locked").writeText("marker")
        File(folder, "obfuscated").writeBytes(byteArrayOf(1, 2, 3))
        File(folder, "child").mkdir()
        File(folder, ".neko_thumbs").mkdir()
        val cache = PrivateFolderSummaryCache()
        assertEquals(PrivateFolderSummary(1, 1, 3), cache.read(folder, true))
        assertTrue(manifest.setLastModified(20_000L))
        cache.read(folder, true)
        assertEquals(2, folder.listings)
    }

    @Test fun refreshAndBackgroundingCanDiscardSummaries() {
        val folder = CountingFolder(temporary.newFolder())
        val cache = PrivateFolderSummaryCache()
        cache.read(folder, false)
        cache.clear()
        cache.read(folder, false)
        assertEquals(2, folder.listings)
    }

    @Test fun cacheIsBoundedAndKeepsRecentlyUsedFolders() {
        val a = CountingFolder(temporary.newFolder())
        val b = CountingFolder(temporary.newFolder())
        val c = CountingFolder(temporary.newFolder())
        val cache = PrivateFolderSummaryCache(capacity = 2)
        cache.read(a, false)
        cache.read(b, false)
        cache.read(a, false)
        cache.read(c, false)
        cache.read(a, false)
        cache.read(b, false)
        assertEquals(1, a.listings)
        assertEquals(2, b.listings)
    }
}
