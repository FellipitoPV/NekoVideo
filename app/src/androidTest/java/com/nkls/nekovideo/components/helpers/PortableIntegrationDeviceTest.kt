package com.nkls.nekovideo.components.helpers

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.components.SortType
import com.nkls.nekovideo.components.loadFolderContent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only new copies below a validated Memes test directory; never migrates the user's vault. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PortableIntegrationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password get() = checkNotNull(InstrumentationRegistry.getArguments().getString("privatePassword"))
    private val owned get() = File(checkNotNull(context.getExternalFilesDir(null)), "portable-integration").apply { mkdirs() }
    private val input get() = File(owned, "input")
    private val stateFile get() = File(owned, "external-state.properties")

    @Before fun requireExplicitDebugTest() {
        assumeTrue(context.packageName == "com.nkls.nekovideo.debug" && BuildConfig.PORTABLE_PRIVATE_WRITES)
        assumeTrue(InstrumentationRegistry.getArguments().containsKey("privatePassword"))
        assertTrue("Test credential must match Debug; do not change app preferences", FilesManager.SecureStorage.verifyPassword(context, password))
        TagSyncBridge.localTestMode = true
        LockedPlaybackSession.clear()
    }

    @org.junit.After fun restoreTagRouting() { TagSyncBridge.localTestMode = false }

    private fun samples() = input.listFiles().orEmpty().filter { it.extension == "mp4" }.sortedBy { it.name }.also {
        require(it.size >= 2) { "Copy two ordinary memes into the dedicated Debug test input" }
    }

    private fun work(): File = checkedWork(File("/storage/emulated/0/Memes", "NekoRecoveryDebugTest-${UUID.randomUUID()}")).apply { check(mkdir()) }
    private fun checkedWork(file: File): File {
        val result = file.canonicalFile
        require(result.parentFile == File("/storage/emulated/0/Memes").canonicalFile)
        require(result.name.matches(Regex("NekoRecoveryDebugTest-[0-9a-f-]{36}")))
        return result
    }
    private fun state() = Properties().apply { stateFile.inputStream().use { load(it) } }
    private fun child(work: File, path: String): File {
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(work.canonicalFile.toPath()) && file != work.canonicalFile)
        return file
    }
    private fun report(text: String) { File(owned, "report.txt").appendText("$text\n") }

    @Test fun folderEntryLoadsWithoutPreparedSessionAndReportsInvalidMetadata() {
        val root = work()
        try {
            var folder = File(root, "entry").apply { check(mkdir()) }
            val sample = samples().first()
            sample.copyTo(File(folder, sample.name))
            folder = protect(folder)
            val protected = folder.listFiles().orEmpty().single { PortableVideoStore.hasRecoveryRecord(it) }
            val hash = PortableVideoStore.sha256(protected)
            LockedPlaybackSession.clear()
            LockedPlaybackSession.authorize(password)
            assertFalse(LockedPlaybackSession.hasSessionForFolder(folder.path))
            var failures = 0
            val items = loadFolderContent(context, folder.path, SortType.NAME_ASC, true, false, true,
                onPrivateFolderUnavailable = { failures++ })
            assertEquals(1, items.size)
            assertEquals(0, failures)
            assertEquals(sample.name, items.single().displayName)
            assertTrue(LockedPlaybackSession.hasSessionForFolder(folder.path))
            val data = HybridDataSourceFactory(context).createDataSource()
            data.open(DataSpec(Uri.parse("locked://${protected.path}")))
            try {
                val actual = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) {
                    val size = data.read(buffer, 0, buffer.size)
                    if (size < 0) break
                    actual.update(buffer, 0, size)
                }
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(sample.readBytes()), actual.digest())
            } finally { data.close() }
            LockedPlaybackSession.clear()
            LockedPlaybackSession.authorize("incorrect-test-credential")
            assertTrue(loadFolderContent(context, folder.path, SortType.NAME_ASC, true, false, true,
                onPrivateFolderUnavailable = { failures++ }).isEmpty())
            assertEquals(1, failures)
            assertFalse(LockedPlaybackSession.hasSessionForFolder(folder.path))
            assertEquals(hash, PortableVideoStore.sha256(protected))
        } finally {
            LockedPlaybackSession.clear()
            val checked = checkedWork(root)
            FolderLockManager.getAllLockedFolders(context).filter { File(it.folderPath).toPath().startsWith(checked.toPath()) }
                .forEach { FolderLockManager.removeFromRegistry(context, it.folderPath) }
            check(checked.deleteRecursively())
        }
    }

    private fun protect(folder: File): File {
        var resolved = folder.absolutePath
        var completed = false
        FolderLockManager.lockFolder(context, folder.absolutePath, password,
            onError = { error(it) }, onResolvedPath = { resolved = it }, onSuccess = { completed = true })
        assertTrue(completed)
        val result = File(resolved)
        assertTrue(File(result, PortablePrivateFolders.INDEX).isFile)
        assertFalse(File(result, ".neko_locked").exists())
        return result
    }

    private fun session(folder: File): LockedFolderManifest {
        val manifest = checkNotNull(FolderLockManager.readManifest(folder.absolutePath, password))
        val salt = checkNotNull(FolderLockManager.getSalt(folder.absolutePath))
        LockedPlaybackSession.start(FolderLockManager.deriveXorKey(password, salt), manifest, folder.absolutePath, password)
        return manifest
    }

    private fun verifyReaders(folder: File, manifest: LockedFolderManifest) {
        for (entry in manifest.files) {
            val file = File(folder, entry.obfuscatedName)
            val reference = File(input, entry.originalName)
            val expected = PortableVideoStore.sha256(reference)
            assertNotNull(entry.portableMetadata)
            assertEquals(reference.length(), checkNotNull(LockedPlaybackSession.getPayloadSize(file.absolutePath)))
            val data = HybridDataSourceFactory(context).createDataSource()
            assertEquals(reference.length(), data.open(DataSpec(Uri.parse("locked://${file.absolutePath}"))))
            val hash = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(32 * 1024)
            while (true) {
                val count = data.read(bytes, 0, bytes.size)
                if (count < 0) break
                assertTrue(count > 0)
                hash.update(bytes, 0, count)
            }
            data.close()
            assertEquals(expected, hash.digest().joinToString("") { "%02x".format(it) })
            // Reusing the same production DataSource after close must still have the correct key.
            data.open(DataSpec.Builder().setUri(Uri.parse("locked://${file.absolutePath}")).setPosition(8170).setLength(60).build())
            assertEquals(60, data.read(bytes, 0, 60))
            assertArrayEquals(reference.readBytes().copyOfRange(8170, 8230), bytes.copyOf(60))
            data.close()
            val original = MediaMetadataRetriever()
            val decoded = MediaMetadataRetriever()
            val reader = XorMediaDataSource(file, checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.absolutePath)))
            try {
                assertEquals(reference.length(), reader.getSize())
                original.setDataSource(reference.absolutePath)
                decoded.setDataSource(reader)
                assertEquals(original.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), decoded.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                val a = checkNotNull(original.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 120))
                val b = checkNotNull(decoded.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 120))
                try { assertTrue(a.sameAs(b)) } finally { a.recycle(); b.recycle() }
            } finally { original.release(); decoded.release(); reader.close() }
        }
    }

    @Test fun prepareNativePrivateFolderForExternalMove() {
        val root = work()
        val source = File(root, "Pasta privada de teste").apply { check(mkdir()) }
        samples().take(2).forEach { it.copyTo(File(source, it.name)) }
        val folder = protect(source)
        val manifest = session(folder)
        assertEquals(2, manifest.files.size)
        assertTrue(manifest.files.all { PortableVideoStore.hasRecoveryRecord(File(folder, it.obfuscatedName)) })
        verifyReaders(folder, manifest)
        val external = File(root, "Destino externo").apply { check(mkdir()) }
        samples().last().copyTo(File(external, "video comum.mp4"))
        val state = Properties().apply {
            setProperty("work", root.absolutePath)
            setProperty("privateFolder", folder.absolutePath)
            setProperty("externalFolder", external.absolutePath)
            setProperty("records", manifest.files.joinToString(",") { it.obfuscatedName })
            setProperty("externalNames", manifest.files.mapIndexed { i, entry -> if (i == 0) entry.originalName else "renamed-$i" }.joinToString(","))
        }
        stateFile.outputStream().use { state.store(it, "Native Debug test; contains no credentials or open keys") }
        report("NATIVE_PREPARE: pasta protegida pela API normal; dois vídeos com record próprio; leitores, tamanho virtual e miniaturas corretos")
        LockedPlaybackSession.clear()
    }

    @Test fun recoverNativeFolderAfterAdbMoveAsOrdinaryVideos() {
        val state = state()
        val root = checkedWork(File(state.getProperty("work")))
        val folder = child(root, state.getProperty("externalFolder"))
        assertFalse(File(folder, PortablePrivateFolders.INDEX).exists())
        assertFalse(File(folder, PortablePrivateFolders.MARKER).exists())
        assertFalse(FolderLockManager.isLocked(folder.absolutePath))
        val protectedFiles = folder.listFiles().orEmpty().filter(PortableVideoStore::hasRecoveryRecord)
        assertEquals(2, protectedFiles.size)
        val before = protectedFiles.associate { it.name to PortableVideoStore.sha256(it) }
        val plain = File(folder, "video comum.mp4")
        val plainHash = PortableVideoStore.sha256(plain)
        assertEquals(1, loadFolderContent(context, folder.absolutePath, SortType.NAME_ASC, false, false, false).size)
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, folder.absolutePath, "wrong-test-password").isEmpty())
        before.forEach { (name, hash) -> assertEquals(hash, PortableVideoStore.sha256(File(folder, name))) }
        LockedPlaybackSession.authorize(password)
        assertNull(LockedPlaybackSession.getXorKeyForFile(plain.absolutePath))
        val visible = loadFolderContent(context, folder.absolutePath, SortType.NAME_ASC, false, false, false)
        assertEquals(3, visible.count { !it.isFolder })
        samples().take(2).forEach { reference ->
            val output = File(folder, reference.name)
            assertEquals(PortableVideoStore.sha256(reference), PortableVideoStore.sha256(output))
            assertFalse(PortableVideoStore.hasRecoveryRecord(output))
            assertNull(LockedPlaybackSession.getXorKeyForFile(output.absolutePath))
        }
        assertTrue(folder.listFiles().orEmpty().none(PortableVideoStore::hasRecoveryRecord))
        assertEquals(plainHash, PortableVideoStore.sha256(plain))
        assertFalse("A recovered ordinary folder must not become permanently private", File(folder, PortablePrivateFolders.MARKER).exists())
        assertFalse(File(folder, ".nomedia").exists())
        assertFalse(FolderLockManager.isLocked(folder.absolutePath))
        report("NATIVE_AUTO_RESTORE: após ADB; sem senha mantém os arquivos; com sessão validada restaura automaticamente os dois vídeos e nomes originais na mesma pasta comum, sem privá-la")
        LockedPlaybackSession.clear()
    }

    private fun moveTestVideoToNormalFolder(root: File, name: String = "normal"): Pair<File, File> {
        val source = File(root, "origem-${UUID.randomUUID()}").apply { check(mkdir()) }
        val reference = samples().first()
        reference.copyTo(File(source, reference.name))
        val locked = protect(source)
        val entry = checkNotNull(FolderLockManager.readManifest(locked.absolutePath, password)).files.single()
        val ordinary = File(root, name).apply { check(mkdir()) }
        val moved = File(ordinary, "movido-externamente")
        check(File(locked, entry.obfuscatedName).renameTo(moved))
        LockedPlaybackSession.clear()
        return ordinary to moved
    }

    @Test fun scannerRestoresOrdinaryArrivalsWithoutOpeningTheFolder() {
        val (ordinary, moved) = moveTestVideoToNormalFolder(work())
        // Every folder created in the normal app can have this marker; it isn't a private vault.
        File(ordinary, ".nekovideo").writeText("")
        LockedPlaybackSession.authorize(password)
        runBlocking {
            com.nkls.nekovideo.services.FolderVideoScanner.refreshPaths(context, listOf(ordinary.absolutePath), scope = this)
            com.nkls.nekovideo.services.FolderVideoScanner.isScanning.first { !it }
        }
        val info = checkNotNull(com.nkls.nekovideo.services.FolderVideoScanner.cache.value[ordinary.absolutePath])
        assertFalse(info.isLocked)
        assertFalse(info.isSecure)
        assertEquals(1, info.videos.size)
        assertEquals(PortableVideoStore.sha256(samples().first()), PortableVideoStore.sha256(File(info.videos.single().path)))
        assertFalse(moved.exists())
        assertFalse(File(ordinary, PortablePrivateFolders.INDEX).exists())

        val (recursiveFolder, _) = moveTestVideoToNormalFolder(work(), "recursive")
        val playlist = runBlocking {
            FilesManager.getVideosRecursive(context, recursiveFolder.absolutePath, false, false, sessionPassword = password)
        }
        assertEquals(listOf("file://${File(recursiveFolder, samples().first().name).absolutePath}"), playlist)
        assertFalse(FolderLockManager.isLocked(recursiveFolder.absolutePath))
    }

    @Test fun authenticationRecoversOnlyPreviouslyDiscoveredArrivalDirectories() {
        val (ordinary, moved) = moveTestVideoToNormalFolder(work(), "arrival-before-login")
        runBlocking {
            com.nkls.nekovideo.services.FolderVideoScanner.refreshPaths(context, listOf(ordinary.path), scope = this)
            com.nkls.nekovideo.services.FolderVideoScanner.isScanning.first { !it }
        }
        assertTrue(moved.isFile)
        assertFalse(File(ordinary, samples().first().name).exists())
        LockedPlaybackSession.authorize(password)
        com.nkls.nekovideo.services.FolderVideoScanner.requestPrivateMigrationCheck(context, password, listOf(ordinary.path))
        runBlocking {
            kotlinx.coroutines.withTimeout(15000) {
                com.nkls.nekovideo.services.FolderVideoScanner.isScanning.first { !it }
            }
        }
        val restored = File(ordinary, samples().first().name)
        assertEquals(PortableVideoStore.sha256(samples().first()), PortableVideoStore.sha256(restored))
        val info = checkNotNull(com.nkls.nekovideo.services.FolderVideoScanner.cache.value[ordinary.path])
        assertFalse(info.isLocked)
        assertTrue(info.videos.any { it.path == restored.path })
        assertFalse(moved.exists())
        LockedPlaybackSession.clear()
    }

    @Test fun automaticRestorePreservesNameCollisionsAndHandlesInPlaceNames() {
        val root = work()
        val reference = samples().first()
        val (ordinary, _) = moveTestVideoToNormalFolder(root)
        val collision = File(ordinary, reference.name).apply { writeText("existing unrelated file") }
        val collisionHash = PortableVideoStore.sha256(collision)
        val restored = PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).single()
        assertEquals("${reference.nameWithoutExtension} (1).${reference.extension}", restored.name)
        assertEquals(PortableVideoStore.sha256(reference), PortableVideoStore.sha256(restored))
        assertEquals(collisionHash, PortableVideoStore.sha256(collision))
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).isEmpty())

        val (sameNameFolder, moved) = moveTestVideoToNormalFolder(root, "same-name")
        val originalName = File(sameNameFolder, reference.name)
        check(moved.renameTo(originalName))
        assertEquals(originalName, PortablePrivateFolders.restoreExternalArrivals(context, sameNameFolder.absolutePath, password).single())
        assertEquals(PortableVideoStore.sha256(reference), PortableVideoStore.sha256(originalName))
        assertFalse(PortableVideoStore.hasRecoveryRecord(originalName))
    }

    @Test fun automaticRestoreRetainsDamagedAndUnauthenticatedInputs() {
        val (ordinary, moved) = moveTestVideoToNormalFolder(work())
        val originalHash = PortableVideoStore.sha256(moved)
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath).isEmpty())
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, "wrong-test-password").isEmpty())
        assertEquals(originalHash, PortableVideoStore.sha256(moved))
        java.io.RandomAccessFile(moved, "rw").use { file ->
            file.seek(9000)
            val value = file.readByte().toInt()
            file.seek(9000)
            file.writeByte(value xor 1)
        }
        val damagedHash = PortableVideoStore.sha256(moved)
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).isEmpty())
        assertEquals(damagedHash, PortableVideoStore.sha256(moved))
        assertEquals(listOf(moved.name), ordinary.listFiles().orEmpty().map { it.name })
        assertFalse(FolderLockManager.isLocked(ordinary.absolutePath))
    }

    @Test fun automaticRestoreSkipsPrivateAncestorsAndProtectionInProgress() {
        val root = work()
        val (ordinary, moved) = moveTestVideoToNormalFolder(root)
        val hash = PortableVideoStore.sha256(moved)
        val flag = File(ordinary, ".neko_lock_in_progress").apply { writeText("") }
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).isEmpty())
        check(flag.delete())
        for (name in listOf(PortablePrivateFolders.MARKER, ".neko_locked")) {
            val marker = File(ordinary, name).apply { writeText("") }
            assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).isEmpty())
            assertEquals(hash, PortableVideoStore.sha256(moved))
            check(marker.delete())
        }
        val privateMarker = File(root, ".nomedia").apply { writeText("") }
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).isEmpty())
        assertEquals(hash, PortableVideoStore.sha256(moved))
        check(privateMarker.delete())
        assertEquals(1, PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).size)
    }

    @Test fun externalMoveBetweenNewPrivateFoldersKeepsTheVideoProtected() {
        val root = work()
        val (destination, moved) = moveTestVideoToNormalFolder(root)
        val source = File(root, "nova-origem").apply { check(mkdir()) }
        check(moved.renameTo(File(source, moved.name)))
        assertTrue(FolderLockManager.createEmptyLockedFolder(context, destination.absolutePath, password))
        val protected = File(destination, "renomeado-por-fora.mp4")
        check(File(source, moved.name).renameTo(protected))
        val hash = PortableVideoStore.sha256(protected)
        assertTrue(PortablePrivateFolders.restoreExternalArrivals(context, destination.absolutePath, password).isEmpty())
        val visible = loadFolderContent(context, destination.absolutePath, SortType.NAME_ASC, false, false, true)
        assertEquals(samples().first().name, visible.single().displayName)
        assertEquals(hash, PortableVideoStore.sha256(protected))
        assertTrue(PortableVideoStore.hasRecoveryRecord(protected))
        assertTrue(FolderLockManager.isLocked(destination.absolutePath))
    }

    @Test fun automaticRestoreRetiresOldUnmanagedIndexWithoutChangingTheFolder() {
        val (ordinary, moved) = moveTestVideoToNormalFolder(work())
        val store = PortableVideoStore(ordinary)
        PortableVideoStore.Keyring(password.toCharArray()).use { keys ->
            val entry = store.inspectEntry(moved, null, keys)
            store.writeIndex(File(ordinary, PortablePrivateFolders.INDEX), listOf(entry), keys, store.readSalt(moved), privateFolder = false)
        }
        assertEquals(1, PortablePrivateFolders.restoreExternalArrivals(context, ordinary.absolutePath, password).size)
        assertFalse(File(ordinary, PortablePrivateFolders.INDEX).exists())
        assertFalse(File(ordinary, PortablePrivateFolders.MARKER).exists())
        assertFalse(File(ordinary, ".nomedia").exists())
        assertFalse(FolderLockManager.isLocked(ordinary.absolutePath))
    }

    @Test fun nativeAddRenameDeletePasswordAndSubfolderOperations() {
        val root = work()
        val parent = File(root, "Coleção de teste").apply { check(mkdir()) }
        assertTrue(FolderLockManager.createEmptyLockedFolder(context, parent.absolutePath, password))
        val first = samples().first()
        val source = first.copyTo(File(parent, first.name))
        var added = false
        FolderLockManager.addFilesToLockedFolder(context, parent.absolutePath, password, listOf(source), onError = { error(it) }, onSuccess = { added = true })
        assertTrue(added)
        val initial = session(parent)
        val name = initial.files.single().obfuscatedName
        assertNotNull(FolderLockManager.renameFileInManifest(parent.absolutePath, name, "nome alterado.mp4", password))
        // Losing only the index must still preserve an in-app rename stored in the actual file.
        val saved = File(root, "saved-index.enc")
        check(File(parent, PortablePrivateFolders.INDEX).renameTo(saved))
        assertEquals("nome alterado.mp4", checkNotNull(FolderLockManager.readManifest(parent.absolutePath, password)).files.single().originalName)
        val replacement = "temporary-replacement-password-for-tests"
        var rekeyed = false
        FolderLockManager.reKeyFolder(parent.absolutePath, password, replacement, onError = { error(it) }, onSuccess = { rekeyed = true })
        assertTrue(rekeyed)
        assertNotNull(FolderLockManager.readManifest(parent.absolutePath, password)) // Durable old-password recovery until settings commit.
        assertNotNull(FolderLockManager.readManifest(parent.absolutePath, replacement))
        PortablePrivateFolders.finalizePasswordChange(parent.absolutePath, replacement)
        assertNull(FolderLockManager.readManifest(parent.absolutePath, password))
        assertNotNull(FolderLockManager.readManifest(parent.absolutePath, replacement))
        // Restore the disposable folder to the user's existing credential, without changing preferences.
        FolderLockManager.reKeyFolder(parent.absolutePath, replacement, password, onError = { error(it) })
        PortablePrivateFolders.finalizePasswordChange(parent.absolutePath, password)
        val nested = File(parent, "Vídeos filhos").apply { check(mkdir()) }
        samples().last().copyTo(File(nested, samples().last().name))
        FolderLockManager.lockFolderRecursive(context, nested.absolutePath, password, onError = { error(it) })
        val childPath = checkNotNull(FolderLockManager.addSubfolderToLockedFolder(context, parent.absolutePath, nested.absolutePath, password))
        FolderLockManager.renameSubfolderInManifest(context, parent.absolutePath, File(childPath).name, "Filhos renomeados", password)
        val export = File(root, "Pasta completa exportada").apply { check(mkdir()) }
        val errors = mutableListOf<String>()
        val moved = runBlocking {
            LockedFolderOperations.pasteItems(context, listOf(childPath), export.absolutePath, parent.absolutePath,
                password, false, onError = { errors += it })
        }
        assertTrue(errors.joinToString(), moved)
        val nestedExport = File(export, "Filhos renomeados/${samples().last().name}")
        assertEquals(PortableVideoStore.sha256(samples().last()), PortableVideoStore.sha256(nestedExport))
        assertFalse(FolderLockManager.isLocked(nestedExport.parentFile!!.absolutePath))
        val remaining = session(parent)
        val selected = File(parent, remaining.files.single().obfuscatedName)
        val deletionErrors = mutableListOf<String>()
        runBlocking { LockedFolderOperations.deleteLockedItems(context, listOf(selected.absolutePath), parent.absolutePath,
            password, onError = { deletionErrors += it }) }
        assertTrue(deletionErrors.joinToString(), deletionErrors.isEmpty())
        assertTrue(checkNotNull(FolderLockManager.readManifest(parent.absolutePath, password)).files.isEmpty())
        assertTrue(FilesManager.SecureStorage.verifyPassword(context, password))
        report("NATIVE_ACTIONS: adicionar, renomear com fallback, trocar senha nas cópias, finalizar backup, mover subpasta com descriptografia e excluir: OK; senha real inalterada")
        LockedPlaybackSession.clear()
    }

    @Test fun productionPlayerAndHttpServerUsePortablePayload() {
        val root = work()
        val source = samples().first()
        val folder = File(root, "Player").apply { check(mkdir()) }
        source.copyTo(File(folder, source.name))
        val protected = protect(folder)
        val manifest = session(protected)
        val entry = manifest.files.single()
        val file = File(protected, entry.obfuscatedName)
        val frame = AtomicReference(CountDownLatch(1))
        val failure = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = PlayerView(activity)
                activity.setContentView(view)
                player = ExoPlayer.Builder(activity).build()
                view.player = player
                player.volume = 0f
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.get().countDown() }
                    override fun onPlayerError(error: PlaybackException) { failure.set(error); frame.get().countDown() }
                })
                player.setMediaSource(ProgressiveMediaSource.Factory(HybridDataSourceFactory(context))
                    .createMediaSource(MediaItem.fromUri("locked://${file.absolutePath}")))
                player.prepare()
                player.playWhenReady = true
            }
            assertTrue(frame.get().await(20, TimeUnit.SECONDS))
            assertNull(failure.get())
            frame.set(CountDownLatch(1))
            scenario.onActivity { player.seekTo(player.duration / 2) }
            assertTrue(frame.get().await(20, TimeUnit.SECONDS))
            assertNull(failure.get())
            scenario.onActivity { player.release() }
        }
        val server = LocalVideoServer(context)
        server.addLockedVideo(file.absolutePath, checkNotNull(LockedPlaybackSession.getXorKeyForFile(file.absolutePath)), entry.originalName)
        server.start()
        try {
            val url = "http://127.0.0.1:${server.listeningPort}/video/${URLEncoder.encode(entry.originalName, "UTF-8") }"
            fun request(range: String?, status: Int, expected: ByteArray?) {
                val connection = URL(url).openConnection() as HttpURLConnection
                if (range != null) connection.setRequestProperty("Range", range)
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                try {
                    assertEquals(status, connection.responseCode)
                    if (expected != null) connection.inputStream.use { assertArrayEquals(expected, it.readBytes()) }
                    if (range == null) assertEquals(source.length(), connection.contentLengthLong)
                } finally { connection.disconnect() }
            }
            request(null, 200, source.readBytes())
            request("bytes=8180-8210", 206, source.readBytes().copyOfRange(8180, 8211))
            request("bytes=-20", 206, source.readBytes().takeLast(20).toByteArray())
            request("bytes=${source.length()}-", 416, null)
        } finally { server.stop(); server.clearVideos() }
        report("NATIVE_MEDIA: HybridDataSource/Media3 renderizou e buscou; servidor de transmissão real retornou somente o payload, com intervalos corretos")
        LockedPlaybackSession.clear()
    }

    @Test fun existingLegacyFolderRemainsReadableAndUnchanged() {
        val folder = File("/storage/emulated/0/Memes/88310d4a69fc479c8af04c71eae1a03f")
        assumeTrue(folder.isDirectory && File(folder, ".neko_locked").isFile)
        val before = folder.listFiles().orEmpty().filter { it.isFile }.associate { it.name to PortableVideoStore.sha256(it) }
        val legacy = checkNotNull(FolderLockManager.readManifest(folder.absolutePath, password))
        assertTrue(legacy.files.isNotEmpty())
        assertTrue(legacy.files.all { it.portableMetadata == null })
        assertFalse(File(folder, PortablePrivateFolders.INDEX).exists())
        before.forEach { (name, hash) -> assertEquals(hash, PortableVideoStore.sha256(File(folder, name))) }
        report("LEGACY_COMPATIBILITY: pasta antiga indicada continua no leitor legado; arquivos e controles sem alteração")
    }

    @Test fun portableFileMovedIntoLegacyFolderUsesItsOwnKey() {
        val original = File("/storage/emulated/0/Memes/88310d4a69fc479c8af04c71eae1a03f")
        assumeTrue(File(original, ".neko_locked").isFile)
        val root = work()
        val legacy = File(root, "Cópia legada")
        check(original.copyRecursively(legacy))
        val originalManifest = checkNotNull(FolderLockManager.readManifest(legacy.absolutePath, password))
        val hashes = originalManifest.files.associate { it.obfuscatedName to PortableVideoStore.sha256(File(legacy, it.obfuscatedName)) }
        val source = samples().last()
        val staging = File(root, "Origem nova").apply { check(mkdir()) }
        source.copyTo(File(staging, source.name))
        val portableFolder = protect(staging)
        val entry = checkNotNull(FolderLockManager.readManifest(portableFolder.absolutePath, password)).files.single()
        val moved = File(legacy, "movido por fora.mp4")
        check(File(portableFolder, entry.obfuscatedName).renameTo(moved))
        val mixed = session(legacy)
        assertEquals(originalManifest.files.size + 1, mixed.files.size)
        val portable = mixed.files.single { it.portableMetadata != null }
        assertEquals(moved.name, portable.obfuscatedName)
        verifyReaders(legacy, mixed.copy(files = listOf(portable)))
        FolderLockManager.renameFileInManifest(legacy.absolutePath, moved.name, "misto renomeado.mp4", password)
        PortableVideoStore.Keyring(password.toCharArray()).use { keys ->
            assertEquals("misto renomeado.mp4", PortableVideoStore(legacy).resolve(moved, null, keys).metadata.originalName)
        }
        val (restored, _) = FolderLockManager.extractFilesFromLockedFolder(context, legacy.absolutePath, listOf(moved.name), password)
        assertEquals(1, restored.size)
        assertEquals(PortableVideoStore.sha256(source), PortableVideoStore.sha256(restored.single()))
        hashes.forEach { (name, hash) -> assertEquals(hash, PortableVideoStore.sha256(File(legacy, name))) }
        report("MIXED_FORMATS: arquivo novo movido para cópia de pasta legada usou sua própria chave; renomear e exportar preservaram os vídeos antigos")
        LockedPlaybackSession.clear()
    }

    @Test fun leaveFreshManualSample() {
        val root = work()
        val source = File(root, "Teste manual do formato novo").apply { check(mkdir()) }
        samples().take(2).forEach { it.copyTo(File(source, it.name)) }
        val protected = protect(source)
        val manifest = session(protected)
        verifyReaders(protected, manifest)
        File(root, "Destino para mover por fora").mkdir()
        File(owned, "manual-sample.txt").writeText("${root.absolutePath}\n${protected.absolutePath}\n")
        report("MANUAL_SAMPLE: ${root.absolutePath}; pasta privada exibida como Teste manual do formato novo")
        LockedPlaybackSession.clear()
    }
}
