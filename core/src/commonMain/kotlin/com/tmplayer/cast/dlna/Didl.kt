package com.tmplayer.cast.dlna

import java.util.Locale

/**
 * The DLNA flags of one served file, written the way the fourth field of a `protocolInfo` and the
 * `contentFeatures.dlna.org` header carry them.
 *
 * @property profile `DLNA.ORG_PN`, only when it is known for certain: a wrong profile makes a
 *   strict TV refuse a file it would have played, a missing one makes none refuse.
 * @property rangeSeek the server answers byte ranges (`DLNA.ORG_OP=01`); false for a stream made
 *   as it is sent (`00`).
 * @property converted the bytes are not the file's own (remuxed, `DLNA.ORG_CI=1`).
 */
data class DlnaFeatures(
    val profile: String? = null,
    val rangeSeek: Boolean = true,
    val converted: Boolean = false,
) {
    /** `DLNA.ORG_PN=...;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=...` */
    fun field(): String = buildString {
        if (profile != null && PROFILE.matches(profile)) append("DLNA.ORG_PN=").append(profile).append(';')
        append("DLNA.ORG_OP=").append(if (rangeSeek) "01" else "00").append(';')
        append("DLNA.ORG_CI=").append(if (converted) "1" else "0").append(';')
        append("DLNA.ORG_FLAGS=").append(FLAGS)
    }

    companion object {
        /**
         * Streaming transfer mode, background transfer mode, connection stall and DLNA 1.5:
         * the value every renderer-facing server sends, followed by 24 zeros.
         */
        const val FLAGS = "01700000000000000000000000000000"

        private val PROFILE = Regex("[A-Za-z0-9_]{1,64}")
    }
}

/** One file as the renderer is told about it. */
data class DidlResource(val url: String, val protocolInfo: String)

/** The first item of a DIDL-Lite document. */
data class DidlItem(val title: String?, val resources: List<DidlResource>)

/**
 * DIDL-Lite, the metadata a renderer takes with SetAVTransportURI: a title, the class, and a
 * `res` per URL with its `protocolInfo`. Every value is escaped by [Xml.escape] (the document
 * goes through a second escape when it is placed in the SOAP body, which is what a renderer
 * undoes). The subtitle is offered three ways, since TVs differ: a `res` of `text/srt`, and the
 * Samsung `sec:CaptionInfoEx` element (the `CaptionInfo.sec` header is the CastServer's).
 */
object Didl {

    fun protocolInfo(mime: String, features: DlnaFeatures): String =
        "http-get:*:${cleanMime(mime)}:${features.field()}"

    fun item(
        title: String,
        mediaUrl: String,
        mime: String,
        features: DlnaFeatures,
        subtitleUrl: String? = null,
        durationMs: Long? = null,
        sizeBytes: Long? = null,
    ): String {
        val res = StringBuilder()
        res.append("<res protocolInfo=\"").append(Xml.escape(protocolInfo(mime, features))).append('"')
        if (sizeBytes != null && sizeBytes > 0) res.append(" size=\"").append(sizeBytes).append('"')
        if (durationMs != null && durationMs > 0) res.append(" duration=\"").append(duration(durationMs)).append('"')
        res.append('>').append(Xml.escape(mediaUrl)).append("</res>")
        if (subtitleUrl != null) {
            res.append("<res protocolInfo=\"http-get:*:text/srt:*\">").append(Xml.escape(subtitleUrl)).append("</res>")
            res.append("<sec:CaptionInfoEx sec:type=\"srt\">").append(Xml.escape(subtitleUrl)).append("</sec:CaptionInfoEx>")
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" " +
            "xmlns:sec=\"http://www.sec.co.kr/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${Xml.escape(title.take(MAX_TITLE))}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            res +
            "</item></DIDL-Lite>"
    }

    /** The first item of [didl], or null when it is not DIDL-Lite. */
    fun parse(didl: String?): DidlItem? {
        val doc = Xml.parse(didl, Xml.DIDL_MAX) ?: return null
        val item = doc.elementsNamed("item").firstOrNull() ?: return null
        val resources = item.childElements("res").take(MAX_RES).mapNotNull { res ->
            val url = res.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DidlResource(url, res.getAttribute("protocolInfo").orEmpty())
        }
        return DidlItem(item.childText("title"), resources)
    }

    /** `H:MM:SS.mmm`, the form `res@duration` takes. */
    fun duration(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
    }

    /** A MIME type with nothing in it that could end the field or the attribute. */
    private fun cleanMime(mime: String): String =
        mime.filter { it.isLetterOrDigit() || it in "/.+-_" }.ifEmpty { "video/mp4" }

    private const val MAX_TITLE = 200
    private const val MAX_RES = 16
}

/**
 * The DLNA headers a file or a rewrapped stream goes out with: a file takes byte ranges, a stream
 * made as it is sent does not and is marked converted. No profile name: a wrong `DLNA.ORG_PN` is
 * worse than none.
 */
fun dlnaFeaturesFor(resource: com.tmplayer.cast.CastResource): DlnaFeatures {
    val ranged = resource is com.tmplayer.cast.CastResource.Ranged
    return DlnaFeatures(profile = null, rangeSeek = ranged, converted = !ranged)
}

/** [dlnaFeaturesFor] as the server's per-resource headers. */
fun dlnaServing(resource: com.tmplayer.cast.CastResource): com.tmplayer.cast.DlnaServing =
    com.tmplayer.cast.DlnaServing(dlnaFeaturesFor(resource).field())
