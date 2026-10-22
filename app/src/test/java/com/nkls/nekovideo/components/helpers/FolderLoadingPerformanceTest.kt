package com.nkls.nekovideo.components.helpers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderLoadingPerformanceTest {
    private class Entry(path: String, private val directory: Boolean) : File(path) {
        var directoryChecks = 0
        override fun isDirectory(): Boolean {
            directoryChecks++
            return directory
        }
    }

    private class Folder(private val entries: Array<File>) : File("videos") {
        var listings = 0
        override fun listFiles(): Array<File> {
            listings++
            return entries
        }
    }

    @Test
    fun cachedVideosSkipDiskChecksAndUnknownFoldersStillAppear() {
        val videos = (1..2_000).map { Entry("videos/$it.mp4", false) }
        val regularFolder = Entry("videos/subfolder", true)
        val folderWithVideoExtension = Entry("videos/collection.mp4", true)
        val newVideo = Entry("videos/new.mp4", false)
        val entries = videos + listOf(regularFolder, folderWithVideoExtension, newVideo)
        val folder = Folder(entries.toTypedArray())

        val result = readFolderChildren(folder, videos.map { it.absolutePath }.toSet())

        assertEquals(listOf(regularFolder, folderWithVideoExtension), result.subfolders)
        assertEquals(entries, result.entries)
        assertEquals(1, folder.listings)
        assertEquals(0, videos.sumOf { it.directoryChecks })
        assertEquals(1, newVideo.directoryChecks)
    }

    @Test
    fun alphabeticalOrderTreatsNumbersAsTextInBothDirections() {
        val names = listOf("video 2.mp4", "video 10.mp4", "Video 1.mp4")
        assertEquals(
            listOf("Video 1.mp4", "video 10.mp4", "video 2.mp4"),
            names.sortedByName { it }
        )
        assertEquals(
            listOf("video 2.mp4", "video 10.mp4", "Video 1.mp4"),
            names.sortedByName(descending = true) { it }
        )
    }

    @Test
    fun caseInsensitiveTiesKeepOriginalOrder() {
        val names = listOf("B.mp4", "a.mp4", "A.mp4", "b.mp4")
        assertEquals(listOf("a.mp4", "A.mp4", "B.mp4", "b.mp4"), names.sortedByName { it })
        assertEquals(
            listOf("B.mp4", "b.mp4", "a.mp4", "A.mp4"),
            names.sortedByName(descending = true) { it }
        )
    }

}
