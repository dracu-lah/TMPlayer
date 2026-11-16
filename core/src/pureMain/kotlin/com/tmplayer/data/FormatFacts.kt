package com.tmplayer.data

import kotlin.math.roundToInt

/**
 * What a file says about its picture and sound, as plain tags for a badge: "4K", "1080p", "HDR10",
 * "Dolby Vision", "HEVC", "60 fps", "Atmos", "5.1" (T8, format badges).
 *
 * Two sources and nothing else:
 *  - the picture's size, which Telegram states on the message, so a tile knows its resolution for
 *    free ([resolutionOf]);
 *  - a [Probe], what a player saw in the tracks of a file it opened ([tags]). Never read from
 *    TDLib per tile; see [FormatProbes].
 *
 * Honest by construction: a tag is only made from a field that states it (a MIME type, a codecs
 * string, a profile text, a channel count, a colour transfer). Atmos, HDR and Dolby Vision are
 * never taken from a file name. The one name based fact is a resolution word when the message has
 * no size, and its tag carries [Tag.fromName] so the screen can draw it differently.
 *
 * Pure and total: every input is outside data, so anything missing, zero, negative, absurd or
 * unreadable gives no tag and never throws. Our own plain text; no brand logo is involved (Dolby,
 * DTS, HDR10+ and "4K Ultra HD" are licensed marks).
 */
object FormatFacts {

    /** The picture's class by its size. [label] is the pill's text. */
    enum class Resolution(val label: String, val lines: Int) {
        Sd("SD", 480),
        Hd("720p", 720),
        FullHd("1080p", 1080),
        Qhd("1440p", 1440),
        Uhd4k("4K", 2160),
        Uhd8k("8K", 4320),
    }

    /** What a tag is about, so a screen can order, limit and style them. */
    enum class Kind { Resolution, Hdr, Codec, FrameRate, Audio, Channels }

    /** One badge: its [text], what it is about, and whether it was read from the file name. */
    data class Tag(val kind: Kind, val text: String, val fromName: Boolean = false)

    /** The colour transfer of the picture. */
    enum class Transfer { Sdr, Pq, Hlg, Unknown }

    /** One sound track as the player lists it. Every field may be missing. */
    data class AudioTrack(
        val mime: String? = null,
        val codecs: String? = null,
        /** The codec profile in words where the player gives one ("Dolby TrueHD + Dolby Atmos", "DTS-HD MA"). */
        val profile: String? = null,
        val channels: Int = 0,
    )

    /**
     * What a player knows of a file once it has the tracks. Every field may be missing; an empty
     * [Probe] gives no tags.
     */
    data class Probe(
        val videoMime: String? = null,
        val videoCodecs: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Float = 0f,
        val transfer: Transfer = Transfer.Unknown,
        /** Bits per colour channel of the picture, 0 when not stated. */
        val bitDepth: Int = 0,
        /** True only where the player read HDR10+ dynamic metadata. Most players cannot, so it is rarely set. */
        val hdr10Plus: Boolean = false,
        val audio: List<AudioTrack> = emptyList(),
    )

    /** Largest picture side believed (16K is 15360): a larger figure is a mistake. */
    const val MAX_SIDE = 20_000

    /** Most channels believed. */
    private const val MAX_CHANNELS = 32

    /** Longest outside string looked at: a codecs field can be anything. */
    private const val MAX_TEXT = 200

    /** Most sound tracks looked at: a file can list hundreds. */
    private const val MAX_TRACKS = 32

    /** A picture wider than this against its height is judged by its height alone (a 32:9 strip). */
    private const val WIDEST_CINEMA = 2.6f

    /** Frame rates at and above this are worth a tag, and the highest believed. */
    private const val HIGH_FPS = 50f
    private const val MAX_FPS = 300f

    // ---- resolution ------------------------------------------------------------------------

    private val LONG_FROM = intArrayOf(1200, 1800, 2400, 3600, 7000)
    private val SHORT_FROM = intArrayOf(700, 1060, 1400, 2100, 4200)
    private val CLASSES = arrayOf(Resolution.Hd, Resolution.FullHd, Resolution.Qhd, Resolution.Uhd4k, Resolution.Uhd8k)

    /**
     * The class of a picture [width] by [height], or null where either is not a believable size.
     * Tolerant of crops (1920 x 800 is 1080p) and of a size that is turned on its side (1080 x 1920
     * is 1080p): see the table in design/redesign/format-badges.md.
     */
    fun resolution(width: Int, height: Int): Resolution? {
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        val long = maxOf(width, height)
        val short = minOf(width, height)
        val short0 = classBy(short, SHORT_FROM)
        // A portrait picture is a phone clip or a turned one: the "p" is its width, whatever its height.
        if (height > width) return short0
        // A very wide strip is no wider a picture for it.
        if (long.toFloat() / short > WIDEST_CINEMA) return short0
        val long0 = classBy(long, LONG_FROM)
        return if (long0.lines >= short0.lines) long0 else short0
    }

    private fun classBy(side: Int, from: IntArray): Resolution {
        var found = Resolution.Sd
        for (i in from.indices) if (side >= from[i]) found = CLASSES[i]
        return found
    }

    /**
     * The tag a video's resolution gives: from its stated size, else from a resolution word in its
     * name ([MediaText.qualityTags]), with [Tag.fromName] set. Null when neither says.
     */
    fun resolutionOf(width: Int, height: Int, fileName: String?): Tag? {
        resolution(width, height)?.let { return Tag(Kind.Resolution, it.label) }
        val word = runCatching { MediaText.qualityTags(fileName?.take(MAX_TEXT * 2)).firstOrNull() }.getOrNull() ?: return null
        return Tag(Kind.Resolution, word, fromName = true)
    }

    /** [resolutionOf] for a video as the list has it. */
    fun resolutionOf(item: MediaItem): Tag? = resolutionOf(item.width, item.height, item.fileName.ifBlank { item.title })

    /**
     * The one pill a poster tile wears: the resolution at 720p and up, and only when known. Below
     * that (SD, or a name that says SD) a pill says nothing a viewer would act on.
     */
    fun tileTag(item: MediaItem): Tag? = resolutionOf(item)?.takeIf { it.isTileWorthy() }

    private fun Tag.isTileWorthy(): Boolean = text != Resolution.Sd.label

    // ---- the probe -------------------------------------------------------------------------

    /**
     * Every tag a [probe] supports, in the order a row shows them: picture (resolution when the
     * probe has a size, HDR kind, bit depth, codec, frame rate), then the best sound track
     * ([audioTags]). [size] is the message's own size, used when the probe has none.
     */
    fun tags(probe: Probe?, size: Tag? = null): List<Tag> {
        if (probe == null) return listOfNotNull(size)
        val resolution = resolution(probe.width, probe.height)?.let { Tag(Kind.Resolution, it.label) } ?: size
        return buildList {
            resolution?.let(::add)
            addAll(pictureTags(probe))
            bestAudio(probe.audio)?.let { addAll(audioTags(it)) }
        }
    }

    /** The row for a video: its size's resolution alone before any probe, everything the probe says after. */
    fun rowFor(item: MediaItem, probe: Probe?): List<Tag> = tags(probe, resolutionOf(item))

    /** The picture's tags beyond its resolution: HDR kind or 10-bit, codec, frame rate. */
    fun pictureTags(probe: Probe): List<Tag> = buildList {
        val vision = dolbyVisionProfile(probe.videoCodecs) != null || isDolbyVisionMime(probe.videoMime)
        val hdr = when {
            vision -> "Dolby Vision"
            probe.hdr10Plus -> "HDR10+"
            probe.transfer == Transfer.Pq -> "HDR10"
            probe.transfer == Transfer.Hlg -> "HLG"
            else -> null
        }
        if (hdr != null) add(Tag(Kind.Hdr, hdr)) else if (probe.bitDepth in 10..16) add(Tag(Kind.Hdr, "10-bit"))
        videoCodec(probe, vision)?.let { add(Tag(Kind.Codec, it)) }
        frameRateText(probe.frameRate)?.let { add(Tag(Kind.FrameRate, it)) }
    }

    /** "60 fps" for a rate of 50 and up, null below it or for a rate that is not believable. */
    fun frameRateText(rate: Float): String? {
        if (rate.isNaN() || rate < HIGH_FPS || rate > MAX_FPS) return null
        return "${rate.roundToInt()} fps"
    }

    /**
     * HEVC or AV1, the two worth saying (H.264 is what a viewer expects). A Dolby Vision track is
     * named by the picture inside it where its profile says (4, 7 and 8 are HEVC, 10 is AV1).
     */
    private fun videoCodec(probe: Probe, vision: Boolean): String? {
        val mime = text(probe.videoMime)?.lowercase()
        val codecs = text(probe.videoCodecs)?.lowercase()
        if (vision) {
            return when (dolbyVisionProfile(codecs)) {
                4, 5, 7, 8 -> "HEVC"
                10 -> "AV1"
                else -> null
            }
        }
        val id = "${mime.orEmpty()} ${codecs.orEmpty()}"
        return when {
            "hevc" in id || "h265" in id || "hvc1" in id || "hev1" in id -> "HEVC"
            "av01" in id || "av1" in id -> "AV1"
            else -> null
        }
    }

    private fun isDolbyVisionMime(mime: String?): Boolean = text(mime)?.lowercase()?.endsWith("dolby-vision") == true

    private val DOLBY_VISION_TAGS = setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    /**
     * The Dolby Vision profile a codecs string names ("dvhe.07.06", "dvh1.08.06", "dvav.09.05",
     * "dav1.10.09"), or null when it is not a Dolby Vision string. The player's format routing
     * ([com.tmplayer.player]'s FormatRoute) asks this too, so there is one reading of the string.
     */
    fun dolbyVisionProfile(codecs: String?): Int? {
        val parts = codecs?.trim()?.take(MAX_TEXT)?.lowercase()?.split('.') ?: return null
        if (parts.size < 2 || parts[0] !in DOLBY_VISION_TAGS) return null
        return parts[1].toIntOrNull()
    }

    // ---- sound -----------------------------------------------------------------------------

    /** What a sound track is, in order of how much a viewer would want to know it. */
    enum class Sound(val label: String?, internal val rank: Int) {
        Atmos("Atmos", 6),
        DtsX("DTS:X", 5),
        DtsHd("DTS-HD", 4),
        Dts("DTS", 3),
        Aac("AAC", 1),
        Other(null, 2),
    }

    /**
     * What [track] is. Atmos only where the track says so: Media3's E-AC-3 JOC type (`ec+3`
     * codecs), or a profile text that names it (mpv and FFmpeg say "Dolby TrueHD + Dolby Atmos").
     * A plain TrueHD or E-AC-3 track is never promoted on a guess.
     */
    fun sound(track: AudioTrack): Sound {
        val mime = text(track.mime)?.lowercase().orEmpty()
        val codecs = text(track.codecs)?.lowercase().orEmpty()
        val profile = text(track.profile)?.lowercase().orEmpty()
        val id = "$mime $codecs $profile"
        return when {
            "atmos" in id || "eac3-joc" in id || "ec+3" in codecs || "e-ac-3-joc" in id -> Sound.Atmos
            "dts:x" in id || "dtsx" in id || "dts uhd" in id || "vnd.dts.uhd" in id -> Sound.DtsX
            "dts-hd" in id || "dts hd" in id || "vnd.dts.hd" in id -> Sound.DtsHd
            "dts" in id || "dca" in id -> Sound.Dts
            "aac" in id || "mp4a" in id -> Sound.Aac
            else -> Sound.Other
        }
    }

    /**
     * The text of the stereo layout tag. The one tag that is a word of the viewer's language, so a
     * screen shows its own translation of it (the others, "5.1" and "Atmos", read the same everywhere).
     */
    const val STEREO = "Stereo"

    /** The layout a channel count is known by: "7.1", "5.1", [STEREO]; other counts say nothing. */
    fun channelLayout(channels: Int): String? = when (channels) {
        8 -> "7.1"
        6 -> "5.1"
        2 -> STEREO
        else -> null
    }

    /** The tags of one sound track: its kind (Atmos, DTS-HD, AAC and so on) then its layout. */
    fun audioTags(track: AudioTrack): List<Tag> = buildList {
        sound(track).label?.let { add(Tag(Kind.Audio, it)) }
        if (track.channels in 1..MAX_CHANNELS) channelLayout(track.channels)?.let { add(Tag(Kind.Channels, it)) }
    }

    /**
     * The sound track a row speaks for: Atmos and DTS ahead of the rest, then the most channels.
     * The first one wins a tie, which is the track the player plays unless told otherwise.
     */
    fun bestAudio(tracks: List<AudioTrack>): AudioTrack? =
        tracks.take(MAX_TRACKS).withIndex().maxWithOrNull(
            compareBy<IndexedValue<AudioTrack>>({ sound(it.value).rank }, { it.value.channels.coerceIn(0, MAX_CHANNELS) }, { -it.index }),
        )?.value

    private fun text(value: String?): String? = value?.takeIf { it.isNotBlank() }?.take(MAX_TEXT)
}
