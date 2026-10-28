package com.nkls.nekovideo.components.helpers

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.PrivateMigrationCoordinator
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** All writes and simulated crashes are constrained to newly created Memes/NekoMigrationTest-* copies. */
@RunWith(AndroidJUnit4::class)
class PrivateVideoMigrationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val password get() = checkNotNull(args.getString("privatePassword"))
    private val owned get() = File(checkNotNull(context.getExternalFilesDir(null)), "portable-integration")
    private val reference get() = File(owned, "input").listFiles().orEmpty().filter { it.extension == "mp4" }.sortedBy { it.name }.first()
    private val roots = mutableListOf<File>()
    private val tagIds = mutableListOf<Long>()
    private val historyNames = mutableListOf<String>()

    @Before fun requireDebugAndCredential() {
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" && BuildConfig.PORTABLE_PRIVATE_WRITES)
        assumeTrue(args.containsKey("privatePassword"))
        assertTrue(FilesManager.SecureStorage.verifyPassword(context, password))
        TagSyncBridge.localTestMode = true
        LockedPlaybackSession.clear()
    }

    private fun checkedRoot(file: File): File = file.canonicalFile.also {
        require(it.parentFile == File("/storage/emulated/0/Memes").canonicalFile)
        require(it.name.matches(Regex("NekoMigrationTest-[0-9a-f-]{36}")))
    }
    private fun root() = checkedRoot(File("/storage/emulated/0/Memes", "NekoMigrationTest-${UUID.randomUUID()}")).apply {
        check(mkdir()); roots += this
    }
    @After fun cleanup() {
        LockedPlaybackSession.clear()
        runBlocking { tagIds.forEach { VideoTagStore.deleteTag(context, it) } }
        historyNames.forEach { name ->
            require(name.startsWith("migration-history-test-"))
            context.deleteSharedPreferences(name)
        }
        roots.forEach { root ->
            val checked = checkedRoot(root)
            FolderLockManager.getAllLockedFolders(context).filter { File(it.folderPath).toPath().startsWith(checked.toPath()) }
                .forEach { FolderLockManager.removeFromRegistry(context, it.folderPath) }
            check(checked.deleteRecursively())
        }
        TagSyncBridge.localTestMode = false
    }

    private fun legacy(folder: File, withVideo: Boolean = true): File {
        check(folder.mkdir())
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val saltString = Base64.getEncoder().encodeToString(salt)
        val key = FolderLockManager.deriveXorKey(password, salt)
        val entries = if (withVideo) {
            val name = UUID.randomUUID().toString().replace("-", "")
            val bytes = reference.readBytes()
            for (i in 0 until minOf(8192, bytes.size)) bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            File(folder, name).writeBytes(bytes)
            listOf(LockedFileEntry(name, reference.name, reference.length(), "00:01"))
        } else emptyList()
        key.fill(0)
        val manifest = LockedFolderManifest(folder.absolutePath, "Pasta de memes", entries, salt = saltString, lockedAt = 1L)
        // Reproduce the released legacy encoding independently of the enabled new-write path.
        val manifestSalt = salt.copyOf().also { it[0] = (it[0].toInt() xor 255).toByte() }
        val aes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), manifestSalt, 10000, 128)).encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16).also(SecureRandom()::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(iv))
        File(folder, ".neko_manifest.enc").writeBytes(iv + cipher.doFinal(Gson().toJson(manifest).toByteArray()))
        aes.fill(0)
        File(folder, ".neko_locked").writeText(saltString)
        File(folder, ".nomedia").writeText("")
        assertNotNull(FolderLockManager.readManifest(folder.absolutePath, password))
        return folder
    }

    private fun payloadIsOriginal(folder: File) {
        val manifest = checkNotNull(FolderLockManager.readManifest(folder.absolutePath, password))
        val salt = checkNotNull(FolderLockManager.getSalt(folder.absolutePath))
        LockedPlaybackSession.start(FolderLockManager.deriveXorKey(password, salt), manifest, folder.absolutePath, password)
        manifest.files.forEach { entry ->
            val data = HybridDataSourceFactory(context).createDataSource()
            assertEquals(reference.length(), data.open(DataSpec(Uri.parse("locked://${File(folder, entry.obfuscatedName).absolutePath}"))))
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = data.read(buffer, 0, buffer.size)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            data.close()
            assertEquals(PortableVideoStore.sha256(reference), hash.digest().joinToString("") { "%02x".format(it) })
        }
    }
    private fun hashes(folder: File) = folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to PortableVideoStore.sha256(it) }
    private fun isConverted(folder: File) {
        assertFalse(File(folder, ".neko_locked").exists())
        assertFalse(File(folder, ".neko_manifest.enc").exists())
        assertFalse(File(folder, PrivateVideoMigration.JOURNAL).exists())
        assertTrue(File(folder, PortablePrivateFolders.INDEX).isFile)
        assertTrue(File(folder, PortablePrivateFolders.MARKER).isFile)
        assertTrue(folder.listFiles().orEmpty().none { it.name.startsWith(".neko_migrate_") })
        payloadIsOriginal(folder)
    }

    @Test fun nestedConversionPreservesPathsTagsAndExternalRecovery() {
        val root = root()
        val parent = legacy(File(root, "parent"))
        val child = legacy(File(parent, "child"))
        val empty = legacy(File(parent, "empty"), false)
        val beforeManifest = checkNotNull(FolderLockManager.readManifest(parent.absolutePath, password))
        FolderLockManager.saveLegacyManifest(parent.absolutePath, beforeManifest.copy(subfolders = listOf(
            LockedSubfolderEntry(child.name, "Filhos"), LockedSubfolderEntry(empty.name, "Vazia"))), password)
        val parentFile = File(parent, beforeManifest.files.single().obfuscatedName)
        val tag = runBlocking { VideoTagStore.createTag(context, "Migration-test-${UUID.randomUUID()}", TagScope.PRIVATE).getOrThrow() }
        tagIds += tag.id
        runBlocking { VideoTagStore.addTagsToVideos(context, listOf(parentFile.absolutePath), setOf(tag.id)) }
        val originalCipherHash = PortableVideoStore.sha256(parentFile)
        val snapshot = hashes(root)
        runBlocking {
            com.nkls.nekovideo.services.FolderVideoScanner.refreshPaths(context, listOf(root.path), scope = this)
            com.nkls.nekovideo.services.FolderVideoScanner.isScanning.first { !it }
        }
        val discovered = com.nkls.nekovideo.services.FolderVideoScanner.cache.value.keys.filter {
            File(it).toPath().startsWith(root.toPath()) && PrivateVideoMigration.isCandidate(it)
        }
        val plan = PrivateVideoMigration.plan(discovered + parent.path, password)
        assertEquals(2, plan.videoCount)
        assertEquals(3, plan.folders.size)
        assertEquals(snapshot, hashes(root))
        val result = PrivateVideoMigration.migrate(context, plan, password)
        assertEquals(0, result.failedFolders)
        assertEquals(3, result.updatedFolders)
        assertEquals(2, result.updatedVideos)
        listOf(parent, child, empty).forEach(::isConverted)
        val converted = checkNotNull(FolderLockManager.readManifest(parent.path, password))
        assertEquals("Filhos", converted.subfolders.first { it.obfuscatedName == child.name }.originalName)
        val metadata = checkNotNull(converted.files.single().portableMetadata)
        assertEquals(originalCipherHash, metadata.protectedSha256)
        assertEquals("00:01", converted.files.single().duration)
        assertEquals(setOf(tag.id), runBlocking { VideoTagStore.getCommonTagIds(context, listOf(parentFile.absolutePath), TagScope.PRIVATE) })
        assertTrue(PrivateVideoMigration.plan(plan.folders, password).folders.isEmpty())
        val normal = File(root, "normal").apply { check(mkdir()) }
        val renamed = File(normal, "externally-renamed")
        check(parentFile.renameTo(renamed))
        val output = PortablePrivateFolders.restoreExternalArrivals(context, normal.path, password).single()
        assertEquals(reference.name, output.name)
        assertEquals(PortableVideoStore.sha256(reference), PortableVideoStore.sha256(output))
        assertFalse(FolderLockManager.isLocked(normal.path))
    }

    private class Stop : Error()

    private fun history(): Pair<String, PrivateMigrationHistory> {
        val name = "migration-history-test-${UUID.randomUUID()}"
        historyNames += name
        return name to PrivateMigrationHistory(context, name)
    }

    @Test fun completionPersistsAndRestoredLegacyFoldersInvalidateIt() {
        val (name, state) = history()
        val root = root()
        state.observe(listOf(root.path), fullScan = true)
        assertTrue(PrivateMigrationHistory(context, name).isComplete())
        state.plan("unused") { _, _ -> error("Completed discovery must not inspect any manifests") }
        val folder = legacy(File(root, "restored-from-backup"))
        state.observe(listOf(folder.path), fullScan = false)
        val reopened = PrivateMigrationHistory(context, name)
        assertFalse(reopened.isComplete())
        assertEquals(setOf(folder.path), reopened.pendingPaths())
        var checked = emptySet<String>()
        val plan = reopened.plan(password) { paths, credential ->
            checked = paths.toSet()
            PrivateVideoMigration.plan(paths, credential)
        }
        assertEquals(setOf(folder.path), checked)
        assertEquals(1, plan.videoCount)
        assertEquals(0, PrivateVideoMigration.migrate(context, plan, password).failedFolders)
        reopened.observe(plan.folders, false)
        assertTrue(PrivateMigrationHistory(context, name).isComplete())
        assertTrue(reopened.pendingPaths().isEmpty())
        reopened.plan("unused") { _, _ -> error("No filesystem inspection after completion") }
    }

    @Test fun legacyBatchDeleteUpdatesFilesAndDirectoriesTogether() {
        val parent = legacy(File(root(), "parent"))
        val child = legacy(File(parent, "child"), false)
        val manifest = checkNotNull(FolderLockManager.readManifest(parent.path, password))
        FolderLockManager.saveLegacyManifest(parent.path, manifest.copy(subfolders = listOf(
            LockedSubfolderEntry(child.name, "Child"))), password)
        LockedPlaybackSession.authorize(password)
        runBlocking {
            LockedFolderOperations.deleteLockedItems(context,
                listOf(File(parent, manifest.files.single().obfuscatedName).path, child.path), parent.path, password,
                onError = { error(it) })
        }
        val updated = checkNotNull(FolderLockManager.readManifest(parent.path, password))
        assertTrue(updated.files.isEmpty())
        assertTrue(updated.subfolders.isEmpty())
        assertFalse(child.exists())
    }

    @Test fun deferredAndFailedMigrationKeepPersistentPendingFolders() {
        val (name, state) = history()
        val folder = legacy(File(root(), "pending"))
        state.observe(listOf(folder.path), fullScan = true)
        val plan = state.plan(password)
        assertFalse(PrivateMigrationHistory(context, name).isComplete()) // Merely offering/deferring is not completion.
        assertEquals(1, state.plan("wrong-test-password").unavailableFolders)
        assertFalse(state.isComplete())
        assertEquals(1, PrivateVideoMigration.migrate(context, plan, password, availableBytes = { 0L }).failedFolders)
        state.observe(plan.folders, false)
        assertFalse(PrivateMigrationHistory(context, name).isComplete())
        assertEquals(setOf(folder.path), state.pendingPaths())
        assertEquals(0, PrivateVideoMigration.migrate(context, state.plan(password), password).failedFolders)
        state.observe(plan.folders, false)
        assertTrue(PrivateMigrationHistory(context, name).isComplete())
    }

    @Test fun partialDiscoveryDoesNotMarkAGlobalCheckAsComplete() {
        val (_, state) = history()
        state.observe(emptyList(), fullScan = false)
        assertFalse(state.isComplete())
        assertFalse(state.hasDiscovery())
        val folder = legacy(File(root(), "pending"))
        state.observe(listOf(folder.path), false)
        assertEquals(0, PrivateVideoMigration.migrate(context, state.plan(password), password).failedFolders)
        state.observe(listOf(folder.path), false)
        assertFalse(state.isComplete())
        state.observe(listOf(folder.path), true)
        assertTrue(state.isComplete())
    }

    @Test fun unreadableSystemAncestorsAreNotPrivateMigrationFailures() {
        val (name, state) = history()
        val ancestors = setOf("/", "/storage", "/storage/emulated")
        assertTrue("Reproduce Android's restricted ancestors", ancestors.any { !File(it).canRead() })
        // Reproduce preferences written by the previous version, not just a fresh scan.
        context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit()
            .putStringSet("pending_folders", ancestors).putInt("discovery_version", 1).commit()
        val plan = state.plan(password)
        assertEquals(0, plan.unavailableFolders)
        assertTrue(plan.folders.isEmpty())
        assertTrue(PrivateMigrationHistory(context, name).isComplete())
        state.observe(ancestors, true)
        assertTrue(state.pendingPaths().isEmpty())
        state.plan(password) { _, _ -> error("Completed history must not inspect manifests") }
    }

    @Test fun staleSystemAncestorsDoNotHideAnActualBrokenPrivateFolder() {
        val (name, state) = history()
        val folder = legacy(File(root(), "broken"))
        check(File(folder, ".neko_manifest.enc").delete())
        context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit()
            .putStringSet("pending_folders", setOf("/", "/storage", "/storage/emulated", folder.path))
            .putInt("discovery_version", 1).commit()
        val plan = state.plan(password)
        assertEquals(1, plan.unavailableFolders)
        assertEquals(setOf(folder.path), state.pendingPaths())
        assertFalse(state.isComplete())
        assertTrue(File(folder, ".neko_locked").isFile)
    }

    /** Opt-in repair of the exact false positives reported on the connected Debug install. No video writes. */
    @Test fun verifyAndRepairReportedSystemAncestors() {
        assumeTrue(args.getString("repairReportedAncestors") == "true")
        val state = PrivateMigrationHistory(context)
        val before = state.pendingPaths()
        assertEquals(setOf("/", "/storage", "/storage/emulated"), before)
        assertTrue(before.none(PrivateVideoMigration::isCandidate))
        val plan = state.plan(password)
        assertEquals(0, plan.unavailableFolders)
        assertTrue(plan.folders.isEmpty())
        assertTrue(state.isComplete())
        assertTrue(state.pendingPaths().isEmpty())
        repeat(2) {
            LockedPlaybackSession.authorize(password)
            com.nkls.nekovideo.services.FolderVideoScanner.requestPrivateMigrationCheck(context, password)
            assertFalse(com.nkls.nekovideo.services.FolderVideoScanner.isScanning.value)
            val checked = checkNotNull(com.nkls.nekovideo.services.FolderVideoScanner.migrationCheck.value)
            assertEquals(LockedPlaybackSession.credentialVersion, checked.credentialVersion)
            assertEquals(0, checked.plan.unavailableFolders)
            assertTrue(checked.plan.folders.isEmpty())
            LockedPlaybackSession.clear()
        }
    }

    @Test fun manyVideosUseOneFinalIndexAndKeepTheLegacyManifestUntilCommit() {
        val folder = legacy(File(root(), "many"))
        val manifest = checkNotNull(FolderLockManager.readManifest(folder.path, password))
        val first = manifest.files.single()
        val entries = (0 until 36).map { index ->
            if (index == 0) first else {
                val name = UUID.randomUUID().toString().replace("-", "")
                File(folder, first.obfuscatedName).copyTo(File(folder, name))
                first.copy(obfuscatedName = name, originalName = "meme-$index.mp4")
            }
        }
        FolderLockManager.saveLegacyManifest(folder.path, manifest.copy(files = entries), password)
        val oldIndex = File(folder, ".neko_manifest.enc")
        val oldHash = PortableVideoStore.sha256(oldIndex)
        var filesChecked = 0
        val result = PrivateVideoMigration.migrate(context, PrivateVideoMigration.plan(listOf(folder.path), password), password,
            checkpoint = { stage, _ -> if (stage == PrivateVideoMigration.Stage.VIDEO_REPLACED) {
                assertEquals(oldHash, PortableVideoStore.sha256(oldIndex)); filesChecked++
            } })
        assertEquals(0, result.failedFolders)
        assertEquals(36, result.updatedVideos)
        assertEquals(36, filesChecked)
        assertEquals(entries.map { it.obfuscatedName }.toSet(), checkNotNull(FolderLockManager.readManifest(folder.path, password)).files.map { it.obfuscatedName }.toSet())
        isConverted(folder)
    }

    @Test fun resumesAtEveryDurableCheckpoint() {
        PrivateVideoMigration.Stage.entries.forEach { stopAt ->
            val folder = legacy(File(root(), "private"))
            val plan = PrivateVideoMigration.plan(listOf(folder.path), password)
            try {
                PrivateVideoMigration.migrate(context, plan, password, checkpoint = { stage, _ -> if (stage == stopAt) throw Stop() })
                fail("Checkpoint was not reached: $stopAt")
            } catch (_: Stop) { }
            LockedPlaybackSession.clear()
            payloadIsOriginal(folder)
            val resumed = PrivateVideoMigration.migrate(context, PrivateVideoMigration.plan(listOf(folder.path), password), password)
            assertEquals("$stopAt", 0, resumed.failedFolders)
            isConverted(folder)
        }
    }

    @Test fun wrongPasswordMissingFileAndLowSpacePreserveInputs() {
        val folder = legacy(File(root(), "private"))
        val before = hashes(folder)
        val wrong = PrivateVideoMigration.plan(listOf(folder.path), "wrong-test-password")
        assertTrue(wrong.folders.isEmpty())
        assertEquals(1, wrong.unavailableFolders)
        assertEquals(before, hashes(folder))
        val plan = PrivateVideoMigration.plan(listOf(folder.path), password)
        val result = PrivateVideoMigration.migrate(context, plan, password, availableBytes = { 0L })
        assertEquals(1, result.failedFolders)
        before.forEach { (name, hash) -> assertEquals(hash, PortableVideoStore.sha256(File(folder, name))) }
        payloadIsOriginal(folder)
        assertEquals(0, PrivateVideoMigration.migrate(context, PrivateVideoMigration.plan(listOf(folder.path), password), password).failedFolders)
        isConverted(folder)
        val truncated = legacy(File(root(), "truncated"))
        val file = truncated.listFiles().orEmpty().single { !it.name.startsWith(".") }
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }
        val damaged = hashes(truncated)
        assertEquals(1, PrivateVideoMigration.plan(listOf(truncated.path), password).unavailableFolders)
        assertEquals(damaged, hashes(truncated))
    }

    @Test fun dialogRequiresAuthenticationAndExplicitConfirmation() {
        val folder = legacy(File(root(), "private"))
        val before = hashes(folder)
        val credential = mutableStateOf<String?>(null)
        val visible = mutableStateOf(false)
        var updated = false
        compose.setContent { MaterialTheme {
            PrivateMigrationCoordinator(credential.value, visible.value, false, listOf(folder.path)) { updated = true }
        } }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.private_migration_title)).assertDoesNotExist()
        compose.runOnIdle { LockedPlaybackSession.authorize(password); credential.value = password }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.private_migration_title)).assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText(context.getString(R.string.private_migration_update))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.private_migration_later)).performClick()
        compose.runOnIdle { assertFalse(updated); assertEquals(before, hashes(folder)); visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText(context.getString(R.string.private_migration_update))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.private_migration_update)).performClick()
        compose.waitUntil(15000) { updated }
        isConverted(folder)
    }

    @Test fun migrationCountIsPublishedBeforeLoadingFinishes() {
        val folder = legacy(File(root(), "private"))
        LockedPlaybackSession.authorize(password)
        com.nkls.nekovideo.services.FolderVideoScanner.requestPrivateMigrationCheck(context, password, listOf(folder.path))
        runBlocking { withTimeout(15000) {
            com.nkls.nekovideo.services.FolderVideoScanner.isScanning.first { !it }
        } }
        val ready = checkNotNull(com.nkls.nekovideo.services.FolderVideoScanner.migrationCheck.value)
        assertEquals(LockedPlaybackSession.credentialVersion, ready.credentialVersion)
        assertEquals(listOf(folder.path), ready.pathsOverride)
        assertEquals(1, ready.plan.videoCount)
        assertEquals(listOf(folder.path), ready.plan.folders)
    }

    @Test fun concurrentSourceChangeAndUnfinishedProtectionAreNotOverwritten() {
        val folder = legacy(File(root(), "private"))
        val source = folder.listFiles().orEmpty().single { !it.name.startsWith(".") }
        val original = source.readBytes()
        val flag = File(folder, ".neko_lock_in_progress").apply { writeText("") }
        assertEquals(1, PrivateVideoMigration.plan(listOf(folder.path), password).unavailableFolders)
        check(flag.delete())
        var changedHash = ""
        val result = PrivateVideoMigration.migrate(context, PrivateVideoMigration.plan(listOf(folder.path), password), password,
            checkpoint = { stage, _ -> if (stage == PrivateVideoMigration.Stage.SESSION_READY) {
                RandomAccessFile(source, "rw").use { file ->
                    file.seek(10000); val value = file.readByte().toInt(); file.seek(10000); file.writeByte(value xor 1)
                }
                changedHash = PortableVideoStore.sha256(source)
            } })
        assertEquals(1, result.failedFolders)
        assertEquals(changedHash, PortableVideoStore.sha256(source))
        assertTrue(File(folder, ".neko_locked").exists())
        // Restore only the disposable test mutation; a subsequent retry can reuse the verified copy.
        source.writeBytes(original)
        assertEquals(0, PrivateVideoMigration.migrate(context, PrivateVideoMigration.plan(listOf(folder.path), password), password).failedFolders)
        isConverted(folder)
    }

    @Test fun prepareAndStopMigrationProcess() {
        assumeTrue(args.containsKey("stopAt"))
        val stage = PrivateVideoMigration.Stage.valueOf(checkNotNull(args.getString("stopAt")))
        val root = root()
        val folder = legacy(File(root, "private"))
        Properties().apply { setProperty("root", root.path); setProperty("folder", folder.path) }
            .also { state -> File(owned, "migration-crash.properties").outputStream().use { state.store(it, "Migration test copies; no credentials") } }
        PrivateVideoMigration.migrate(context, PrivateVideoMigration.plan(listOf(folder.path), password), password,
            checkpoint = { reached, _ -> if (reached == stage) android.os.Process.killProcess(android.os.Process.myPid()) })
        fail("Expected process termination at $stage")
    }

    @Test fun resumeAfterProcessTermination() {
        assumeTrue(args.containsKey("resumeMigration"))
        val state = Properties().apply { File(owned, "migration-crash.properties").inputStream().use { load(it) } }
        val root = checkedRoot(File(state.getProperty("root"))).also { roots += it }
        val folder = File(state.getProperty("folder")).canonicalFile
        require(folder.parentFile == root)
        payloadIsOriginal(folder)
        val result = PrivateVideoMigration.migrate(context, PrivateVideoMigration.plan(listOf(folder.path), password), password)
        assertEquals(0, result.failedFolders)
        isConverted(folder)
    }
}
