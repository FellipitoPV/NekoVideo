package com.nkls.nekovideo.recoverylab

import com.nkls.nekovideo.components.helpers.PortableVideoStore

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.components.helpers.FolderLockManager
import com.nkls.nekovideo.components.helpers.XorMediaDataSource
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal class LabDeviceSupport {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "com.nkls.nekovideo.recoverylab")
    }
    val experiment = DeviceRecoveryExperiment(context)
    val lab = experiment.lab
    val root = experiment.root
    fun work(name: String) = lab.checked(File(root, "advanced/$name-${UUID.randomUUID()}")).apply { check(mkdirs()) }
    fun source() = checkNotNull(experiment.input.listFiles()?.filter { it.extension == "mp4" }?.minByOrNull { it.length() })
    fun password(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("legacyPassword")) {
        "Supply legacyPassword through instrumentation arguments; never embed real credentials in source"
    }
    fun legacyFixture() = lab.checked(File(root, "legacy-fixture-20261026"))
    fun report(name: String, text: String) {
        File(root, "advanced-report.txt").appendText("$name: $text\n")
    }
    fun activeLegacy(): File {
        val relative = File(root, "legacy-bridge-active.txt").readText().trim()
        require(relative.matches(Regex("advanced/legacy-[0-9a-f-]{36}")))
        return lab.checked(File(root, relative))
    }
    fun hashMedia(source: MediaDataSource): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        var position = 0L
        while (position < source.size) {
            val read = source.readAt(position, bytes, 0, minOf(bytes.size.toLong(), source.size - position).toInt())
            check(read > 0)
            hash.update(bytes, 0, read)
            position += read
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun compareDecoders(old: MediaDataSource, portable: MediaDataSource): Int {
        val a = MediaMetadataRetriever()
        val b = MediaMetadataRetriever()
        try {
            a.setDataSource(old)
            b.setDataSource(portable)
            val duration = checkNotNull(a.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            assertEquals(duration.toString(), b.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
            var frames = 0
            listOf(0L, duration * 500L).forEach { time ->
                val x = checkNotNull(a.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                val y = checkNotNull(b.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                try { assertTrue(x.sameAs(y)); frames++ } finally { x.recycle(); y.recycle() }
            }
            // The same reader can extract small thumbnails without the folder manifest.
            val x = checkNotNull(a.getScaledFrameAtTime(duration * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 120))
            val y = checkNotNull(b.getScaledFrameAtTime(duration * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 120))
            try { assertTrue(x.sameAs(y)); frames++ } finally { x.recycle(); y.recycle() }
            return frames
        } finally { a.release(); b.release() }
    }
}

/** Methods with prepare/recover names are deliberately invoked separately around actual ADB moves. */
@RunWith(AndroidJUnit4::class)
class AdvancedRecoveryLabDeviceTest {
    @Test fun prepareUserLegacyCopy() {
        val support = LabDeviceSupport()
        val lab = support.lab
        val fixture = support.legacyFixture()
        val before = checkNotNull(fixture.listFiles()).filter { it.isFile }.associate { it.name to PortableVideoStore.sha256(it) }
        val password = support.password()
        val manifest = checkNotNull(FolderLockManager.readManifest(fixture.absolutePath, password))
        val key = FolderLockManager.deriveXorKey(password, checkNotNull(FolderLockManager.getSalt(fixture.absolutePath)))
        val work = support.work("legacy")
        val protected = File(work, "protected").apply { check(mkdir()) }
        PortableVideoStore.Keyring(password.toCharArray()).use { keys ->
            val salt = lab.newSalt()
            val entries = manifest.files.mapIndexed { i, item ->
                val source = lab.checked(File(fixture, item.obfuscatedName))
                assertEquals(item.originalSize, source.length())
                val entry = lab.attachLegacyCopy(source, File(protected, "legacy-$i.nekolab"), item.originalName, key, keys, salt)
                XorMediaDataSource(source, key).use { old -> assertEquals(support.hashMedia(old), entry.metadata.originalSha256) }
                val plainReference = lab.checked(File(support.experiment.input, item.originalName))
                if (plainReference.isFile) assertEquals(PortableVideoStore.sha256(plainReference), entry.metadata.originalSha256)
                entry
            }
            lab.writeIndex(File(protected, PortableVideoStore.INDEX_NAME), entries, keys, salt)
            val index = checkNotNull(lab.readIndexOrNull(File(protected, PortableVideoStore.INDEX_NAME), keys))
            entries.forEach { assertEquals(PortableVideoStore.Lookup.MANIFEST, lab.resolve(File(protected, it.diskName), index, keys).lookup) }
        }
        key.fill(0)
        before.forEach { (name, hash) -> assertEquals(hash, PortableVideoStore.sha256(File(fixture, name))) }
        File(support.root, "legacy-bridge-active.txt").writeText("advanced/${work.name}")
        support.report("LEGACY_PREPARE", "${manifest.files.size} arquivo(s) legado(s); ciphertext original preservado; recuperação anexada apenas em novas cópias")
    }

    @Test fun recoverUserLegacyCopyAfterExternalMove() {
        val support = LabDeviceSupport()
        val lab = support.lab
        val work = support.activeLegacy()
        val moved = File(work, "externally-moved")
        val files = checkNotNull(moved.listFiles()).filter { it.name.startsWith("renamed-") }
        assertTrue(files.isNotEmpty())
        val password = support.password()
        val fixture = support.legacyFixture()
        val manifest = checkNotNull(FolderLockManager.readManifest(fixture.absolutePath, password))
        val legacyKey = FolderLockManager.deriveXorKey(password, checkNotNull(FolderLockManager.getSalt(fixture.absolutePath)))
        val exports = File(work, "exports-${UUID.randomUUID()}").apply { check(mkdir()) }
        PortableVideoStore.Keyring(password.toCharArray()).use { keys ->
            files.forEach { file ->
                val before = PortableVideoStore.sha256(file)
                val resolved = lab.resolve(file, null, keys)
                assertEquals(PortableVideoStore.Lookup.EMBEDDED, resolved.lookup)
                val oldEntry = manifest.files.single { it.originalName == resolved.metadata.originalName }
                val oldFile = lab.checked(File(fixture, oldEntry.obfuscatedName))
                val export = File(exports, resolved.metadata.originalName)
                lab.restoreCopy(file, export, keys)
                assertEquals(resolved.metadata.originalSha256, PortableVideoStore.sha256(export))
                XorMediaDataSource(oldFile, legacyKey).use { old ->
                    assertEquals(support.hashMedia(old), PortableVideoStore.sha256(export))
                    RecoveryLabMediaDataSource(file, resolved.metadata).use { current ->
                        assertEquals(3, support.compareDecoders(old, current))
                    }
                }
                PortableVideoStore.Keyring("replacement-password-lab-only".toCharArray()).use { replacement ->
                    val rotated = File(exports, "rotated-${file.name}")
                    lab.rewrapCopy(file, rotated, keys, replacement, lab.newSalt())
                    assertTrue(runCatching { lab.resolve(rotated, null, keys) }.isFailure)
                    val restored = File(exports, "rotated-${resolved.metadata.originalName}")
                    lab.restoreCopy(rotated, restored, replacement)
                    assertEquals(PortableVideoStore.sha256(export), PortableVideoStore.sha256(restored))
                }
                assertEquals(before, PortableVideoStore.sha256(file))
            }
            lab.rebuildIndex(moved, files, keys, lab.newSalt())
        }
        legacyKey.fill(0)
        support.report("LEGACY_RECOVER", "${files.size} arquivo(s) recuperado(s) em novo processo após movimento externo; SHA-256, duração, frames, miniatura e troca de senha nas cópias: OK")
    }

    @Test fun batchAndTenThousandEntryIndexOnDevice() {
        val support = LabDeviceSupport()
        val lab = support.lab
        val work = support.work("batch")
        val source = support.source()
        val expected = PortableVideoStore.sha256(source)
        val start = SystemClock.elapsedRealtime()
        PortableVideoStore.Keyring(DeviceRecoveryExperiment.PASSWORD.toCharArray()).use { keys ->
            val salt = lab.newSalt()
            val entries = (0 until 250).map { lab.protectCopy(source, File(work, "video-$it.nekolab"), keys, salt) }
            val preparedMs = SystemClock.elapsedRealtime() - start
            val indexFile = File(work, PortableVideoStore.INDEX_NAME)
            lab.writeIndex(indexFile, entries, keys, salt)
            val index = checkNotNull(lab.readIndexOrNull(indexFile, keys))
            val scanStart = SystemClock.elapsedRealtime()
            entries.forEach { assertEquals(PortableVideoStore.Lookup.MANIFEST, lab.resolve(File(work, it.diskName), index, keys).lookup) }
            val indexScanMs = SystemClock.elapsedRealtime() - scanStart
            val moved = File(work, "moved").apply { check(mkdir()) }
            val movedFiles = entries.mapIndexed { i, entry ->
                val target = File(moved, "renamed-$i")
                check(File(work, entry.diskName).renameTo(target))
                target
            }
            val fallbackStart = SystemClock.elapsedRealtime()
            movedFiles.forEach { file ->
                val item = lab.resolve(file, null, keys)
                assertEquals(PortableVideoStore.Lookup.EMBEDDED, item.lookup)
                assertEquals(expected, item.metadata.originalSha256)
            }
            val fallbackMs = SystemClock.elapsedRealtime() - fallbackStart
            lab.rebuildIndex(moved, movedFiles, keys, salt)
            val rebuilt = checkNotNull(lab.readIndexOrNull(File(moved, PortableVideoStore.INDEX_NAME), keys))
            assertEquals(250, rebuilt.entries.size)
            movedFiles.filterIndexed { i, _ -> i % 25 == 0 }.forEachIndexed { i, file ->
                val export = File(work, "export-$i.mp4")
                lab.restoreCopy(file, export, keys, rebuilt)
                assertEquals(expected, PortableVideoStore.sha256(export))
            }
            val largeEntries = entries + (250 until 10_000).map {
                entries.first().copy(diskName = "virtual-$it", metadata = entries.first().metadata.copy(id = UUID.randomUUID().toString()))
            }
            val largeIndex = File(work, "large-index.enc")
            val largeStart = SystemClock.elapsedRealtime()
            lab.writeIndex(largeIndex, largeEntries, keys, salt)
            assertEquals(10_000, checkNotNull(lab.readIndexOrNull(largeIndex, keys)).entries.size)
            assertEquals(1, keys.derivations)
            support.report("BATCH", "250 arquivos físicos: preparar ${preparedMs} ms; consultar índice ${indexScanMs} ms; fallback ${fallbackMs} ms; 10 exportações SHA-256 OK; índice sintético 10.000 entradas (${largeIndex.length()} bytes), gravar/ler ${SystemClock.elapsedRealtime() - largeStart} ms")
        }
        assertEquals(expected, PortableVideoStore.sha256(source))
    }

    @Test fun largePayloadRoundTripAndOffsetsAboveFourGiB() {
        val support = LabDeviceSupport()
        val lab = support.lab
        val work = support.work("large")
        val source = lab.checked(File(work, "synthetic-256MiB.mp4"))
        support.source().copyTo(source)
        RandomAccessFile(source, "rw").use { it.setLength(256L * 1024 * 1024 + 123) }
        val size = source.length()
        val before = PortableVideoStore.sha256(source)
        val protected = File(work, "large.nekolab")
        val restored = File(work, "restored.mp4")
        val start = SystemClock.elapsedRealtime()
        PortableVideoStore.Keyring(DeviceRecoveryExperiment.PASSWORD.toCharArray()).use { keys ->
            lab.protectCopy(source, protected, keys, lab.newSalt())
            val resolved = lab.restoreCopy(protected, restored, keys)
            assertEquals(size, resolved.metadata.payloadSize)
            assertEquals(before, PortableVideoStore.sha256(restored))
            assertEquals(before, PortableVideoStore.sha256(source))
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        // Sparse fixture targets Long arithmetic, independently of the full 256 MiB round-trip.
        val sparse = lab.checked(File(work, "sparse-offset-check"))
        val sparseSize = 4L * 1024 * 1024 * 1024 + 123
        val key = ByteArray(32) { 7 }
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6)
        val offsets = listOf(2L * 1024 * 1024 * 1024 + 19, 4L * 1024 * 1024 * 1024 + 41)
        RandomAccessFile(sparse, "rw").use { raf ->
            raf.setLength(sparseSize)
            offsets.forEach { raf.seek(it); raf.write(expected) }
        }
        val metadata = PortableVideoStore.Metadata(UUID.randomUUID().toString(), "sparse.mp4", sparseSize,
            "0".repeat(64), "0".repeat(64), Base64.getEncoder().encodeToString(key))
        RecoveryLabMediaDataSource(sparse, metadata).use { reader ->
            assertEquals(sparseSize, reader.getSize())
            offsets.forEach {
                val bytes = ByteArray(expected.size)
                assertEquals(bytes.size, reader.readAt(it, bytes, 0, bytes.size))
                assertArrayEquals(expected, bytes)
            }
            assertEquals(-1, reader.readAt(sparseSize, ByteArray(10), 0, 10))
        }
        // Remove only these known, disposable large files; keep the small report and other fixtures.
        listOf(source, protected, restored, sparse).forEach { check(lab.checked(it).delete()) }
        support.report("LARGE", "${size} bytes: proteção + exportação + hashes ${elapsed} ms; SHA-256 idêntico; leitura esparsa acima de 2 e 4 GiB OK; fixtures grandes removidas")
    }

    @Test fun loopbackHttpRangeRequestsMatchOriginalMedia() {
        val support = LabDeviceSupport()
        val work = support.work("http")
        val source = support.source()
        val original = source.readBytes()
        val protected = File(work, "http.nekolab")
        PortableVideoStore.Keyring(DeviceRecoveryExperiment.PASSWORD.toCharArray()).use { keys ->
            val entry = support.lab.protectCopy(source, protected, keys, support.lab.newSalt())
            val recovered = support.lab.resolve(protected, null, keys)
            val server = RecoveryLabRangeServer(protected, recovered.metadata)
            server.start()
            try {
                fun request(range: String?, status: Int, expected: ByteArray?) {
                    val connection = URL("http://127.0.0.1:${server.listeningPort}/video").openConnection() as HttpURLConnection
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    if (range != null) connection.setRequestProperty("Range", range)
                    try {
                        assertEquals(status, connection.responseCode)
                        if (expected != null) connection.inputStream.use { assertArrayEquals(expected, it.readBytes()) }
                        if (status == 200) assertEquals(entry.metadata.payloadSize, connection.contentLengthLong)
                    } finally { connection.disconnect() }
                }
                request(null, 200, original)
                request("bytes=0-63", 206, original.copyOfRange(0, 64))
                request("bytes=8180-8210", 206, original.copyOfRange(8180, 8211))
                request("bytes=${original.size - 100}-", 206, original.takeLast(100).toByteArray())
                request("bytes=-75", 206, original.takeLast(75).toByteArray())
                request("bytes=${original.size}-", 416, null)
                request("bytes=20-10", 416, null)
                request("bytes=0-2,4-6", 416, null)
                support.report("HTTP", "loopback: arquivo completo e intervalos inicial, fronteira 8 KB, final e sufixo idênticos; intervalos inválidos recusados; trailer não transmitido")
            } finally { server.stop() }
        }
    }
}
