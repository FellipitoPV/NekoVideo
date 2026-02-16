package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class LocalVideoServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    private val videoMap = mutableMapOf<String, String>() // nome -> path
    private val lockedVideoKeys = mutableMapOf<String, ByteArray>() // nome -> xorKey

    fun addVideo(videoPath: String) {
        val videoName = File(videoPath).name
        videoMap[videoName] = videoPath
    }

    fun addLockedVideo(videoPath: String, xorKey: ByteArray, originalName: String) {
        videoMap[originalName] = videoPath
        lockedVideoKeys[originalName] = xorKey
    }

    fun clearVideos() {
        videoMap.clear()
        lockedVideoKeys.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        // Formato: /video/nome_do_arquivo.mp4
        if (uri.startsWith("/video/")) {
            val videoName = java.net.URLDecoder.decode(uri.removePrefix("/video/"), "UTF-8")
            val videoPath = videoMap[videoName]

            if (videoPath != null) {
                val xorKey = lockedVideoKeys[videoName]
                return serveVideo(videoPath, session, xorKey)
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")
    }

    private fun serveVideo(videoPath: String, session: IHTTPSession, xorKey: ByteArray? = null): Response {
        try {
            val file = File(videoPath)
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")
            }

            val mimeType = getMimeType(file.name)
            val fileSize = file.length()

            val headers = session.headers
            val rangeHeader = headers["range"]

            if (rangeHeader != null) {
                // Range request (seek support)
                val range = rangeHeader.substring("bytes=".length).split("-")
                val start = range[0].toLongOrNull() ?: 0L
                val end = if (range.size > 1 && range[1].isNotEmpty()) {
                    range[1].toLong()
                } else {
                    fileSize - 1
                }

                val contentLength = end - start + 1
                val fis = FileInputStream(file)
                fis.skip(start)

                val inputStream: InputStream = if (xorKey != null) {
                    XorDecryptingInputStream(fis, xorKey, start)
                } else {
                    fis
                }

                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mimeType,
                    inputStream,
                    contentLength
                )

                response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())

                return response
            } else {
                // Full file request
                val fis = FileInputStream(file)

                val inputStream: InputStream = if (xorKey != null) {
                    XorDecryptingInputStream(fis, xorKey, 0)
                } else {
                    fis
                }

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    inputStream,
                    fileSize
                )
                response.addHeader("Accept-Ranges", "bytes")
                return response
            }

        } catch (e: Exception) {
            Log.e("LocalVideoServer", "Error serving video", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".mkv") -> "video/x-matroska"
            fileName.endsWith(".avi") -> "video/x-msvideo"
            fileName.endsWith(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }

    public fun getVideoUrl(): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:$listeningPort/video"
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalVideoServer", "Error getting IP", e)
        }
        return null
    }
}

/**
 * InputStream wrapper that applies XOR decryption to the first 8KB of a locked video file.
 * Bytes beyond 8KB are passed through unchanged.
 */
private class XorDecryptingInputStream(
    private val delegate: FileInputStream,
    private val xorKey: ByteArray,
    private var position: Long
) : InputStream() {

    companion object {
        private const val HEADER_SIZE = 8192L
    }

    override fun read(): Int {
        val b = delegate.read()
        if (b == -1) return -1
        val result = if (position < HEADER_SIZE) {
            b xor xorKey[(position % xorKey.size).toInt()].toInt() and 0xFF
        } else {
            b
        }
        position++
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = delegate.read(buffer, offset, length)
        if (bytesRead == -1) return -1

        // Apply XOR only to bytes within the first 8KB
        if (position < HEADER_SIZE) {
            val xorEnd = minOf(bytesRead.toLong(), HEADER_SIZE - position).toInt()
            for (i in 0 until xorEnd) {
                val filePos = (position + i) % xorKey.size
                buffer[offset + i] = (buffer[offset + i].toInt() xor xorKey[filePos.toInt()].toInt()).toByte()
            }
        }

        position += bytesRead
        return bytesRead
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}
