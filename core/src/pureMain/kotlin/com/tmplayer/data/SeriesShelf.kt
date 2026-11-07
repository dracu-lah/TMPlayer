package com.tmplayer.data

import com.tmplayer.platform.putIfMissing
import com.tmplayer.platform.sortedByKey
import com.tmplayer.i18n.L

/**
 * One episode of a series, with the numbers [MediaName] read off its name or its caption.
 *
 * [episode] is never null here: a file that names a season but no episode (a whole-season pack)
 * stays a loose file, since there is nowhere in an episode list to put it.
 *
 * [copies] is every file of this one episode, newest first: channels post the same episode in
 * several sizes. [item] is the first of them, the copy the list shows when nothing says which
 * one the viewer prefers; [SeriesShelf.pick] is how to choose.
 */
data class SeriesEpisode(
    val item: MediaItem,
    val season: Int,
    val episode: Int,
    val copies: List<MediaItem> = listOf(item),
    /**
     * Files of several episodes listed under an earlier one that hold this one too: the
     * "S01E01E02" file for episode two. Watching one counts as watching this episode.
     */
    val alsoIn: List<MediaItem> = emptyList(),
    /** Which half of a split episode this is (1 for "E06a", 2 for "E06b"); 0 for a whole episode. */
    val part: Int = 0,
) {
    /** Watched when any copy is, or any file of several episodes that holds it. */
    fun watched(finished: (MediaItem) -> Boolean): Boolean = copies.any(finished) || alsoIn.any(finished)

    /** "S01E02"; a release with no season of its own counts as season one. */
    val code: String
        get() = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}" + if (part > 0) ('a' + part - 1).toString() else ""

    /** The same episode, whichever copy each of the two stands for. */
    fun sameAs(other: SeriesEpisode): Boolean = season == other.season && episode == other.episode && part == other.part
}

/** One season's episodes, in watching order. */
data class SeriesSeason(val number: Int, val episodes: List<SeriesEpisode>)

/**
 * Every file in a chat that belongs to one show, grouped by season.
 *
 * [key] is what the files were grouped on and stays stable while pages load, so a list can key
 * the tile on it. [title] is the show's name as the first of its files wrote it.
 */
data class Series(val key: String, val title: String, val seasons: List<SeriesSeason>) {
    val episodes: List<SeriesEpisode> get() = seasons.flatMap { it.episodes }
    val episodeCount: Int get() = seasons.sumOf { it.episodes.size }

    /** The newest upload in the show, for the tile's picture: it is what was posted last. */
    val cover: MediaItem get() = episodes.maxBy { it.item.date }.item
}

/** One thing in a chat's listing once series are folded together: a show, or a file on its own. */
sealed interface ShelfEntry {
    val key: String

    data class Show(val series: Series) : ShelfEntry {
        override val key: String get() = "series-${series.key}"
    }

    data class File(val item: MediaItem) : ShelfEntry {
        override val key: String get() = "media-${item.id}"
    }
}

/** How far through a show the viewer is, for the tile's line and the episode list. */
data class SeriesProgress(
    /** Episodes on the Watched list. */
    val watched: Int,
    val total: Int,
    /**
     * Where to carry on from: the episode in progress, else the one after the furthest finished
     * episode, else the first. Null once every episode is watched.
     */
    val next: SeriesEpisode?,
    /**
     * The copy the viewer watched last, in progress or the furthest finished, or null for a show
     * not yet started. The next episode's copy is chosen to be like it: see [SeriesShelf.pick].
     */
    val like: MediaItem? = null,
) {
    val finished: Boolean get() = total > 0 && watched >= total
}

/**
 * Folds a chat's videos into one tile per show, and the copies of one film into one tile.
 *
 * Grouped on the cleaned title, lower-cased and stripped of punctuation, so "Harbour.Notes.S01E01"
 * and "Harbour Notes - 02" land together. A show needs [MIN_EPISODES] episodes before it earns a
 * tile of its own; a lone episode is clearer as the file it is. Copies of one episode (the same
 * episode posted at 350 MB and at 1.8 GB) are one episode with several [SeriesEpisode.copies].
 *
 * Once the chat's files have established a show, a file that names it less carefully joins it:
 * a bare "E05" the parser would not trust on its own, a caption titled in another script or by a
 * shorter name ("Family Full House Episode 5" for "Family Full House With Rohit Sharma").
 *
 * A name is a guess, so the name alone never folds two files together; the facts have to agree
 * too, as [SameVideo] weighs them: the same file, matching lengths, or a year with no length to
 * contradict it. A remake (the same title, another year) stays apart. A trailer, a teaser or a sample is an extra ([MediaItem.extra]): never folded in,
 * listed straight after its film. A film cut into "CD1" and "CD2" is one video with
 * [MediaItem.parts]. What the viewer corrected by hand ([VideoCorrections]) wins over all of it.
 *
 * Order follows the chat: a show sits where its newest file would have been, so the listing still
 * reads newest first and nothing jumps when the Series view is turned on.
 */
object SeriesShelf {

    const val MIN_EPISODES = 2

    /** A file at least this long is the feature a short file of the same name was posted beside. */
    const val FEATURE_SEC = 20 * 60

    /**
     * The parse a file is grouped by: the title the viewer gave it ([VideoCorrection.title]), else
     * its name first and its caption second ([MediaItem.parsedName], read once per item). With
     * [trustCaption] false the caption is left out: a line the chat puts under every post says
     * nothing about which video this one is.
     */
    fun episodeOf(
        item: MediaItem,
        corrections: Map<String, VideoCorrection> = VideoCorrections.current,
        trustCaption: Boolean = true,
    ): ParsedName {
        val renamed = corrections[item.id]?.title
        return when {
            renamed != null -> MediaName.parse(renamed)
            trustCaption -> item.parsedName
            else -> item.parsedNameAlone
        }
    }

    /**
     * Which files may be copies of [item], before any evidence is weighed ([SameVideo]): the same
     * film (title and year) or the same episode, a split film's pieces sharing one key. Null for a
     * file that may be nobody's copy: one the viewer set apart, an extra, or a name with nothing
     * to key on.
     */
    internal fun candidateKey(
        item: MediaItem,
        corrections: Map<String, VideoCorrection> = VideoCorrections.current,
        name: ParsedName = episodeOf(item, corrections),
    ): String? {
        if (corrections[item.id]?.separate == true || name.extra != null) return null
        // One byte piece of a cut video is no video at all, so never a copy of one; the video the
        // pieces make ([SplitPieces.join]) is keyed as any other.
        if (item.splitPiece?.certain == true && item.pieces.isEmpty()) return null
        filmKeyOf(item, name)?.let { return it }
        val key = foldAccents(name.titleKey).ifEmpty { return null }
        name.airDate?.let { return "date:$key|$it" }
        val episode = name.episode ?: return null
        return "episode:$key|${name.season ?: 1}|$episode"
    }

    /**
     * "harbour notes" for "Harbour.Notes", "Harbour Notes:" and "HARBOUR NOTES": the letters,
     * marks and digits of the title, lower-cased, each run of anything else one space, none at
     * either end. A scan rather than three regular expressions, since every build of Home keys
     * hundreds of names; a name with a character outside the basic plane (an emoji, a rare
     * script) still goes through the expressions, which read it as one character rather than two.
     */
    fun keyOf(title: String): String {
        val lower = title.lowercase()
        // Most names are a key already, or need nothing but lower case: no copy made for those.
        var key = true
        for (index in lower.indices) {
            val char = lower[index]
            if (char.isSurrogate()) return keyByPattern(lower)
            if (!isWordChar(char) && (char != ' ' || index == lower.lastIndex || index == 0 || !isWordChar(lower[index - 1]))) key = false
        }
        if (key) return lower
        val out = StringBuilder(lower.length)
        var gap = false
        for (char in lower) {
            if (isWordChar(char)) {
                if (gap && out.isNotEmpty()) out.append(' ')
                gap = false
                out.append(char)
            } else {
                gap = true
            }
        }
        return out.toString()
    }

    /**
     * [key] with the accents of Latin letters taken off, for the keys that fold copies of one film
     * ("Am\u00e9lie" and "Amelie" are one film in two names). Only film copies use it: a show's key
     * is stored on the device ([WatchLaterRecord]) and stays as it was.
     */
    internal fun foldAccents(key: String): String {
        if (key.all { it < '\u00c0' }) return key
        val out = StringBuilder(key.length)
        for (char in key) {
            when {
                char in '\u0300'..'\u036f' -> Unit
                char < '\u00c0' -> out.append(char)
                else -> out.append(LATIN_BASE[char] ?: char)
            }
        }
        return out.toString()
    }

    private val LATIN_BASE: Map<Char, Char> = buildMap {
        for ((base, accented) in listOf(
            'a' to "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u0101\u0103\u0105",
            'c' to "\u00e7\u0107\u010d",
            'd' to "\u010f\u0111",
            'e' to "\u00e8\u00e9\u00ea\u00eb\u0113\u0117\u0119\u011b",
            'g' to "\u011f",
            'i' to "\u00ec\u00ed\u00ee\u00ef\u012b\u0131",
            'l' to "\u0142",
            'n' to "\u00f1\u0144\u0148",
            'o' to "\u00f2\u00f3\u00f4\u00f5\u00f6\u00f8\u014d\u0151",
            'r' to "\u0159",
            's' to "\u015b\u0161\u015f\u00df",
            't' to "\u0165\u021b",
            'u' to "\u00f9\u00fa\u00fb\u00fc\u016b\u016f\u0171",
            'y' to "\u00fd\u00ff",
            'z' to "\u017a\u017c\u017e",
        )) for (c in accented) put(c, base)
    }

    private fun isWordChar(char: Char): Boolean = if (char < '\u0080') {
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
    } else when (char.category) {
        CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
        CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.COMBINING_SPACING_MARK,
        CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
        -> true
        else -> false
    }

    /** [keyOf] by its regular expressions, for a name the scan leaves to them. */
    private fun keyByPattern(lower: String): String = lower.replace(NOT_WORD, " ").trim().replace(SPACES, " ")

    /** One file that names an episode: the key it was read under, and the others it could go by. */
    private class Found(
        val item: MediaItem,
        val key: String,
        val name: ParsedName,
        val episode: Int,
        /** The file name's own title and the caption's, where they differ from [key]. */
        val alternates: List<String>,
    )

    /**
     * Where every file of a listing goes, worked out once for [arrange] and [foldFilmCopies].
     *
     * [shown] is what a file's tile stands for when that is not the file alone: the copy that leads
     * a folded film (with its [MediaItem.versions] and [MediaItem.parts]), or the file labelled as
     * an extra. [group] names the folded video a file belongs to, so it is listed once. [after]
     * holds the extras listed straight after a group or a file, and [attached] the extras so placed.
     */
    private class Plan(
        val showOf: Array<String?>,
        val shows: Map<String, Series>,
        val shown: Map<String, MediaItem>,
        val group: Map<String, String>,
        val after: Map<String, List<MediaItem>>,
        val attached: Set<String>,
    )

    /**
     * [items] as a chat's shelf: shows, folded films, extras after their films, the rest as files.
     *
     * @param corrections the viewer's own ([VideoCorrections.current] unless a caller holds them).
     * @param durationOf a file's length in seconds, 0 when unknown, for the evidence [SameVideo]
     *   weighs: the stated one by default, the player's learned one first where a caller has it.
     */
    fun arrange(
        listed: List<MediaItem>,
        corrections: Map<String, VideoCorrection> = VideoCorrections.current,
        durationOf: (MediaItem) -> Int = SameVideo.statedLength,
    ): List<ShelfEntry> {
        // A video cut into byte pieces is one video before anything else is decided about it.
        val items = SplitPieces.join(listed)
        val plan = plan(items, corrections, durationOf)
        val placed = HashSet<String>()
        val out = ArrayList<ShelfEntry>(items.size)
        items.forEachIndexed { index, item ->
            val show = plan.showOf[index]
            if (show != null) {
                if (placed.add("show:$show")) out += ShelfEntry.Show(plan.shows.getValue(show))
                return@forEachIndexed
            }
            emit(item, plan, placed) { out += ShelfEntry.File(it) }
        }
        return out
    }

    /**
     * One file's tile and the extras listed after it, unless it is an extra already listed after
     * its film, or a copy of a video already listed.
     */
    private inline fun emit(item: MediaItem, plan: Plan, placed: HashSet<String>, add: (MediaItem) -> Unit) {
        if (item.id in plan.attached) return
        val group = plan.group[item.id] ?: item.id
        if (!placed.add("video:$group")) return
        add(plan.shown[item.id] ?: item)
        plan.after[group]?.forEach { extra -> if (placed.add("extra:${extra.id}")) add(extra) }
    }

    private fun plan(
        items: List<MediaItem>,
        corrections: Map<String, VideoCorrection>,
        durationOf: (MediaItem) -> Int,
    ): Plan {
        // A caption line the chat puts under most of its posts says nothing about which video a
        // post is, so a file whose caption opens with one is read by its name alone.
        val repeated = MediaText.repeatedCaptionLines(items)
        val trusted = items.map { item -> repeated.isEmpty() || firstLine(item)?.lowercase()?.let { it !in repeated } != false }
        val names = items.mapIndexed { index, item -> episodeOf(item, corrections, trusted[index]) }
        val separate = items.map { corrections[it.id]?.separate == true }
        val strict = items.indices.map { index ->
            val item = items[index]
            val name = names[index]
            val key = name.titleKey
            val number = name.episode
            if (key.isEmpty() || number == null || name.extra != null) {
                null
            } else {
                val alternates = if (corrections[item.id]?.title != null || !trusted[index]) emptyList() else alternatesOf(item, key)
                Found(item, key, name, number, alternates)
            }
        }
        // A show is two episodes or more, not two files: three copies of one episode are one.
        val showKeys = strict.filterNotNull()
            .groupBy { it.key }
            .filterValues { files -> files.distinctBy { (it.name.season ?: 1) to it.episode }.size >= MIN_EPISODES }
            .keys

        // The same season uploaded twice under two names, "Kerala Crime Files" and "Kerala Crime
        // Files Shiju Parayil Veedu Neendakara", is one show: the longer name joins the shorter
        // when it starts with it and most of its episodes are ones the shorter already has. A
        // sequel under a longer name has episodes of its own and stays apart.
        val numbersOf = strict.filterNotNull().groupBy { it.key }
            .mapValues { (_, files) -> files.mapTo(HashSet()) { (it.name.season ?: 1) to it.episode } }
        val alias = showKeys.associateWith { key ->
            val mine = numbersOf[key].orEmpty()
            showKeys.filter { other -> other != key && key.startsWith("$other ") && other.split(' ').size >= 2 }
                .maxByOrNull { it.length }
                ?.takeIf { other -> mine.count { it in numbersOf[other].orEmpty() } * 2 >= mine.size }
                ?: key
        }

        // Everything else gets a second look, now that the chat has said which shows it holds. A
        // file the viewer renamed, or one that is a trailer, is read only as it now reads.
        val placedUnder = items.indices.map { index ->
            val readLoosely = corrections[items[index].id]?.title == null && names[index].extra == null && trusted[index]
            val found = strict[index] ?: if (readLoosely && showKeys.isNotEmpty()) loose(items[index]) else null
            when {
                found == null -> null
                found.key in showKeys -> alias.getValue(found.key).let { key ->
                    if (key == found.key) found else Found(found.item, key, found.name, found.episode, emptyList())
                }
                else -> joining(found, showKeys)?.let { key -> Found(found.item, alias.getValue(key), found.name, found.episode, emptyList()) }
            }
        }
        val shows = placedUnder.filterNotNull()
            .groupBy { it.key }
            .mapValues { (key, files) -> series(key, files, separate = { corrections[it.id]?.separate == true }, durationOf) }
        val showOf = Array(items.size) { placedUnder[it]?.key }

        // What is left over and names a film, or one episode of a show the chat has no more of:
        // copies of it (a 720p and a 1080p of Loki S02E05) fold into one tile, where the evidence
        // says they are copies.
        val loose = items.indices.filter { showOf[it] == null }
        val shown = HashMap<String, MediaItem>()
        val group = HashMap<String, String>()
        loose.mapNotNull { index -> candidateKey(items[index], corrections, names[index])?.let { it to index } }
            .groupBy({ it.first }, { it.second })
            .forEach { (key, members) ->
                // An episode's code or its air date says which one it is as surely as a year
                // says which film.
                val dated = names[members.first()].year != null || key.startsWith("episode:") || key.startsWith("date:")
                SameVideo.videosOf(members.map { items[it] to names[it] }, dated, durationOf).forEachIndexed { n, video ->
                    if (video.members.size < 2) return@forEachIndexed
                    video.members.forEach { member ->
                        group[member.id] = "$key#$n"
                        shown[member.id] = video.lead
                    }
                }
            }

        // Extras: a short file beside a feature of the same name, labelled by its name ("Trailer")
        // or as an extra, listed straight after the first feature of that name, never folded. A
        // name that says "Trailer" needs something to back it (a feature beside it, a short length
        // or a small size), so a film titled "The Sample Room" stays a film; a short with a year of
        // its own and no such word is a short film, not an extra.
        fun titleKey(index: Int) = names[index].titleKey
        val featureOf = HashMap<String, String>()
        val featureYear = HashMap<String, Int?>()
        for (index in loose) {
            val item = items[index]
            if (names[index].extra != null || strict[index] != null) continue
            if (!isFeature(shown[item.id] ?: item, durationOf)) continue
            val key = titleKey(index).ifEmpty { continue }
            if (featureOf.putIfMissing(key, group[item.id] ?: item.id) == null) featureYear[key] = names[index].year
        }
        val after = HashMap<String, MutableList<MediaItem>>()
        val attached = HashSet<String>()
        for (index in loose) {
            val item = items[index]
            if (item.id in group || separate[index] || strict[index] != null) continue
            val length = durationOf(item)
            val short = length in 1 until SameVideo.SHORT_SEC
            val key = titleKey(index)
            val feature = key.takeIf { it.isNotEmpty() }?.let { featureOf[it] }
            val small = item.sizeBytes in 1 until SameVideo.FEATURE_BYTES
            val word = names[index].extra?.takeIf { feature != null || short || small }
            val year = names[index].year
            val sameFilm = year == null || year == featureYear[key]
            val kind = word ?: ExtraKind.Extra.takeIf { short && feature != null && sameFilm } ?: continue
            val labelled = item.copy(extra = kind).keptReads(item)
            shown[item.id] = labelled
            if (feature != null && feature != item.id) {
                after.getOrPut(feature) { ArrayList() } += labelled
                attached += item.id
            }
        }
        return Plan(showOf, shows, shown, group, after, attached)
    }

    private fun firstLine(item: MediaItem): String? =
        item.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }


    /** Long enough to be the film a trailer is posted beside, or, with no length stated, big enough. */
    private fun isFeature(item: MediaItem, durationOf: (MediaItem) -> Int): Boolean {
        val pieces = item.parts.ifEmpty { listOf(item) }.map(durationOf)
        val length = if (pieces.any { it <= 0 }) 0 else pieces.sum()
        return if (length > 0) length >= FEATURE_SEC else item.sizeBytes >= SameVideo.FEATURE_BYTES
    }

    /**
     * [items] with the copies of each film folded into one, and nothing else touched: what a
     * chat's grid lists. Episodes stay one tile apiece, in the chat's order, since a chat lists
     * what was posted; only a film posted five times over (720p, 1080p, HEVC, a small x264) is one
     * tile, its [MediaItem.versions] the copies, newest first. It sits where its newest copy was.
     * A split film is one tile too, and an extra is labelled and follows its film, as in [arrange].
     */
    fun foldFilmCopies(
        listed: List<MediaItem>,
        corrections: Map<String, VideoCorrection> = VideoCorrections.current,
        durationOf: (MediaItem) -> Int = SameVideo.statedLength,
    ): List<MediaItem> {
        val items = SplitPieces.join(listed)
        val plan = plan(items, corrections, durationOf)
        if (plan.shown.isEmpty()) return items
        val placed = HashSet<String>()
        val out = ArrayList<MediaItem>(items.size)
        items.forEachIndexed { index, item ->
            // An episode of a show stays the file it is here; only the shelf folds shows.
            if (plan.showOf[index] != null) out += item else emit(item, plan, placed) { out += it }
        }
        return out
    }

    /**
     * A candidate key only: files that might be copies of each other share it (the same film by
     * title and year, the same episode, the same air date), whatever their size or quality. It is
     * not the answer. Whether two files are one video takes evidence a file read alone cannot give
     * (lengths, the same file forwarded), which [SameVideo.group] weighs over a whole listing, and
     * two files with one key can be two videos there. Key nothing stored on it: saved data stays
     * per message.
     *
     * Null for a video that names nothing to key on, which is never a copy of anything, for one
     * the viewer set apart ([VideoCorrection.separate]), and for a trailer or a sample. A piece of
     * a split film carries its number ("night train|2019|part2"), since each piece plays on its own.
     */
    fun sameVideoKey(item: MediaItem): String? {
        val corrections = VideoCorrections.current
        // Asked for every tile on every build of Home: kept on the item while the viewer has not
        // corrected it.
        if (corrections.isEmpty() || item.id !in corrections) return item.uncorrectedCopyKey
        return copyKeyOf(item, corrections)
    }

    /** [sameVideoKey] with none of the viewer's corrections, which [MediaItem] keeps. */
    internal fun uncorrectedCopyKey(item: MediaItem): String? = copyKeyOf(item, emptyMap())

    private fun copyKeyOf(item: MediaItem, corrections: Map<String, VideoCorrection>): String? {
        val name = episodeOf(item, corrections)
        val key = candidateKey(item, corrections, name) ?: return null
        return if (name.part != null) "$key|part${name.part}" else key
    }

    /**
     * The film's own name for a tile that stands for several copies: "Night Train (2019)" rather
     * than whichever file name happened to be newest. The item's title when the name reads as nothing.
     */
    fun filmTitle(item: MediaItem): String {
        val name = episodeOf(item)
        if (name.title.isBlank()) return item.title
        return if (name.year != null) "${name.title} (${name.year})" else name.title
    }

    /**
     * The name a video's tile goes by, wherever a tile is drawn: a chat's grid, Home, Continue
     * watching, Watched and Watch later. The viewer's own title where they gave one, the film's
     * name for a tile of several copies or pieces ([filmTitle]), the cleaned file name for a
     * release ([MediaName.tileTitle]), and the title as it is for a video named by its caption,
     * since a person wrote that and nothing in it is a release tag. An extra says what it is:
     * "Night Train (2019) · Trailer".
     *
     * Works on an item rebuilt from a saved record as well (`ResumeRecord.toMediaItem()` and the
     * like), whose file name is the title that was stored, so a record written before this existed
     * reads as cleanly as a fresh one and nothing stored changes.
     */
    fun tileTitle(item: MediaItem): String {
        val renamed = VideoCorrections.current[item.id]?.title
        val title = when {
            renamed != null -> renamed
            item.versions.size > 1 || item.parts.size > 1 || item.isJoined -> filmTitle(item)
            item.fileName.isBlank() || item.genericFileName -> item.title
            else -> item.fileTileTitle ?: item.title
        }
        val extra = item.extra ?: return title
        return L.messages.gridExtraTitle(title, extraLabel(extra))
    }

    /** "Trailer", "Sample": what an extra is, in the viewer's language. */
    fun extraLabel(kind: ExtraKind): String = when (kind) {
        ExtraKind.Trailer -> L.gridExtraTrailer
        ExtraKind.Teaser -> L.gridExtraTeaser
        ExtraKind.Promo -> L.gridExtraPromo
        ExtraKind.Sample -> L.gridExtraSample
        ExtraKind.Extra -> L.gridExtraOther
    }

    /**
     * Whether [item] is a film, which a grid draws as a 2:3 poster card: several copies or pieces of
     * one film, or a release name (or the viewer's own title) that states a year and no episode.
     * Everything else, a clip, a lecture, a screen recording, a trailer or a file named by its
     * caption, is a video and stays a wide card that shows what the file is.
     */
    fun isFilm(item: MediaItem): Boolean {
        if (item.extra != null) return false
        if (item.versions.size > 1 || item.parts.size > 1) return true
        val renamed = VideoCorrections.current[item.id]?.title
        if (renamed == null && (item.fileName.isBlank() || item.genericFileName)) return false
        // The item's own reading where the name is its file's: Home sorts and folds by this, and
        // reading the name afresh each time held a TV stick's main thread (1.28.0).
        val name = if (renamed != null) MediaName.parse(renamed) else item.parsedName
        return !name.isSeries && name.extra == null && name.year != null && name.title.isNotBlank()
    }

    /** Whether [entry] is drawn as a poster: a show, or a film ([isFilm]). */
    fun isPoster(entry: ShelfEntry): Boolean = when (entry) {
        is ShelfEntry.Show -> true
        is ShelfEntry.File -> isFilm(entry.item)
    }

    /**
     * The keys of [entries] drawn as 2:3 posters: all of them. A listing keeps the chat's own order
     * and is never split into films and other videos, since release names vary too much for that
     * split to be trusted: a film named without a year, or written up like a video's description,
     * would land among the clips. So every card is the same portrait shape, with the film's poster
     * where one is found and the video's own frame or cover otherwise, and a row never mixes a
     * tall card with a short one.
     */
    fun posterKeys(entries: List<ShelfEntry>): Set<String> = entries.mapTo(HashSet()) { it.key }

    /**
     * A film poster's name, without the year ("Gladiator II"), which its line of facts carries
     * instead; [tileTitle] where the name reads as nothing.
     */
    fun posterTitle(item: MediaItem): String =
        episodeOf(item).title.ifBlank { tileTitle(item) }

    /**
     * What makes two files the same film: the title read off the name, and the year. A file names
     * a film only when it carries a year, a quality marker (1080p, x265), or a title specific
     * enough on its own (two words or more), so two clips that share a plain single-word name
     * ("Lecture", "Video") are never taken for copies of one another, while two re-posts of the
     * same multi-word title fold even when neither carries a year (a channel handle prefixed to
     * the name instead, HX45).
     */
    private fun filmKeyOf(item: MediaItem, name: ParsedName): String? {
        if (name.isSeries) return null
        val key = foldAccents(name.titleKey)
        if (key.isEmpty()) return null
        if (name.year == null && item.nameMarkers.isEmpty() && (item.genericFileName || ' ' !in key)) return null
        return "$key|${name.year ?: ""}"
    }

    /**
     * A file the parser would not call an episode on its own, read the lenient way: "Show E05",
     * "Show, E5. Vlog", which only counts once [joining] finds the show it names.
     */
    private fun loose(item: MediaItem): Found? {
        // Most files carry no bare "E05" at all, and finding that out is cheaper than a parse.
        if (!item.mayNameLooseEpisode) return null
        val caption = item.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val name = listOfNotNull(item.nameForParsing.ifBlank { null }, caption)
            .firstNotNullOfOrNull { MediaName.looseEpisode(it) }
            ?: return null
        val key = name.titleKey
        val number = name.episode ?: return null
        if (key.isEmpty()) return null
        return Found(item, key, name, number, alternatesOf(item, key))
    }

    /**
     * The established show [found] belongs to, or null. Its own key or one of its alternates,
     * exactly; else the one show whose name starts with it, for a caption that shortens the name.
     * A shortened name has to keep two words at least, so "Harbour" never swallows a file into
     * "Harbour Notes".
     */
    private fun joining(found: Found, showKeys: Set<String>): String? {
        val keys = listOf(found.key) + found.alternates
        keys.firstOrNull { it in showKeys }?.let { return it }
        return keys.asSequence()
            .filter { it.split(' ').size >= 2 }
            .mapNotNull { short -> showKeys.filter { it.startsWith("$short ") }.singleOrNull() }
            .firstOrNull()
    }

    /** Whether [item]'s name or its caption's first line holds something [MediaName.looseEpisode] could read. */
    internal fun mayBeLoose(item: MediaItem): Boolean {
        if (MAYBE_LOOSE.containsMatchIn(item.nameForParsing)) return true
        val caption = item.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return caption != null && MAYBE_LOOSE.containsMatchIn(caption)
    }

    /** The keys the file name alone and the caption alone would give, other than [key]. */
    private fun alternatesOf(item: MediaItem, key: String): List<String> =
        item.alternateKeys.filter { it.isNotEmpty() && it != key }.distinct()

    /** Every key [alternatesOf] picks from, read once per item ([MediaItem.alternateKeys]). */
    internal fun alternateKeysOf(item: MediaItem): List<String> {
        val caption = item.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        // Without a caption the file name alone gave the key already.
        if (caption == null) return emptyList()
        return listOfNotNull(
            item.fileName.ifBlank { null }?.let { if (it == item.nameForParsing) item.parsedNameAlone.title else MediaName.parse(it).title },
            MediaName.parse(caption).title,
        ).map(::keyOf)
    }

    private fun series(key: String, files: List<Found>, separate: (MediaItem) -> Boolean, durationOf: (MediaItem) -> Int): Series {
        // A release with no season (the fansub form, a bare "Ep 04") is season one, unless every
        // file that does name a season names the same one.
        val named = files.mapNotNull { it.name.season }.distinct()
        val defaultSeason = named.singleOrNull() ?: 1
        val episodes = files
            // A split episode's halves ("E06a", "E06b") are rows of their own: they are not copies.
            .groupBy { Triple(it.name.season ?: defaultSeason, it.episode, it.name.episodePart ?: 0) }
            .flatMap { (number, copies) ->
                // Copies of one episode (720p and 1080p) under one row, the newer first; a copy
                // whose length says it is something else, or that the viewer set apart, a row of
                // its own under the same number.
                val (apart, rest) = copies.partition { separate(it.item) }
                val rows = SameVideo.videosOf(rest.map { it.item to it.name }, dated = true, durationOf).map { it.members } +
                    apart.map { listOf(it.item) }
                rows.filter { it.isNotEmpty() }.map { members ->
                    val sorted = members.sortedWith(compareByDescending<MediaItem> { it.date }.thenBy { it.messageId })
                    SeriesEpisode(sorted.first(), number.first, number.second, sorted, part = number.third)
                }
            }
            .let { rows -> withMultiEpisodeFiles(rows, files, defaultSeason) }
        val seasons = episodes
            .groupBy { it.season }
            .sortedByKey()
            .map { (number, list) -> SeriesSeason(number, list.sortedWith(compareBy({ it.episode }, { it.part }))) }
        // "harbour_notes_720p" is the same show as "Harbour.Notes", but the second is how to write it.
        // A file that joined under another name is a last resort for the title.
        // Each title keyed once, not once per comparison of a sort.
        val (own, joined) = files.map { it.name.title }.partition { keyOf(it) == key }
        val title = own.firstOrNull { it != it.lowercase() }
            ?: own.firstOrNull()
            ?: joined.first()
        return Series(key = key, title = title, seasons = seasons)
    }


    /** Each file of several episodes, named as a holder of the later ones it holds. */
    private fun withMultiEpisodeFiles(rows: List<SeriesEpisode>, files: List<Found>, defaultSeason: Int): List<SeriesEpisode> {
        val multi = files.filter { (it.name.lastEpisode ?: 0) > it.episode }
        if (multi.isEmpty()) return rows
        return rows.map { row ->
            val holders = multi.filter { file ->
                (file.name.season ?: defaultSeason) == row.season && row.episode in (file.episode + 1)..file.name.lastEpisode!!
            }.map { it.item }.filter { holder -> row.copies.none { it.id == holder.id } }
            if (holders.isEmpty()) row else row.copy(alsoIn = holders)
        }
    }

    /**
     * Where the viewer is in [series]. An episode counts as watched when any copy of it is, and
     * as started when any copy is partway.
     *
     * @param finished whether an item is on the Watched list.
     * @param position how far into an item the viewer got, 0 to 1, or 0 when never started.
     */
    fun progress(
        series: Series,
        finished: (MediaItem) -> Boolean,
        position: (MediaItem) -> Float,
    ): SeriesProgress {
        val all = series.episodes
        val done = all.map { episode -> episode.watched(finished) }
        val watched = done.count { it }
        fun started(index: Int) = all[index].copies.firstOrNull { !finished(it) && position(it) > 0f }
        // Partway through one beats everything: it is literally where the viewer stopped.
        val inProgress = all.indices.lastOrNull { !done[it] && started(it) != null }
        val furthest = done.lastIndexOf(true)
        val like = inProgress?.let(::started)
            ?: all.getOrNull(furthest)?.copies?.firstOrNull(finished)
        val next = when {
            inProgress != null -> all[inProgress]
            else -> (furthest + 1 until all.size).firstOrNull { !done[it] }?.let { all[it] }
                ?: all.indices.firstOrNull { !done[it] }?.let { all[it] }
        }
        return SeriesProgress(
            watched = watched,
            total = all.size,
            next = next?.let { it.copy(item = pick(it, finished, position, like)) },
            like = like,
        )
    }

    /**
     * Which copy of [episode] to play: the one partway through, else the one most like [like]
     * (the copy the viewer watched last), else the newest.
     */
    fun pick(
        episode: SeriesEpisode,
        finished: (MediaItem) -> Boolean,
        position: (MediaItem) -> Float,
        like: MediaItem?,
    ): MediaItem {
        if (episode.copies.size <= 1) return episode.item
        episode.copies.firstOrNull { !finished(it) && position(it) > 0f }?.let { return it }
        return like?.let { EpisodeCopies.closest(it, episode.copies) } ?: episode.item
    }

    private val NOT_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")

    /** Something [MediaName.looseEpisode] could read: an "E" and a number, not inside a word. */
    private val MAYBE_LOOSE = Regex("""(?<![a-z])e\d{1,3}(?!\d)""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
}
