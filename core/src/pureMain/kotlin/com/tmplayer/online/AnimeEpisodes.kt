package com.tmplayer.online

/**
 * A long anime's episodes, numbered the way its files are: straight through ("Show - 123", the
 * absolute number), laid over the places TMDB gives them (season 6, episode 12) and the titles,
 * stills and dates the providers hold. Pure: the providers' answers go in as [SourceList]s, one
 * guide comes out ([merge]), so the same rules run on the phone, the television, the desktop and
 * the web, and a test can feed them fixtures.
 *
 * Why it exists (T11): AniList lists a whole run as one entry and names none of its episodes, and
 * TMDB lists the same run in 21 seasons, so neither alone answers "what is episode 123?". The
 * sources, in the order they are asked and the order a title is taken in:
 *
 *  1. TMDB's episode group of type "Absolute order", where it has one (each episode with its place),
 *  2. TMDB's seasons counted in order (season 1's episodes, then season 2's, ...),
 *  3. Kitsu's episodes, found by the MyAnimeList id (no key),
 *  4. Jikan's (MyAnimeList's) titles and dates,
 *  5. AniList's `streamingEpisodes` (a title and a picture, numbered in the title's words).
 *
 * The first source with a real title gives the title; the picture, the overview and the date each
 * come from the first source that has one, and the guide says which source gave each episode.
 */
object AnimeEpisodes {

    enum class Source(val label: String) {
        TmdbGroup("TMDB"),
        TmdbSeasons("TMDB"),
        Kitsu("Kitsu"),
        Jikan("MyAnimeList"),
        AniList("AniList"),
    }

    /** One source's word on one episode, numbered absolutely; [season] and [episode] are TMDB's place for it, where known. */
    data class Piece(
        val absolute: Int,
        val name: String = "",
        val overview: String = "",
        val stillUrl: String? = null,
        val airDate: String? = null,
        val runtimeMin: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    /** What one source knows, in the order the source gave it. */
    data class SourceList(val source: Source, val pieces: List<Piece>)

    /** One episode as the guide answers it. [source] gave the name, [stillSource] the picture. */
    data class Entry(
        val absolute: Int,
        /** TMDB's season and the episode's number in it, null where TMDB has no place for it. */
        val season: Int?,
        val episode: Int?,
        val name: String,
        val overview: String,
        val stillUrl: String?,
        val airDate: String?,
        val runtimeMin: Int?,
        val source: Source?,
        val stillSource: Source?,
    )

    /**
     * The merged answer for one show. [layout] is TMDB's seasons counted in order (empty where TMDB
     * does not know the show); [next] is the next episode to air, numbered absolutely; [declared] is
     * how many episodes AniList says the run has (zero where it says none yet).
     */
    class Guide(val entries: List<Entry>, val layout: List<MetaSeason>, val next: MetaAiring?, val declared: Int = 0) {
        private val byAbsolute: Map<Int, Entry> = entries.associateBy { it.absolute }
        private val byPlace: Map<Long, Int> = entries.asSequence()
            .filter { it.season != null && it.episode != null }
            .associate { place(it.season!!, it.episode!!) to it.absolute }

        /** The last absolute number: the last listed, the run's declared count, or the one before the next to air. */
        val total: Int get() = maxOf(entries.maxOfOrNull { it.absolute } ?: 0, declared.coerceIn(0, MAX_EPISODES), (next?.episode ?: 1) - 1)

        val isEmpty: Boolean get() = entries.isEmpty() && layout.isEmpty() && total <= 0

        fun entry(absolute: Int): Entry? = byAbsolute[absolute]

        /**
         * The absolute number of episode [episode] of [season], however the file counted: where TMDB
         * places it (a file "S06E12"), else by counting [layout]'s seasons, else the number itself
         * as one counted straight through ("S01E123" in a season of 32 is episode 123). Null when
         * nothing says: a second season of a show no list breaks into seasons, or a number past the end.
         */
        fun absoluteOf(season: Int, episode: Int): Int? {
            if (season < 1 || episode < 1 || episode > MAX_NUMBER) return null
            byPlace[place(season, episode)]?.let { return it }
            val count = layout.firstOrNull { it.number == season }?.episodeCount ?: 0
            if (count > 0 && episode <= count) {
                return offset(layout, season) + episode
            }
            val straight = season == 1 || count > 0
            if (!straight) return null
            return episode.takeIf { it <= maxOf(total, 1) || total == 0 }
        }

        /** TMDB's season and episode for [absolute]: its own place, else counted through [layout]; null when it has none. */
        fun locate(absolute: Int): Pair<Int, Int>? {
            byAbsolute[absolute]?.let { e -> if (e.season != null && e.episode != null) return e.season to e.episode }
            return locate(layout, absolute)
        }

        /** The numbers [season] holds, [absolute] counted: used to list a season whose files are numbered per season. */
        fun seasonEntries(season: Int): List<Entry> = entries.filter { it.season == season }
    }

    /** More seasons than any show has; the rest of a garbage answer is dropped. */
    const val MAX_SEASONS = 200

    /** The longest list taken from one source: One Piece has about a thousand and five hundred. */
    const val MAX_EPISODES = 3000

    /** The largest episode number taken; an absurd one is dropped. */
    const val MAX_NUMBER = 9999

    private const val MAX_NAME = 300
    private const val MAX_OVERVIEW = 4000

    private fun place(season: Int, episode: Int): Long = season.toLong() * 100_000 + episode

    /** TMDB's seasons, specials left out, in order, each with a count: the layout episodes are counted through. */
    fun layout(seasons: List<MetaSeason>): List<MetaSeason> =
        seasons.asSequence()
            .filter { it.number in 1..MAX_SEASONS && it.episodeCount in 1..MAX_EPISODES }
            .take(MAX_SEASONS * 2)
            .distinctBy { it.number }
            .sortedBy { it.number }
            .take(MAX_SEASONS)
            .toList()

    private fun offset(layout: List<MetaSeason>, season: Int): Int = layout.takeWhile { it.number < season }.sumOf { it.episodeCount }

    private fun locate(layout: List<MetaSeason>, absolute: Int): Pair<Int, Int>? {
        if (absolute < 1) return null
        var left = absolute
        for (s in layout) {
            if (left <= s.episodeCount) return s.number to left
            left -= s.episodeCount
        }
        return null
    }

    // ---- the sources, each turned into pieces ------------------------------------------------

    /**
     * TMDB's "Absolute order" episode group: each episode in the order the groups and the episodes
     * in them give ([groupOrder], [order]), numbered by its place in that run.
     */
    data class GroupEpisode(val groupOrder: Int, val order: Int, val season: Int, val episode: Int, val piece: Piece)

    fun fromGroup(episodes: List<GroupEpisode>): SourceList {
        val sorted = episodes.asSequence().take(MAX_EPISODES * 2).sortedWith(compareBy({ it.groupOrder }, { it.order })).toList()
        val pieces = sorted.take(MAX_EPISODES).mapIndexed { i, e -> e.piece.copy(absolute = i + 1, season = e.season, episode = e.episode) }
        return SourceList(Source.TmdbGroup, pieces)
    }

    /**
     * TMDB's seasons counted in order: season 1's episodes numbered from 1, season 2's carrying on
     * after the last number of season 1 (its count, else its highest episode). [seasons] is each
     * season's episodes, [counts] the seasons' counts from the show's own page.
     */
    fun fromSeasons(seasons: Map<Int, List<MetaEpisode>>, counts: List<MetaSeason>): SourceList {
        val layout = layout(counts.ifEmpty { seasons.map { (n, list) -> MetaSeason(n, list.maxOfOrNull { it.number } ?: 0) } })
        val pieces = ArrayList<Piece>()
        var offset = 0
        for (s in layout) {
            val list = seasons[s.number].orEmpty()
            for (e in list.asSequence().take(MAX_EPISODES)) {
                if (e.number !in 1..s.episodeCount.coerceAtLeast(e.number)) continue
                pieces += Piece(
                    absolute = offset + e.number, name = e.name, overview = e.overview, stillUrl = e.stillUrl, airDate = e.airDate,
                    runtimeMin = e.runtimeMin, season = s.number, episode = e.number,
                )
            }
            offset += s.episodeCount
            if (offset > MAX_EPISODES) break
        }
        return SourceList(Source.TmdbSeasons, pieces)
    }

    /** A source that numbers its episodes straight through (Kitsu, Jikan): each [MetaEpisode.number] is the absolute one. */
    fun fromNumbered(source: Source, episodes: List<MetaEpisode>): SourceList =
        SourceList(
            source,
            episodes.asSequence().take(MAX_EPISODES * 2)
                .map { Piece(it.number, it.name, it.overview, it.stillUrl, it.airDate, it.runtimeMin) }
                .toList(),
        )

    private val STREAM_TITLE = Regex("""^\s*(?:episode|ep\.?|e|folge|épisode|episodio|ep)\s*(\d{1,4})\s*[-:\u2013\u2014.]\s*(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * An AniList streaming episode ("Episode 12 - The Title", a picture): the number is in the
     * title's words. Null for a title that does not start with one, which is not trusted with a place.
     */
    fun streamingPiece(title: String, thumbnail: String?): Piece? {
        val match = STREAM_TITLE.find(title.take(MAX_NAME * 2)) ?: return null
        val number = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_NUMBER } ?: return null
        return Piece(number, name = match.groupValues[2].trim(), stillUrl = thumbnail)
    }

    // ---- the merge ----------------------------------------------------------------------------

    /**
     * Whether [list] describes the show a chat's entry of [expected] episodes is. A list well short
     * of the entry (the other show of a similar name, "Naruto" for "Naruto Shippuden") or far past
     * it is a wrong match, and its titles would be wrong ones, so it is left out. With nothing to
     * compare ([expected] null or zero) it stands.
     */
    fun plausible(expected: Int?, list: SourceList): Boolean {
        val total = list.pieces.maxOfOrNull { it.absolute } ?: return false
        if (expected == null || expected <= 0) return true
        return total.toLong() * 10 >= expected.toLong() * 9 && total <= expected.toLong() * 6 + 50
    }

    /**
     * One guide from [lists] in the order they are to be read (the first with a real title wins),
     * [counts] TMDB's seasons (for the layout) and [next], the next episode to air with its absolute
     * number. [lists] a wrong match was found in are the caller's to drop ([plausible]).
     */
    fun merge(lists: List<SourceList>, counts: List<MetaSeason>, next: MetaAiring?, declared: Int = 0): Guide {
        val clean = lists.map { l ->
            l.source to l.pieces.asSequence()
                .filter { it.absolute in 1..MAX_EPISODES }
                .take(MAX_EPISODES * 2)
                .groupBy { it.absolute }
        }
        val numbers = clean.flatMap { it.second.keys }.distinct().sorted()
        val entries = ArrayList<Entry>(numbers.size)
        for (n in numbers) {
            var name: String? = null
            var nameSource: Source? = null
            var overview: String? = null
            var overviewSource: Source? = null
            var still: String? = null
            var stillSource: Source? = null
            var date: String? = null
            var runtime: Int? = null
            var season: Int? = null
            var episode: Int? = null
            for ((source, byNumber) in clean) {
                val piece = byNumber[n]?.first() ?: continue
                if (season == null && piece.season != null && piece.episode != null) {
                    season = piece.season
                    episode = piece.episode
                }
                val words = piece.name.trim().take(MAX_NAME)
                if (name == null && words.isNotEmpty() && !EpisodeNames.placeholder(words, n) && !(piece.episode != null && EpisodeNames.placeholder(words, piece.episode))) {
                    name = words
                    nameSource = source
                }
                val about = metaPlain(piece.overview).take(MAX_OVERVIEW)
                if (overview == null && about.isNotEmpty()) {
                    overview = about
                    overviewSource = source
                }
                if (still == null && stillOk(piece.stillUrl)) {
                    still = piece.stillUrl
                    stillSource = source
                }
                if (date == null && dateOk(piece.airDate)) date = piece.airDate
                if (runtime == null && piece.runtimeMin != null && piece.runtimeMin in 1..1000) runtime = piece.runtimeMin
            }
            entries += Entry(
                absolute = n, season = season, episode = episode, name = name.orEmpty(), overview = overview.orEmpty(), stillUrl = still,
                airDate = date, runtimeMin = runtime, source = nameSource ?: overviewSource ?: stillSource, stillSource = stillSource,
            )
        }
        return Guide(entries, layout(counts), next, declared)
    }

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    private fun dateOk(text: String?): Boolean = text != null && DATE.matches(text)

    /** A picture only from a host the app fetches pictures from ([OnlineMetadata.IMAGE_HOSTS]): another is never drawn. */
    private fun stillOk(url: String?): Boolean =
        url != null && url.length <= MAX_URL && url.startsWith("https://") && hostOf(url) in OnlineMetadata.IMAGE_HOSTS

    private const val MAX_URL = 600

    // ---- what the series page reads ----------------------------------------------------------

    /**
     * The guide as one season counted straight through, the numbers a file "Show - 123" has: what
     * the series page lists and names. An episode with no date of its own that has aired takes the
     * date of the one before it (or the show's first), so nothing that aired reads as "to air".
     */
    fun flat(guide: Guide): List<MetaEpisode> {
        var last: String? = guide.entries.firstNotNullOfOrNull { it.airDate }
        val nextAt = guide.next?.episode
        // Every number up to the run's end, named or not: an episode no source describes is still one.
        return (1..guide.total.coerceAtMost(MAX_EPISODES)).map { n ->
            val e = guide.entry(n)
            if (e?.airDate != null) last = e.airDate
            val aired = nextAt == null || n < nextAt
            MetaEpisode(
                season = 1, number = n, name = e?.name.orEmpty(), overview = e?.overview.orEmpty(), stillUrl = e?.stillUrl,
                airDate = e?.airDate ?: if (aired) (last ?: DAWN) else null, runtimeMin = e?.runtimeMin,
                source = e?.source?.label.orEmpty(),
            )
        }
    }

    /** What an undated episode that has aired is dated, so it never counts as still to come. */
    const val DAWN = "1970-01-01"

    /**
     * [season]'s episodes: season 1 is the guide counted straight through ([flat]), which holds
     * every number a file of season 1 can have; another season is TMDB's own list for it, numbered
     * in the season, for the files that count that way ("S06E12").
     */
    fun seasonEpisodes(guide: Guide, season: Int): List<MetaEpisode> =
        if (season == 1) {
            flat(guide)
        } else {
            guide.seasonEntries(season).mapNotNull { e ->
                MetaEpisode(season, e.episode ?: return@mapNotNull null, e.name, e.overview, e.stillUrl, e.airDate, e.runtimeMin, e.source?.label.orEmpty())
            }
        }

    /** The season list the series page merges: one season holding the whole run, or none for an empty guide. */
    fun seasonList(guide: Guide): List<MetaSeason> =
        if (guide.total <= 0) {
            emptyList()
        } else {
            listOf(MetaSeason(1, guide.total.coerceAtMost(MAX_EPISODES), guide.entries.firstNotNullOfOrNull { it.airDate }))
        }

    /**
     * The next episode in the season list's numbering, from the entry's own next episode, or null.
     * AniList counts it straight through, which is the one season [seasonList] lists.
     */
    fun nextEpisode(guide: Guide): MetaAiring? = guide.next?.takeIf { it.episode in 1..MAX_NUMBER }?.copy(season = 1)

    /** [extras] with the run's season and next episode in them, for a show whose provider lists none. */
    fun overlay(extras: MetaExtras, guide: Guide): MetaExtras {
        if (extras.seasonList.isNotEmpty() || guide.total <= 0) return extras
        return extras.copy(seasonList = seasonList(guide), nextEpisode = extras.nextEpisode ?: nextEpisode(guide))
    }

    // ---- dates -------------------------------------------------------------------------------

    /** `2026-10-14` for [epochSeconds] (UTC), after Howard Hinnant's civil-from-days; null for a date no show has. */
    fun dateOf(epochSeconds: Long): String? {
        if (epochSeconds < 0 || epochSeconds > 253_402_300_799L) return null
        val z = epochSeconds / 86_400 + 719_468
        val era = z / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return "${year.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }
}
