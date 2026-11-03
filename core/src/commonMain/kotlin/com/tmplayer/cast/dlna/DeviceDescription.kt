package com.tmplayer.cast.dlna

import org.w3c.dom.Element
import java.net.URI
import java.util.Locale

/** One UPnP service of a renderer, with its control address made absolute. */
data class DlnaService(val type: String, val controlUrl: String)

/**
 * What a renderer's `description.xml` says that TMPlayer uses. Only the MediaRenderer device is
 * read (the root, or one nested in a combined server and renderer), and only its three services:
 * AVTransport (required, without it the device is no row), RenderingControl (volume) and
 * ConnectionManager (what it can play).
 */
data class DeviceDescription(
    /** `uuid:...`, lower case. */
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val iconUrl: String?,
    val avTransport: DlnaService,
    val renderingControl: DlnaService?,
    val connectionManager: DlnaService?,
) {
    companion object {
        /**
         * Parses [xml], fetched from [location]. Null when it is not a description of a renderer
         * with an AVTransport service. Every address in it is resolved against its URLBase (or the
         * LOCATION), and a control URL that leaves the LOCATION's host is dropped: a description
         * must not send TMPlayer's SOAP calls to another machine (SSRF).
         */
        fun parse(xml: String?, location: String): DeviceDescription? {
            val doc = Xml.parse(xml, Xml.DESCRIPTION_MAX) ?: return null
            val origin = runCatching { URI(location) }.getOrNull()?.takeIf { it.scheme.isHttp() && !it.host.isNullOrEmpty() } ?: return null
            val base = doc.elementsNamed("URLBase").firstOrNull()?.textContent?.trim()
                ?.let { runCatching { URI(it) }.getOrNull() }
                ?.takeIf { it.scheme.isHttp() && it.host.equals(origin.host, ignoreCase = true) }
                ?: origin
            val device = doc.elementsNamed("device").firstOrNull {
                it.childText("deviceType")?.startsWith(Ssdp.RENDERER_PREFIX, ignoreCase = true) == true
            } ?: return null
            val udn = device.childText("UDN")?.lowercase(Locale.ROOT)?.takeIf { it.startsWith("uuid:") && it.length <= 100 } ?: return null

            var av: DlnaService? = null
            var rc: DlnaService? = null
            var cm: DlnaService? = null
            for (service in device.child("serviceList")?.childElements("service").orEmpty()) {
                val type = service.childText("serviceType") ?: continue
                val control = service.childText("controlURL")?.let { resolve(base, origin, it) } ?: continue
                val s = DlnaService(type, control)
                when {
                    type.contains(":service:AVTransport:", ignoreCase = true) -> if (av == null) av = s
                    type.contains(":service:RenderingControl:", ignoreCase = true) -> if (rc == null) rc = s
                    type.contains(":service:ConnectionManager:", ignoreCase = true) -> if (cm == null) cm = s
                }
            }
            av ?: return null
            val name = device.childText("friendlyName")?.take(MAX_TEXT) ?: device.childText("modelName")?.take(MAX_TEXT) ?: "TV"
            return DeviceDescription(
                udn = udn,
                friendlyName = name,
                manufacturer = device.childText("manufacturer")?.take(MAX_TEXT),
                modelName = device.childText("modelName")?.take(MAX_TEXT),
                iconUrl = bestIcon(device, base, origin),
                avTransport = av,
                renderingControl = rc,
                connectionManager = cm,
            )
        }

        private const val MAX_TEXT = 120
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        private fun String?.isHttp(): Boolean = this != null && (equals("http", true) || equals("https", true))

        /** The largest PNG or JPEG of the icon list, up to 512 px, on the same host. */
        private fun bestIcon(device: Element, base: URI, origin: URI): String? {
            val icons = device.child("iconList")?.childElements("icon").orEmpty()
            return icons.mapNotNull { icon ->
                val url = icon.childText("url")?.let { resolve(base, origin, it) } ?: return@mapNotNull null
                val mime = icon.childText("mimetype")?.lowercase(Locale.ROOT).orEmpty()
                if (mime != "image/png" && mime != "image/jpeg") return@mapNotNull null
                val w = icon.childText("width")?.toIntOrNull() ?: 0
                url to w
            }.filter { it.second <= 512 }.maxByOrNull { it.second }?.first
        }

        /**
         * [ref] made absolute against [base], or null when it is not an http address on
         * [origin]'s host. Done by hand: the references real TVs send include a bare
         * `_urn:schemas-upnp-org:service:AVTransport_control`, which a URI parser reads as a scheme.
         */
        fun resolve(base: URI, origin: URI, ref: String): String? {
            val r = ref.trim()
            if (r.isEmpty() || r.length > 512 || r.any { it.code < 0x21 || it.code == 0x7F }) return null
            // Another scheme (file:, javascript:, ftp:) is not a path on the renderer, whatever a parser would make of it.
            if (SCHEME.containsMatchIn(r) && !r.startsWith("http://", true) && !r.startsWith("https://", true)) return null
            val absolute = when {
                r.startsWith("http://", true) || r.startsWith("https://", true) -> r
                r.startsWith("//") -> "${base.scheme}:$r"
                r.startsWith("/") -> "${base.scheme}://${authority(base)}$r"
                else -> {
                    val path = base.rawPath.orEmpty()
                    val dir = path.substring(0, path.lastIndexOf('/') + 1).ifEmpty { "/" }
                    "${base.scheme}://${authority(base)}$dir$r"
                }
            }
            val parsed = runCatching { URI(absolute) }.getOrNull() ?: return null
            if (!parsed.scheme.isHttp() || !parsed.host.equals(origin.host, ignoreCase = true)) return null
            return absolute
        }

        private fun authority(u: URI): String = if (u.port > 0) "${u.host.let { if (':' in it) "[$it]" else it }}:${u.port}" else u.host
    }
}
