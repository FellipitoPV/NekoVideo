package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.net.*

/**
 * DLNA/UPnP cast manager — open-source replacement for Google Cast SDK.
 *
 * Discovery: SSDP multicast (UDP 239.255.255.250:1900)
 * File serving: LocalVideoServer (NanoHTTPD, port 8080)
 * Playback control: UPnP AvTransport via SOAP/HTTP
 */
class DLNACastManager(private val context: Context) {

    private val tag = "DLNACastManager"

    data class DLNADevice(
        val name: String,
        val controlUrl: String,
        val baseUrl: String
    )

    private var videoServer: LocalVideoServer? = null
    private var connectedDevice: DLNADevice? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Playlist state
    private var playlist = listOf<String>()
    private var playlistTitles = listOf<String>()
    private var currentIndex = 0

    // Playback state (updated via polling)
    var isConnected = false
        private set
    var isPlaying = false
        private set
    var currentPositionMs = 0L
        private set
    var durationMs = 0L
        private set
    var currentTitle = ""
        private set

    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onDevicesFound: ((List<DLNADevice>) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    private var connectionListener: ((Boolean) -> Unit)? = null

    fun setConnectionStatusListener(listener: (Boolean) -> Unit) {
        connectionListener = listener
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    fun discoverDevices() {
        scope.launch {
            val found = mutableListOf<DLNADevice>()
            try {
                val group = InetAddress.getByName("239.255.255.250")
                val socket = MulticastSocket(null).apply {
                    bind(InetSocketAddress(0))
                    soTimeout = 3000
                    joinGroup(group)
                }

                val search = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 3\r\n")
                    append("ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n")
                }
                val buf = search.toByteArray()
                socket.send(DatagramPacket(buf, buf.size, group, 1900))

                val deadline = System.currentTimeMillis() + 3000L
                val respBuf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val pkt = DatagramPacket(respBuf, respBuf.size)
                        socket.receive(pkt)
                        val response = String(pkt.data, 0, pkt.length)
                        val location = extractHeader(response, "LOCATION") ?: continue
                        val device = fetchDeviceDescription(location)
                        if (device != null && found.none { it.baseUrl == device.baseUrl }) {
                            found.add(device)
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(tag, "SSDP discovery error", e)
            }
            withContext(Dispatchers.Main) {
                onDevicesFound?.invoke(found)
            }
        }
    }

    private fun fetchDeviceDescription(location: String): DLNADevice? {
        return try {
            val url = URL(location)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
            }
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val friendlyName = extractXmlTag(xml, "friendlyName") ?: "DLNA Device"
            val baseUrl = "${url.protocol}://${url.host}:${url.port}"

            for (block in xml.split("<service>")) {
                if (block.contains("AVTransport", ignoreCase = true)) {
                    val path = extractXmlTag(block, "controlURL") ?: continue
                    val controlUrl = if (path.startsWith("http")) path else "$baseUrl$path"
                    return DLNADevice(friendlyName, controlUrl, baseUrl)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(tag, "fetchDeviceDescription failed: $location — ${e.message}")
            null
        }
    }

    // ── Connection ───────────────────────────────────────────────────────────

    fun connectToDevice(device: DLNADevice) {
        connectedDevice = device
        isConnected = true
        connectionListener?.invoke(true)
        onConnectionStateChanged?.invoke(true)
        startPolling()
    }

    private fun startPolling() {
        scope.launch {
            while (isConnected) {
                try {
                    val pos = getPositionInfo()
                    if (pos != null) {
                        currentPositionMs = pos.first
                        durationMs = pos.second
                        isPlaying = getTransportState() == "PLAYING"
                        withContext(Dispatchers.Main) { onStateChanged?.invoke() }
                    }
                } catch (_: Exception) {
                }
                delay(500)
            }
        }
    }

    // ── Casting ──────────────────────────────────────────────────────────────

    fun castVideo(videoPath: String, videoTitle: String) {
        playlist = listOf(videoPath)
        playlistTitles = listOf(videoTitle)
        currentIndex = 0
        prepareServer()
        loadAndPlay(videoPath, videoTitle)
    }

    fun castPlaylist(videosPaths: List<String>, videosTitles: List<String>, startIndex: Int = 0) {
        playlist = videosPaths
        playlistTitles = videosTitles
        currentIndex = startIndex
        prepareServer()
        val path = videosPaths.getOrElse(startIndex) { return }
        val title = videosTitles.getOrElse(startIndex) { File(path.removePrefix("file://")).nameWithoutExtension }
        loadAndPlay(path, title)
    }

    private fun prepareServer() {
        if (videoServer == null) {
            videoServer = LocalVideoServer(context, 8080).also { it.start() }
        }
        videoServer!!.clearVideos()

        playlist.forEach { path ->
            if (path.startsWith("locked://")) {
                val filePath = path.removePrefix("locked://")
                val xorKey = LockedPlaybackSession.getXorKeyForFile(filePath)
                val obfuscatedName = File(filePath).name
                val originalName = LockedPlaybackSession.getOriginalName(obfuscatedName) ?: obfuscatedName
                if (xorKey != null) {
                    videoServer!!.addLockedVideo(filePath, xorKey, originalName)
                } else {
                    videoServer!!.addVideo(filePath)
                }
            } else {
                videoServer!!.addVideo(path.removePrefix("file://"))
            }
        }
    }

    private fun videoUrlFor(videoPath: String): String {
        val name = if (videoPath.startsWith("locked://")) {
            val obfuscated = File(videoPath.removePrefix("locked://")).name
            LockedPlaybackSession.getOriginalName(obfuscated) ?: obfuscated
        } else {
            File(videoPath.removePrefix("file://")).name
        }
        val ip = videoServer?.getLocalIpAddress() ?: "127.0.0.1"
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "http://$ip:8080/video/$encoded"
    }

    private fun loadAndPlay(videoPath: String, videoTitle: String) {
        val device = connectedDevice ?: return
        currentTitle = videoTitle
        scope.launch {
            try {
                val url = videoUrlFor(videoPath)
                sendSoap(device.controlUrl, "SetAVTransportURI",
                    "<CurrentURI>${url.escapeXml()}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
                delay(500)
                sendSoap(device.controlUrl, "Play", "<Speed>1</Speed>")
                isPlaying = true
            } catch (e: Exception) {
                Log.e(tag, "loadAndPlay error", e)
            }
        }
    }

    // ── Playback controls ────────────────────────────────────────────────────

    fun play() {
        val device = connectedDevice ?: return
        scope.launch {
            sendSoap(device.controlUrl, "Play", "<Speed>1</Speed>")
            isPlaying = true
        }
    }

    fun pause() {
        val device = connectedDevice ?: return
        scope.launch {
            sendSoap(device.controlUrl, "Pause", "")
            isPlaying = false
        }
    }

    fun seekTo(posMs: Long) {
        val device = connectedDevice ?: return
        scope.launch {
            sendSoap(device.controlUrl, "Seek",
                "<Unit>REL_TIME</Unit><Target>${msToTimeString(posMs)}</Target>")
        }
    }

    fun next() {
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            val path = playlist[currentIndex]
            val title = playlistTitles.getOrElse(currentIndex) { File(path.removePrefix("file://")).nameWithoutExtension }
            loadAndPlay(path, title)
        }
    }

    fun previous() {
        if (currentIndex > 0) {
            currentIndex--
            val path = playlist[currentIndex]
            val title = playlistTitles.getOrElse(currentIndex) { File(path.removePrefix("file://")).nameWithoutExtension }
            loadAndPlay(path, title)
        }
    }

    fun stopCasting() {
        connectedDevice?.let { scope.launch { sendSoap(it.controlUrl, "Stop", "") } }
        disconnect()
    }

    fun disconnect() {
        isConnected = false
        isPlaying = false
        connectedDevice = null
        connectionListener?.invoke(false)
        onConnectionStateChanged?.invoke(false)
        stopServer()
    }

    fun destroy() {
        scope.cancel()
        stopServer()
    }

    private fun stopServer() {
        videoServer?.stop()
        videoServer = null
    }

    // ── UPnP state queries ───────────────────────────────────────────────────

    private fun getPositionInfo(): Pair<Long, Long>? {
        val device = connectedDevice ?: return null
        return try {
            val response = sendSoap(device.controlUrl, "GetPositionInfo", "") ?: return null
            val pos = parseTimeString(extractXmlTag(response, "RelTime") ?: "0:00:00")
            val dur = parseTimeString(extractXmlTag(response, "TrackDuration") ?: "0:00:00")
            Pair(pos, dur)
        } catch (_: Exception) { null }
    }

    private fun getTransportState(): String {
        val device = connectedDevice ?: return "STOPPED"
        return try {
            val response = sendSoap(device.controlUrl, "GetTransportInfo", "") ?: return "STOPPED"
            extractXmlTag(response, "CurrentTransportState") ?: "STOPPED"
        } catch (_: Exception) { "STOPPED" }
    }

    // ── SOAP ─────────────────────────────────────────────────────────────────

    private fun sendSoap(controlUrl: String, action: String, args: String): String? {
        return try {
            val soap = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      $args
    </u:$action>
  </s:Body>
</s:Envelope>"""
            val url = URL(controlUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction",
                    "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            }
            conn.outputStream.write(soap.toByteArray(Charsets.UTF_8))
            val response = try {
                conn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.readText()
            }
            conn.disconnect()
            response
        } catch (e: Exception) {
            Log.w(tag, "SOAP $action failed: ${e.message}")
            null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun extractHeader(response: String, header: String): String? =
        response.lines()
            .firstOrNull { it.startsWith("$header:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()

    private fun extractXmlTag(xml: String, tag: String): String? {
        val start = xml.indexOf("<$tag>").takeIf { it >= 0 } ?: return null
        val end = xml.indexOf("</$tag>", start).takeIf { it >= 0 } ?: return null
        return xml.substring(start + tag.length + 2, end).trim()
    }

    private fun msToTimeString(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun parseTimeString(time: String): Long {
        return try {
            val parts = time.split(":").map { it.toLong() }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                else -> 0L
            }
        } catch (_: Exception) { 0L }
    }

    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
