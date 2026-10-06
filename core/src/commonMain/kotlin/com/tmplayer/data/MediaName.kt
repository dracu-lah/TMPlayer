package com.tmplayer.data

import java.util.Calendar
import java.util.Locale

/** A video title and year recovered from a descriptive file name. */
data class ParsedName(
    val title: String,
    val year: Int?,
    /** Set when the name looks like an episode rather than a standalone video. */
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** Episodic media rather than a standalone video. */
    val isSeries: Boolean get() = season != null || episode != null

    /** A single episode, as opposed to a season pack that names no episode at all. */
    val isEpisode: Boolean get() = episode != null

    /** What to send to a search box: the year disambiguates remakes, so keep it attached. */
    val query: String get() = if (year != null) "$title $year" else title

    /** "S01E02", or "E02" where the uploader never wrote a season down. */
    val episodeCode: String?
        get() {
            val number = episode ?: return null
            val tail = "E%02d".format(Locale.ROOT, number)
            return season?.let { "S%02d".format(Locale.ROOT, it) + tail } ?: tail
        }
}

/**
 * Turns "Harbour Notes (2026) Malayalam (1080p WEB-Rip E-AC3).mkv" into
 * "Harbour Notes", 2026.
 *
 * Descriptive file names follow a loose convention: title first, then the year, then an unbounded
 * pile of technical markers. So the approach is to find where the technical markers begin and treat
 * everything before them as the title.
 *
 * Deliberately conservative: a wrong title produces a wrong label in the player, which is worse
 * than producing nothing, so anything ambiguous returns a bare title with no year.
 */
object MediaName {

    fun parse(fileName: String, maxYear: Int = thisYear() + 1): ParsedName {
        val read = read(stripExtension(fileName), maxYear)
        // Falling back to the whole name matters for a file that is nothing but a title.
        return read.parsed.copy(title = read.parsed.title.ifEmpty { read.whole })
    }

    /**
     * The same, with the post's caption as a second source for the episode.
     *
     * Plenty of channels upload "harbour_notes_720p.mkv" and write "Harbour Notes S02E04" in the
     * caption, or name the file after the show and put "Episode 4" underneath. The file name still
     * wins whenever it carries an episode of its own: a caption is free text, and the first line
     * of it is often an advert or a channel's greeting rather than a description of the file.
     *
     * Only the caption's first line that says anything is read, and it is read as text rather than
     * as a file name, so a trailing "Mr." is not taken for an extension. When that line names an
     * episode but no title ("Episode 4"), the title stays the file name's.
     */
    fun parse(fileName: String, caption: String?, maxYear: Int = thisYear() + 1): ParsedName {
        val fromName = parse(fileName, maxYear)
        if (fromName.isEpisode) return fromName
        val line = caption?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?: return fromName
        val fromCaption = read(line, maxYear).parsed
        if (!fromCaption.isEpisode) return fromName
        val nameTitle = fromName.title.takeIf { fileName.isNotBlank() }.orEmpty()
        return fromCaption.copy(
            title = fromCaption.title.ifEmpty { nameTitle },
            year = fromCaption.year ?: fromName.year,
        )
    }

    /** What [read] found, with the title left empty when nothing came before the first marker. */
    private class Read(val parsed: ParsedName, val whole: String)

    private fun read(text: String, maxYear: Int): Read {
        val stripped = stripDecoration(text)
        // Separators are interchangeable: the same release shows up dot-separated on one tracker
        // and space-separated on another.
        val normalised = stripped.replace(SEPARATORS, " ").replace(WHITESPACE, " ").trim()
        if (normalised.isEmpty()) return Read(ParsedName("", null), "")

        val episode = findEpisode(normalised)
        val year = findYear(normalised, maxYear)

        // The title ends at whichever marker comes first: the year, the episode code, or the
        // start of the technical block.
        val cut = listOfNotNull(
            year?.at,
            episode?.at,
            firstTagIndex(normalised),
        ).minOrNull() ?: normalised.length

        return Read(
            ParsedName(
                title = clean(normalised.substring(0, cut)),
                year = year?.value,
                season = episode?.season,
                episode = episode?.number,
            ),
            whole = clean(normalised),
        )
    }

    /**
     * The file that carries on from [current], out of everything posted in the same chat.
     *
     * Matching is by title and by number rather than by position in the list, because a chat is in
     * the order things were posted, which is not the order they are watched in. Only the next
     * episode of the same season counts: rolling on to the next season would be guessing at where
     * a series ends.
     */
    fun <T> nextEpisode(current: String, candidates: List<T>, nameOf: (T) -> String): T? =
        episodeAt(current, candidates, step = 1, nameOf = nameOf)

    /** The episode before [current], found the same way [nextEpisode] finds the one after it. */
    fun <T> previousEpisode(current: String, candidates: List<T>, nameOf: (T) -> String): T? =
        episodeAt(current, candidates, step = -1, nameOf = nameOf)

    private fun <T> episodeAt(
        current: String,
        candidates: List<T>,
        step: Int,
        nameOf: (T) -> String,
    ): T? {
        val here = parse(current)
        val season = here.season ?: return null
        val episode = here.episode ?: return null
        val title = here.title.lowercase()
        val wanted = episode + step
        if (wanted < 1) return null

        return candidates.firstOrNull { candidate ->
            val other = parse(nameOf(candidate))
            other.season == season &&
                other.episode == wanted &&
                other.title.lowercase() == title
        }
    }

    /** A season and episode number, and where in the name the code starts. */
    private data class Episode(val season: Int?, val number: Int?, val at: Int)

    /**
     * Finds an episode however the uploader chose to write it.
     *
     * Beyond "S02E04", names arrive as "2x04", "Season 2 Episode 4", a season and an episode
     * written separately as "S01 EP04", "Ep 04" on its own, or the fansub form "Show - 04", where
     * the number is absolute and there is no season at all.
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
        val number = EPISODE_ONLY.find(text) ?: DASHED_EPISODE.find(text)?.takeUnless { looksLikeYear(it) }
        if (season == null && number == null) return null
        return Episode(
            season = season?.groupValues?.get(1)?.toIntOrNull(),
            number = number?.groupValues?.get(1)?.toIntOrNull(),
            at = listOfNotNull(season?.range?.first, number?.range?.first).min(),
        )
    }

    /** "Show - 2019" is a year, not episode 2019; the dashed form is otherwise four digits wide. */
    private fun looksLikeYear(match: MatchResult): Boolean {
        val digits = match.groupValues[1]
        return digits.length == 4 && digits.toInt() in MIN_YEAR..2099
    }

    /** A year and where in the name it was found, so the title can be cut at that point. */
    private data class Year(val value: Int, val at: Int)

    /**
     * Years are ambiguous because titles can contain them. Two rules settle almost every real
     * case: a bracketed year is always the recording year, and a year later than next year cannot
     * be one.
     */
    private fun findYear(text: String, maxYear: Int): Year? {
        // A bracketed year is unambiguous, so it wins outright over anything bare.
        BRACKETED_YEAR.findAll(text)
            .mapNotNull { match -> match.groups[1]?.let { Year(it.value.toInt(), match.range.first) } }
            .lastOrNull { it.value in MIN_YEAR..maxYear }
            ?.let { return it }

        // Otherwise the last plausible year wins, so "Studio Log 2049 2017" resolves to 2017
        // while "Studio Log 2049" keeps 2049 in the title, being beyond next year.
        return BARE_YEAR.findAll(text)
            .mapNotNull { match -> match.groups[1]?.let { Year(it.value.toInt(), match.range.first) } }
            .lastOrNull { it.value in MIN_YEAR..maxYear }
    }

    /**
     * Where the technical markers start, or null when the name carries none.
     *
     * A marker at the very front is not a boundary: it would cut the title down to nothing. That
     * is what saves a title such as "Tamil Lessons" from losing its own first word to the language
     * list below.
     */
    private fun firstTagIndex(text: String): Int? =
        TAGS.findAll(text).map { it.range.first }.filter { it > 0 }.minOrNull()

    /**
     * Removes the branding uploaders staple to the front of a file name.
     *
     * Uploads get signed with whatever renders as a logo: "🅂🅂_Harbour_Notes_2025...",
     * "@CreatorClips - Workshop.mkv", "www.creatorfiles.org - Workshop.mkv". None of it is title.
     *
     * Symbols go by Unicode category rather than by an emoji list: the squared and circled letter
     * ranges channels favour are all OTHER_SYMBOL, alongside every emoji, so one rule covers
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
            // A fansub group's tag, "[SubsPlease] Show - 02", is a signature in brackets.
            .replace(GROUP_TAG, "")
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
        // Only strip something that is actually an extension: "Workshop 2" must keep its 2, and
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

    /**
     * The fansub form: "Show - 02", "Show - 1071", "Show - 02v2". Spaces on both sides of the dash
     * and at least two digits, which is how every group pads the number, so "Part - 2" stays a
     * title. A resolution never matches, because the number has to end the word, and a year is
     * turned away by [looksLikeYear].
     */
    private val DASHED_EPISODE = Regex("""\s-\s(\d{2,4})(?:v\d)?(?=\s|$)""")

    /** A bracketed group name at the very front, before any title. Never a year. */
    private val GROUP_TAG = Regex("""^\s*\[(?!\s*(?:19|20)\d{2}\s*\])[^\]]{1,40}\]\s*""")

    private val SEASON_ONLY = Regex("""\b(?:s|season\s?)(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    /**
     * The word has to be there. A bare "E04" is too easy to find inside a title, and guessing
     * wrong turns a standalone video into episode four of a series that does not exist.
     */
    private val EPISODE_ONLY = Regex("""\bep(?:isode)?[\s-]?(\d{1,3})\b""", RegexOption.IGNORE_CASE)

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
            "webrip", "web", "hdrip", "hdtvrip", "dvdrip", "dvdr", "hdtv", "pdtv",
            "vodrip", "ds4k",
            // Video codec
            "x264", "x265", "h ?264", "h ?265", "hevc", "avc", "xvid", "divx", "av1", "vp9",
            "10bit", "8bit", "hdr10\\+?", "hdr", "dolby ?vision", "dv",
            // Audio
            "aac\\d?", "ac3", "eac3", "e-ac3", "dd5", "ddp5", "ddp", "dd\\+", "dts", "dts-hd",
            "dts-ma", "truehd", "atmos", "flac", "mp3", "opus", "\\d\\.\\d?ch", "\\d+ch",
            "\\d+kbps", "dual ?audio", "multi",
            // Streaming source, which Indian releases in particular carry
            "nf", "amzn", "dsnp", "hmax", "hulu", "sonyliv", "zee5", "hotstar", "jiocinema",
            // Language. Uploaders name the audio track, and it otherwise reads as part of the
            // title: "Harbour Notes Tamil" should be grouped under "Harbour Notes".
            "tamil", "telugu", "hindi", "malayalam", "kannada", "marathi", "bengali", "punjabi",
            "english", "korean", "japanese", "chinese", "spanish", "french", "russian", "german",
            "italian", "turkish", "arabic",
            // Edition labels describe a particular cut, not the base title, so they only get in
            // the way of grouping neighbouring files.
            "director'?s ?cut", "theatrical", "imax", "remastered", "criterion",
            "anniversary", "special ?edition", "final ?cut",
            // Release furniture
            "proper", "repack", "extended", "unrated", "uncut", "limited", "internal",
            "complete", "retail", "subbed", "dubbed", "esubs?", "msubs?", "hq", "hs", "nuked",
        ).joinToString("|") + """)\b""",
        RegexOption.IGNORE_CASE,
    )
}
