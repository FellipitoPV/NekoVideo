package com.nkls.nekovideo.components

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nkls.nekovideo.components.helpers.NekoTrimConnection
import com.nkls.nekovideo.components.helpers.NekoTrimOutputAccess
import com.nkls.nekovideo.components.helpers.NekoTrimVideoAccess
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NekoTrimOutputDeviceTest {
    @Test fun outputPublicationRecoversAndNeverOverwritesExistingVideos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val client = "com.nkls.nekotrim.debug"
        val uid = context.packageManager.getApplicationInfo(client, 0).uid
        val root = File(context.cacheDir, "trim-output-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val prefs = context.getSharedPreferences(NekoTrimConnection.PREFS, 0)
        try {
            val source = File(root, "source.mp4").apply { writeBytes(instrumentation.context.assets.open("embedded-covers/plain.mp4").use { it.readBytes() }) }
            val sourceBytes = source.readBytes()
            val request = NekoTrimVideoAccess.createIntent(context, client, listOf(source.path))
            @Suppress("DEPRECATION") val anchor = request.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM)!!.single()
            val tag = "NekoTrim:${UUID.randomUUID()}"
            val payload = instrumentation.context.assets.open("embedded-covers/cover.mkv").use { it.readBytes() }
            fun rpc(method: String, data: Bundle = Bundle()) = NekoTrimOutputAccess.call(context, client, uid, method, anchor.toString(), data)
            fun stage(name: String): Bundle = rpc("trim_stage", Bundle().apply { putString("name", name); putString("tag", tag); putLong("size", payload.size.toLong()) })
            fun args(output: Bundle) = Bundle().apply { putString("uri", output.getString("uri")) }
            fun write(output: Bundle, bytes: ByteArray = payload) {
                val descriptor = NekoTrimOutputAccess.open(context, client, uid, Uri.parse(output.getString("uri")), "w")
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(bytes) }
            }

            val first = stage("cut.mkv")
            write(first)
            rpc("trim_verify", args(first))
            rpc("trim_publish", args(first))
            val result = File(root, "NekoTrim/cut.mkv")
            assertArrayEquals(payload, result.readBytes())

            // Simulate process death after moving the file, before saving the host's final receipt.
            val id = Uri.parse(first.getString("uri")).lastPathSegment!!
            val record = JSONObject(prefs.getString("output.$id", null)!!).put("published", JSONObject.NULL)
            prefs.edit().putString("output.$id", record.toString()).commit()
            rpc("trim_publish", args(first))
            assertArrayEquals(payload, result.readBytes())
            assertEquals(1, result.parentFile!!.listFiles()!!.count { it.extension == "mkv" })

            val second = stage("cut.mkv")
            assertEquals("cut (1).mkv", second.getString("name"))
            write(second, payload.copyOfRange(0, 8))
            assertTrue(runCatching { rpc("trim_verify", args(second)) }.isFailure)
            rpc("trim_delete", args(second))

            // A new file appearing between stage and publish belongs to its creator.
            val raced = stage("race.mkv")
            write(raced)
            val unrelated = File(root, "NekoTrim/race.mkv").apply { writeText("keep this file") }
            assertTrue(runCatching { rpc("trim_publish", args(raced)) }.isFailure)
            rpc("trim_delete", args(raced))
            assertEquals("keep this file", unrelated.readText())
            assertTrue(result.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
            assertArrayEquals(sourceBytes, source.readBytes())
            assertTrue(runCatching { rpc("trim_delete", args(first)) }.isFailure)
            assertArrayEquals(payload, result.readBytes())
        } finally {
            val edit = prefs.edit()
            prefs.all.forEach { (key, value) -> if ((key.startsWith("video.") || key.startsWith("output.")) && value.toString().contains(root.name)) edit.remove(key) }
            edit.commit()
            root.deleteRecursively()
        }
    }
}
