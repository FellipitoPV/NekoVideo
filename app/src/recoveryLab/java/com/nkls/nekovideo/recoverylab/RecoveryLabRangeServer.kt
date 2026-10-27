package com.nkls.nekovideo.recoverylab

import com.nkls.nekovideo.components.helpers.PortableVideoStore

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream

/** Loopback-only transport experiment. It neither discovers nor transmits to external devices. */
class RecoveryLabRangeServer(
    private val file: File,
    private val metadata: PortableVideoStore.Metadata
) : NanoHTTPD("127.0.0.1", 0) {
    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/video") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        if (session.method != Method.GET) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET only")
        val size = metadata.payloadSize
        val range = session.headers["range"]
        var start = 0L
        var end = size - 1
        if (range != null) {
            val parsed = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
            if (parsed == null || size == 0L) return unsatisfied(size)
            val first = parsed.groupValues[1]
            val last = parsed.groupValues[2]
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return unsatisfied(size)
                if (suffix <= 0) return unsatisfied(size)
                start = (size - suffix).coerceAtLeast(0)
            } else {
                start = first.toLongOrNull() ?: return unsatisfied(size)
                if (last.isNotEmpty()) end = (last.toLongOrNull() ?: return unsatisfied(size)).coerceAtMost(end)
            }
            if (start >= size || end < start) return unsatisfied(size)
        }
        val count = if (size == 0L) 0 else end - start + 1
        val reader = RecoveryLabMediaDataSource(file, metadata)
        val input = object : InputStream() {
            var position = start
            var remaining = count
            private val single = ByteArray(1)
            override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining == 0L) return -1
                val read = reader.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
                if (read > 0) { position += read; remaining -= read }
                return read
            }
            override fun close() = reader.close()
        }
        return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
            "video/mp4", input, count).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range != null) addHeader("Content-Range", "bytes $start-$end/$size")
        }
    }

    private fun unsatisfied(size: Long) = newFixedLengthResponse(
        Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range"
    ).apply { addHeader("Content-Range", "bytes */$size") }
}
