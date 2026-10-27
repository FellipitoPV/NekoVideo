package com.nkls.nekovideo.components.helpers

import java.io.File
import java.util.Base64
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LockedPlaybackSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clear() = LockedPlaybackSession.clear()

    private fun manifest(folder: File): LockedFolderManifest {
        val metadata = PortableVideoStore.Metadata(UUID.randomUUID().toString(), "new.mp4", 120,
            "0".repeat(64), "1".repeat(64), Base64.getEncoder().encodeToString(ByteArray(32) { 11 }))
        return LockedFolderManifest(folder.absolutePath, folder.name, listOf(
            LockedFileEntry("legacy", "old.mp4", 360),
            LockedFileEntry("portable", "new.mp4", 120, portableMetadata = metadata)
        ), salt = Base64.getEncoder().encodeToString(ByteArray(16)), lockedAt = 1)
    }

    @Test fun mixedEntriesHaveCorrectKeysAndUntrackedPlainFilesGetNoKey() {
        val folder = temporary.newFolder()
        LockedPlaybackSession.start(ByteArray(32) { 7 }, manifest(folder), folder.absolutePath, "test-only")
        assertArrayEquals(ByteArray(32) { 7 }, LockedPlaybackSession.getXorKeyForFile(File(folder, "legacy").absolutePath))
        assertArrayEquals(ByteArray(32) { 11 }, LockedPlaybackSession.getXorKeyForFile(File(folder, "portable").absolutePath))
        assertEquals(120L, LockedPlaybackSession.getPayloadSize(File(folder, "portable").absolutePath))
        assertNull(LockedPlaybackSession.getPayloadSize(File(folder, "legacy").absolutePath))
        assertNull(LockedPlaybackSession.getXorKeyForFile(File(folder, "untracked.mp4").absolutePath))
        assertNull(LockedPlaybackSession.getXorKeyForFile("no-parent"))
    }

    @Test fun refreshPreservesKeysAndActiveFolderAndClearDiscardsAllSessions() {
        val first = temporary.newFolder()
        val second = temporary.newFolder()
        val original = ByteArray(32) { 7 }
        val firstManifest = manifest(first)
        LockedPlaybackSession.start(original, firstManifest, first.absolutePath, "test-only")
        original.fill(0)
        val oldReference = checkNotNull(LockedPlaybackSession.getXorKeyForFile(File(first, "legacy").absolutePath))
        LockedPlaybackSession.start(ByteArray(32) { 8 }, manifest(second), second.absolutePath, "test-only")
        LockedPlaybackSession.updateManifest(first.absolutePath, firstManifest)
        assertEquals(second.absolutePath, LockedPlaybackSession.currentFolderPath)
        assertArrayEquals(ByteArray(32), oldReference)
        assertArrayEquals(ByteArray(32) { 7 }, LockedPlaybackSession.getXorKeyForFile(File(first, "legacy").absolutePath))
        val portableKey = checkNotNull(LockedPlaybackSession.getXorKeyForFile(File(first, "portable").absolutePath))
        LockedPlaybackSession.clear()
        assertArrayEquals(ByteArray(32), portableKey)
        assertFalse(LockedPlaybackSession.isActive)
        assertNull(LockedPlaybackSession.sessionPassword)
    }
}
