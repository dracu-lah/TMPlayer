package com.tmplayer.desktop.player

import com.tmplayer.player.FormatLine

/**
 * The failed format on the desktop's error sheet (EG-F37): the same one plain line the phone says,
 * built from mpv's track list, and the block "Copy details" adds (the line and the decoders mpv
 * lists for the codecs). Reads nothing but properties, never throws, and gives empty text when no
 * track was known (an error before the file was opened far enough to list any).
 */
internal object MpvFormats {

    /** The properties it reads, so a test can stand in for mpv. */
    interface Props {
        fun string(name: String): String?
        fun int(name: String): Int?
        fun double(name: String): Double?
        fun bool(name: String): Boolean?
    }

    /** The line for the sheet and the block for the clipboard; both empty when nothing is known. */
    data class Failure(val line: String, val block: String) {
        companion object {
            val NONE = Failure("", "")
        }
    }

    /** Most tracks and decoders looked at: a file can list thousands of tracks, and mpv a few hundred decoders. */
    private const val MAX_TRACKS = 64
    private const val MAX_DECODERS = 1_000

    fun describe(props: Props, softwareNote: String = ""): Failure = runCatching { read(props, softwareNote) }.getOrDefault(Failure.NONE)

    private fun read(p: Props, softwareNote: String): Failure {
        val count = (p.int("track-list/count") ?: 0).coerceIn(0, MAX_TRACKS)
        var video: Int? = null
        var audio: Int? = null
        for (i in 0 until count) {
            val selected = p.bool("track-list/$i/selected") == true
            when (p.string("track-list/$i/type")) {
                // Cover art is a picture track in mpv's list, and not what this is about.
                "video" -> if (p.bool("track-list/$i/albumart") != true && (video == null || selected)) video = i
                "audio" -> if (audio == null || selected) audio = i
            }
        }
        val videoCodec = video?.let { p.string("track-list/$it/codec") }
        val audioCodec = audio?.let { p.string("track-list/$it/codec") }
        val hwdec = p.string("hwdec-current")
        val facts = FormatLine.Facts(
            videoMime = videoCodec?.let { "video/$it" },
            videoProfile = video?.let { p.string("track-list/$it/codec-profile") },
            width = video?.let { p.int("track-list/$it/demux-w") } ?: 0,
            height = video?.let { p.int("track-list/$it/demux-h") } ?: 0,
            frameRate = (video?.let { p.double("track-list/$it/demux-fps") } ?: 0.0).toFloat(),
            transfer = when (p.string("video-params/gamma")) {
                "pq" -> FormatLine.Transfer.Hdr10
                "hlg" -> FormatLine.Transfer.Hlg
                null -> FormatLine.Transfer.Unknown
                else -> FormatLine.Transfer.Sdr
            },
            audioMime = audioCodec?.let { "audio/$it" },
            channels = audio?.let { p.int("track-list/$it/demux-channel-count") } ?: 0,
            containerMime = containerOf(p.string("file-format")),
        )
        // Software decoding is only a note while a picture was being decoded: hwdec "no" says so.
        val extras = listOfNotNull(softwareNote.takeIf { videoCodec != null && softwareNote.isNotEmpty() && (hwdec == null || hwdec == "no") })
        val line = FormatLine.line(facts, extras)
        val decoders = buildList {
            videoCodec?.let { add(FormatLine.Decoders("video/$it", decodersFor(p, it))) }
            audioCodec?.let { add(FormatLine.Decoders("audio/$it", decodersFor(p, it))) }
            hwdec?.let { add(FormatLine.Decoders("hwdec-current", listOf(it))) }
        }
        return Failure(line, FormatLine.copyBlock(line, decoders))
    }

    /** mpv's `file-format` is a comma list of the demuxer's names ("mov,mp4,m4a,3gp,3g2,mj2", "matroska,webm"). */
    fun containerOf(format: String?): String? {
        val names = format?.lowercase()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(16) ?: return null
        return when {
            names.isEmpty() -> null
            "mp4" in names -> "video/mp4"
            "matroska" in names -> "video/x-matroska"
            "webm" in names -> "video/webm"
            "mpegts" in names -> "video/mp2t"
            "avi" in names -> "video/avi"
            else -> "video/" + names.first()
        }
    }

    /** The drivers mpv lists for [codec] ("hevc", "hevc_qsv"), in its order. */
    private fun decodersFor(p: Props, codec: String): List<String> {
        val count = (p.int("decoder-list/count") ?: 0).coerceIn(0, MAX_DECODERS)
        return (0 until count).mapNotNull { i ->
            if (p.string("decoder-list/$i/codec") == codec) p.string("decoder-list/$i/driver") else null
        }
    }
}
