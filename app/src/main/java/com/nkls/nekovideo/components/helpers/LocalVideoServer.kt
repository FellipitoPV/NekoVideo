package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class LocalVideoServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    private val videoMap = mutableMapOf<String, String>() // nome -> path

    fun addVideo(videoPath: String) {
        val videoName = File(videoPath).name
        videoMap[videoName] = videoPath
    }

    fun clearVideos() {
        videoMap.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        // Formato: /video/nome_do_arquivo.mp4
        if (uri.startsWith("/video/")) {
            val videoName = uri.removePrefix("/video/")
            val videoPath = videoMap[videoName]

            if (videoPath != null) {
                return serveVideo(videoPath, session)
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")
    }

    private fun serveVideo(videoPath: String, session: IHTTPSession): Response {
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

                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mimeType,
                    fis,
                    contentLength
                )

                response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())

                return response
            } else {
                // Full file request
                val fis = FileInputStream(file)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    fis,
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