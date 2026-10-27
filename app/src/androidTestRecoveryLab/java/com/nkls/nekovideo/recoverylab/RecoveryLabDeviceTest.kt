package com.nkls.nekovideo.recoverylab

import com.nkls.nekovideo.components.helpers.PortableVideoStore

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nkls.nekovideo.components.helpers.FolderLockManager
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryLabDeviceTest {
    private fun experiment(): DeviceRecoveryExperiment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.nkls.nekovideo.recoverylab")
        return DeviceRecoveryExperiment(context)
    }

    @Test fun prepareBeforeExternalMove() {
        Log.i("RecoveryLab", experiment().prepare())
    }

    @Test fun recoverAfterExternalMoveInNewProcess() {
        Log.i("RecoveryLab", experiment().verify())
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test fun media3PlaysAndSeeksUsingEmbeddedFallback() {
        val experiment = experiment()
        val arguments = InstrumentationRegistry.getArguments()
        val legacy = arguments.getString("useLegacyBridge") == "true"
        val folder = File(if (legacy) LabDeviceSupport().activeLegacy() else experiment.activeRun(), "externally-moved")
        val file = checkNotNull(folder.listFiles()?.firstOrNull { it.name.startsWith("renamed-") })
        val password = if (legacy) checkNotNull(arguments.getString("legacyPassword")) else DeviceRecoveryExperiment.PASSWORD
        val metadata = PortableVideoStore.Keyring(password.toCharArray()).use {
            experiment.lab.resolve(file, null, it).metadata
        }
        val frame = AtomicReference(CountDownLatch(1))
        val error = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        ActivityScenario.launch(RecoveryLabActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                player = activity.startPlayback(file, metadata, object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.get().countDown() }
                    override fun onPlayerError(failure: PlaybackException) {
                        error.set(failure)
                        frame.get().countDown()
                    }
                }, muted = true)
            }
            assertTrue("No first video frame", frame.get().await(20, TimeUnit.SECONDS))
            assertNull(error.get())
            frame.set(CountDownLatch(1))
            scenario.onActivity {
                assertTrue(player.duration > 0)
                player.seekTo(player.duration / 2)
            }
            assertTrue("No video frame after seeking", frame.get().await(20, TimeUnit.SECONDS))
            assertNull(error.get())
            scenario.onActivity { assertTrue(player.currentPosition >= player.duration / 2 - 1_000) }
        }
    }

    @Test fun legacyFolderStillLocksAndRestoresWithoutPortableFormat() {
        val experiment = experiment()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = checkNotNull(experiment.input.listFiles()?.firstOrNull { it.extension == "mp4" })
        val parent = experiment.lab.checked(File(experiment.root, "legacy-check-${UUID.randomUUID()}"))
        check(parent.mkdir())
        val folder = File(parent, "legacy").apply { check(mkdir()) }
        val copy = source.copyTo(File(folder, source.name))
        val expected = PortableVideoStore.sha256(copy)
        var locked = false
        FolderLockManager.lockFolder(context, folder.absolutePath, DeviceRecoveryExperiment.PASSWORD,
            onError = { error(it) }, onSuccess = { locked = true })
        assertTrue(locked)
        val lockedFolder = checkNotNull(parent.listFiles()?.single { it.isDirectory })
        assertTrue(FolderLockManager.isLocked(lockedFolder.absolutePath))
        assertNotNull(FolderLockManager.readManifest(lockedFolder.absolutePath, DeviceRecoveryExperiment.PASSWORD))
        var restoredPath: String? = null
        FolderLockManager.unlockFolder(context, lockedFolder.absolutePath, DeviceRecoveryExperiment.PASSWORD,
            onError = { error(it) }, onSuccess = { restoredPath = it })
        assertEquals(expected, PortableVideoStore.sha256(File(checkNotNull(restoredPath), source.name)))
        assertEquals(expected, PortableVideoStore.sha256(source))
    }
}
