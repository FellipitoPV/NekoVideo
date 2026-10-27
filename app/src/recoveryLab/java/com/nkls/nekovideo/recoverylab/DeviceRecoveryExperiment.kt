package com.nkls.nekovideo.recoverylab

import com.nkls.nekovideo.components.helpers.PortableVideoStore

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.system.measureNanoTime

/** All writable paths are below this variant's own externalFilesDir; inputs are copies only. */
class DeviceRecoveryExperiment(context: Context) {
    val root = File(checkNotNull(context.getExternalFilesDir(null)), "recovery-lab")
    val lab = PortableVideoStore(root)
    val input = File(root, "input")
    private val activeFile = File(root, "active-run.txt")

    fun activeRun(): File {
        val relative = activeFile.readText().trim()
        require(relative.matches(Regex("runs/[0-9a-f-]{36}")))
        return lab.checked(File(root, relative))
    }

    fun prepare(): String {
        val sources = input.listFiles()?.filter { it.isFile && it.extension.equals("mp4", true) }
            ?.sortedBy { it.name }?.take(3).orEmpty()
        require(sources.size >= 2) { "Copie pelo menos dois memes MP4 para ${input.absolutePath}" }
        val run = lab.checked(File(root, "runs/${UUID.randomUUID()}"))
        val protected = File(run, "protected").apply { check(mkdirs()) }
        val report = StringBuilder("Experimento isolado — proteção de 8 KiB + recuperação AES-GCM\n")
        PortableVideoStore.Keyring(PASSWORD.toCharArray()).use { keys ->
            val salt = lab.newSalt()
            val entries = sources.mapIndexed { i, source ->
                val before = PortableVideoStore.sha256(source)
                lateinit var entry: PortableVideoStore.Entry
                val elapsed = measureNanoTime {
                    entry = lab.protectCopy(source, File(protected, "sample-$i.nekolab"), keys, salt)
                }
                check(PortableVideoStore.sha256(source) == before)
                report.append("${source.name}: ${source.length()} bytes; proteger cópia ${ms(elapsed)} ms; " +
                    "acréscimo ${File(protected, entry.diskName).length() - source.length()} bytes\n")
                entry
            }
            lab.writeIndex(File(protected, PortableVideoStore.INDEX_NAME), entries, keys, salt)
            val index = checkNotNull(lab.readIndexOrNull(File(protected, PortableVideoStore.INDEX_NAME), keys))
            entries.forEach { check(lab.resolve(File(protected, it.diskName), index, keys).lookup == PortableVideoStore.Lookup.MANIFEST) }
            val manifestTime = measureNanoTime {
                repeat(20) { entries.forEach { lab.resolve(File(protected, it.diskName), index, keys) } }
            }
            val embeddedTime = measureNanoTime {
                repeat(20) { entries.forEach { lab.resolve(File(protected, it.diskName), null, keys) } }
            }
            report.append("Manifesto: OK. Consulta aquecida média: ${ms(manifestTime / (20 * entries.size))} ms; " +
                "fallback aquecido: ${ms(embeddedTime / (20 * entries.size))} ms.\n")
            report.append("Derivações de senha na sessão: ${keys.derivations}.\n")
        }
        File(run, "prepare-report.txt").writeText(report.toString())
        activeFile.writeText("runs/${run.name}")
        return report.append("Preparado em ${run.absolutePath}\n").toString()
    }

    /** Called by adb/instrumentation or the demo button, only after prepare created this run. */
    fun simulateExternalMove() {
        val run = activeRun()
        val protected = lab.checked(File(run, "protected"))
        val external = lab.checked(File(run, "externally-moved")).apply { check(mkdir()) }
        val withheld = lab.checked(File(run, "withheld-metadata")).apply { check(mkdir()) }
        val files = checkNotNull(protected.listFiles()).filter { it.extension == "nekolab" }.sortedBy { it.name }
        require(files.isNotEmpty())
        files.forEachIndexed { i, file -> check(file.renameTo(File(external, "renamed-$i"))) }
        check(File(protected, PortableVideoStore.INDEX_NAME)
            .renameTo(File(withheld, PortableVideoStore.INDEX_NAME)))
    }

    fun verify(): String {
        val run = activeRun()
        val external = lab.checked(File(run, "externally-moved"))
        val candidates = external.listFiles()?.filter { it.isFile && it.name.startsWith("renamed-") }
            ?.sortedBy { it.name }.orEmpty()
        require(candidates.size >= 2)
        val exports = lab.checked(File(run, "exports-${UUID.randomUUID()}")).apply { check(mkdir()) }
        val report = StringBuilder(File(run, "prepare-report.txt").readText())
        report.append("\nApós movimento/renomeação e nova sessão, sem manifesto:\n")
        val coldStart = System.nanoTime()
        PortableVideoStore.Keyring(PASSWORD.toCharArray()).use { keys ->
            candidates.forEachIndexed { i, file ->
                val protectedBefore = PortableVideoStore.sha256(file)
                val resolved = lab.resolve(file, null, keys)
                check(resolved.lookup == PortableVideoStore.Lookup.EMBEDDED)
                if (i == 0) report.append("Primeiro desbloqueio frio: ${ms(System.nanoTime() - coldStart)} ms.\n")
                val source = lab.checked(File(input, resolved.metadata.originalName))
                val originalHash = PortableVideoStore.sha256(source)
                val restored = File(exports, resolved.metadata.originalName)
                val restoreTime = measureNanoTime { lab.restoreCopy(file, restored, keys) }
                check(PortableVideoStore.sha256(restored) == originalHash)
                check(PortableVideoStore.sha256(file) == protectedBefore)
                check(PortableVideoStore.sha256(source) == originalHash)
                verifyRandomReads(source, file, resolved.metadata)
                verifyDecoder(source, file, resolved.metadata)
                report.append("${source.name}: SHA-256 idêntico; leitura aleatória e frame inicial/meio OK; " +
                    "exportar ${ms(restoreTime)} ms.\n")
            }
            // A corrupt manifest must also use the fallback, without changing it automatically.
            val indexFile = File(external, PortableVideoStore.INDEX_NAME)
            indexFile.writeText("manifesto corrompido apenas do laboratório")
            val previous = indexFile.readBytes()
            val damagedIndex = lab.readIndexOrNull(indexFile, keys)
            check(damagedIndex == null)
            candidates.forEach { file ->
                check(lab.resolve(file, damagedIndex, keys).lookup == PortableVideoStore.Lookup.EMBEDDED)
            }
            check(indexFile.readBytes().contentEquals(previous))
            lab.rebuildIndex(external, candidates, keys, lab.newSalt())
            val rebuilt = checkNotNull(lab.readIndexOrNull(indexFile, keys))
            candidates.forEach { check(lab.resolve(it, rebuilt, keys).lookup == PortableVideoStore.Lookup.MANIFEST) }
            report.append("Manifesto reconstruído: todos os arquivos voltaram à consulta pelo índice.\n")
        }
        val first = candidates.first()
        val originalProtectedHash = PortableVideoStore.sha256(first)
        PortableVideoStore.Keyring("senha-errada-do-laboratorio".toCharArray()).use { wrong ->
            val rejected = runCatching { lab.restoreCopy(first, File(exports, "wrong-password.mp4"), wrong) }.isFailure
            check(rejected && !File(exports, "wrong-password.mp4").exists())
        }
        check(PortableVideoStore.sha256(first) == originalProtectedHash)
        report.append("Senha incorreta recusada; arquivos protegidos e cópias de entrada preservados.\n")
        File(run, "verification-report.txt").writeText(report.toString())
        return report.toString()
    }

    private fun verifyRandomReads(source: File, protected: File, metadata: PortableVideoStore.Metadata) {
        RandomAccessFile(source, "r").use { expected ->
            RecoveryLabMediaDataSource(protected, metadata).use { actual ->
                check(actual.getSize() == source.length())
                val positions = listOf(0L, 8170L, 8192L, source.length() / 2, (source.length() - 100).coerceAtLeast(0))
                positions.filter { it < source.length() }.forEach { position ->
                    val size = minOf(4096L, source.length() - position).toInt()
                    val wanted = ByteArray(size)
                    val found = ByteArray(size)
                    expected.seek(position)
                    expected.readFully(wanted)
                    check(actual.readAt(position, found, 0, size) == size && found.contentEquals(wanted))
                }
                check(actual.readAt(source.length(), ByteArray(16), 0, 16) == -1)
            }
        }
    }

    private fun verifyDecoder(source: File, protected: File, metadata: PortableVideoStore.Metadata) {
        val original = MediaMetadataRetriever()
        val recovered = MediaMetadataRetriever()
        try {
            original.setDataSource(source.absolutePath)
            RecoveryLabMediaDataSource(protected, metadata).use { data ->
                recovered.setDataSource(data)
                val duration = checkNotNull(original.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                check(recovered.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) == duration)
                listOf(0L, duration.toLong() * 500L).forEach { timestamp ->
                    val a = checkNotNull(original.getFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                    val b = checkNotNull(recovered.getFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                    try { check(a.sameAs(b)) { "Decoded frame mismatch" } }
                    finally { a.recycle(); b.recycle() }
                }
            }
        } finally { original.release(); recovered.release() }
    }

    companion object {
        // Disposable experiment password; never use or request the user's real vault password.
        const val PASSWORD = "NekoVideo-lab-only-2026"
        private fun ms(nanos: Long) = String.format(java.util.Locale.US, "%.2f", nanos / 1_000_000.0)
    }
}
