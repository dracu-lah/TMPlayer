package com.tmplayer.data

import java.util.Calendar
import java.util.Locale

/** A film title and year recovered from a release file name. */
data class ParsedName(
    val title: String,
    val year: Int?,
    /** Set when the name looks like a TV episode rather than a film. */
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = season != null || episode != null

    /** What to send to a search box: the year disambiguates remakes, so keep it attached. */
    val query: String get() = if (year != null) "$title $year" else title
}

/**
 * Turns "Uyir (2026) Malayalam (DS4K 1080p HS WEB-Rip E-AC3 5.1 Atmos).mkv" into "Uyir", 2026.
 *
 * Scene releases follow a loose but real convention: the title comes first, then the year, then
 * an unbounded pile of technical markers. Nobody has written this down as a grammar, so the
 * practical approach, the one guessit and parse-torrent-title both take, is to find where the
 * technical markers begin and treat everything before them as the title.
 *
 * There is no Kotlin port of either library, so this is that approach reimplemented against the
 * cases this app actually sees. It is deliberately conservative: a wrong title produces a wrong
 * film on the details panel, which is worse than producing nothing, so anything ambiguous
 * returns a bare title with no year and lets the search engine sort it out.
 */
object FilmName {

    fun parse(fileName: String, maxYear: Int = thisYear() + 1): ParsedName {
        val stripped = stripExtension(fileName)
        // Separators are interchangeable in practice: the same release shows up dot-separated on
        // one tracker and space-separated on another.
        val normalised = stripped.replace(SEPARATORS, " ").replace(WHITESPACE, " ").trim()
        if (normalised.isEmpty()) return ParsedName("", null)

        val episode = EPISODE.find(normalised)
        val year = findYear(normalised, maxYear)

        // The title ends at whichever marker comes first: the year, the episode code, or the
        // start of the technical block.
        val cut = listOfNotNull(
            year?.at,
            episode?.range?.first,
            firstTagIndex(normalised),
        ).minOrNull() ?: normalised.length

        val title = clean(normalised.substring(0, cut))

        return ParsedName(
            // Falling back to the whole name matters for a file that is nothing but a title.
            title = title.ifEmpty { clean(normalised) },
            year = year?.value,
            season = episode?.groupValues?.getOrNull(1)?.toIntOrNull(),
            episode = episode?.groupValues?.getOrNull(2)?.toIntOrNull(),
        )
    }

    /** A year and where in the name it was found, so the title can be cut at that point. */
    private data class Year(val value: Int, val at: Int)

    /**
     * Years are ambiguous because titles contain them: "Blade Runner 2049" and "2012" are films,
     * not dates. Two rules settle almost every real case: a bracketed year is always the release
     * year, and a year later than next year cannot be one.
     */
    private fun findYear(text: String, maxYear: Int): Year? {
        // A bracketed year is unambiguous, so it wins outright over anything bare.
        BRACKETED_YEAR.findAll(text)
            .mapNotNull { match -> match.groups[1]?.let { Year(it.value.toInt(), match.range.first) } }
            .lastOrNull { it.value in MIN_YEAR..maxYear }
            ?.let { return it }

        // Otherwise the last plausible year wins, so "Blade Runner 2049 2017" resolves to 2017
        // while "Blade Runner 2049" on its own keeps 2049 in the title, being beyond next year.
        return BARE_YEAR.findAll(text)
            .mapNotNull { match -> match.groups[1]?.let { Year(it.value.toInt(), match.range.first) } }
            .lastOrNull { it.value in MIN_YEAR..maxYear }
    }

    /** Where the technical markers start, or null when the name carries none. */
    private fun firstTagIndex(text: String): Int? =
        TAGS.findAll(text).minOfOrNull { it.range.first }

    private fun clean(raw: String): String = raw
        // An unterminated "(" is what is left when the title is cut at a bracketed year.
        .trim { it.isWhitespace() || it in TRIM_CHARS }
        .replace(WHITESPACE, " ")
        .trim()

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
        // Only strip something that is actually an extension: "Deadpool 2" must keep its 2, and
        // "S.W.A.T" must keep its T.
        return if (extension.length in 2..4 && extension.all { it.isLetterOrDigit() }) {
            name.substring(0, dot)
        } else {
            name
        }
    }

    private fun thisYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private const val MIN_YEAR = 1900

    private val SEPARATORS = Regex("""[._]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val TRIM_CHARS = charArrayOf('-', '(', ')', '[', ']', '{', '}', ',', ':', '_', '.', '|')

    private val BRACKETED_YEAR = Regex("""[(\[{]\s*((?:19|20)\d{2})\s*[)\]}]""")
    private val BARE_YEAR = Regex("""(?<![\d])((?:19|20)\d{2})(?![\d])""")

    private val EPISODE = Regex("""\bs(\d{1,2})[\s.-]?e(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    /**
     * The technical vocabulary, used only to locate where the title stops.
     *
     * It does not need to be exhaustive; one hit is enough to find the boundary, and every real
     * release carries a resolution or a source somewhere.
     */
    private val TAGS = Regex(
        """\b(""" + listOf(
            // Resolution and scan
            "\\d{3,4}[pi]", "4k", "uhd", "hd", "sd",
            // Source
            "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "web-?rip", "web-?dl",
            "webrip", "web", "hdrip", "dvdrip", "dvdscr", "hdtv", "pdtv", "cam", "camrip",
            "ts", "telesync", "tc", "telecine", "scr", "screener", "hdts", "predvd", "ds4k",
            // Video codec
            "x264", "x265", "h ?264", "h ?265", "hevc", "avc", "xvid", "divx", "av1", "vp9",
            "10bit", "8bit", "hdr10\\+?", "hdr", "dolby ?vision", "dv",
            // Audio
            "aac", "ac3", "eac3", "e-ac3", "dd5", "ddp5", "dd\\+", "dts", "dts-hd", "truehd",
            "atmos", "flac", "mp3", "opus", "\\d\\.\\d?ch", "dual ?audio", "multi",
            // Release furniture
            "proper", "repack", "extended", "unrated", "uncut", "limited", "internal",
            "complete", "retail", "subbed", "dubbed", "esub", "msub", "hq", "hs",
        ).joinToString("|") + """)\b""",
        RegexOption.IGNORE_CASE,
    )
}
