package com.nkls.nekovideo.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaCoverReaderTest {
    @Test fun prefersTheMainCoverAndSkipsVideoPayloadAndFonts() {
        val bytes = movie(
            element("1F43B675", ByteArray(1_000_000)),
            element("1941A469",
                attachment("font.ttf", "application/x-truetype-font", "font") +
                    attachment("small_cover.jpg", "image/jpeg", "small") +
                    attachment("cover.jpg", "image/jpeg", "main")
            )
        )
        var bytesRead = 0
        val reader = MatroskaCoverReader(bytes.size.toLong()) { position, length ->
            bytesRead += length
            bytes.copyOfRange(position.toInt(), position.toInt() + length)
        }
        assertEquals("main", reader.readCover { it.toString(Charsets.UTF_8) })
        assertTrue("Read $bytesRead bytes instead of seeking over the video", bytesRead < 1_024)
    }

    @Test fun seeksToAttachmentsAfterAnUnknownLengthCluster() {
        val cluster = hex("1F43B675FF") + ByteArray(500)
        fun seekHead(position: Int) = element("114D9B74", element("4DBB",
            element("53AB", hex("1941A469")) +
                element("53AC", byteArrayOf(0, 0, (position shr 8).toByte(), position.toByte()))
        ))
        val head = seekHead(seekHead(0).size + cluster.size)
        val bytes = hex("1A45DFA38018538067FF") + head + cluster +
            element("1941A469", attachment("cover.png", "image/png", "after-cluster"))
        assertEquals("after-cluster", reader(bytes).readCover { it.toString(Charsets.UTF_8) })
    }

    @Test fun triesTheNextCoverWhenThePreferredImageCannotBeDecoded() {
        val bytes = movie(element("1941A469",
            attachment("cover.jpg", "image/jpeg", "invalid") +
                attachment("cover_land.png", "image/png", "valid")
        ))
        assertEquals("valid", reader(bytes).readCover { it.toString(Charsets.UTF_8).takeIf { text -> text == "valid" } })
    }

    @Test fun ignoresNonImageAttachments() {
        val bytes = movie(element("1941A469", attachment("font.ttf", "application/x-truetype-font", "font")))
        assertNull(reader(bytes).readCover { error("Font data must not be decoded as an image") })
    }

    @Test fun rejectsTruncatedAndOutOfBoundsElements() {
        for (bytes in listOf(hex("1A45"), hex("1A45DFA3FF"), hex("1A45DFA380185380674FFF"), hex("00000000"))) {
            assertTrue(runCatching { reader(bytes).readCover { it } }.isFailure)
        }
    }

    @Test fun boundsTheNumberOfElementsReadFromMalformedFiles() {
        val bytes = movie(*Array(4_200) { element("EC", byteArrayOf()) })
        var reads = 0
        val parser = MatroskaCoverReader(bytes.size.toLong()) { position, length ->
            reads++
            bytes.copyOfRange(position.toInt(), position.toInt() + length)
        }
        assertTrue(runCatching { parser.readCover { it } }.isFailure)
        assertTrue(reads <= 4_096)
    }

    private fun reader(bytes: ByteArray) = MatroskaCoverReader(bytes.size.toLong()) { position, length ->
        require(position >= 0 && position + length <= bytes.size)
        bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }

    private fun movie(vararg elements: ByteArray) =
        element("1A45DFA3", byteArrayOf()) + element("18538067", elements.fold(byteArrayOf()) { result, value -> result + value })

    private fun attachment(name: String, mime: String, data: String) = element("61A7",
        element("465C", data.toByteArray()) +
            element("466E", name.toByteArray()) + element("4660", mime.toByteArray())
    )

    private fun element(id: String, data: ByteArray): ByteArray {
        var sizeLength = 1
        while (data.size.toLong() >= (1L shl (7 * sizeLength)) - 1) sizeLength++
        val encodedSize = (1L shl (7 * sizeLength)) or data.size.toLong()
        val size = ByteArray(sizeLength) { index -> (encodedSize shr (8 * (sizeLength - index - 1))).toByte() }
        return hex(id) + size + data
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
