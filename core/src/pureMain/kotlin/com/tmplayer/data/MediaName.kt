package com.tmplayer.data


import com.tmplayer.platform.appendCodePointValue
import com.tmplayer.platform.charCountOf
import com.tmplayer.platform.codePointAtIndex
import com.tmplayer.platform.currentYear
import com.tmplayer.platform.isOtherSymbol
import com.tmplayer.platform.monotonicMillis
import kotlin.concurrent.Volatile

/** A video title and year recovered from a descriptive file name. */
data class ParsedName(
    val title: String,
    val year: Int?,
    /** Set when the name looks like an episode rather than a standalone video. */
    val season: Int? = null,
    val episode: Int? = null,
    /**
     * Which piece of a film cut into several files this one is: "CD2", "Part2", "pt.2" after the
     * title. Null for a whole file, and for "Part 2" written inside a title, which is a sequel.
     */
    val part: Int? = null,
    /** Set when the name says this is not the video itself but a trailer, a teaser or a sample. */
    val extra: ExtraKind? = null,
    /**
     * "2024-10-14" for a daily show or a match named by the day it aired ("Show 2024.10.14",
     * "Show 14-10-2024"): an episode by date, which [year] then dates too. Never a film.
     */
    val airDate: String? = null,
    /**
     * The last episode a file of several holds: 2 for "S01E01E02" and "S01E01-E02". Null for a
     * file of one episode, which is nearly every file.
     */
    val lastEpisode: Int? = null,
) {
    /**
     * [SeriesShelf.keyOf] of [title], worked out once: a parse is kept on its [MediaItem], and the
     * shelf groups, keys and folds by this for every file on every build of Home.
     */
    internal val titleKey: String
        get() = titleKeyRead ?: SeriesShelf.keyOf(title).also { titleKeyRead = it }

    @Volatile private var titleKeyRead: String? = null

    /** Episodic media rather than a standalone video. */
    val isSeries: Boolean get() = season != null || episode != null || airDate != null

    /** A single episode, as opposed to a season pack that names no episode at all. */
    val isEpisode: Boolean get() = episode != null

    /** What to send to a search box: the year disambiguates remakes, so keep it attached. */
    val query: String get() = if (year != null) "$title $year" else title

    /** "S01E02", or "E02" where the uploader never wrote a season down. */
    val episodeCode: String?
        get() {
            val number = episode ?: return null
            val tail = "E" + number.toString().padStart(2, '0')
            return season?.let { "S" + it.toString().padStart(2, '0') + tail } ?: tail
        }
}

/** What a short file posted beside a film can be, as its name says. */
enum class ExtraKind { Trailer, Teaser, Promo, Sample, Extra }

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

    /**
     * The lenient reading of [text], for a file whose chat has already established the show: a
     * bare "E05" or "E5" after the title, which [parse] turns down because, with nothing else to
     * go on, it is too easy to find inside a title. Null unless there is a title before it and an
     * episode number in it. [SeriesShelf] only accepts the answer when the title is a show the
     * chat's other files name.
     */
    fun looseEpisode(text: String, maxYear: Int = thisYear() + 1): ParsedName? {
        val strict = parse(text, maxYear)
        if (strict.isEpisode) return strict
        val normalised = spaced(stripDecoration(stripExtension(text))).trim()
        val match = BARE_EPISODE.find(normalised)?.takeIf { it.range.first > 0 } ?: return null
        val title = clean(read(normalised.substring(0, match.range.first), maxYear).parsed.title)
        if (title.isEmpty()) return null
        return ParsedName(title = title, year = strict.year, season = null, episode = match.groupValues[1].toInt())
    }

    /**
     * The episode's own name, where the uploader wrote one after the code:
     * "Harbour.Notes.S01E04.The.Lighthouse.1080p.mkv" gives "The Lighthouse", and
     * "Show, E5. Vlog" gives "Vlog". Null when [text] names no episode, or when nothing but release
     * tags, a year or a bracket follows the code.
     */
    fun episodeName(text: String): String? {
        val normalised = spaced(stripDecoration(stripExtension(text))).trim()
        val code = (PAIRED_EPISODE + EPISODE_ONLY + BARE_EPISODE)
            .mapNotNull { it.find(normalised) }
            .plus(listOfNotNull(DASHED_EPISODE.find(normalised)?.takeUnless { notAnEpisode(it, normalised) }))
            .minByOrNull { it.range.first }
            ?: return null
        // A file that holds two episodes, "S01E01-E02", names the second straight after the first.
        val rest = normalised.substring(code.range.last + 1).replace(EXTRA_EPISODE, "")
        val cut = listOfNotNull(
            TAGS.find(rest)?.range?.first,
            BARE_YEAR.find(rest)?.range?.first,
            rest.indexOfAny(charArrayOf('(', '[', '{')).takeIf { it >= 0 },
        ).minOrNull() ?: rest.length
        val name = clean(rest.substring(0, cut))
        return name.takeIf { it.count(Char::isLetter) >= 2 }
    }

    /**
     * The name a tile shows for a file called [fileName]: "Gladiator II (2024)" for
     * "Gladiator.II.2024.1080p.WEB-DL.x265.mkv", "Harbour Notes S01E03" for an episode, and for a
     * name that is no release name at all, the name with its extension and its dots and
     * underscores taken out ("Lecture 07 - Limits" from "Lecture_07_-_Limits.mp4").
     *
     * The title is only cut short where the name proves it is a release, with a year, an episode or
     * a resolution or codec marker. Without one, a language or a source word inside an ordinary
     * name ("Learn Tamil Fast") would be taken for the start of the technical block and cut the
     * title to one word, which is worse than leaving the name whole. Null when nothing readable is
     * left, so the caller keeps whatever it had.
     */
    fun tileTitle(fileName: String, caption: String? = null, maxYear: Int = thisYear() + 1): String? {
        if (fileName.isBlank()) return null
        val tidy = clean(spaced(stripDecoration(stripExtension(fileName))))
        val parsed = parse(fileName, caption, maxYear)
        val release = parsed.year != null || parsed.isEpisode || EpisodeCopies.markers(fileName).isNotEmpty() ||
            endsInTags(tidy) || parsed.part != null || parsed.extra != null || SHORT_LANGUAGE.containsMatchIn(tidy) ||
            (HARD_TAGS.find(tidy)?.range?.first ?: 0) > 0 ||
            (CAST_BRACKET.find(tidy)?.range?.first ?: 0) > 0
        // A name that is nothing but its marker ("Episode 1") reads back as the whole name, which
        // says it already; adding the code to it would say it twice.
        val title = parsed.title.takeIf { release && it.isNotBlank() && !it.equals(tidy, ignoreCase = true) }
            ?: return tidy.ifEmpty { null }
        return when {
            parsed.isEpisode -> "$title ${parsed.episodeCode}"
            parsed.year != null -> "$title (${parsed.year})"
            else -> title
        }
    }

    /**
     * Whether everything in [text] from its first release tag on is tags and separators, as in
     * "Marina Tamil Hd" or "Chinna Veedu | Full Movie": a name with a title and nothing but tags
     * after it, which is safe to cut where "Learn Tamil Fast" is not.
     */
    private fun endsInTags(text: String): Boolean {
        val start = firstTagIndex(text) ?: return false
        return text.substring(start).replace(TAGS, " ").all { it.isWhitespace() || it in TRIM_CHARS }
    }

    /** What [read] found, with the title left empty when nothing came before the first marker. */
    private class Read(val parsed: ParsedName, val whole: String)

    private fun read(text: String, maxYear: Int): Read {
        val stripped = stripDecoration(text)
        // Separators are interchangeable: the same release shows up dot-separated on one tracker
        // and space-separated on another.
        var normalised = spaced(stripped).trim()
        if (normalised.isEmpty()) return Read(ParsedName("", null), "")

        // "Trailer - Night Train (2024)": the word in front says what the file is, the rest names it.
        // Only with a dash or a colon after it, so "Sample Room Tales" stays a title.
        val leadingExtra = EXTRA.find(normalised)?.takeIf {
            it.range.first == 0 && LEADING_SEPARATOR.containsMatchIn(normalised.substring(it.range.last + 1))
        }
        if (leadingExtra != null) {
            normalised = clean(normalised.substring(leadingExtra.range.last + 1))
            if (normalised.isEmpty()) return Read(ParsedName("", null, extra = extraOf(leadingExtra.value)), "")
        }

        // A daily show or a match is numbered by the day it aired, "Show 2024.10.14 720p".
        val date = findAirDate(normalised)
        val episode = if (date == null) findEpisode(normalised) else null
        val year = if (date != null) Year(date.year, date.at) else findYear(normalised, maxYear)

        // The title ends at whichever marker comes first: the year, the episode code, the air date,
        // the start of the technical block, a split film's "CD1", a trailer's own word, or a cast list.
        val dated = listOfNotNull(year?.at, episode?.at, date?.at).minOrNull()
        val markers = listOfNotNull(dated, titleEnd(normalised, dated)).minOrNull()
        val part = if (episode == null) findPart(normalised, markers) else null
        // The word has to end the title: "Night Train Trailer", "Night Train (2024) Teaser 2", but
        // not "The Sample Room (2020)", where more of the title follows it.
        val extra = EXTRA.findAll(normalised).firstOrNull { match ->
            match.range.first > 0 && (
                (markers != null && match.range.first > markers) ||
                    clean(normalised.substring(match.range.last + 1, maxOf(markers ?: normalised.length, match.range.last + 1))).isEmpty()
                )
        }
        val bracket = CAST_BRACKET.find(normalised)?.range?.first?.takeIf { it > 0 }
        val cut = listOfNotNull(markers, part?.at, extra?.range?.first, bracket).minOrNull() ?: normalised.length

        return Read(
            ParsedName(
                title = withoutLanguageTail(clean(normalised.substring(0, cut))),
                year = year?.value,
                season = episode?.season,
                episode = episode?.number,
                lastEpisode = episode?.last,
                part = part?.number,
                extra = (extra ?: leadingExtra)?.let { extraOf(it.value) },
                airDate = date?.text,
            ),
            whole = clean(normalised),
        )
    }

    /**
     * [title] without the short audio language channels write after it ("Night Train Mal",
     * "Night Train #Hin", "Night Train Tam 2023" once cut at the year), up to three of them. Not
     * when it is the whole title ("Mal (2011)") or follows an article or a joining word ("Tales of
     * Tam"). They are not [TAGS]: as one of those, "Tam" would end a title wherever it stood.
     */
    private fun withoutLanguageTail(title: String): String {
        var text = title
        repeat(3) {
            if (text.length < 4 || text.takeLast(3).lowercase() !in SHORT_LANGUAGE_WORDS) return text
            val match = SHORT_LANGUAGE_TAIL.find(text) ?: return text
            val before = text.substring(0, match.range.first).trim()
            if (clean(before).lowercase() in ARTICLES || before.substringAfterLast(' ').lowercase() in JOINING_WORDS) return text
            // A bracket the title closed stays closed: "Night Train (2k23) Mal".
            text = before.trimEnd { it.isWhitespace() || (it in TRIM_CHARS && it !in CLOSING) }
        }
        return text
    }

    /** A piece of a split film, and where in the name its marker starts. */
    private data class Part(val number: Int, val at: Int)

    /**
     * "CD1", "Disc 2", "Part2", "pt.3": the piece of one film a file holds. "Part 2" with a space
     * reads as a sequel's title ("Night Train Part 2 (2021)") unless it comes after the year or the
     * release tags, where no title is; the run-together forms and a disc number never belong to a
     * title. [markers] is where the year, the episode or the tags begin.
     */
    private fun findPart(text: String, markers: Int?): Part? {
        val match = PART.findAll(text).firstOrNull { it.range.first > 0 } ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null
        if (number !in 1..MAX_PARTS) return null
        val word = match.groupValues[1].lowercase()
        val runTogether = match.value.none { it == ' ' }
        val afterTitle = markers != null && match.range.first > markers
        val split = word == "cd" || word.startsWith("dis") || runTogether || afterTitle
        return if (split) Part(number, match.range.first) else null
    }

    private fun extraOf(word: String): ExtraKind {
        val text = word.lowercase()
        return when {
            "trailer" in text || TRAILER_WORDS.any { it in text } -> ExtraKind.Trailer
            "teaser" in text || TEASER_WORDS.any { it in text } -> ExtraKind.Teaser
            "promo" in text -> ExtraKind.Promo
            "sample" in text -> ExtraKind.Sample
            else -> ExtraKind.Extra
        }
    }

    /**
     * The audio languages [text] names after its title ("Night Train 2022 Tamil HDRip" gives
     * `ta`), as ISO 639-1 codes in the order written. A language word at the very front is the
     * title's own ("Tamil Lessons"), and is not counted.
     */
    fun languages(text: String): List<String> {
        val normalised = spaced(stripDecoration(stripExtension(text))).trim()
        return LANGUAGE.findAll(normalised)
            .filter { it.range.first > 0 }
            .mapNotNull { LANGUAGE_CODES[it.value.lowercase()] }
            .distinct()
            .toList()
    }

    /**
     * The file that carries on from [current], out of everything posted in the same chat.
     *
     * Matching is by title and by number rather than by position in the list, because a chat is in
     * the order things were posted, which is not the order they are watched in. Only the next
     * episode of the same season counts: rolling on to the next season would be guessing at where
     * a series ends.
     */
    fun <T> nextEpisode(
        current: String,
        candidates: List<T>,
        currentSize: Long = 0,
        sizeOf: (T) -> Long = { 0L },
        nameOf: (T) -> String,
    ): T? = episodeAt(current, candidates, step = 1, nameOf = nameOf, currentSize = currentSize, sizeOf = sizeOf)

    /** The episode before [current], found the same way [nextEpisode] finds the one after it. */
    fun <T> previousEpisode(
        current: String,
        candidates: List<T>,
        currentSize: Long = 0,
        sizeOf: (T) -> Long = { 0L },
        nameOf: (T) -> String,
    ): T? = episodeAt(current, candidates, step = -1, nameOf = nameOf, currentSize = currentSize, sizeOf = sizeOf)

    /**
     * When the chat holds several copies of the episode wanted (350 MB, 600 MB, 1.8 GB), the one
     * most like [current] at [currentSize] bytes: see [EpisodeCopies.closest].
     */
    private fun <T> episodeAt(
        current: String,
        candidates: List<T>,
        step: Int,
        nameOf: (T) -> String,
        currentSize: Long,
        sizeOf: (T) -> Long,
    ): T? {
        val here = parse(current)
        val season = here.season ?: return null
        val episode = here.episode ?: return null
        val title = here.title.lowercase()
        val wanted = episode + step
        if (wanted < 1) return null

        val copies = candidates.filter { candidate ->
            val other = parse(nameOf(candidate))
            other.season == season &&
                other.episode == wanted &&
                other.title.lowercase() == title
        }
        return EpisodeCopies.closest(current, currentSize, copies, nameOf, sizeOf)
    }

    /** A season and episode number, and where in the name the code starts. */
    private data class Episode(val season: Int?, val number: Int?, val at: Int, val last: Int? = null)

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
                val number = it.groupValues[2].toIntOrNull()
                return Episode(
                    season = it.groupValues[1].toIntOrNull(),
                    number = number,
                    at = it.range.first,
                    // A second code straight after the first, the file's last episode.
                    last = (it.groupValues.getOrNull(3)?.toIntOrNull() ?: it.groupValues.getOrNull(4)?.toIntOrNull())
                        ?.takeIf { last -> number != null && last > number },
                )
            }

        val season = SEASON_ONLY.find(text)
        val range = EPISODE_RANGE.find(text)
        val number = range ?: EPISODE_ONLY.find(text) ?: DASHED_EPISODE.find(text)?.takeUnless { notAnEpisode(it, text) }
        if (season == null && number == null) return null
        val first = number?.groupValues?.get(1)?.toIntOrNull()
        return Episode(
            season = season?.groupValues?.get(1)?.toIntOrNull(),
            number = first,
            at = listOfNotNull(season?.range?.first, number?.range?.first).min(),
            last = range?.groupValues?.get(2)?.toIntOrNull()?.takeIf { last -> first != null && last > first },
        )
    }

    /**
     * "Show - 2019" is a year, not episode 2019. A number after the release tags, "Film 2026
     * HDRip - 1080" or "- x264 - AAC - 40", is a picture height or what is left of a name Telegram
     * cut short, since a fansub's "One Piece - 1080" puts the number before any tag. Anime run to
     * four figures, so a bare 1080 with nothing in front of it is an episode.
     */
    private fun notAnEpisode(match: MatchResult, text: String): Boolean {
        if (TAGS.containsMatchIn(text.substring(0, match.range.first))) return true
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
        // while "Studio Log 2049" keeps 2049 in the title, being beyond next year. A year that
        // opens the name is its title ("1944.1080p.mkv", "1944 2019 HDRip"): a release year
        // never stands in front of the title it dates. "[2014] Garden Notes" arrives here with
        // its opening bracket trimmed off, and the closing one still says it is a year.
        return BARE_YEAR.findAll(text)
            .mapNotNull { match -> match.groups[1]?.let { Year(it.value.toInt(), match.range.first) } }
            .lastOrNull { it.value in MIN_YEAR..maxYear && (it.at > 0 || text.getOrNull(4) in CLOSING) }
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
     * Where the title ends on account of its tags, given that the year, the episode or the air date
     * (whichever comes first) is at [dated].
     *
     * A hard marker (a resolution, a codec, a source) is never part of a title, so it ends one
     * wherever it is. A softer word (a language, an edition, "Complete", "Web") ends it only where
     * nothing but tags follows it up to the next marker: "Harbour Notes Tamil 2022 HDRip" ends at
     * "Tamil", but "The Italian Job 2003" and "Learn Tamil Fast" keep their words, and so does a
     * title the cut would leave as a bare article ("The Final Cut").
     */
    private fun titleEnd(text: String, dated: Int?): Int? {
        val hard = HARD_TAGS.findAll(text).map { it.range.first }.firstOrNull { it > 0 }
        val bound = listOfNotNull(dated, hard).minOrNull() ?: text.length
        val soft = TAGS.findAll(text).firstOrNull { match ->
            val at = match.range.first
            at in 1 until bound &&
                text.substring(at, bound).replace(TAGS, " ").all { it.isWhitespace() || it in TRIM_CHARS } &&
                clean(text.substring(0, at)).lowercase() !in ARTICLES &&
                // "Into the Web", "Tales of Tamil": a word straight after "the" or "of" is the title's.
                text.substring(0, at).trim().substringAfterLast(' ').lowercase() !in JOINING_WORDS
        }?.range?.first
        return listOfNotNull(hard, soft).minOrNull()
    }

    /** A date and where it starts. */
    private class AirDate(val text: String, val year: Int, val at: Int)

    /** "2024 10 14" or "14 10 2024", separators already spaces, after a title. */
    private fun findAirDate(text: String): AirDate? {
        ISO_DATE.find(text)?.takeIf { it.range.first > 0 }?.let { m ->
            val (y, mo, d) = m.destructured
            return AirDate("$y-$mo-$d", y.toInt(), m.range.first)
        }
        DAY_FIRST_DATE.find(text)?.takeIf { it.range.first > 0 }?.let { m ->
            val (d, mo, y) = m.destructured
            return AirDate("$y-$mo-$d", y.toInt(), m.range.first)
        }
        return null
    }

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
        val visible = withoutInvisibles(name)
        // Most names carry no symbol at all, and are not copied out a character at a time.
        val withoutSymbols = if (!hasOtherSymbol(visible)) visible else buildString(visible.length) {
            var i = 0
            while (i < visible.length) {
                val code = visible.codePointAtIndex(i)
                if (!isOtherSymbol(code)) appendCodePointValue(code)
                i += charCountOf(code)
            }
        }
        // A fansub group's tag, "[SubsPlease] Show - 02", is a signature in brackets.
        val unsigned = withoutSymbols.replace(GROUP_TAG, "")
        return unsigned
            .replace(HANDLE) {
                handleOf(it.value, unsigned.substring(0, it.range.first), unsigned.substring(it.range.last + 1))
            }
            .replace(SITE, " ")
            // Whatever the signature was attached with, once it is gone.
            .trimStart { it.isWhitespace() || it in TRIM_CHARS }
    }

    /**
     * [name] without the characters that take no space: zero width spaces, direction marks, soft
     * hyphens and the variation selectors an emoji leaves behind, which a copied name carries in
     * front of its title. A zero width joiner or non-joiner after a letter of another script stays,
     * because Malayalam, Hindi and Persian spell words with them.
     */
    private fun withoutInvisibles(name: String): String {
        if (name.none { isInvisible(it) || it == ZWNJ || it == ZWJ }) return name
        return buildString(name.length) {
            for (index in name.indices) {
                val char = name[index]
                val joinerInWord = (char == ZWNJ || char == ZWJ) && name.getOrNull(index - 1)?.let { joinsInScript(it) } == true
                if (joinerInWord || !(isInvisible(char) || char == ZWNJ || char == ZWJ)) append(char)
            }
        }
    }

    private fun isInvisible(char: Char): Boolean =
        char == '​' || char == '‎' || char == '‏' || char in '‪'..'‮' ||
            char in '⁠'..'⁤' || char == '﻿' || char == '­' || char in '︀'..'️'

    /** A letter or a vowel sign or virama of a script past Latin, which a joiner may follow. */
    private fun joinsInScript(char: Char): Boolean =
        char.code > 0x24F && (
            char.isLetter() || char.category == CharCategory.NON_SPACING_MARK ||
                char.category == CharCategory.COMBINING_SPACING_MARK
            )

    private fun hasOtherSymbol(name: String): Boolean {
        var i = 0
        while (i < name.length) {
            val code = name.codePointAtIndex(i)
            if (isOtherSymbol(code)) return true
            i += charCountOf(code)
        }
        return false
    }

    /**
     * What a [HANDLE] match leaves behind, given the text [before] and [after] it. A handle can hold
     * underscores, and so can the name it is stapled to: "@TamilMob_LinkZz_BEAST_2022_Tamil_HQ_HDRip.mkv"
     * matches as one word. When the match runs on into a year or a release tag, it is a handle
     * followed by the name, and the handle is taken as its first two words, which is how channels
     * name themselves ("@TamilMob_LinkZz", "@HEVC_Mob"); the rest goes back as the name.
     *
     * Otherwise a one word handle goes whole, and so does one signed after the name ("... @cinema_company")
     * or before a name written apart ("@TG_Clips4u -Night Train"). A handle that opens the name and
     * has nothing after it but the year and the tags carries the name in its underscores
     * ("@CC_Night_Train", "@WMR_Night_Train.1990.Malayalam"): the handle is its first word, and the
     * second too when that is a word channels name themselves with ("@CC_MAL_..."), never a title's.
     */
    private fun handleOf(match: String, before: String, after: String): String {
        val words = match.removePrefix("@").split('_').filter { it.isNotEmpty() }
        val runsOn = words.drop(1).any { BARE_YEAR.matches(it) || TAGS.matches(it) }
        if (runsOn) return if (words.size > 2) " " + words.drop(2).joinToString(" ") else " "
        if (words.size < 2) return " "
        val opensName = before.all { it.isWhitespace() || it in TRIM_CHARS }
        if (!opensName || !(after.isBlank() || startsWithMarker(after))) return " "
        val channelWord = words.size > 2 && words[1].lowercase() in CHANNEL_WORDS
        return " " + words.drop(if (channelWord) 2 else 1).joinToString(" ") + " "
    }

    /** Whether [text], past its separators, opens on a year or a release tag. */
    private fun startsWithMarker(text: String): Boolean {
        val word = text.trimStart { it.isWhitespace() || it in TRIM_CHARS }
            .takeWhile { it.isLetterOrDigit() || it == '-' || it == '+' }
        return word.isNotEmpty() && (BARE_YEAR.matches(word) || TAGS.matches(word))
    }

    private fun clean(raw: String): String =
        // An unterminated "(" is what is left when the title is cut at a bracketed year.
        collapsedSpaces(raw.trim { it.isWhitespace() || it in TRIM_CHARS }).trim()

    /**
     * [text] with each run of dots and underscores a space and each run of whitespace one space,
     * as replacing `[._]+` and then `\s+` with a space gives it. Every name is read through
     * this, so the separators go in one scan and the expression only runs on a name that still has
     * a run of spaces or a tab, a line break or a wide space in it.
     */
    private fun spaced(text: String): String {
        val collapsed = collapsedSpaces(separatorsAsSpaces(text))
        // "Night Train 20241080p": a year run into its resolution is the two of them.
        if (!GLUED_YEAR.containsMatchIn(collapsed)) return collapsed
        return collapsed.replace(GLUED_YEAR) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }

    private fun separatorsAsSpaces(text: String): String {
        val first = text.indexOfFirst { it == '.' || it == '_' }
        if (first < 0) return text
        val out = StringBuilder(text.length).append(text, 0, first)
        var index = first
        while (index < text.length) {
            if (text[index] == '.' || text[index] == '_') {
                out.append(' ')
                while (index < text.length && (text[index] == '.' || text[index] == '_')) index++
            } else {
                out.append(text[index++])
            }
        }
        return out.toString()
    }

    /**
     * `replace(WHITESPACE, " ")`, run only where it would change something. What counts as
     * whitespace differs by platform (Android's expressions take every Unicode space), so the
     * check takes the widest reading and leaves the answer to the expression.
     */
    private fun collapsedSpaces(text: String): String {
        for (index in text.indices) {
            val char = text[index]
            val run = char == ' ' && index > 0 && text[index - 1] == ' '
            if (run || (char != ' ' && (char.isWhitespace() || char == '\u0085' || char == '\uFEFF'))) return text.replace(WHITESPACE, " ")
        }
        return text
    }

    private fun stripExtension(whole: String): String {
        // A byte piece's suffix first ("Name.mkv.001", "Name.part007"): the name is its base's,
        // and a "part007" left in would read as a piece of a film cut into parts, or as a lesson.
        // Only a name that is surely a piece: "Name.part001.mkv" may be a whole file, "Part 1" of
        // a film in parts, and keeps its number for [findPart].
        val piece = SplitPieces.pieceOf(whole)?.takeIf { it.certain }
        // "Name.part001" kept no extension, and the base's last word is the name's, not one.
        if (piece != null && piece.base.substringAfterLast('.', "").lowercase() !in MediaText.VIDEO_EXTENSIONS) {
            return piece.base
        }
        val name = piece?.base ?: whole
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val extension = name.substring(dot + 1).lowercase()
        // Only strip something that is actually an extension: "Workshop 2" must keep its 2, and
        // "S.W.A.T" must keep its T.
        return if (extension.length in 2..4 && extension.all { it.isLetterOrDigit() }) {
            name.substring(0, dot)
        } else {
            name
        }
    }

    /**
     * The year now, read again once a minute rather than on every name: a calendar is a dozen
     * objects on Android, and a chat's listing parses hundreds of names.
     */
    private fun thisYear(): Int {
        val now = monotonicMillis()
        val read = yearRead
        if (read != null && now - read.at in 0 until YEAR_RECHECK_MS) return read.year
        return currentYear().also { yearRead = YearRead(it, now) }
    }

    private class YearRead(val year: Int, val at: Long)

    @Volatile private var yearRead: YearRead? = null

    private const val YEAR_RECHECK_MS = 60_000L

    private const val MIN_YEAR = 1900

    /** More pieces than this is a numbered run of clips, not one film cut to fit a disc. */
    private const val MAX_PARTS = 9

    /** "Trailer" in Tamil, Hindi and Malayalam script. Declared before [EXTRA], which reads them. */
    private val TRAILER_WORDS = listOf("ட்ரெய்லர்", "டிரெய்லர்", "ट्रेलर", "ട്രെയിലർ")

    /** "Teaser" in the same scripts. */
    private val TEASER_WORDS = listOf("டீசர்", "टीज़र", "ടീസർ")

    /** "CD1", "Disc 2", "Part2", "Part 2", "pt.3" (a dot is a space by now), "Pt-1". */
    private val PART = Regex("""\b(cd|dis[ck]|part|pt)[\s-]?0*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    /**
     * What a trailer or a sample calls itself. "Official" goes with the word after it, so "Night
     * Train Official Trailer 2" keeps no "Official" in its title.
     */
    private val EXTRA = Regex(
        """\b(?:official\s+)?(?:trailer|teaser|promo|sample|sneak\s?peek|first\s?look|glimpse|making\s?of)\b(?:\s?\d{1,2}\b)?""" +
            // The same words as the Tamil, Hindi and Malayalam channels write them, which end on a
            // vowel sign, so the edges are spelt out rather than left to \b.
            """|(?<![\p{L}\p{M}])(?:${TRAILER_WORDS.joinToString("|")}|${TEASER_WORDS.joinToString("|")})(?![\p{L}\p{M}])""",
        RegexOption.IGNORE_CASE,
    )

    private val CLOSING = setOf(')', ']', '}')

    private val ARTICLES = setOf("", "the", "a", "an")

    private val JOINING_WORDS = setOf("the", "a", "an", "of", "and", "in", "on", "into", "to", "for", "with", "from", "my", "our")

    private val ISO_DATE = Regex("""(?<!\d)((?:19|20)\d{2})[ -](0[1-9]|1[0-2])[ -](0[1-9]|[12]\d|3[01])(?!\d)""")
    private val DAY_FIRST_DATE = Regex("""(?<!\d)(0[1-9]|[12]\d|3[01])[ -](0[1-9]|1[0-2])[ -]((?:19|20)\d{2})(?!\d)""")

    /**
     * The markers that never belong to a title: a resolution, a codec, a source, a size. The rest
     * of [TAGS] (languages, editions, furniture) are words a title can hold.
     */
    private val HARD_TAGS = Regex(
        """\b(""" + listOf(
            "\\d{3,4}[pi]", "4k", "uhd", "2160p",
            "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "web-?rip", "web-?dl",
            "webrip", "hdrip", "hdtvrip", "dvdrip", "dvdr", "hdtv", "pdtv", "vodrip", "ds4k",
            "x264", "x265", "h ?264", "h ?265", "hevc", "xvid", "divx", "10bit", "8bit", "hdr10\\+?",
            "aac\\d?", "ac3", "eac3", "e-ac3", "dd5", "ddp5", "ddp", "dts-hd", "dts-ma", "truehd",
            "\\d\\.\\dch", "\\d+kbps", "esubs?", "msubs?", "\\d+(?:\\.\\d+)? ?[mg]b", "full ?movie",
        ).joinToString("|") + """)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A dash, a colon or a bar, the long dashes written as escapes. */
    private val LEADING_SEPARATOR = Regex("^\\s*[-:|\\u2013\\u2014]")

    /**
     * A "[" after the title that opens on two words and holds no digit: "Night Train [ Arun Kumar,
     * Devi ...", the cast or the uploader's notes, which is a caption written as a file name. A
     * bracket with a number in it ("[1080p]", "[Part 1]") is left to the other rules.
     */
    private val CAST_BRACKET = Regex("""\[\s*(?=\p{L}{2,}\s+\p{L})(?![^\]]*\d)""")

    private val LANGUAGE_CODES = mapOf(
        "tamil" to "ta", "telugu" to "te", "hindi" to "hi", "malayalam" to "ml", "kannada" to "kn",
        "marathi" to "mr", "bengali" to "bn", "punjabi" to "pa", "english" to "en", "korean" to "ko",
        "japanese" to "ja", "chinese" to "zh", "spanish" to "es", "french" to "fr", "russian" to "ru",
        "german" to "de", "italian" to "it", "turkish" to "tr", "arabic" to "ar",
        // "Hindi-Japan": how a dubbed anime's release names its original audio.
        "japan" to "ja",
        // The short forms a multi-audio release lists: "Tam + Tel + Hin".
        "tam" to "ta", "tel" to "te", "hin" to "hi", "mal" to "ml", "eng" to "en",
    )

    private val LANGUAGE = Regex("""\b(""" + LANGUAGE_CODES.keys.joinToString("|") + """)\b""", RegexOption.IGNORE_CASE)

    /** The short forms of the languages channels write after a title: "Night Train Mal". */
    private val SHORT_LANGUAGE_WORDS = setOf("mal", "tam", "tel", "hin", "eng")

    private val SHORT_LANGUAGE_TAIL = Regex("""\s+#?(?:mal|tam|tel|hin|eng)$""", RegexOption.IGNORE_CASE)

    private val SHORT_LANGUAGE = Regex("""\s#?(?:mal|tam|tel|hin|eng)\b""", RegexOption.IGNORE_CASE)

    /** "20241080p", a year run into the resolution after it. */
    private val GLUED_YEAR = Regex("""(?<!\d)((?:19|20)\d{2})((?:480|576|720|1080|1440|2160)[pi])\b""", RegexOption.IGNORE_CASE)

    private val WHITESPACE = Regex("""\s+""")
    /** Includes the long dashes, written as escapes: "Show \u2014 E5" ends in one once cut. */
    private val TRIM_CHARS = charArrayOf('-', '\u2013', '\u2014', '(', ')', '[', ']', '{', '}', ',', ':', '_', '.', '|', '~')

    private const val ZWNJ = '\u200c'
    private const val ZWJ = '\u200d'

    /** An uploader's Telegram handle, wherever in the name they put it. "@CC" is one. */
    private val HANDLE = Regex("""@[A-Za-z0-9_]{2,}""")

    /**
     * The second words of a channel's handle ("@CC_MAL_...", "@cinema_company_..."), lower case.
     * Only these go with the first word, so "@CC_Harbour_Night" keeps "Harbour".
     */
    private val CHANNEL_WORDS = setOf(
        "all", "mal", "tam", "tel", "hin", "eng", "new", "movies", "movie", "hd", "hevc", "mob", "files", "hub",
        "club", "official", "world", "company", "cinema", "tv", "tg", "dub", "dubbed",
    )

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
     * Season and episode written together. A multi-episode file ("S01E01E02", "S01E01-E02") is
     * the first of them, which is the one it opens on, and its last ([ParsedName.lastEpisode]).
     */
    private val PAIRED_EPISODE = listOf(
        // "S01E01-06" as well, the range a season split in two files writes (not "S01E05-720p").
        Regex("""\bs(\d{1,2})[\s.-]?e(\d{1,3})(?:-?e(\d{1,3})|-(\d{1,3})(?![\dpPiI]))?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,2})x(\d{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\bseason\s?(\d{1,2})\s?episode\s?(\d{1,3})\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * The fansub form: "Show - 02", "Show - 1071", "Show - 02v2". Spaces on both sides of the dash
     * and at least two digits, which is how every group pads the number, so "Part - 2" stays a
     * title. A resolution never matches, because the number has to end the word, and a year is
     * turned away by [notAnEpisode].
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
     * A combined range of episodes in one file, "EP(29-32)", "Ep 29-32": the first and the last
     * ([ParsedName.lastEpisode]), the parenthesised form the "COMBINED" releases that post several
     * episodes together tend to write.
     */
    private val EPISODE_RANGE = Regex("""\bep(?:isode)?[\s-]?\(?\s*(\d{1,3})\s*-\s*(\d{1,3})\s*\)?""", RegexOption.IGNORE_CASE)

    /** "E05" with no word in front, which only [looseEpisode] reads. */
    private val BARE_EPISODE = Regex("""\be(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    /** The second code of a two-episode file, "-E02" or "&E02", just after the first. */
    private val EXTRA_EPISODE = Regex("""^\s*[-&+]?\s*e\d{1,3}\b""", RegexOption.IGNORE_CASE)

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
            "full ?movie", "\\d+(?:\\.\\d+)? ?[mg]b",
        ).joinToString("|") + """)\b""",
        RegexOption.IGNORE_CASE,
    )
}
