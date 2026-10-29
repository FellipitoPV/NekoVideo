package com.nkls.nekovideo.components.helpers

import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableVideoStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(test: (PortableVideoStore, File, File, PortableVideoStore.Entry,
        PortableVideoStore.Keyring, ByteArray) -> Unit) {
        val lab = PortableVideoStore(temporary.newFolder())
        val source = File(lab.workspace, "meme original.mp4").apply {
            writeBytes(ByteArray(180_123) { (it * 37).toByte() })
        }
        val protected = File(lab.workspace, "video.nekolab")
        PortableVideoStore.Keyring("lab-password-only".toCharArray()).use { keys ->
            val salt = lab.newSalt()
            val entry = lab.protectCopy(source, protected, keys, salt)
            test(lab, source, protected, entry, keys, salt)
        }
    }

    @Test fun indexFastPathAndEmbeddedFallbackRestoreExactlyTheSameBytes() = fixture { lab, source, file, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, listOf(entry), keys, salt)
        val index = assertNotNullValue(lab.readIndexOrNull(indexFile, keys))
        assertEquals(PortableVideoStore.Lookup.MANIFEST, lab.resolve(file, index, keys).lookup)
        val restored = File(lab.workspace, "restored.mp4")
        lab.restoreCopy(file, restored, keys, index)
        assertArrayEquals(source.readBytes(), restored.readBytes())
        assertTrue(indexFile.delete())
        val fallback = File(lab.workspace, "fallback.mp4")
        assertEquals(PortableVideoStore.Lookup.EMBEDDED,
            lab.restoreCopy(file, fallback, keys, lab.readIndexOrNull(indexFile, keys)).lookup)
        assertArrayEquals(source.readBytes(), fallback.readBytes())
    }

    @Test fun movedRenamedFileRecoversInFreshSessionAndRebuildsIndex() = fixture { lab, source, file, _, _, _ ->
        val folder = File(lab.workspace, "externally-moved").apply { mkdir() }
        val moved = File(folder, "unrecognizable-name")
        assertTrue(file.renameTo(moved))
        PortableVideoStore.Keyring("lab-password-only".toCharArray()).use { fresh ->
            val metadata = lab.resolve(moved, null, fresh).metadata
            assertEquals(source.name, metadata.originalName)
            val restored = File(folder, metadata.originalName)
            lab.restoreCopy(moved, restored, fresh)
            assertArrayEquals(source.readBytes(), restored.readBytes())
            lab.rebuildIndex(folder, listOf(moved), fresh, lab.newSalt())
            val rebuilt = lab.readIndexOrNull(File(folder, PortableVideoStore.INDEX_NAME), fresh)
            assertEquals(PortableVideoStore.Lookup.MANIFEST, lab.resolve(moved, rebuilt, fresh).lookup)
        }
    }

    @Test fun corruptOrIncompleteIndexFallsBackWithoutOverwritingIt() = fixture { lab, _, file, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, emptyList(), keys, salt)
        assertEquals(PortableVideoStore.Lookup.EMBEDDED,
            lab.resolve(file, lab.readIndexOrNull(indexFile, keys), keys).lookup)
        indexFile.writeBytes(byteArrayOf(0, 1, 2))
        assertNull(lab.readIndexOrNull(indexFile, keys))
        assertEquals(PortableVideoStore.Lookup.EMBEDDED, lab.resolve(file, null, keys).lookup)
        assertArrayEquals(byteArrayOf(0, 1, 2), indexFile.readBytes())
        lab.writeIndex(indexFile, listOf(entry.copy(envelopeSha256 = "0".repeat(64))), keys, salt)
        assertEquals(PortableVideoStore.Lookup.EMBEDDED,
            lab.resolve(file, lab.readIndexOrNull(indexFile, keys), keys).lookup)
    }

    @Test fun wrongPasswordDoesNotChangeProtectedFileOrPublishAnExport() = fixture { lab, _, file, _, _, _ ->
        val before = file.readBytes()
        val output = File(lab.workspace, "wrong-password.mp4")
        PortableVideoStore.Keyring("wrong-password".toCharArray()).use { wrong ->
            assertThrows(Exception::class.java) { lab.restoreCopy(file, output, wrong) }
        }
        assertFalse(output.exists())
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun tamperedMetadataIsRejectedEvenWithAnOlderValidIndex() = fixture { lab, _, file, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, listOf(entry), keys, salt)
        val index = lab.readIndexOrNull(indexFile, keys)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(entry.metadata.payloadSize)
            val byte = raf.readByte()
            raf.seek(entry.metadata.payloadSize)
            raf.writeByte(byte.toInt() xor 1)
        }
        assertThrows(Exception::class.java) { lab.resolve(file, index, keys) }
    }

    @Test fun damagedPayloadDoesNotPublishExportOrChangeSource() = fixture { lab, _, file, _, keys, _ ->
        RandomAccessFile(file, "rw").use { raf -> raf.seek(30_000); raf.writeByte(7) }
        val before = file.readBytes()
        val output = File(lab.workspace, "damaged.mp4")
        assertThrows(IllegalArgumentException::class.java) { lab.restoreCopy(file, output, keys) }
        assertFalse(output.exists())
        assertArrayEquals(before, file.readBytes())
        assertFalse(lab.workspace.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun truncatedFooterAndHostileLengthsAreRejected() = fixture { lab, _, file, _, keys, _ ->
        val original = file.readBytes()
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 5) }
        assertThrows(IllegalArgumentException::class.java) { lab.resolve(file, null, keys) }
        file.writeBytes(original)
        RandomAccessFile(file, "rw").use { raf -> raf.seek(raf.length() - 12); raf.writeInt(Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { lab.resolve(file, null, keys) }
    }

    @Test fun sourceAndExistingDestinationAreNeverOverwrittenAndPathsCannotEscape() = fixture { lab, source, file, _, keys, _ ->
        val before = source.readBytes()
        assertThrows(IllegalArgumentException::class.java) { lab.restoreCopy(file, source, keys) }
        assertArrayEquals(before, source.readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            lab.restoreCopy(file, File(lab.workspace, "../outside.mp4"), keys)
        }
        assertThrows(IllegalArgumentException::class.java) {
            lab.protectCopy(source, File(lab.workspace, "../outside.nekolab"), keys, lab.newSalt())
        }
    }

    @Test fun aGroupOnlyDerivesPasswordKeyOncePerSession() = fixture { lab, source, file, entry, _, salt ->
        PortableVideoStore.Keyring("lab-password-only".toCharArray()).use { fresh ->
            repeat(20) { lab.resolve(file, null, fresh) }
            val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
            lab.writeIndex(indexFile, listOf(entry), fresh, salt)
            lab.readIndexOrNull(indexFile, fresh)
            lab.protectCopy(source, File(lab.workspace, "second.nekolab"), fresh, salt)
            assertEquals(1, fresh.derivations)
        }
    }

    @Test fun aFileFromAnotherGroupUsesItsOwnEmbeddedSaltAndKey() = fixture { lab, source, _, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, listOf(entry), keys, salt)
        val foreign = File(lab.workspace, "from-another-folder")
        lab.protectCopy(source, foreign, keys, lab.newSalt())
        PortableVideoStore.Keyring("lab-password-only".toCharArray()).use { fresh ->
            val index = lab.readIndexOrNull(indexFile, fresh)
            val output = File(lab.workspace, "foreign-restored.mp4")
            assertEquals(PortableVideoStore.Lookup.EMBEDDED,
                lab.restoreCopy(foreign, output, fresh, index).lookup)
            assertArrayEquals(source.readBytes(), output.readBytes())
            assertEquals(2, fresh.derivations)
        }
    }

    @Test fun smallAndEmptyFilesRoundTripAndNamesAreEncrypted() {
        val lab = PortableVideoStore(temporary.newFolder())
        PortableVideoStore.Keyring("lab-password-only".toCharArray()).use { keys ->
            val salt = lab.newSalt()
            listOf(0, 1, 8191, 8192, 8193).forEach { size ->
                val source = File(lab.workspace, "nome confidencial $size.mp4").apply {
                    writeBytes(ByteArray(size) { (it * 11).toByte() })
                }
                val file = File(lab.workspace, "$size.nekolab")
                lab.protectCopy(source, file, keys, salt)
                assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(source.name))
                val output = File(lab.workspace, "$size-restored.mp4")
                lab.restoreCopy(file, output, keys)
                assertArrayEquals(source.readBytes(), output.readBytes())
            }
        }
    }

    @Test fun legacyCiphertextReceivesRecoveryWithoutChangingItsPayloadOrSource() = fixture { lab, source, _, _, keys, salt ->
        val key = ByteArray(32) { (it * 13).toByte() }
        val legacy = File(lab.workspace, "legacy-ciphertext")
        val ciphertext = source.readBytes()
        PortableVideoStore.xor(ciphertext, 0, ciphertext.size, 0, key)
        legacy.writeBytes(ciphertext)
        val wrapped = File(lab.workspace, "legacy-with-recovery")
        lab.attachLegacyCopy(legacy, wrapped, source.name, key, keys, salt)
        RandomAccessFile(wrapped, "r").use { raf ->
            val actualPayload = ByteArray(ciphertext.size)
            raf.readFully(actualPayload)
            assertArrayEquals(ciphertext, actualPayload)
        }
        val restored = File(lab.workspace, "legacy-restored.mp4")
        lab.restoreCopy(wrapped, restored, keys)
        assertArrayEquals(source.readBytes(), restored.readBytes())
        assertArrayEquals(ciphertext, legacy.readBytes())
    }

    @Test fun passwordChangePreservesPayloadAndRejectsOldPasswordIncludingOldIndex() = fixture { lab, source, file, entry, oldKeys, salt ->
        val oldIndexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(oldIndexFile, listOf(entry), oldKeys, salt)
        val oldIndex = lab.readIndexOrNull(oldIndexFile, oldKeys)
        val originalProtected = file.readBytes()
        val rotated = File(lab.workspace, "changed-password")
        PortableVideoStore.Keyring("new-lab-password".toCharArray()).use { newKeys ->
            lab.rewrapCopy(file, rotated, oldKeys, newKeys, lab.newSalt())
            assertThrows(Exception::class.java) { lab.resolve(rotated, oldIndex, oldKeys) }
            val output = File(lab.workspace, "new-password.mp4")
            lab.restoreCopy(rotated, output, newKeys, oldIndex)
            assertArrayEquals(source.readBytes(), output.readBytes())
            RandomAccessFile(rotated, "r").use { raf ->
                val payload = ByteArray(source.length().toInt())
                raf.readFully(payload)
                assertArrayEquals(originalProtected.copyOf(payload.size), payload)
            }
        }
        assertArrayEquals(originalProtected, file.readBytes())
    }

    @Test fun aDecryptedIndexCannotBypassWrongOrClosedSession() = fixture { lab, _, file, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, listOf(entry), keys, salt)
        val index = lab.readIndexOrNull(indexFile, keys)
        PortableVideoStore.Keyring("wrong-password".toCharArray()).use { wrong ->
            assertThrows(Exception::class.java) { lab.resolve(file, index, wrong) }
        }
        keys.close()
        assertThrows(IllegalStateException::class.java) { lab.resolve(file, index, keys) }
    }

    @Test fun interruptedWritesPreserveInputsAndPreviousIndex() = fixture { lab, source, file, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, listOf(entry), keys, salt)
        val oldIndexBytes = indexFile.readBytes()
        val sourceBytes = source.readBytes()
        val ciphertext = file.readBytes()
        val failing = PortableVideoStore(lab.workspace) { _, _ -> throw IOException("Injected write interruption") }
        val output = File(lab.workspace, "interrupted.nekolab")
        assertThrows(IOException::class.java) { failing.protectCopy(source, output, keys, salt) }
        assertFalse(output.exists())
        assertThrows(IOException::class.java) { failing.writeIndex(indexFile, emptyList(), keys, salt) }
        assertArrayEquals(oldIndexBytes, indexFile.readBytes())
        val export = File(lab.workspace, "interrupted-export.mp4")
        assertThrows(IOException::class.java) { failing.restoreCopy(file, export, keys) }
        assertFalse(export.exists())
        assertArrayEquals(sourceBytes, source.readBytes())
        assertArrayEquals(ciphertext, file.readBytes())
        assertFalse(lab.workspace.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun passwordChangeBatchResumesWithoutReplacingCompletedFiles() = fixture { lab, source, file, _, oldKeys, _ ->
        val second = File(lab.workspace, "second-for-rotation")
        lab.protectCopy(source, second, oldKeys, lab.newSalt())
        val folder = File(lab.workspace, "rotation-batch").apply { mkdir() }
        val failing = PortableVideoStore(lab.workspace) { stage, destination ->
            if (stage == PortableVideoStore.Stage.FILE_SYNCED && destination.name == second.name) {
                throw IOException("Stop after one committed file")
            }
        }
        PortableVideoStore.Keyring("new-lab-password".toCharArray()).use { newKeys ->
            assertThrows(IOException::class.java) {
                failing.rewrapBatchCopies(listOf(file, second), folder, oldKeys, newKeys, lab.newSalt())
            }
            val completed = File(folder, file.name)
            val before = completed.readBytes()
            assertFalse(File(folder, PortableVideoStore.INDEX_NAME).exists())
            lab.rewrapBatchCopies(listOf(file, second), folder, oldKeys, newKeys, lab.newSalt())
            assertArrayEquals(before, completed.readBytes())
            val index = assertNotNullValue(lab.readIndexOrNull(File(folder, PortableVideoStore.INDEX_NAME), newKeys))
            listOf(file, second).forEach {
                val protected = File(folder, it.name)
                assertEquals(PortableVideoStore.Lookup.MANIFEST, lab.resolve(protected, index, newKeys).lookup)
                val output = File(lab.workspace, "${it.name}-resumed.mp4")
                lab.restoreCopy(protected, output, newKeys, index)
                assertArrayEquals(source.readBytes(), output.readBytes())
            }
        }
    }

    @Test fun tenThousandEntryIndexRoundTripsAndRejectsOverflowWithoutReplacement() = fixture { lab, _, file, entry, keys, salt ->
        val entries = listOf(entry) + (1 until 10_000).map {
            entry.copy(diskName = "copy-$it", metadata = entry.metadata.copy(id = UUID.randomUUID().toString()))
        }
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, entries, keys, salt)
        assertTrue(indexFile.length() > 1024 * 1024)
        val index = assertNotNullValue(lab.readIndexOrNull(indexFile, keys))
        assertEquals(10_000, index.entries.size)
        assertEquals(PortableVideoStore.Lookup.MANIFEST, lab.resolve(file, index, keys).lookup)
        val before = PortableVideoStore.sha256(indexFile)
        assertThrows(IllegalArgumentException::class.java) {
            lab.writeIndex(indexFile, entries + entry.copy(diskName = "overflow"), keys, salt)
        }
        assertEquals(before, PortableVideoStore.sha256(indexFile))
    }

    @Test fun authenticatedManifestRecoversDamagedTrailerOnlyWhenPayloadStillMatches() = fixture { lab, source, file, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        lab.writeIndex(indexFile, listOf(entry), keys, salt)
        val index = assertNotNullValue(lab.readIndexOrNull(indexFile, keys))
        RandomAccessFile(file, "rw").use { raf -> raf.setLength(entry.metadata.payloadSize) }
        assertThrows(Exception::class.java) { lab.resolve(file, index, keys) }
        val recovered = lab.resolve(file, index, keys, allowManifestRecovery = true)
        assertEquals(PortableVideoStore.Lookup.MANIFEST, recovered.lookup)
        val output = File(lab.workspace, "recovered-with-index.mp4")
        lab.restoreKnownCopy(file, output, recovered.metadata)
        assertArrayEquals(source.readBytes(), output.readBytes())
        PortableVideoStore.Keyring("wrong-password".toCharArray()).use { wrong ->
            assertThrows(Exception::class.java) { lab.resolve(file, index, wrong, allowManifestRecovery = true) }
        }
        RandomAccessFile(file, "rw").use { raf -> raf.seek(30_000); raf.writeByte(7) }
        assertThrows(Exception::class.java) { lab.resolve(file, index, keys, allowManifestRecovery = true) }
    }

    @Test fun folderMetadataIsAuthenticatedAndRoundTrips() = fixture { lab, _, _, entry, keys, salt ->
        val indexFile = File(lab.workspace, PortableVideoStore.INDEX_NAME)
        val directories = listOf(PortableVideoStore.Directory("uuid-child", "Vídeos filhos"))
        lab.writeIndex(indexFile, listOf(entry), keys, salt, "Coleção privada", directories, true)
        val index = assertNotNullValue(lab.readIndexOrNull(indexFile, keys))
        assertEquals("Coleção privada", index.folderName)
        assertEquals(directories, index.directories)
        assertEquals(true, index.privateFolder)
    }

    private fun <T : Any> assertNotNullValue(value: T?): T { assertNotNull(value); return value!! }

    @Test fun revokedPublicationKeepsCiphertextAndRemovesPlaintextTemporaryFiles() = fixture { lab, _, file, entry, _, _ ->
        val protected = file.readBytes()
        val output = File(lab.workspace, "revoked.mp4")
        assertThrows(IllegalStateException::class.java) {
            lab.restoreKnownCopy(file, output, entry.metadata, commit = { error("Visibility revoked") })
        }
        assertFalse(output.exists())
        assertArrayEquals(protected, file.readBytes())
        assertFalse(lab.workspace.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test fun revocationDuringCopyAbortsWithoutPublishingPlaintext() = fixture { lab, _, file, entry, _, _ ->
        val protected = file.readBytes()
        val output = File(lab.workspace, "interrupted.mp4")
        var chunks = 0
        assertThrows(IllegalStateException::class.java) {
            lab.restoreKnownCopy(file, output, entry.metadata, checkPermission = {
                if (++chunks == 2) error("Visibility revoked during copy")
            })
        }
        assertEquals(2, chunks)
        assertFalse(output.exists())
        assertArrayEquals(protected, file.readBytes())
        assertFalse(lab.workspace.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }
}
