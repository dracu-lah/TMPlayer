package com.tmplayer.player

import kotlin.math.roundToInt

/**
 * The one plain line the error sheet says about a file that would not play (EG-F37):
 * "HEVC Main 10 L5.0, 3840 x 2160, 23.976 fps, HDR10, E-AC-3 5.1, Matroska".
 *
 * Built from what the player knew of the failed format: the sample MIME type and the codecs string
 * of the picture and of the sound (Media3's `Format` on a phone and a TV, mpv's track list on the
 * desktop), the size, the frame rate and the colour transfer. Pure and total: every input is
 * outside data (a container can say anything), so a part that is missing, empty, absurdly long or
 * not what it should be is left out of the line, never thrown on, and a line nothing could be said
 * for is empty (an error before any format is known shows no line).
 *
 * It carries no title, chat name or file name: the line goes on the clipboard and into reports.
 */
object FormatLine {

    /** The colour transfer of the picture, as far as the sheet names it. */
    enum class Transfer { Sdr, Hdr10, Hlg, Unknown }

    /** What the player knows of the failed format. Every field may be missing. */
    data class Facts(
        val videoMime: String? = null,
        val videoCodecs: String? = null,
        /** The picture's profile in words, where the player gives one instead of a codecs string (mpv's `codec-profile`). */
        val videoProfile: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Float = 0f,
        val transfer: Transfer = Transfer.Unknown,
        val audioMime: String? = null,
        val audioCodecs: String? = null,
        val channels: Int = 0,
        val containerMime: String? = null,
    )

    /** A device decoder list for one MIME type, for the clipboard. */
    data class Decoders(val mime: String, val names: List<String>)

    /** Longest piece of outside text kept in the line; a longer one is cut. */
    const val MAX_PART = 40

    /** Most decoder names kept for one MIME type, and the longest name. */
    private const val MAX_NAMES = 12
    private const val MAX_NAME = 64

    /** Largest picture side believed (16K is 15360): a larger figure is a mistake. */
    private const val MAX_SIDE = 20_000

    /** Most channels believed. */
    private const val MAX_CHANNELS = 32

    /**
     * The line for [facts], with [extras] (already in the viewer's language: "read by FFmpeg",
     * "software decoder") after the technical parts. Empty when nothing is known.
     */
    fun line(facts: Facts, extras: List<String> = emptyList()): String {
        val parts = ArrayList<String>(8)
        val video = videoName(facts.videoMime, facts.videoCodecs, facts.videoProfile)
        if (video != null) parts += video
        val w = facts.width
        val h = facts.height
        // A size only goes with a picture, and only when both sides are believable.
        if (video != null && w in 1..MAX_SIDE && h in 1..MAX_SIDE) parts += "$w x $h"
        if (video != null) {
            frameRateText(facts.frameRate)?.let { parts += "$it fps" }
            when (facts.transfer) {
                Transfer.Hdr10 -> parts += "HDR10"
                Transfer.Hlg -> parts += "HLG"
                else -> Unit
            }
        }
        val audio = audioName(facts.audioMime, facts.audioCodecs)
        if (audio != null) {
            val layout = channelLayout(facts.channels)
            parts += if (layout != null) "$audio $layout" else audio
        }
        containerName(facts.containerMime)?.let { parts += it }
        if (parts.isEmpty()) return ""
        for (extra in extras) clean(extra)?.let { parts += it }
        return parts.joinToString(", ")
    }

    /**
     * The block "Copy details" adds: the technical [line] and the device's decoders for the
     * formats in it, one row each. Empty parts leave their row out.
     */
    fun copyBlock(line: String, decoders: List<Decoders>): String = buildString {
        clean(line, MAX_PART * 8)?.let { appendLine("Format: $it") }
        for (entry in decoders) {
            val mime = clean(entry.mime) ?: continue
            val names = entry.names.mapNotNull { clean(it, MAX_NAME) }.distinct().take(MAX_NAMES)
            appendLine("Decoders $mime: ${if (names.isEmpty()) "none" else names.joinToString(", ")}")
        }
    }.trimEnd()

    // ---- picture ---------------------------------------------------------------------------

    /** "HEVC Main 10 L5.0", "H.264 High L4.0", "Dolby Vision profile 5", or null when nothing is known. */
    fun videoName(mime: String?, codecs: String?, profile: String? = null): String? {
        val type = clean(mime, 80)?.lowercase()
        val strings = clean(codecs, 80)?.lowercase()
        val family = videoFamily(type) ?: strings?.let(::familyOfCodecs)
        val name = family?.label ?: return type?.let(::rawName)
        val detail = when (family) {
            Family.Hevc -> strings?.let(::hevcDetail)
            Family.Avc -> strings?.let(::avcDetail)
            Family.Av1 -> strings?.let(::av1Detail)
            Family.Vp9 -> strings?.let(::vp9Detail)
            Family.DolbyVision -> strings?.let(::dolbyVisionDetail)
            else -> null
        }
        // A profile in words (mpv's "Main 10") stands in where the codecs string said nothing.
        val shown = detail?.takeIf { it.isNotEmpty() } ?: clean(profile, 24)
        return if (shown.isNullOrEmpty()) name else "$name $shown"
    }

    private enum class Family(val label: String) {
        Hevc("HEVC"), Avc("H.264"), Av1("AV1"), Vp9("VP9"), Vp8("VP8"), DolbyVision("Dolby Vision"),
        Mpeg2("MPEG-2"), Mpeg4("MPEG-4 Part 2"), Vc1("VC-1"), H263("H.263"), ProRes("ProRes"), Mjpeg("MJPEG"), Theora("Theora"),
        Mpeg1("MPEG-1"), Wmv("WMV"), Rv("RealVideo"), Dnxhd("DNxHD"),
    }

    private fun videoFamily(mime: String?): Family? {
        mime ?: return null
        val core = mime.removePrefix("video/").removePrefix("x-ffmpeg-").removePrefix("x-")
        return when {
            core == "hevc" || core == "h265" -> Family.Hevc
            core == "avc" || core == "h264" -> Family.Avc
            core == "av01" || core == "av1" -> Family.Av1
            core.startsWith("vp9") || core == "x-vnd.on2.vp9" || core == "vnd.on2.vp9" -> Family.Vp9
            core.startsWith("vp8") || core == "x-vnd.on2.vp8" || core == "vnd.on2.vp8" -> Family.Vp8
            core == "dolby-vision" -> Family.DolbyVision
            core == "mpeg2" || core == "mpeg2video" -> Family.Mpeg2
            core == "mp4v-es" || core == "mp4v" || core == "mpeg4" || core == "mpeg4-generic" -> Family.Mpeg4
            core == "wvc1" || core == "vc1" || core == "vc-1" -> Family.Vc1
            core == "3gpp" || core == "h263" -> Family.H263
            core.startsWith("prores") -> Family.ProRes
            core == "mjpeg" -> Family.Mjpeg
            core == "theora" -> Family.Theora
            core == "mpeg" || core == "mpeg1video" || core == "mpeg1" -> Family.Mpeg1
            core.startsWith("wmv") -> Family.Wmv
            core.startsWith("rv") -> Family.Rv
            core.startsWith("dnxhd") -> Family.Dnxhd
            else -> null
        }
    }

    private fun familyOfCodecs(codecs: String): Family? {
        val head = codecs.substringBefore(',').substringBefore('.')
        return when (head) {
            "hvc1", "hev1" -> Family.Hevc
            "avc1", "avc3" -> Family.Avc
            "av01" -> Family.Av1
            "vp09" -> Family.Vp9
            "vp08" -> Family.Vp8
            "dvhe", "dvh1", "dvav", "dva1", "dav1" -> Family.DolbyVision
            "mp4v" -> Family.Mpeg4
            else -> null
        }
    }

    /** `hvc1.2.4.L150.B0`: the profile (Main, Main 10) and the level (L150 is 5.0). */
    private fun hevcDetail(codecs: String): String? {
        val fields = codecs.substringBefore(',').split('.')
        if (fields.size < 2 || fields[0] != "hvc1" && fields[0] != "hev1") return null
        val profile = fields[1].trimStart('a', 'b', 'c').toIntOrNull()
        val profileName = when (profile) {
            1 -> "Main"
            2 -> "Main 10"
            3 -> "Main Still Picture"
            4 -> "Range Extensions"
            5 -> "High Throughput"
            9 -> "Screen Content"
            else -> null
        }
        val level = fields.getOrNull(3)?.takeIf { it.startsWith("l") }?.drop(1)?.toIntOrNull()
        return listOfNotNull(profileName, level?.let { levelText(it, 30) }).joinToString(" ").ifEmpty { null }
    }

    /** `avc1.640028`: profile_idc 0x64 is High, level_idc 0x28 is 4.0. */
    private fun avcDetail(codecs: String): String? {
        val tag = codecs.substringBefore(',').substringAfter('.', "")
        if (tag.length != 6 || !tag.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val profile = tag.substring(0, 2).toIntOrNull(16)
        val level = tag.substring(4, 6).toIntOrNull(16)
        val profileName = when (profile) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4"
            44 -> "CAVLC 4:4:4"
            else -> null
        }
        return listOfNotNull(profileName, level?.let { levelText(it, 10) }).joinToString(" ").ifEmpty { null }
    }

    /** `av01.0.08M.10`: profile 0 is Main, level index 8 is 4.0, 10 bits. */
    private fun av1Detail(codecs: String): String? {
        val fields = codecs.substringBefore(',').split('.')
        if (fields.size < 4 || fields[0] != "av01") return null
        val profileName = when (fields[1].toIntOrNull()) {
            0 -> "Main"
            1 -> "High"
            2 -> "Professional"
            else -> null
        }
        val index = fields[2].takeWhile { it.isDigit() }.takeIf { it.length in 1..2 }?.toIntOrNull()
        val level = index?.takeIf { it in 0..31 }?.let { "L${2 + it / 4}.${it % 4}" }
        val bits = fields[3].toIntOrNull()?.takeIf { it in 8..16 }?.let { "$it-bit" }
        return listOfNotNull(profileName, level, bits).joinToString(" ").ifEmpty { null }
    }

    /** `vp09.02.10.10`: profile 2, level 1.0. */
    private fun vp9Detail(codecs: String): String? {
        val fields = codecs.substringBefore(',').split('.')
        if (fields.size < 2 || fields[0] != "vp09") return null
        val profile = fields[1].toIntOrNull()?.takeIf { it in 0..3 }?.let { "Profile $it" }
        val level = fields.getOrNull(2)?.toIntOrNull()?.takeIf { it in 10..62 }?.let { levelText(it, 10) }
        return listOfNotNull(profile, level).joinToString(" ").ifEmpty { null }
    }

    /** `dvhe.05.06`: profile 5. */
    private fun dolbyVisionDetail(codecs: String): String? {
        val fields = codecs.substringBefore(',').split('.')
        if (fields.size < 2 || familyOfCodecs(codecs) != Family.DolbyVision) return null
        val profile = fields[1].toIntOrNull()?.takeIf { it in 0..99 } ?: return null
        return "profile $profile"
    }

    /** 150 over 30 is "L5.0"; 40 over 10 is "L4.0"; a level that is zero or absurd is left out. */
    private fun levelText(level: Int, per: Int): String? {
        if (level <= 0 || level > 255) return null
        return "L${level / per}.${(level % per) * 10 / per}"
    }

    private fun frameRateText(fps: Float): String? {
        if (!fps.isFinite() || fps < 5f || fps > 240f) return null
        val milli = (fps * 1000f).roundToInt()
        val whole = milli / 1000
        val fraction = (milli % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
    }

    // ---- sound -----------------------------------------------------------------------------

    /** "E-AC-3", "AAC LC", "DTS-HD", or null when nothing is known. */
    fun audioName(mime: String?, codecs: String?): String? {
        val type = clean(mime, 80)?.lowercase() ?: return clean(codecs, 80)?.lowercase()?.let(::audioOfCodecs)
        val core = type.removePrefix("audio/").removePrefix("x-ffmpeg-").removePrefix("x-")
        val named = when {
            core == "mp4a-latm" || core == "aac" || core == "mp4a" -> aacName(clean(codecs, 80)?.lowercase())
            core == "ac3" || core == "ac-3" -> "AC-3"
            core == "eac3" || core == "e-ac-3" -> "E-AC-3"
            core == "eac3-joc" -> "E-AC-3 JOC (Atmos)"
            core == "ac4" -> "AC-4"
            core == "true-hd" || core == "truehd" -> "TrueHD"
            core == "vnd.dts" || core == "dts" || core == "dca" -> "DTS"
            core.startsWith("vnd.dts.hd") || core == "dts-hd" -> if (type.contains("lbr")) "DTS Express" else "DTS-HD"
            core.startsWith("vnd.dts.uhd") -> "DTS:X"
            core == "opus" -> "Opus"
            core == "vorbis" -> "Vorbis"
            core == "flac" -> "FLAC"
            core == "mpeg" || core == "mp3" -> "MP3"
            core == "mpeg-l2" || core == "mp2" -> "MP2"
            core == "mpeg-l1" || core == "mp1" -> "MP1"
            core == "raw" || core.startsWith("pcm") -> "PCM"
            core == "alac" -> "ALAC"
            core == "amr-nb" || core == "3gpp" -> "AMR"
            core == "amr-wb" || core == "amr-wb+" -> "AMR-WB"
            core.startsWith("wma") -> "WMA"
            core == "g711-alaw" || core == "g711-mlaw" -> "G.711"
            else -> null
        }
        return named ?: rawName(type)
    }

    private fun aacName(codecs: String?): String {
        val object_ = codecs?.substringBefore(',')?.substringAfter("mp4a.40.", "")?.toIntOrNull()
        return when (object_) {
            2 -> "AAC LC"
            5 -> "HE-AAC"
            29 -> "HE-AAC v2"
            23 -> "AAC LD"
            39 -> "AAC ELD"
            42 -> "xHE-AAC"
            else -> "AAC"
        }
    }

    private fun audioOfCodecs(codecs: String): String? = when (codecs.substringBefore(',').substringBefore('.')) {
        "mp4a" -> aacName(codecs)
        "ac-3" -> "AC-3"
        "ec-3" -> "E-AC-3"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "flac" -> "FLAC"
        else -> null
    }

    /** Channels as people say them: 6 is 5.1, 8 is 7.1; 0 or an absurd count is unknown. */
    fun channelLayout(channels: Int): String? = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        3 -> "3.0"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        in 9..MAX_CHANNELS -> "$channels ch"
        else -> null
    }

    // ---- container -------------------------------------------------------------------------

    /** "Matroska", "MP4", "WebM"; a MIME type nothing here knows by the part after its slash. */
    fun containerName(mime: String?): String? {
        val type = clean(mime, 80)?.lowercase() ?: return null
        val core = type.substringAfter('/').removePrefix("x-")
        return when {
            core == "matroska" || core == "mkv" -> "Matroska"
            core == "webm" -> "WebM"
            core == "mp4" || core == "m4v" -> "MP4"
            core == "quicktime" || core == "mov" -> "QuickTime"
            core == "avi" || core == "msvideo" -> "AVI"
            core == "mp2t" || core == "mpegts" -> "MPEG-TS"
            core == "ms-wmv" || core == "ms-asf" || core == "asf" -> "ASF"
            core == "flv" -> "FLV"
            core == "3gpp" -> "3GP"
            core == "ogg" -> "Ogg"
            core.startsWith("ffmpeg-container-") -> core.removePrefix("ffmpeg-container-").uppercase().let { clean(it) }
            else -> rawName(type)
        }
    }

    // ---- shared ----------------------------------------------------------------------------

    /** An unknown MIME type by what follows its slash, as a short name; null when nothing readable is left. */
    private fun rawName(type: String): String? {
        val core = type.substringAfter('/').removePrefix("x-ffmpeg-").removePrefix("x-")
        return clean(core)
    }

    /**
     * Outside text made safe for one line: control characters and runs of space dropped, trimmed,
     * cut at [limit] with "...". Null when nothing is left.
     */
    fun clean(text: String?, limit: Int = MAX_PART): String? {
        text ?: return null
        // A 2 KB codec string is cut before it is looked at, so the work stays small.
        val head = if (text.length > limit * 4) text.substring(0, limit * 4) else text
        val sb = StringBuilder(head.length)
        var space = false
        for (c in head) {
            val blank = c.isWhitespace() || c.code < 32 || c.code == 127
            if (blank) {
                space = sb.isNotEmpty()
            } else {
                if (space) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        if (sb.isEmpty()) return null
        return if (sb.length > limit) sb.substring(0, limit).trimEnd() + "..." else sb.toString()
    }
}
