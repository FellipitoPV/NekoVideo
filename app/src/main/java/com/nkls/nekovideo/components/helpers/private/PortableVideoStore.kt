package com.nkls.nekovideo.components.helpers

import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned portable private-file format. Payload protection intentionally remains the legacy 8 KiB XOR scheme;
 * only the self-contained recovery metadata and index use authenticated AES-GCM encryption.
 * This is NOT full video encryption. Normal builds write this format; the isolated legacy lab opts out.
 *
 * Layout: XOR-protected payload | encrypted recovery JSON | authenticated 64-byte footer.
 * A password-protected record travels with each file, including when renamed or moved.
 * The index duplicates those records for fast lookup and is always replaceable by a scan.
 */
class PortableVideoStore(
    workspace: File,
    private val migrationTemporaryFiles: Boolean = false,
    private val checkpoint: (Stage, File) -> Unit = { _, _ -> }
) {
    val workspace: File = workspace.canonicalFile
    private val gson = Gson()
    private val random = SecureRandom()

    data class Metadata(
        val id: String,
        val originalName: String,
        val payloadSize: Long,
        val originalSha256: String,
        val protectedSha256: String,
        val contentKey: String,
        val folderName: String? = null,
        val duration: String? = null
    )

    data class Entry(val diskName: String, val metadata: Metadata, val envelopeSha256: String)
    data class Directory(val diskName: String, val originalName: String)
    data class Index(val version: Int = VERSION, val entries: List<Entry>,
                     val folderName: String? = null, val directories: List<Directory>? = null,
                     val privateFolder: Boolean? = null) {
        @Transient private var authenticatedSession: Keyring? = null
        @Transient private var byName: Map<String, Entry>? = null
        internal fun bind(session: Keyring): Index = apply { authenticatedSession = session }
        internal fun authenticatedFor(session: Keyring) = authenticatedSession === session
        internal fun find(name: String): Entry? {
            val lookup = byName ?: entries.associateBy { it.diskName }.also { byName = it }
            return lookup[name]
        }
    }
    enum class Stage { PAYLOAD_WRITTEN, FILE_SYNCED }
    enum class Lookup { MANIFEST, EMBEDDED }
    data class Resolved(val metadata: Metadata, val lookup: Lookup)
    data class Envelope(val footer: ByteArray, val ciphertext: ByteArray, val salt: ByteArray,
                        val nonce: ByteArray, val payloadSize: Long)

    /** KDF runs once per group salt per unlocked session, never per media read. */
    class Keyring(password: CharArray) : AutoCloseable {
        private val password = password.copyOf()
        private val keys = mutableMapOf<String, ByteArray>()
        var derivations: Int = 0
            private set
        private var closed = false

        internal fun requireOpen() = check(!closed) { "Session closed" }

        internal fun key(salt: ByteArray): ByteArray {
            requireOpen()
            require(salt.size == 16)
            return keys.getOrPut(Base64.getEncoder().encodeToString(salt)) {
                derivations++
                val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
                try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
                finally { spec.clearPassword() }
            }
        }

        override fun close() {
            password.fill('\u0000')
            keys.values.forEach { it.fill(0) }
            keys.clear()
            closed = true
        }
    }

    init { require(this.workspace.isDirectory || this.workspace.mkdirs()) }

    fun newSalt(): ByteArray = ByteArray(16).also(random::nextBytes)

    /** All inputs, generated copies and restored files must live in this dedicated workspace. */
    fun checked(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(workspace.toPath()) && canonical != workspace) {
            "Access outside the operation folder is forbidden"
        }
        return canonical
    }

    fun protectCopy(source: File, destination: File, keyring: Keyring, salt: ByteArray,
                    folderName: String? = null, duration: String? = null): Entry {
        val input = checked(source)
        val output = checked(destination)
        require(input.isFile && !output.exists() && input != output)
        require(validName(input.name))
        val contentKey = ByteArray(32).also(random::nextBytes)
        val originalHash = MessageDigest.getInstance("SHA-256")
        val protectedHash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var result: Entry? = null
        try {
            publish(output, replace = false) { temp ->
                temp.outputStream().buffered().use { out ->
                    input.inputStream().buffered().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            originalHash.update(buffer, 0, count)
                            xor(buffer, 0, count, size, contentKey)
                            protectedHash.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                            size += count
                        }
                    }
                    checkpoint(Stage.PAYLOAD_WRITTEN, output)
                    val metadata = Metadata(UUID.randomUUID().toString(), input.name, size,
                        hex(originalHash.digest()), hex(protectedHash.digest()),
                        Base64.getEncoder().encodeToString(contentKey), folderName, duration)
                    val envelope = encrypt(gson.toJson(metadata), FILE_MAGIC, size, salt, keyring)
                    out.write(envelope.ciphertext)
                    out.write(envelope.footer)
                    result = Entry(output.name, metadata, envelopeHash(envelope))
                }
            }
            return checkNotNull(result)
        } finally { contentKey.fill(0) }
    }

    fun writeIndex(file: File, entries: List<Entry>, keyring: Keyring, salt: ByteArray,
                   folderName: String? = null, directories: List<Directory> = emptyList(),
                   privateFolder: Boolean? = null) {
        val output = checked(file)
        require(entries.size <= MAX_ENTRIES && entries.map { it.diskName }.distinct().size == entries.size)
        entries.forEach { validate(it.metadata); require(validName(it.diskName)) }
        require(folderName == null || validName(folderName))
        directories.forEach { require(validName(it.diskName) && validName(it.originalName)) }
        val envelope = encrypt(gson.toJson(Index(entries = entries, folderName = folderName,
            directories = directories, privateFolder = privateFolder)), INDEX_MAGIC, 0, salt, keyring)
        publish(output, replace = true) { temp ->
            temp.outputStream().use { it.write(envelope.ciphertext); it.write(envelope.footer) }
        }
    }

    /** A missing, damaged or outdated index must never prevent a valid embedded fallback. */
    fun readIndexOrNull(file: File, keyring: Keyring): Index? {
        return try {
            val envelope = readEnvelope(checked(file), INDEX_MAGIC)
            val index = gson.fromJson(decrypt(envelope, keyring), Index::class.java)
            require(index.version == VERSION && index.entries.size <= MAX_ENTRIES && envelope.payloadSize == 0L)
            index.entries.forEach {
                validate(it.metadata)
                require(validName(it.diskName) && it.envelopeSha256.matches(HASH))
            }
            require(index.entries.map { it.diskName }.distinct().size == index.entries.size)
            require(index.folderName == null || validName(index.folderName))
            index.directories.orEmpty().forEach { require(validName(it.diskName) && validName(it.originalName)) }
            index.bind(keyring)
        } catch (_: Exception) { null }
    }

    fun resolve(file: File, index: Index?, keyring: Keyring, allowManifestRecovery: Boolean = false): Resolved {
        keyring.requireOpen()
        val cached = index?.takeIf { it.authenticatedFor(keyring) }?.find(file.name)
        fun trustedRecovery(): Resolved? {
            if (!allowManifestRecovery || cached == null) return null
            validate(cached.metadata)
            if (file.length() < cached.metadata.payloadSize) return null
            return if (payloadHash(file, cached.metadata.payloadSize) == cached.metadata.protectedSha256)
                Resolved(cached.metadata, Lookup.MANIFEST) else null
        }
        val envelope = try { readEnvelope(checked(file), FILE_MAGIC) }
        catch (failure: Exception) { return trustedRecovery() ?: throw failure }
        // The authenticated index is trusted only when the actual file's record still matches.
        if (cached != null && cached.metadata.payloadSize == envelope.payloadSize &&
            cached.envelopeSha256 == envelopeHash(envelope)) {
            validate(cached.metadata)
            return Resolved(cached.metadata, Lookup.MANIFEST)
        }
        return try {
            val metadata = gson.fromJson(decrypt(envelope, keyring), Metadata::class.java)
            validate(metadata)
            require(metadata.payloadSize == envelope.payloadSize) { "Payload length mismatch" }
            Resolved(metadata, Lookup.EMBEDDED)
        } catch (failure: Exception) { trustedRecovery() ?: throw failure }
    }

    fun inspectEntry(file: File, index: Index?, keyring: Keyring,
                     allowManifestRecovery: Boolean = false): Entry {
        val metadata = resolve(file, index, keyring, allowManifestRecovery).metadata
        val fingerprint = runCatching { envelopeHash(readEnvelope(file, FILE_MAGIC)) }
            .getOrElse { index?.find(file.name)?.envelopeSha256 ?: throw it }
        return Entry(file.name, metadata, fingerprint)
    }

    fun readSalt(file: File, index: Boolean = false): ByteArray =
        readEnvelope(checked(file), if (index) INDEX_MAGIC else FILE_MAGIC).salt.copyOf()

    fun rebuildIndex(folder: File, files: List<File>, keyring: Keyring, salt: ByteArray): Index {
        val directory = checked(folder)
        require(directory.isDirectory)
        // Finish validation of every candidate before committing a replacement index.
        val entries = files.map { file ->
            require(checked(file).parentFile == directory)
            val recovered = resolve(file, null, keyring)
            Entry(file.name, recovered.metadata, envelopeHash(readEnvelope(file, FILE_MAGIC)))
        }
        writeIndex(File(directory, INDEX_NAME), entries, keyring, salt)
        return Index(entries = entries).bind(keyring)
    }

    /** Attach recovery to a COPY of legacy ciphertext without writing any plaintext to storage. */
    fun attachLegacyCopy(source: File, destination: File, originalName: String, legacyKey: ByteArray,
                         keyring: Keyring, salt: ByteArray, folderName: String? = null,
                         duration: String? = null): Entry {
        val input = checked(source)
        val output = checked(destination)
        require(input.isFile && !output.exists() && input != output && legacyKey.size == 32)
        require(validName(originalName))
        val originalHash = MessageDigest.getInstance("SHA-256")
        val protectedHash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var result: Entry? = null
        publish(output, replace = false) { temp ->
            temp.outputStream().buffered().use { out ->
                input.inputStream().buffered().use { stream ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(bytes)
                        if (count < 0) break
                        protectedHash.update(bytes, 0, count)
                        out.write(bytes, 0, count)
                        xor(bytes, 0, count, size, legacyKey)
                        originalHash.update(bytes, 0, count)
                        size += count
                    }
                }
                checkpoint(Stage.PAYLOAD_WRITTEN, output)
                val metadata = Metadata(UUID.randomUUID().toString(), originalName, size,
                    hex(originalHash.digest()), hex(protectedHash.digest()), Base64.getEncoder().encodeToString(legacyKey),
                    folderName, duration)
                val envelope = encrypt(gson.toJson(metadata), FILE_MAGIC, size, salt, keyring)
                out.write(envelope.ciphertext)
                out.write(envelope.footer)
                result = Entry(output.name, metadata, envelopeHash(envelope))
            }
        }
        return checkNotNull(result)
    }

    /** Password-change experiment: a fresh copy with a new wrapping key, identical media payload. */
    fun rewrapCopy(source: File, destination: File, oldKeys: Keyring, newKeys: Keyring,
                   newSalt: ByteArray): Entry {
        return rewrapKnownCopy(source, destination, resolve(source, null, oldKeys).metadata, newKeys, newSalt)
    }

    /** Metadata must come from an index/record authenticated by the caller's unlocked session. */
    internal fun rewrapKnownCopy(source: File, destination: File, metadata: Metadata,
                                 newKeys: Keyring, newSalt: ByteArray): Entry {
        val input = checked(source)
        val output = checked(destination)
        require(!output.exists() && input != output)
        validate(metadata)
        val hash = MessageDigest.getInstance("SHA-256")
        var result: Entry? = null
        publish(output, replace = false) { temp ->
            temp.outputStream().buffered().use { out ->
                RandomAccessFile(input, "r").use { raf ->
                    val bytes = ByteArray(64 * 1024)
                    var remaining = metadata.payloadSize
                    while (remaining > 0) {
                        val count = minOf(bytes.size.toLong(), remaining).toInt()
                        raf.readFully(bytes, 0, count)
                        hash.update(bytes, 0, count)
                        out.write(bytes, 0, count)
                        remaining -= count
                    }
                }
                require(hex(hash.digest()) == metadata.protectedSha256) { "Protected payload changed" }
                checkpoint(Stage.PAYLOAD_WRITTEN, output)
                val envelope = encrypt(gson.toJson(metadata), FILE_MAGIC, metadata.payloadSize, newSalt, newKeys)
                out.write(envelope.ciphertext)
                out.write(envelope.footer)
                result = Entry(output.name, metadata, envelopeHash(envelope))
            }
        }
        return checkNotNull(result)
    }

    /** Resume an interrupted password-change COPY batch. Originals are never replaced. */
    fun rewrapBatchCopies(sources: List<File>, destinationFolder: File, oldKeys: Keyring,
                          newKeys: Keyring, newSalt: ByteArray): Index {
        val folder = checked(destinationFolder)
        require(folder.isDirectory || folder.mkdirs())
        require(sources.size <= MAX_ENTRIES && sources.map { it.name }.distinct().size == sources.size)
        val entries = sources.map { source ->
            val input = checked(source)
            val output = checked(File(folder, input.name))
            require(input != output)
            if (!output.exists()) {
                rewrapCopy(input, output, oldKeys, newKeys, newSalt)
            } else {
                val expected = resolve(input, null, oldKeys).metadata
                val actual = resolve(output, null, newKeys).metadata
                require(actual == expected) { "Existing batch output belongs to another video" }
                require(payloadHash(output, actual.payloadSize) == actual.protectedSha256) {
                    "Existing batch output is damaged"
                }
                Entry(output.name, actual, envelopeHash(readEnvelope(output, FILE_MAGIC)))
            }
        }
        writeIndex(File(folder, INDEX_NAME), entries, newKeys, newSalt)
        return Index(entries = entries).bind(newKeys)
    }

    private fun payloadHash(file: File, size: Long): String {
        val hash = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                raf.readFully(buffer, 0, count)
                hash.update(buffer, 0, count)
                remaining -= count
            }
        }
        return hex(hash.digest())
    }

    internal fun matchesPayload(file: File, metadata: Metadata): Boolean =
        checked(file).length() >= metadata.payloadSize && payloadHash(file, metadata.payloadSize) == metadata.protectedSha256

    /** Export to a fresh file; do not mutate the protected source on failure or success. */
    fun restoreCopy(source: File, destination: File, keyring: Keyring, index: Index? = null): Resolved {
        val resolved = resolve(source, index, keyring)
        restoreKnownCopy(source, destination, resolved.metadata)
        return resolved
    }

    internal fun restoreKnownCopy(source: File, destination: File, metadata: Metadata) {
        val input = checked(source)
        val output = checked(destination)
        require(!output.exists() && input != output)
        validate(metadata)
        val originalHash = MessageDigest.getInstance("SHA-256")
        val protectedHash = MessageDigest.getInstance("SHA-256")
        val key = Base64.getDecoder().decode(metadata.contentKey)
        try {
            publish(output, replace = false) { temp ->
                RandomAccessFile(input, "r").use { raf ->
                    temp.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var position = 0L
                        while (position < metadata.payloadSize) {
                            val count = minOf(buffer.size.toLong(), metadata.payloadSize - position).toInt()
                            raf.readFully(buffer, 0, count)
                            protectedHash.update(buffer, 0, count)
                            xor(buffer, 0, count, position, key)
                            originalHash.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                            position += count
                        }
                    }
                }
                require(hex(protectedHash.digest()) == metadata.protectedSha256 &&
                    hex(originalHash.digest()) == metadata.originalSha256) { "Video integrity check failed" }
            }
        } finally { key.fill(0) }
    }

    private fun encrypt(json: String, magic: ByteArray, size: Long, salt: ByteArray,
                        keyring: Keyring): Envelope {
        require(salt.size == 16)
        val plain = json.toByteArray(Charsets.UTF_8)
        require(plain.size + 16 <= metadataLimit(magic))
        val nonce = ByteArray(12).also(random::nextBytes)
        val footer = ByteBuffer.allocate(FOOTER_SIZE).put(magic).putInt(VERSION).putInt(ITERATIONS)
            .put(salt).put(nonce).putInt(plain.size + 16).putLong(size).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyring.key(salt), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(footer)
        return Envelope(footer, cipher.doFinal(plain), salt.copyOf(), nonce, size)
    }

    private fun readEnvelope(file: File, magic: ByteArray): Envelope {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= FOOTER_SIZE) { "Not a portable private file" }
            val footer = ByteArray(FOOTER_SIZE)
            raf.seek(raf.length() - FOOTER_SIZE)
            raf.readFully(footer)
            val buffer = ByteBuffer.wrap(footer)
            val actualMagic = ByteArray(16).also { buffer.get(it) }
            val oldMagic = if (magic.contentEquals(FILE_MAGIC)) OLD_FILE_MAGIC else OLD_INDEX_MAGIC
            require((actualMagic.contentEquals(magic) || actualMagic.contentEquals(oldMagic)) && buffer.int == VERSION) { "Unknown portable private format" }
            require(buffer.int == ITERATIONS) { "Unsupported password derivation parameters" }
            val salt = ByteArray(16).also { buffer.get(it) }
            val nonce = ByteArray(12).also { buffer.get(it) }
            val length = buffer.int
            val size = buffer.long
            require(length in 16..metadataLimit(magic) && size >= 0 &&
                size == raf.length() - FOOTER_SIZE - length) { "Invalid portable private file length" }
            val encrypted = ByteArray(length)
            raf.seek(size)
            raf.readFully(encrypted)
            return Envelope(footer, encrypted, salt, nonce, size)
        }
    }

    private fun decrypt(envelope: Envelope, keyring: Keyring): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyring.key(envelope.salt), "AES"),
            GCMParameterSpec(128, envelope.nonce))
        cipher.updateAAD(envelope.footer)
        return String(cipher.doFinal(envelope.ciphertext), Charsets.UTF_8)
    }

    private fun validate(metadata: Metadata) {
        require(UUID.fromString(metadata.id).toString() == metadata.id)
        require(validName(metadata.originalName) && metadata.payloadSize >= 0)
        require(metadata.originalSha256.matches(HASH) && metadata.protectedSha256.matches(HASH))
        require(Base64.getDecoder().decode(metadata.contentKey).size == 32)
        require(metadata.folderName == null || validName(metadata.folderName))
    }

    private fun publish(destination: File, replace: Boolean, write: (File) -> Unit) {
        checked(destination)
        val parent = checkNotNull(destination.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        if (!replace) require(!destination.exists())
        val prefix = if (migrationTemporaryFiles) ".neko_migrate_" else "."
        val temp = checked(File(parent, "$prefix${UUID.randomUUID()}.part"))
        require(temp.createNewFile())
        try {
            write(temp)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            checkpoint(Stage.FILE_SYNCED, destination)
            if (replace) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } else {
                // No REPLACE_EXISTING: never replace an input or an existing export.
                // Unlike hard links, this also works on Android's emulated shared storage.
                Files.move(temp.toPath(), destination.toPath())
            }
        } finally { if (temp.exists()) check(temp.delete()) }
    }

    companion object {
        const val INDEX_NAME = ".neko_recovery_index.enc"
        const val FOOTER_SIZE = 64
        const val VERSION = 1
        private const val ITERATIONS = 210_000
        private const val MAX_ENTRIES = 10_000
        private val FILE_MAGIC = "NEKO_RECOVER_V01".toByteArray(Charsets.US_ASCII)
        private val INDEX_MAGIC = "NEKO_RECOV_IDX01".toByteArray(Charsets.US_ASCII)
        private val OLD_FILE_MAGIC = "NEKO_LAB_FILE_01".toByteArray(Charsets.US_ASCII)
        private val OLD_INDEX_MAGIC = "NEKO_LAB_INDEX01".toByteArray(Charsets.US_ASCII)
        private val HASH = Regex("[0-9a-f]{64}")
        private fun metadataLimit(magic: ByteArray) =
            if (magic.contentEquals(INDEX_MAGIC)) 16 * 1024 * 1024 else 16 * 1024
        fun hasRecoveryRecord(file: File): Boolean = runCatching {
            if (!file.isFile || file.length() < FOOTER_SIZE) return@runCatching false
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - FOOTER_SIZE)
                val footer = ByteArray(FOOTER_SIZE).also(raf::readFully)
                val buffer = ByteBuffer.wrap(footer)
                val magic = ByteArray(16).also { buffer.get(it) }
                if (!magic.contentEquals(FILE_MAGIC) && !magic.contentEquals(OLD_FILE_MAGIC)) return@use false
                if (buffer.int != VERSION || buffer.int != ITERATIONS) return@use false
                buffer.position(52)
                val length = buffer.int
                val payload = buffer.long
                length in 16..16384 && payload >= 0 && payload == raf.length() - FOOTER_SIZE - length
            }
        }.getOrDefault(false)
        private fun validName(name: String) = name.isNotBlank() && name.length <= 1024 &&
            name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    hash.update(bytes, 0, count)
                }
            }
            return hex(hash.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun envelopeHash(envelope: Envelope): String {
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update(envelope.ciphertext)
            return hex(hash.digest(envelope.footer))
        }
        internal fun xor(buffer: ByteArray, offset: Int, count: Int, position: Long, key: ByteArray) {
            val end = minOf(count.toLong(), 8192L - position).coerceAtLeast(0).toInt()
            for (i in 0 until end) buffer[offset + i] =
                (buffer[offset + i].toInt() xor key[((position + i) % key.size).toInt()].toInt()).toByte()
        }
    }
}
