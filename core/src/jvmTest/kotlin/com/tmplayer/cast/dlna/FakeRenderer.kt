package com.tmplayer.cast.dlna

import java.io.Closeable
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A MediaRenderer on loopback for the whole-flow tests: it answers M-SEARCH on a UDP port, serves
 * its description and the three services over HTTP, and behaves like a TV where it matters. It
 * checks the address it is given the way a real TV does (a HEAD with the DLNA headers, so a wrong
 * or missing one shows up), turns down types it does not play with 714 and addresses it cannot
 * fetch with 716, answers 701 when busy, and reports a position that only moves when the test
 * calls [advance]. Its answers are shaped like the captured Rygel and gmrender ones.
 */
class FakeRenderer(
    val friendlyName: String = "Living room TV",
    var acceptedMimes: Set<String> = setOf("video/mp4"),
    private val sinkList: String = "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC_520,http-get:*:video/x-matroska:*",
    private val withRenderingControl: Boolean = true,
    var allowSeek: Boolean = true,
    /** `0:00:05.250` (Rygel) or `0:00:05` (gmrender). */
    var fractionalTimes: Boolean = true,
    var reportsDuration: Boolean = true,
) : Closeable {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val http = ServerSocket(0, 16, loopback)
    private val udp = DatagramSocket(0, loopback)
    val httpPort: Int get() = http.localPort
    val udpPort: Int get() = udp.localPort
    val udn = "uuid:fa4e0000-0000-0000-0000-000000000001"
    val location: String get() = "http://127.0.0.1:$httpPort/description.xml"

    /** Every SOAP action received, in order. */
    val calls = CopyOnWriteArrayList<String>()
    val searches = AtomicInteger()

    /** False once the TV is "switched off": it no longer answers M-SEARCH. */
    @Volatile var answersSearches = true

    @Volatile var state = "STOPPED"
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 120_000L
    @Volatile var uri = ""
    @Volatile var didl = ""
    @Volatile var volume = 30
    @Volatile var muted = false

    /** Answer the next SetAVTransportURI with 701 (busy in another app), once. */
    @Volatile var busyOnce = false

    /** Answer the next Play with this UPnP error code, once (716: the TV timed the stream out). */
    @Volatile var playFaultOnce: Int? = null

    /** A stopped player writes zero as its position, as many real ones do. */
    @Volatile var zeroOnStop = true

    // What the "TV" saw when it fetched the media.
    @Volatile var headStatus = 0
    @Volatile var headHeaders: Map<String, String> = emptyMap()
    @Volatile var getStatus = 0
    @Volatile var getContentRange: String? = null
    @Volatile var fetchedSubtitle: String? = null
    val setUris = CopyOnWriteArrayList<String>()

    @Volatile private var closed = false

    init {
        daemon("fake-ssdp") { ssdpLoop() }
        daemon("fake-http") { httpLoop() }
    }

    /** Playing time passes on the TV. At the end it stops, on the last position. */
    fun advance(ms: Long) {
        if (state != "PLAYING") return
        positionMs += ms
        if (positionMs >= durationMs) {
            positionMs = durationMs
            state = "STOPPED"
        }
    }

    /** Sends the byebye NOTIFY (what a TV does when it is switched off) to a listener on [port]. */
    fun byebye(port: Int) {
        val text = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: ${Ssdp.MEDIA_RENDERER}\r\nNTS: ssdp:byebye\r\nUSN: $udn::${Ssdp.MEDIA_RENDERER}\r\n\r\n"
        val bytes = text.toByteArray()
        udp.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(loopback, port)))
    }

    /** The TV is switched off: nothing answers on HTTP any more. */
    fun powerOff() {
        runCatching { http.close() }
    }

    override fun close() {
        closed = true
        runCatching { http.close() }
        runCatching { udp.close() }
    }

    private fun daemon(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }

    // SSDP

    private fun ssdpLoop() {
        val buffer = ByteArray(2048)
        while (!closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(packet)
            } catch (e: Exception) {
                return
            }
            if (!String(packet.data, 0, packet.length).startsWith("M-SEARCH")) continue
            searches.incrementAndGet()
            if (!answersSearches) continue
            val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $location\r\n" +
                "SERVER: Linux/0 UPnP/1.0 FakeRenderer/1\r\nST: ${Ssdp.MEDIA_RENDERER}\r\nUSN: $udn::${Ssdp.MEDIA_RENDERER}\r\n\r\n"
            val bytes = answer.toByteArray()
            runCatching { udp.send(DatagramPacket(bytes, bytes.size, packet.socketAddress)) }
        }
    }

    // HTTP

    private fun httpLoop() {
        while (!closed) {
            val socket = try {
                http.accept()
            } catch (e: Exception) {
                return
            }
            daemon("fake-conn") { runCatching { serve(socket) } }
        }
    }

    private fun serve(socket: Socket) {
      socket.use { s ->
        s.soTimeout = 5000
        val input = s.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return
            read += n
        }
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val (status, type, payload) = when {
            path == "/description.xml" -> Triple(200, "text/xml", description().toByteArray())
            path.startsWith("/ctl/") -> control(path.removePrefix("/ctl/"), headers["soapaction"].orEmpty(), String(body, Charsets.UTF_8))
            else -> Triple(404, "text/plain", ByteArray(0))
        }
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Internal Server Error" }
        s.getOutputStream().apply {
            write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(payload)
            flush()
        }
      }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun description(): String {
        val rc = if (withRenderingControl) {
            "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><controlURL>/ctl/RenderingControl</controlURL><eventSubURL>/evt/RC</eventSubURL><SCPDURL>/scpd/RC.xml</SCPDURL></service>"
        } else ""
        return """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>$friendlyName</friendlyName>
<manufacturer>Fake &amp; Sons</manufacturer><modelName>FR-1</modelName><UDN>$udn</UDN>
<iconList><icon><mimetype>image/png</mimetype><width>48</width><height>48</height><depth>24</depth><url>/icon48.png</url></icon><icon><mimetype>image/png</mimetype><width>120</width><height>120</height><depth>24</depth><url>/icon120.png</url></icon></iconList>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><controlURL>/ctl/AVTransport</controlURL><eventSubURL>/evt/AV</eventSubURL><SCPDURL>/scpd/AV.xml</SCPDURL></service>
$rc
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><controlURL>/ctl/ConnectionManager</controlURL><eventSubURL>/evt/CM</eventSubURL><SCPDURL>/scpd/CM.xml</SCPDURL></service>
</serviceList></device></root>"""
    }

    private fun clock(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val base = "%d:%02d:%02d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60)
        return if (fractionalTimes) "$base.%03d".format(t % 1000) else base
    }

    private fun ok(service: String, action: String, vararg out: Pair<String, String>): Triple<Int, String, ByteArray> {
        val inner = out.joinToString("") { "<${it.first}>${Xml.escape(it.second)}</${it.first}>" }
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">$inner</u:${action}Response></s:Body></s:Envelope>"
        return Triple(200, "text/xml", body.toByteArray())
    }

    private fun fault(code: Int, text: String): Triple<Int, String, ByteArray> {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>" +
            "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode><errorDescription>$text</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        return Triple(500, "text/xml", body.toByteArray())
    }

    private fun control(service: String, soapAction: String, body: String): Triple<Int, String, ByteArray> {
        val action = soapAction.trim('"').substringAfter('#')
        calls += action
        val args = (Soap.parse(200, body) as? SoapResult.Ok)?.values.orEmpty()
        if (service == "ConnectionManager") {
            return if (action == "GetProtocolInfo") ok("ConnectionManager", action, "Source" to "", "Sink" to sinkList) else fault(401, "Invalid Action")
        }
        if (service == "RenderingControl") {
            return when (action) {
                "GetVolume" -> ok("RenderingControl", action, "CurrentVolume" to volume.toString())
                "SetVolume" -> { volume = args["DesiredVolume"]?.toIntOrNull() ?: volume; ok("RenderingControl", action) }
                "GetMute" -> ok("RenderingControl", action, "CurrentMute" to if (muted) "1" else "0")
                "SetMute" -> { muted = args["DesiredMute"] == "1"; ok("RenderingControl", action) }
                else -> fault(401, "Invalid Action")
            }
        }
        if (args["InstanceID"] != "0") return fault(718, "Invalid InstanceID")
        return when (action) {
            "SetAVTransportURI" -> setUri(args["CurrentURI"].orEmpty(), args["CurrentURIMetaData"].orEmpty())
            "Play" -> play()
            "Pause" -> if (state == "PLAYING") { state = "PAUSED_PLAYBACK"; ok("AVTransport", action) } else fault(701, "Transition not available")
            "Stop" -> { state = "STOPPED"; if (zeroOnStop) positionMs = 0; ok("AVTransport", action) }
            "Seek" -> {
                val target = UpnpTime.parseMs(args["Target"])
                when {
                    !allowSeek -> fault(710, "Seek mode not supported")
                    args["Unit"] != "REL_TIME" || target == null -> fault(711, "Illegal seek target")
                    state == "STOPPED" -> fault(701, "Transition not available")
                    else -> { positionMs = target; ok("AVTransport", action) }
                }
            }
            "GetTransportInfo" -> ok("AVTransport", action, "CurrentTransportState" to state, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> ok(
                "AVTransport", action, "Track" to "1", "TrackDuration" to if (reportsDuration) clock(durationMs) else "NOT_IMPLEMENTED",
                "TrackMetaData" to "", "TrackURI" to uri, "RelTime" to clock(positionMs), "AbsTime" to clock(positionMs),
                "RelCount" to "2147483647", "AbsCount" to "2147483647",
            )
            "GetCurrentTransportActions" -> ok("AVTransport", action, "Actions" to when (state) {
                "PLAYING" -> "Stop,Pause" + if (allowSeek) ",Seek,X_DLNA_SeekTime" else ""
                "PAUSED_PLAYBACK" -> "Play,Stop" + if (allowSeek) ",Seek,X_DLNA_SeekTime" else ""
                else -> "Play"
            })
            else -> fault(401, "Invalid Action")
        }
    }

    private fun setUri(newUri: String, newDidl: String): Triple<Int, String, ByteArray> {
        setUris += newUri
        if (busyOnce) {
            busyOnce = false
            return fault(701, "Transition not available")
        }
        val res = Didl.parse(newDidl)?.resources?.firstOrNull { it.url == newUri }
        val mime = res?.protocolInfo?.split(':')?.getOrNull(2)
        if (res == null || mime == null) return fault(714, "Illegal MIME-type")
        if (mime !in acceptedMimes) return fault(714, "Illegal MIME-type")
        // A TV looks at the address before it takes it: HEAD, asking for the DLNA headers.
        val head = request("HEAD", newUri, mapOf("getcontentFeatures.dlna.org" to "1", "getCaptionInfo.sec" to "1", "transferMode.dlna.org" to "Streaming"))
        headStatus = head.first
        headHeaders = head.second
        if (head.first != 200) return fault(716, "Resource not found")
        uri = newUri
        didl = newDidl
        state = "STOPPED"
        positionMs = 0
        return ok("AVTransport", "SetAVTransportURI")
    }

    private fun play(): Triple<Int, String, ByteArray> {
        playFaultOnce?.let { playFaultOnce = null; return fault(it, "Resource not found") }
        if (uri.isEmpty()) return fault(701, "Transition not available")
        val get = request("GET", uri, mapOf("Range" to "bytes=0-99", "transferMode.dlna.org" to "Streaming"))
        getStatus = get.first
        getContentRange = get.second["content-range"]
        if (get.first != 200 && get.first != 206) return fault(716, "Resource not found")
        // Samsung style: the subtitle named in CaptionInfo.sec is fetched too.
        headHeaders["captioninfo.sec"]?.let { fetchedSubtitle = String(fetchBody(it), Charsets.UTF_8) }
        state = "PLAYING"
        return ok("AVTransport", "Play")
    }

    private fun request(method: String, url: String, headers: Map<String, String>): Pair<Int, Map<String, String>> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method
            c.connectTimeout = 2000
            c.readTimeout = 2000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val status = c.responseCode
            if (method == "GET") (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes() }
            status to c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
        } catch (e: Exception) {
            0 to emptyMap()
        } finally {
            c.disconnect()
        }
    }

    private fun fetchBody(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 2000
            c.readTimeout = 2000
            c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            ByteArray(0)
        } finally {
            c.disconnect()
        }
    }
}
