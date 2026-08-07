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
    /** Television rather than film, so the search goes to a different index. */
    val isSeries: Boolean get() = season != null || episode != null

    /** A single episode, as opposed to a season pack that names no episode at all. */
    val isEpisode: Boolean get() = episode != null

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
        val stripped = stripDecoration(stripExtension(fileName))
        // Separators are interchangeable in practice: the same release shows up dot-separated on
        // one tracker and space-separated on another.
        val normalised = stripped.replace(SEPARATORS, " ").replace(WHITESPACE, " ").trim()
        if (normalised.isEmpty()) return ParsedName("", null)

        val episode = findEpisode(normalised)
        val year = findYear(normalised, maxYear)

        // The title ends at whichever marker comes first: the year, the episode code, or the
        // start of the technical block.
        val cut = listOfNotNull(
            year?.at,
            episode?.at,
            firstTagIndex(normalised),
        ).minOrNull() ?: normalised.length

        val title = clean(normalised.substring(0, cut))

        return ParsedName(
            // Falling back to the whole name matters for a file that is nothing but a title.
            title = title.ifEmpty { clean(normalised) },
            year = year?.value,
            season = episode?.season,
            episode = episode?.number,
        )
    }

    /**
     * The file that carries on from [current], out of everything posted in the same chat.
     *
     * A series watcher's next move is almost always the next episode, and the alternative is
     * backing out to a grid of near-identical file names and finding it by eye. Matching is by
     * title and by number rather than by position in the list, because a chat is in the order
     * things were posted, which is not the order they are watched in.
     *
     * Only the next episode of the same season counts. Rolling on to the next season would be
     * guessing at where a series ends, and guessing wrong sends the viewer somewhere they did
     * not ask to go.
     */
    fun <T> nextEpisode(current: String, candidates: List<T>, nameOf: (T) -> String): T? {
        val here = parse(current)
        val season = here.season ?: return null
        val episode = here.episode ?: return null
        val title = here.title.lowercase()

        return candidates.firstOrNull { candidate ->
            val other = parse(nameOf(candidate))
            other.season == season &&
                other.episode == episode + 1 &&
                other.title.lowercase() == title
        }
    }

    /** A season and episode number, and where in the name the code starts. */
    private data class Episode(val season: Int?, val number: Int?, val at: Int)

    /**
     * Finds an episode however the uploader chose to write it.
     *
     * "S02E04" is the convention, but only in the sense that most people follow it. The rest
     * arrive as "2x04", "Season 2 Episode 4", or a season and an episode written separately as
     * "S01 EP04", which is the usual shape in the Indian and anime channels this app browses.
     *
     * A season with no episode is a whole-season pack, and that is a real answer rather than a
     * failure: it still belongs to the television index, it just has no single episode to show.
     */
    private fun findEpisode(text: String): Episode? {
        PAIRED_EPISODE.asSequence()
            .mapNotNull { it.find(text) }
            .minByOrNull { it.range.first }
            ?.let {
                return Episode(
                    season = it.groupValues[1].toIntOrNull(),
                    number = it.groupValues[2].toIntOrNull(),
                    at = it.range.first,
                )
            }

        val season = SEASON_ONLY.find(text)
        val number = EPISODE_ONLY.find(text)
        if (season == null && number == null) return null
        return Episode(
            season = season?.groupValues?.get(1)?.toIntOrNull(),
            number = number?.groupValues?.get(1)?.toIntOrNull(),
            at = listOfNotNull(season?.range?.first, number?.range?.first).min(),
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

    /**
     * Where the technical markers start, or null when the name carries none.
     *
     * A marker at the very front is not a boundary: it would cut the title down to nothing. That
     * is what saves the Tamil comedy actually called "Tamil Padam" from losing its own name to
     * the language list below.
     */
    private fun firstTagIndex(text: String): Int? =
        TAGS.findAll(text).map { it.range.first }.filter { it > 0 }.minOrNull()

    /**
     * Removes the branding uploaders staple to the front of a file name.
     *
     * Telegram film channels sign their work, and they do it in whatever renders as a logo:
     * "🅂🅂_Maareesan_2025...", "@SomeChannel - Film.mkv", "www.tracker.to - Film.mkv". None of it
     * is part of the title, and all of it poisons a database search, which then quietly returns
     * nothing at all.
     *
     * Symbols go by Unicode category rather than by an emoji list. The squared and circled letter
     * ranges that channels favour are all OTHER_SYMBOL, alongside every emoji, so one rule covers
     * decoration nobody has invented yet. Letters and digits in any script are left alone, because
     * a title written in Tamil or Malayalam is a title.
     */
    private fun stripDecoration(name: String): String {
        val withoutSymbols = buildString {
            var i = 0
            while (i < name.length) {
                val code = name.codePointAt(i)
                if (Character.getType(code) != Character.OTHER_SYMBOL.toInt()) appendCodePoint(code)
                i += Character.charCount(code)
            }
        }
        return withoutSymbols
            .replace(HANDLE, " ")
            .replace(SITE, " ")
            // Whatever the signature was attached with, once it is gone.
            .trimStart { it.isWhitespace() || it in TRIM_CHARS }
    }

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

    /** An uploader's Telegram handle, wherever in the name they put it. */
    private val HANDLE = Regex("""@[A-Za-z0-9_]{3,}""")

    /**
     * A tracker's domain. The label has to be three characters or more and the suffix has to be
     * one of these, so that "S.W.A.T" and a title ending in a short word are not mistaken for one.
     */
    private val SITE = Regex(
        """\b(?:www\.)?[a-z0-9][a-z0-9-]{2,}\.(?:com|net|org|to|me|cc|io|xyz|site|link|club|ws|sbs|top|pw|info|biz)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val BRACKETED_YEAR = Regex("""[(\[{]\s*((?:19|20)\d{2})\s*[)\]}]""")
    private val BARE_YEAR = Regex("""(?<![\d])((?:19|20)\d{2})(?![\d])""")

    /**
     * Season and episode written together. A multi-episode file ("S01E01-E02") matches the first
     * of them, which is the one it opens on.
     */
    private val PAIRED_EPISODE = listOf(
        Regex("""\bs(\d{1,2})[\s.-]?e(\d{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,2})x(\d{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\bseason\s?(\d{1,2})\s?episode\s?(\d{1,3})\b""", RegexOption.IGNORE_CASE),
    )

    private val SEASON_ONLY = Regex("""\b(?:s|season\s?)(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    /**
     * The word has to be there. A bare "E04" is too easy to find inside a title, and guessing
     * wrong turns a film into episode four of a series that does not exist.
     */
    private val EPISODE_ONLY = Regex("""\bep(?:isode)?\s?(\d{1,3})\b""", RegexOption.IGNORE_CASE)

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
            "webrip", "web", "hdrip", "hdtvrip", "dvdrip", "dvdscr", "dvdr", "hdtv", "pdtv",
            "cam", "camrip", "hdcam", "vodrip", "ts", "telesync", "tc", "telecine", "scr",
            "screener", "hdts", "hdtc", "predvd", "predvdrip", "ds4k", "workprint", "wp",
            // Video codec
            "x264", "x265", "h ?264", "h ?265", "hevc", "avc", "xvid", "divx", "av1", "vp9",
            "10bit", "8bit", "hdr10\\+?", "hdr", "dolby ?vision", "dv",
            // Audio
            "aac\\d?", "ac3", "eac3", "e-ac3", "dd5", "ddp5", "ddp", "dd\\+", "dts", "dts-hd",
            "dts-ma", "truehd", "atmos", "flac", "mp3", "opus", "\\d\\.\\d?ch", "\\d+ch",
            "\\d+kbps", "dual ?audio", "multi",
            // Streaming source, which Indian releases in particular carry
            "nf", "amzn", "dsnp", "hmax", "hulu", "sonyliv", "zee5", "hotstar", "jiocinema",
            // Language. Uploaders name the audio track, and it reads as part of the title
            // otherwise: "Maareesan Tamil" finds nothing where "Maareesan" finds the film.
            "tamil", "telugu", "hindi", "malayalam", "kannada", "marathi", "bengali", "punjabi",
            "english", "korean", "japanese", "chinese", "spanish", "french", "russian", "german",
            "italian", "turkish", "arabic",
            // Edition. These describe a cut of the film, which TMDB knows nothing about, so they
            // only ever get in the way of the search.
            "director'?s ?cut", "theatrical", "imax", "remastered", "criterion",
            "anniversary", "special ?edition", "final ?cut",
            // Release furniture
            "proper", "repack", "extended", "unrated", "uncut", "limited", "internal",
            "complete", "retail", "subbed", "dubbed", "esubs?", "msubs?", "hq", "hs", "nuked",
        ).joinToString("|") + """)\b""",
        RegexOption.IGNORE_CASE,
    )
}
