package com.tmplayer.data

/**
 * The fallback for a show named in a way no rule knows: files of one chat that differ only by one
 * number are the episodes of one show. "Harbour Notes 06", "Harbour Notes #07", "Harbour Notes
 * Chapter 8", "E09 Harbour Notes", "06 - Harbour Notes S2" carry no code [MediaName] trusts on its
 * own (a bare number is too easy to find inside a title), but three or more of them with the same
 * words around a changing number are a listing, which is how a person reads it too.
 *
 * It only looks at files that read as no episode, no film (a year makes a film, so sequels
 * "Harbour Run 2 (2021)" stay films), no extra and no date. A group needs [MIN_FILES] different
 * numbers that sit close together, so "Harbour Run 2", "Harbour Run 9" and "Harbour Run 40" are not
 * one. Everything read here is a guess made from names alone: the viewer's own corrections and a
 * caption-free read still win ([eligible]).
 */
internal object SiblingEpisodes {

    const val MIN_FILES = 3
    private const val MAX_SLOTS = 4
    private const val MAX_NAME = 400

    /** A number of up to three digits that is a word of its own, with the word that says what it counts in front. */
    private val SLOT = Regex(
        """(?<![\p{L}\p{N}.])((?:ep(?:isode|i|s)?|e|chapter|ch|part|pt|#)[\s.-]?)?(\d{1,3})(?![\p{L}\p{N}]|\.\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING = Regex(
        """^\s*(?:(?:ep(?:isode|i|s)?|e|chapter|ch|#)[\s.-]?)?(\d{1,3})(?![\p{L}\p{N}]|\.\d)[\s:|)\-]*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SEPARATORS_TAIL = Regex("""[\s#:|(\[\-]+$""")
    private val WHITESPACE = Regex("""\s+""")
    private val SEASON_TAIL = Regex("""\b(?:season|series|seasons|s)\s*$""", RegexOption.IGNORE_CASE)

    /** Stems a camera, a messenger or a file manager numbers its files by: a run of them is no show. */
    private val NOT_A_SHOW = setOf(
        "img", "vid", "pxl", "mov", "mvi", "dsc", "dscn", "mah", "gopr", "video", "videos", "clip", "clips", "movie", "movies",
        "file", "files", "recording", "screen recording", "screenrecording", "screenshot", "untitled", "new video", "whatsapp video",
        "telegram video", "video clip", "voice", "audio", "track", "sample", "test", "download", "downloads", "document", "doc",
    )

    /** A lesson is a course's: those files are stepped by the course reading, not made episodes of a show. */
    private val LESSON_WORD = Regex("""\b(?:lecture|lec|lesson|class|module|unit|session|tutorial|lab|assignment|week|day)\b""", RegexOption.IGNORE_CASE)

    private class Cand(val index: Int, val number: Int, val title: String, val season: Int?)

    /**
     * [names] with the title, season and episode filled in for the files that [eligible] and a
     * group of siblings say are episodes. The same list when nothing was found.
     */
    fun apply(items: List<MediaItem>, names: List<ParsedName>, eligible: (Int) -> Boolean): List<ParsedName> {
        val groups = HashMap<String, MutableList<Cand>>()
        for (index in items.indices) {
            if (!eligible(index)) continue
            val name = names[index]
            if (name.episode != null || name.extra != null || name.airDate != null || name.year != null ||
                name.seasonEnd != null || name.part != null
            ) {
                continue
            }
            val raw = items[index].fileName.ifBlank { items[index].title }
            if (raw.isBlank() || raw.length > MAX_NAME) continue
            val text = spaced(raw)
            if (LESSON_WORD.containsMatchIn(text)) continue
            for (cand in candidates(index, text, name)) {
                groups.getOrPut(SeriesShelf.keyOf(cand.title)) { ArrayList() } += cand
            }
        }
        if (groups.isEmpty()) return names
        val taken = HashSet<Int>()
        var out: MutableList<ParsedName>? = null
        for ((key, members) in groups.entries.sortedByDescending { it.value.distinctBy { c -> c.number }.size }) {
            if (key.length < 3 || !key.any { it.isLetter() } || key in NOT_A_SHOW) continue
            val numbers = members.map { it.number }.distinct().sorted()
            if (numbers.size < MIN_FILES || numbers.last() - numbers.first() + 1 > numbers.size * 2 + 2) continue
            for (cand in members) {
                if (!taken.add(cand.index)) continue
                val list = out ?: names.toMutableList().also { out = it }
                list[cand.index] = names[cand.index].copy(title = cand.title, episode = cand.number, season = cand.season ?: names[cand.index].season)
            }
        }
        return out ?: names
    }

    /** What each way of reading one number of [text] as the episode would make of the name. */
    private fun candidates(index: Int, text: String, name: ParsedName): List<Cand> {
        val found = ArrayList<Cand>(2)
        // "06 - Harbour Notes S2": the number first, the name after it.
        LEADING.find(text)?.let { lead ->
            val number = lead.groupValues[1].toIntOrNull()
            val rest = MediaName.parse(lead.groupValues[2])
            if (number != null && rest.title.isNotBlank()) found += Cand(index, number, rest.title, rest.season ?: name.season)
        }
        // A name that already says a season and no episode is a season pack ("Show 5 Season 2 Complete"):
        // its numbers count shows and seasons, not episodes.
        if (name.season != null) return found
        // "Harbour Notes - 06", "Harbour Notes #06", "Harbour Notes E06 240315": the name, then the number.
        var slots = 0
        for (match in SLOT.findAll(text)) {
            if (slots++ >= MAX_SLOTS) break
            if (match.range.first == 0) continue
            val number = match.groupValues[2].toIntOrNull() ?: continue
            val before = text.substring(0, match.range.first).replace(SEPARATORS_TAIL, "")
            // "Season 2" and "S 2" count seasons, not episodes.
            if (before.isEmpty() || SEASON_TAIL.containsMatchIn(before)) continue
            val title = MediaName.parse(before).title
            if (title.isNotBlank()) found += Cand(index, number, title, name.season)
        }
        return found
    }

    /** The dot of an audio layout, "5.1" or "2.0": one digit on each side. "E01.240315" is not one. */
    private fun channelDot(text: String, at: Int): Boolean =
        at > 0 && at < text.lastIndex && text[at - 1].isDigit() && text[at + 1].isDigit() &&
            (at < 2 || !text[at - 2].isDigit()) && (at + 2 > text.lastIndex || !text[at + 2].isDigit())

    /** Dots and underscores between words are spaces; the dot of "5.1" stays. */
    private fun spaced(raw: String): String {
        val dot = raw.lastIndexOf('.')
        val base = if (dot > 0 && raw.length - dot <= 5) raw.substring(0, dot) else raw
        val out = StringBuilder(base.length)
        for (i in base.indices) {
            val c = base[i]
            out.append(
                when {
                    c == '_' -> ' '
                    c == '.' && !channelDot(base, i) -> ' '
                    else -> c
                },
            )
        }
        return out.toString().replace(WHITESPACE, " ").trim()
    }
}
