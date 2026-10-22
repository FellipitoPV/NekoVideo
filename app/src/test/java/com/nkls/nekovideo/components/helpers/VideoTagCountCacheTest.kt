package com.nkls.nekovideo.components.helpers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTagCountCacheTest {
    @Test
    fun editingOneVideoInLargeFolderQueriesOnlyThatVideo() = runBlocking {
        val paths = (1..10_000).map { "/videos/$it.mp4" }.toSet()
        val editedPath = "/videos/5000.mp4"
        val storedCounts = mutableMapOf<String, Int>()
        val queries = mutableListOf<List<String>>()
        val load: suspend (List<String>) -> Map<String, Int> = { requested ->
            queries += requested
            requested.associateWith { storedCounts[it] ?: 0 }
        }
        var cache = VideoTagCountCache().refresh(paths, VideoTagChange(), 0, load)
        queries.clear()

        storedCounts[editedPath] = 1
        cache = cache.refresh(paths, VideoTagChange(1, setOf(editedPath)), 0, load)

        assertEquals(listOf(listOf(editedPath)), queries)
        assertEquals(9_999, paths.count { cache.counts[it] == 0 })
        assertFalse(cache.counts[editedPath] == 0)

        // Removing the last tag restores the video from the existing file cache.
        queries.clear()
        storedCounts[editedPath] = 0
        cache = cache.refresh(paths, VideoTagChange(2, setOf(editedPath)), 0, load)
        assertEquals(listOf(listOf(editedPath)), queries)
        assertTrue(paths.all { cache.counts[it] == 0 })
    }

    @Test
    fun unchangedVideosAreNotQueriedAgainWhenCacheGrows() = runBlocking {
        val firstPath = "/videos/first.mp4"
        val secondPath = "/videos/second.mp4"
        val change = VideoTagChange()
        var cache = VideoTagCountCache().refresh(setOf(firstPath), change, 0) { mapOf(firstPath to 1) }

        cache = cache.refresh(setOf(firstPath), change, 0) { error("Unchanged cache queried again") }
        cache = cache.refresh(setOf(firstPath, secondPath), change, 0) { requested ->
            assertEquals(listOf(secondPath), requested)
            mapOf(secondPath to 0)
        }

        assertEquals(mapOf(firstPath to 1, secondPath to 0), cache.counts)
    }

    @Test
    fun missedChangesRefreshTagCountsSoEarlierEditsAreNotLost() = runBlocking {
        val paths = setOf("/videos/first.mp4", "/videos/second.mp4")
        var cache = VideoTagCountCache().refresh(paths, VideoTagChange(), 0) { requested ->
            requested.associateWith { 0 }
        }

        // Both videos changed while the screen was paused; only the latest event is replayed.
        cache = cache.refresh(paths, VideoTagChange(2, setOf(paths.last())), 0) { requested ->
            assertEquals(paths, requested.toSet())
            requested.associateWith { 1 }
        }
        assertTrue(paths.none { cache.counts[it] == 0 })
    }

    @Test
    fun deletingTagGloballyRestoresUntaggedVideosWithoutReloadingFiles() = runBlocking {
        val paths = setOf("/videos/first.mp4", "/videos/second.mp4")
        var cache = VideoTagCountCache().refresh(paths, VideoTagChange(), 0) { requested ->
            requested.associateWith { 1 }
        }
        cache = cache.refresh(paths, VideoTagChange(1), 0) { requested ->
            assertEquals(paths, requested.toSet())
            requested.associateWith { 0 }
        }
        assertTrue(paths.all { cache.counts[it] == 0 })
    }
}
