package com.nkls.nekovideo.recoverylab

import com.nkls.nekovideo.components.helpers.PortableVideoStore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deliberately stopped by ADB at a synced checkpoint, then verified in a new process. */
@RunWith(AndroidJUnit4::class)
class CrashRecoveryLabDeviceTest {
    private val support = LabDeviceSupport()
    private val mode = checkNotNull(InstrumentationRegistry.getArguments().getString("crashMode")).also {
        require(it in setOf("protect", "export", "index", "rotation"))
    }
    private val operationId = checkNotNull(InstrumentationRegistry.getArguments().getString("operationId")).also {
        require(it.matches(Regex("[0-9a-f-]{36}")))
    }
    private val pointer get() = File(support.root, "crash-active-$mode-$operationId.txt")

    @Test fun waitForExternalForceStop() {
        val lab = support.lab
        val work = support.work("crash-$mode")
        val source = support.source()
        val first = File(work, "first.nekolab")
        val second = File(work, "second.nekolab")
        val indexFile = File(work, PortableVideoStore.INDEX_NAME)
        PortableVideoStore.Keyring(DeviceRecoveryExperiment.PASSWORD.toCharArray()).use { keys ->
            val salt = lab.newSalt()
            val entries = listOf(lab.protectCopy(source, first, keys, salt), lab.protectCopy(source, second, keys, salt))
            lab.writeIndex(indexFile, entries, keys, salt)
            val state = Properties().apply {
                setProperty("sourceName", source.name)
                setProperty("sourceHash", PortableVideoStore.sha256(source))
                setProperty("firstHash", PortableVideoStore.sha256(first))
                setProperty("secondHash", PortableVideoStore.sha256(second))
                setProperty("indexHash", PortableVideoStore.sha256(indexFile))
            }
            File(work, "state.properties").outputStream().use { state.store(it, "Disposable lab crash checkpoint") }
            pointer.writeText("advanced/${work.name}")
            val targetName = when (mode) {
                "protect" -> "uncommitted.nekolab"
                "export" -> "uncommitted.mp4"
                "index" -> PortableVideoStore.INDEX_NAME
                else -> second.name
            }
            val failing = PortableVideoStore(support.root) { stage, destination ->
                if (stage == PortableVideoStore.Stage.FILE_SYNCED && destination.name == targetName) {
                    val marker = File(work, "ready-for-force-stop")
                    marker.outputStream().use { stream ->
                        stream.write("FILE_SYNCED $mode".toByteArray())
                        stream.fd.sync()
                    }
                    CountDownLatch(1).await(45, TimeUnit.SECONDS)
                    error("External force-stop did not arrive")
                }
            }
            when (mode) {
                "protect" -> failing.protectCopy(source, File(work, targetName), keys, salt)
                "export" -> failing.restoreCopy(first, File(work, targetName), keys)
                "index" -> failing.writeIndex(indexFile, emptyList(), keys, salt)
                else -> PortableVideoStore.Keyring(ROTATED_PASSWORD.toCharArray()).use { newKeys ->
                    failing.rewrapBatchCopies(listOf(first, second), File(work, "rotation"), keys, newKeys, lab.newSalt())
                }
            }
        }
        fail("Expected a real external process interruption")
    }

    @Test fun verifyAndRetryAfterForceStop() {
        val relative = pointer.readText().trim()
        require(relative.matches(Regex("advanced/crash-$mode-[0-9a-f-]{36}")))
        val work = support.lab.checked(File(support.root, relative))
        assertTrue(File(work, "ready-for-force-stop").isFile)
        val state = Properties().apply { File(work, "state.properties").inputStream().use { load(it) } }
        val source = support.lab.checked(File(support.experiment.input, state.getProperty("sourceName")))
        val first = File(work, "first.nekolab")
        val second = File(work, "second.nekolab")
        val indexFile = File(work, PortableVideoStore.INDEX_NAME)
        assertEquals(state.getProperty("sourceHash"), PortableVideoStore.sha256(source))
        assertEquals(state.getProperty("firstHash"), PortableVideoStore.sha256(first))
        assertEquals(state.getProperty("secondHash"), PortableVideoStore.sha256(second))
        assertEquals(state.getProperty("indexHash"), PortableVideoStore.sha256(indexFile))
        val pendingFolders = listOf(work, File(work, "rotation")).filter { it.isDirectory }
        val pending = pendingFolders.flatMap { it.listFiles().orEmpty().filter { file -> file.name.endsWith(".part") } }
        assertTrue("No staged file remained after the real stop", pending.isNotEmpty())
        PortableVideoStore.Keyring(DeviceRecoveryExperiment.PASSWORD.toCharArray()).use { keys ->
            when (mode) {
                "protect" -> {
                    val output = File(work, "uncommitted.nekolab")
                    assertFalse(output.exists())
                    support.lab.protectCopy(source, output, keys, support.lab.newSalt())
                    assertEquals(state.getProperty("sourceHash"), support.lab.resolve(output, null, keys).metadata.originalSha256)
                }
                "export" -> {
                    val output = File(work, "uncommitted.mp4")
                    assertFalse(output.exists())
                    support.lab.restoreCopy(first, output, keys)
                    assertEquals(state.getProperty("sourceHash"), PortableVideoStore.sha256(output))
                }
                "index" -> {
                    assertEquals(2, checkNotNull(support.lab.readIndexOrNull(indexFile, keys)).entries.size)
                    support.lab.writeIndex(indexFile, emptyList(), keys, support.lab.newSalt())
                    val index = checkNotNull(support.lab.readIndexOrNull(indexFile, keys))
                    assertEquals(0, index.entries.size)
                    assertEquals(PortableVideoStore.Lookup.EMBEDDED, support.lab.resolve(first, index, keys).lookup)
                }
                else -> PortableVideoStore.Keyring(ROTATED_PASSWORD.toCharArray()).use { newKeys ->
                    val folder = File(work, "rotation")
                    val completed = File(folder, first.name)
                    val before = PortableVideoStore.sha256(completed)
                    assertFalse(File(folder, second.name).exists())
                    assertFalse(File(folder, PortableVideoStore.INDEX_NAME).exists())
                    val index = support.lab.rewrapBatchCopies(listOf(first, second), folder, keys, newKeys, support.lab.newSalt())
                    assertEquals(before, PortableVideoStore.sha256(completed))
                    assertEquals(2, index.entries.size)
                    listOf(first, second).forEach { original ->
                        val rotated = File(folder, original.name)
                        val export = File(work, "restored-${original.name}.mp4")
                        support.lab.restoreCopy(rotated, export, newKeys, index)
                        assertEquals(state.getProperty("sourceHash"), PortableVideoStore.sha256(export))
                        assertTrue(runCatching { support.lab.resolve(rotated, null, keys) }.isFailure)
                    }
                }
            }
        }
        pending.forEach { check(support.lab.checked(it).delete()) }
        assertEquals(state.getProperty("sourceHash"), PortableVideoStore.sha256(source))
        assertEquals(state.getProperty("firstHash"), PortableVideoStore.sha256(first))
        assertEquals(state.getProperty("secondHash"), PortableVideoStore.sha256(second))
        support.report("CRASH_$mode", "force-stop real após fsync; entradas e índice anterior preservados; nova execução/reinício do lote bem-sucedido; temporários órfãos removidos somente neste teste")
    }

    companion object { private const val ROTATED_PASSWORD = "rotation-password-lab-only" }
}
