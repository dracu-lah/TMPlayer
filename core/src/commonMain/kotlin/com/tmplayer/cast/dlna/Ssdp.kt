package com.tmplayer.cast.dlna

import java.util.Locale

/** What one SSDP datagram said about a device. */
data class SsdpMessage(
    val kind: Kind,
    /** `uuid:...::urn:...` as sent. */
    val usn: String?,
    /** ST of an answer, NT of a NOTIFY. */
    val target: String?,
    val location: String?,
    val maxAgeSeconds: Int?,
    val server: String?,
) {
    enum class Kind { SearchAnswer, Alive, ByeBye }

    /** The device's `uuid:...`, lower case, the key of one row in the picker. */
    val udn: String? get() = Ssdp.udnOf(usn)

    /** A renderer's own device type, not one of its services or its root device. */
    val isRendererType: Boolean get() = target?.startsWith(Ssdp.RENDERER_PREFIX, ignoreCase = true) == true
}

/**
 * Simple Service Discovery Protocol, the small part a control point needs: the M-SEARCH it sends
 * and the answers and NOTIFYs it reads. The parser takes whatever arrives on UDP 1900 (every
 * smart device on the network talks there) and answers null for anything that is not a plain
 * answer or alive/byebye NOTIFY. Header names are case blind (the captured renderers differ:
 * `LOCATION` and `Location`), a line is capped, and a datagram cannot hold more than a few KB.
 */
object Ssdp {

    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val RENDERER_PREFIX = "urn:schemas-upnp-org:device:MediaRenderer:"

    private const val MAX_DATAGRAM = 8 * 1024
    private const val MAX_HEADERS = 64

    /** The M-SEARCH for [target]; [mx] is the seconds a device may wait before answering. */
    fun search(target: String = MEDIA_RENDERER, mx: Int = 2): ByteArray =
        ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: ${mx.coerceIn(1, 5)}\r\n" +
            "ST: ${target.filter { it.code in 0x20..0x7E }}\r\n" +
            "USER-AGENT: TMPlayer\r\n\r\n").toByteArray(Charsets.US_ASCII)

    fun parse(bytes: ByteArray, length: Int = bytes.size): SsdpMessage? {
        if (length <= 0 || length > MAX_DATAGRAM) return null
        val text = String(bytes, 0, minOf(length, bytes.size), Charsets.UTF_8)
        val lines = text.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim()?.uppercase(Locale.ROOT) ?: return null
        val isAnswer = first.startsWith("HTTP/1.") && first.contains(" 200")
        val isNotify = first.startsWith("NOTIFY ")
        if (!isAnswer && !isNotify) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1).take(MAX_HEADERS)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers.putIfAbsent(line.substring(0, colon).trim().lowercase(Locale.ROOT), line.substring(colon + 1).trim())
        }
        val kind = if (isAnswer) {
            SsdpMessage.Kind.SearchAnswer
        } else {
            when (headers["nts"]?.lowercase(Locale.ROOT)) {
                "ssdp:alive", "ssdp:update" -> SsdpMessage.Kind.Alive
                "ssdp:byebye" -> SsdpMessage.Kind.ByeBye
                else -> return null
            }
        }
        val target = if (isAnswer) headers["st"] else headers["nt"]
        val usn = headers["usn"]
        // An answer or an alive with nowhere to fetch the description from is no use; a byebye needs none.
        val location = headers["location"] ?: headers["al"]
        if (kind != SsdpMessage.Kind.ByeBye && location.isNullOrBlank()) return null
        if (usn.isNullOrBlank() && target.isNullOrBlank()) return null
        return SsdpMessage(kind, usn, target, location, maxAge(headers["cache-control"]), headers["server"])
    }

    /** `max-age=1800` (spaces allowed round the `=`) out of a Cache-Control value. */
    fun maxAge(cacheControl: String?): Int? {
        val m = Regex("max-age\\s*=\\s*(\\d{1,9})", RegexOption.IGNORE_CASE).find(cacheControl ?: return null) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** `uuid:abc` out of `uuid:abc::urn:schemas-...` (or the bare `uuid:abc`), lower case. */
    fun udnOf(usn: String?): String? {
        val head = usn?.substringBefore("::")?.trim()?.lowercase(Locale.ROOT) ?: return null
        return head.takeIf { it.startsWith("uuid:") && it.length > 5 && it.length <= 100 }
    }
}
