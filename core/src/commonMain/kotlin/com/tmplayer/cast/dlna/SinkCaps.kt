package com.tmplayer.cast.dlna

import com.tmplayer.cast.CastPlan
import com.tmplayer.cast.CastRefusal
import com.tmplayer.cast.CastRoute
import com.tmplayer.cast.Container
import com.tmplayer.cast.ReceiverCaps
import com.tmplayer.cast.Support
import java.util.Locale

/**
 * What a renderer's ConnectionManager `Sink` list says it plays, in the terms the rest of the cast
 * code plans with.
 *
 * The list is a declaration, not a test, and the captured renderers show how loose that is: Rygel
 * lists one AVC baseline CIF profile and a few MPEG-TS ones and plays whatever GStreamer opens,
 * gmrender-resurrect lists 330 formats of which half are not video. So a codec the list does not
 * name is [Support.Maybe] (tried, with a fallback) rather than [Support.No], unless the list is
 * empty or missing, when the safe set is assumed: MP4, H.264, AAC, 1080p, and nothing else.
 */
data class SinkCaps(
    val caps: ReceiverCaps,
    /** Containers the list names. For an empty list, MP4 alone. */
    val containers: Set<Container>,
    /** The renderer named at least one `video/` format (or said nothing, and may be a TV). */
    val takesVideo: Boolean,
    /** The renderer gave a list at all. */
    val declared: Boolean,
) {
    companion object {
        /** An empty or missing list: MP4, H.264, AAC and 1080p, nothing more assumed. */
        val SAFE = SinkCaps(
            caps = ReceiverCaps(
                hevc = Support.No, vp9 = Support.No, av1 = Support.No, tenBit = Support.No,
                matroska = Support.No, dolbyPassthrough = Support.No, uhd = Support.No,
            ),
            containers = setOf(Container.Mp4),
            takesVideo = true,
            declared = false,
        )

        private const val MAX_ENTRIES = 4096

        private val MP4 = setOf("video/mp4", "video/x-m4v", "video/quicktime", "video/x-mp4")
        private val MKV = setOf("video/x-matroska", "video/x-mkv", "video/mkv", "video/matroska")
        private val TS = setOf("video/mpeg", "video/mp2t", "video/vnd.dlna.mpeg-tts", "video/mpegts", "video/x-mpegts")
        private val HEVC = setOf("video/hevc", "video/h265", "video/x-h265")
        private val VP9 = setOf("video/x-vp9", "video/vp9")
        private val AV1 = setOf("video/x-av1", "video/av1")
        private val DOLBY = setOf("audio/x-ac3", "audio/ac3", "audio/eac3", "audio/x-eac3", "audio/vnd.dolby.dd-raw")

        /** The Sink list `GetProtocolInfo` returned, or null when the call failed. */
        fun parse(sink: String?): SinkCaps {
            if (sink.isNullOrBlank()) return SAFE
            val entries = sink.split(',').take(MAX_ENTRIES).mapNotNull { entry ->
                val parts = entry.trim().split(':', limit = 4)
                if (parts.size < 3) return@mapNotNull null
                if (!parts[0].equals("http-get", true) && parts[0] != "*") return@mapNotNull null
                val mime = parts[2].substringBefore(';').trim().lowercase(Locale.ROOT)
                val profile = parts.getOrNull(3)?.let { Regex("DLNA\\.ORG_PN=([A-Za-z0-9_]+)").find(it)?.groupValues?.get(1) }
                if (mime.isEmpty() && profile == null) null else mime to profile.orEmpty()
            }
            if (entries.isEmpty()) return SAFE
            val mimes = entries.map { it.first }.toSet()
            val profiles = entries.map { it.second }.filter { it.isNotEmpty() }
            fun named(prefix: String) = profiles.any { it.startsWith(prefix, ignoreCase = true) }

            val containers = buildSet {
                if (mimes.any { it in MP4 } || named("AVC_MP4") || named("HEVC_MP4") || named("MPEG4_P2_MP4")) add(Container.Mp4)
                if (mimes.any { it in MKV }) add(Container.Matroska)
                if ("video/webm" in mimes) add(Container.WebM)
                if (mimes.any { it in TS } || named("MPEG_TS") || named("AVC_TS") || named("HEVC_TS")) add(Container.MpegTs)
                if (mimes.any { it == "video/x-msvideo" || it == "video/avi" }) add(Container.Avi)
            }
            val anyVideo = mimes.any { it.startsWith("video/") } || profiles.any { it.contains("MP4") || it.contains("_TS") }

            fun by(listed: Boolean): Support = if (listed) Support.Yes else Support.Maybe
            val hevc = by(mimes.any { it in HEVC } || named("HEVC"))
            val caps = ReceiverCaps(
                hevc = hevc,
                vp9 = by(mimes.any { it in VP9 }),
                av1 = by(mimes.any { it in AV1 }),
                tenBit = if (hevc == Support.Yes) Support.Yes else Support.Maybe,
                matroska = by(mimes.any { it in MKV }),
                dolbyPassthrough = by(mimes.any { it in DOLBY } || named("AC3") || named("EAC3")),
                uhd = if (hevc == Support.Yes || profiles.any { it.contains("UHD", true) }) Support.Yes else Support.Maybe,
            )
            return SinkCaps(caps, containers.ifEmpty { setOf(Container.Mp4) }, anyVideo, declared = true)
        }
    }
}

/**
 * [this] as it applies to a DLNA renderer: HLS is dropped, since few TVs take a playlist over
 * DLNA, and what is left is tried in order (a progressive rewrap goes as a Maybe and falls back
 * to the next route on a refusal). Nothing left means the container has no route here.
 */
fun CastPlan.forDlna(): CastPlan {
    if (routes.none { it is CastRoute.Hls }) return this
    val kept = routes.filterNot { it is CastRoute.Hls }
    return if (kept.isEmpty()) copy(routes = kept, refusal = refusal ?: CastRefusal.Container) else copy(routes = kept)
}
