package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.*

/**
 * DLNA/UPnP cast manager — open-source replacement for Google Cast SDK.
 *
 * Discovery: SSDP multicast (UDP 239.255.255.250:1900)
 * File serving: LocalVideoServer (NanoHTTPD, system-assigned available port)
 * Playback control: UPnP AvTransport via SOAP/HTTP
 */
class DLNACastManager(private val context: Context) {

    companion object {
        private const val PROGRESS_PERSIST_INTERVAL_MS = 10_000L

        @Volatile private var instance: DLNACastManager? = null

        fun getInstance(context: Context): DLNACastManager =
            instance ?: synchronized(this) {
                instance ?: DLNACastManager(context.applicationContext).also { instance = it }
            }
    }

    private val tag = "DLNACastManager"

    data class DLNADevice(
        val name: String,
        val controlUrl: String,
        val baseUrl: String,
        val localAddress: String? = null
    )

    private var videoServer: LocalVideoServer? = null
    private var connectedDevice: DLNADevice? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val seekMutex = Mutex()

    // Playlist state
    private var playlist = listOf<String>()
    private var playlistTitles = listOf<String>()
    private var currentIndex = 0

    // Playback state (updated via polling)
    var isConnected = false
        private set
    val connectedDeviceName: String get() = connectedDevice?.name ?: ""
    val currentPlaylistIndex: Int get() = currentIndex
    val playlistSize: Int get() = playlist.size
    fun currentPlaylistSnapshot(): List<String> = playlist.toList()
    var isPlaying = false
        private set
    var currentPositionMs = 0L
        private set
    var durationMs = 0L
        private set
    var currentTitle = ""
        private set
    var currentVideoPath = ""
        private set

    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onDevicesFound: ((List<DLNADevice>) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null
    // Separate observer for DLNACastService (avoids overwriting onStateChanged from UI)
    var onServiceStateChanged: (() -> Unit)? = null

    private var stoppedByUser = false
    private var isLoadingTrack = false
    private var lastProgressPersistAtMs = 0L

    private var connectionListener: ((Boolean) -> Unit)? = null

    fun setConnectionStatusListener(listener: (Boolean) -> Unit) {
        connectionListener = listener
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    fun discoverDevices() {
        scope.launch {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicastLock = wifiManager.createMulticastLock("nekovideo_ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(tag, "MulticastLock acquired: ${multicastLock.isHeld}")

            try {
                val targets = activeIpv4Interfaces()
                Log.d(tag, "Scanning SSDP on: ${targets.joinToString { "${it.first.name}/${it.second.hostAddress}" }}")
                val found = coroutineScope {
                    targets.map { (networkInterface, address) ->
                        async { discoverOnInterface(networkInterface, address) }
                    }.awaitAll().flatten()
                }.distinctBy { it.baseUrl }
                Log.d(tag, "Discovery done. Devices found: ${found.size}")

                withContext(Dispatchers.Main) {
                    onDevicesFound?.invoke(found)
                }
            } catch (e: Exception) {
                Log.e(tag, "SSDP discovery error", e)
                withContext(Dispatchers.Main) {
                    onDevicesFound?.invoke(emptyList())
                }
            } finally {
                multicastLock.release()
                Log.d(tag, "MulticastLock released")
            }
        }
    }

    private fun activeIpv4Interfaces(): List<Pair<NetworkInterface, Inet4Address>> =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            ?.flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .map { networkInterface to it }
            }
            ?.toList()
            .orEmpty()

    private suspend fun discoverOnInterface(
        networkInterface: NetworkInterface,
        localAddress: Inet4Address
    ): List<DLNADevice> {
        val found = mutableListOf<DLNADevice>()
        val localIp = localAddress.hostAddress ?: return found
        val group = InetAddress.getByName("239.255.255.250")
        val search = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
        }.toByteArray()

        try {
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(localAddress, 0))
                socket.soTimeout = 500
                socket.networkInterface = networkInterface
                socket.joinGroup(InetSocketAddress(group, 1900), networkInterface)
                Log.d(tag, "SSDP socket on ${networkInterface.name}/${localAddress.hostAddress}")

                repeat(2) {
                    socket.send(DatagramPacket(search, search.size, group, 1900))
                    delay(200)
                }

                val deadline = System.currentTimeMillis() + 4000L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val buffer = ByteArray(4096)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val location = extractHeader(String(packet.data, 0, packet.length), "LOCATION")
                            ?: continue
                        val device = fetchDeviceDescription(location, localIp)
                        if (device != null && found.none { it.baseUrl == device.baseUrl }) {
                            Log.d(tag, "Device found via ${networkInterface.name}: ${device.name}")
                            found.add(device)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue receiving until the shared discovery window ends.
                    }
                }
            }
        } catch (e: Exception) {
            // Individual interfaces can reject multicast; keep scanning the others.
            Log.w(tag, "SSDP unavailable on ${networkInterface.name}/${localAddress.hostAddress}: ${e.message}")
        }
        return found
    }

    private fun fetchDeviceDescription(location: String, localAddress: String): DLNADevice? {
        return try {
            val url = URL(location)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
            }
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val friendlyName = extractXmlTag(xml, "friendlyName") ?: "DLNA Device"
            val baseUrl = "${url.protocol}://${url.host}:${url.port.takeIf { it >= 0 } ?: url.defaultPort}"

            for (block in xml.split("<service>")) {
                if (block.contains("AVTransport", ignoreCase = true)) {
                    val path = extractXmlTag(block, "controlURL") ?: continue
                    val controlUrl = if (path.startsWith("http")) path else URL(url, path).toString()
                    return DLNADevice(friendlyName, controlUrl, baseUrl, localAddress)
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
        context.startService(Intent(context, com.nkls.nekovideo.DLNACastService::class.java))
        startPolling()
    }

    private fun startPolling() {
        scope.launch {
            var wasPlaying = false
            var lastPersistedPositionMs = -1L
            while (isConnected) {
                try {
                    val pos = getPositionInfo()
                    if (pos != null) {
                        currentPositionMs = pos.first
                        durationMs = pos.second
                        val transportState = getTransportState()
                        isPlaying = transportState == "PLAYING"

                        val now = System.currentTimeMillis()
                        val shouldPersistProgress = !isLoadingTrack && currentTitle.isNotBlank() && durationMs > 0L && (
                            (isPlaying && now - lastProgressPersistAtMs >= PROGRESS_PERSIST_INTERVAL_MS) ||
                                (!isPlaying && currentPositionMs != lastPersistedPositionMs)
                            )
                        if (shouldPersistProgress) {
                            persistCurrentCastProgress()
                            lastPersistedPositionMs = currentPositionMs
                            lastProgressPersistAtMs = now
                        }

                        // Auto-advance when video ends naturally (PLAYING → STOPPED/NO_MEDIA_PRESENT)
                        // Guard isLoadingTrack: Smart TVs briefly enter STOPPED during SetAVTransportURI
                        // which would otherwise trigger a spurious next() and skip the intended video.
                        if (wasPlaying && !isPlaying && !stoppedByUser && !isLoadingTrack && playlist.size > 1
                            && transportState != "PAUSED_PLAYBACK") {
                            withContext(Dispatchers.Main) { next() }
                        }

                        wasPlaying = isPlaying
                        withContext(Dispatchers.Main) {
                            onStateChanged?.invoke()
                            onServiceStateChanged?.invoke()
                        }
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

    fun castPlaylist(
        videosPaths: List<String>,
        videosTitles: List<String>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L
    ) {
        playlist = videosPaths
        playlistTitles = videosTitles
        currentIndex = startIndex
        prepareServer()
        val path = videosPaths.getOrElse(startIndex) { return }
        val title = videosTitles.getOrElse(startIndex) { File(path.removePrefix("file://")).nameWithoutExtension }
        loadAndPlay(path, title, startPositionMs)
    }

    private fun prepareServer() {
        if (videoServer == null) {
            videoServer = LocalVideoServer(context).also { it.start() }
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
        val ip = connectedDevice?.localAddress
            ?: videoServer?.getLocalIpAddress(connectedDevice?.baseUrl)
            ?: "127.0.0.1"
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val port = videoServer?.listeningPort ?: return ""
        return "http://$ip:$port/video/$encoded"
    }

    private fun mimeTypeFor(videoPath: String): String {
        val name = if (videoPath.startsWith("locked://")) {
            val obfuscated = File(videoPath.removePrefix("locked://")).name
            LockedPlaybackSession.getOriginalName(obfuscated) ?: obfuscated
        } else {
            File(videoPath.removePrefix("file://")).name
        }
        return mimeTypeForVideoFileName(name)
    }

    private fun buildDIDLMetadata(title: String, url: String, mimeType: String): String {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="false"><dc:title>${title.escapeXml()}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01500000000000000000000000000000">${url.escapeXml()}</res></item></DIDL-Lite>"""
        return didl.escapeXml()
    }

    private fun loadAndPlay(videoPath: String, videoTitle: String, startPositionMs: Long = 0L) {
        val device = connectedDevice ?: return
        currentTitle = videoTitle
        currentVideoPath = videoPath
        lastProgressPersistAtMs = System.currentTimeMillis()
        stoppedByUser = false
        isLoadingTrack = true
        scope.launch {
            try {
                val url = videoUrlFor(videoPath)
                val mime = mimeTypeFor(videoPath)
                val metadata = buildDIDLMetadata(videoTitle, url, mime)
                sendSoap(device.controlUrl, "SetAVTransportURI",
                    "<CurrentURI>${url.escapeXml()}</CurrentURI><CurrentURIMetaData>$metadata</CurrentURIMetaData>")
                delay(500)
                sendSoap(device.controlUrl, "Play", "<Speed>1</Speed>")
                isPlaying = true
                if (startPositionMs > 0L) {
                    // Some renderers reject Seek until playback has begun after SetAVTransportURI.
                    delay(700)
                    seekTo(startPositionMs)
                }
                // Keep the flag set until the TV has had time to transition to PLAYING.
                // Smart TVs briefly report STOPPED during SetAVTransportURI; without this
                // guard the polling loop would fire next() and skip the intended video.
                delay(1500)
                isLoadingTrack = false
            } catch (e: Exception) {
                Log.e(tag, "loadAndPlay error", e)
                isLoadingTrack = false
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
            persistCurrentCastProgress()
        }
    }

    fun seekTo(
        posMs: Long,
        wasPlayingBeforeSeek: Boolean = isPlaying,
        resumePlaybackIfPaused: Boolean = false
    ) {
        val device = connectedDevice ?: return
        scope.launch {
            seekMutex.withLock {
                val shouldResumePlayback = !wasPlayingBeforeSeek && resumePlaybackIfPaused
                if (shouldResumePlayback) {
                    sendSoap(device.controlUrl, "Play", "<Speed>1</Speed>")
                    isPlaying = true
                    // Kodi and some DLNA receivers need playback to resume before accepting Seek.
                    delay(500)
                }

                val response = sendSoap(device.controlUrl, "Seek",
                    "<Unit>REL_TIME</Unit><Target>${msToTimeString(posMs)}</Target>")
                if (response == null) {
                    Log.w(tag, "Seek failed at ${msToTimeString(posMs)}")
                    return@withLock
                }

                currentPositionMs = posMs
                if (!wasPlayingBeforeSeek && !shouldResumePlayback) {
                    // Some renderers resume after a seek even when they were paused.
                    delay(150)
                    sendSoap(device.controlUrl, "Pause", "")
                    isPlaying = false
                }
                persistCurrentCastProgress()
            }
        }
    }

    fun next() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        val path = playlist[currentIndex]
        val title = playlistTitles.getOrElse(currentIndex) { File(path.removePrefix("file://")).nameWithoutExtension }
        loadAndPlay(path, title)
    }

    fun previous() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
        val path = playlist[currentIndex]
        val title = playlistTitles.getOrElse(currentIndex) { File(path.removePrefix("file://")).nameWithoutExtension }
        loadAndPlay(path, title)
    }

    fun stopPlayback() {
        persistCurrentCastProgress()
        stoppedByUser = true
        playlist = listOf()
        playlistTitles = listOf()
        currentIndex = 0
        currentTitle = ""
        currentVideoPath = ""
        isPlaying = false
        currentPositionMs = 0L
        durationMs = 0L
        connectedDevice?.let { scope.launch { sendSoap(it.controlUrl, "Stop", "") } }
        scope.launch(Dispatchers.Main) { onStateChanged?.invoke() }
    }

    fun stopCasting() {
        persistCurrentCastProgress()
        stoppedByUser = true
        connectedDevice?.let { scope.launch { sendSoap(it.controlUrl, "Stop", "") } }
        disconnect()
    }

    private fun persistCurrentCastProgress() {
        val videoPath = currentVideoPath
        val title = currentTitle
        val positionMs = currentPositionMs
        val duration = durationMs
        if (videoPath.isBlank() || title.isBlank() || duration <= 0L) return

        ContinueWatchingStore.save(
            context = context,
            videoPath = videoPath,
            title = title,
            positionMs = positionMs,
            durationMs = duration
        )
        VideoProgressStore.save(
            context = context,
            videoPath = videoPath,
            title = title,
            positionMs = positionMs,
            durationMs = duration
        )
    }

    fun disconnect() {
        isConnected = false
        isPlaying = false
        connectedDevice = null
        connectionListener?.invoke(false)
        onConnectionStateChanged?.invoke(false)
        context.stopService(Intent(context, com.nkls.nekovideo.DLNACastService::class.java))
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
