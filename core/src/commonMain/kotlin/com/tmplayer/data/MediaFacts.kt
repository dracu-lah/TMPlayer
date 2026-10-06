package com.tmplayer.data

import java.util.Locale

/**
 * What the detail panel can say about a video without opening it.
 *
 * Telegram states a picture's size for a video sent as a video and nothing at all for one sent as
 * a file, and it never names the codecs or the tracks. Releases do, in their file names
 * ("Show.S01E02.1080p.WEB-DL.DDP5.1.x265.mkv"), so everything past the size is read from there.
 * Opening the container would be exact, but it costs a download per panel; the name is free and
 * right for the uploads people actually keep.
 *
 * Every field is null (or empty) when the name does not say, and the panel leaves that line out:
 * a guessed codec is worse than none.
 */
data class MediaFacts(
    /** "4K", "1080p", "720p", "SD": from the stated size when there is one, else from the name. */
    val resolution: String? = null,
    /** "1920 × 1080", only when Telegram stated it. */
    val pixels: String? = null,
    /** "HEVC", "H.264", "AV1", "VP9", "XviD". */
    val videoCodec: String? = null,
    /** "Dolby Vision", "HDR10+", "HDR10", "HDR". */
    val dynamicRange: String? = null,
    /** "E-AC3 5.1", "AAC 2.0", "TrueHD Atmos": codec, then the channel layout when named. */
    val audio: String? = null,
    /** What the name says about the tracks inside, beyond the first audio. */
    val tracks: List<Track> = emptyList(),
    /** "WEB-DL", "WEBRip", "Blu-ray", "HDTV", "DVD". */
    val source: String? = null,
    /** "MKV", "MP4": the extension, upper case. */
    val container: String? = null,
) {
    /** Kinds of track a release name announces. The UI words them in the viewer's language. */
    enum class Track { DualAudio, MultiAudio, Subtitles }

    companion object {
        fun of(item: MediaItem): MediaFacts = read(item.fileName, item.width, item.height)

        fun read(fileName: String, width: Int = 0, height: Int = 0): MediaFacts {
            val name = fileName.lowercase(Locale.ROOT)
            return MediaFacts(
                resolution = resolutionOf(width, height) ?: MediaMapper.qualityTags(fileName).firstOrNull(),
                pixels = if (width > 0 && height > 0) "$width × $height" else null,
                videoCodec = first(name, VIDEO_CODECS),
                dynamicRange = first(name, DYNAMIC_RANGE),
                audio = audioOf(name),
                tracks = TRACKS.filter { (_, pattern) -> pattern.containsMatchIn(name) }.map { it.first },
                source = first(name, SOURCES),
                container = containerOf(fileName),
            )
        }

        /**
         * The common name for a stated picture size. Either side may decide it: a scope film at
         * 3840 × 1600 is 4K although it is not 2160 tall, and a portrait clip is judged by its width.
         */
        internal fun resolutionOf(width: Int, height: Int): String? {
            if (width <= 0 || height <= 0) return null
            val long = maxOf(width, height)
            val short = minOf(width, height)
            return when {
                long >= 3800 || short >= 2100 -> "4K"
                long >= 1900 || short >= 1060 -> "1080p"
                long >= 1260 || short >= 700 -> "720p"
                else -> "SD"
            }
        }

        private fun audioOf(name: String): String? {
            val codec = first(name, AUDIO_CODECS)
            val atmos = ATMOS.containsMatchIn(name) && codec != null
            val channels = CHANNELS.find(name)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
            if (codec == null) return null
            return listOfNotNull(codec, "Atmos".takeIf { atmos }, channels).joinToString(" ")
        }

        private fun containerOf(fileName: String): String? {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return null
            val extension = fileName.substring(dot + 1)
            if (extension.length !in 2..4 || !extension.all { it.isLetterOrDigit() }) return null
            if (!MediaMapper.looksLikeVideo(fileName, "")) return null
            return extension.uppercase(Locale.ROOT)
        }

        private fun first(name: String, table: List<Pair<String, Regex>>): String? =
            table.firstOrNull { (_, pattern) -> pattern.containsMatchIn(name) }?.first

        /** A marker standing on its own: not inside a longer word or number. */
        private fun word(body: String) = Regex("""(?<![a-z0-9])(?:$body)(?![a-z0-9])""")

        /** First match wins, so the more specific spelling of each family comes first. */
        private val VIDEO_CODECS = listOf(
            "HEVC" to word("""x265|h\.?265|hevc"""),
            "H.264" to word("""x264|h\.?264|avc"""),
            "AV1" to word("av1"),
            "VP9" to word("vp9"),
            "XviD" to word("xvid"),
            "DivX" to word("divx"),
        )

        private val DYNAMIC_RANGE = listOf(
            "Dolby Vision" to word("""dv|dovi|dolby[ ._-]?vision"""),
            "HDR10+" to Regex("""(?<![a-z0-9])hdr10(?:\+|plus)"""),
            "HDR10" to word("hdr10"),
            "HDR" to word("hdr"),
        )

        /** A codec may run straight into its channels ("DDP5.1", "AAC2.0"), so only the left is bounded. */
        private fun audio(body: String) = Regex("""(?<![a-z0-9])(?:$body)(?![a-z])""")

        private val AUDIO_CODECS = listOf(
            "TrueHD" to audio("truehd"),
            "DTS-HD MA" to audio("""dts[ ._-]?hd[ ._-]?ma"""),
            "DTS" to audio("dts"),
            "E-AC3" to audio("""e[ ._-]?ac[ ._-]?3|ddp|dd\+"""),
            "AC3" to audio("""ac[ ._-]?3|dd(?=[ ._-]?[257]\.[01])"""),
            "FLAC" to audio("flac"),
            "Opus" to audio("opus"),
            "AAC" to audio("aac"),
            "MP3" to audio("mp3"),
        )

        private val ATMOS = word("atmos")

        /** "5.1", "7.1", "2.0", alone or straight after a codec, never a piece of "H.264". */
        private val CHANNELS = Regex("""(?<![0-9])(?<![0-9]\.)([1-8])\.([01])(?![0-9])""")

        private val SOURCES = listOf(
            "WEB-DL" to word("""web[ ._-]?dl"""),
            "WEBRip" to word("""web[ ._-]?rip"""),
            "Blu-ray" to word("""blu[ ._-]?ray|bd[ ._-]?rip|br[ ._-]?rip|bdremux|remux"""),
            "HDTV" to word("hdtv"),
            "DVD" to word("""dvd[ ._-]?rip|dvd"""),
        )

        private val TRACKS = listOf(
            Track.DualAudio to word("""dual[ ._-]?audio"""),
            Track.MultiAudio to word("""multi[ ._-]?audio|multi"""),
            Track.Subtitles to word("""e?subs?|m?subs|esub|msubs?|subbed|hardsubs?|softsubs?"""),
        )
    }
}
