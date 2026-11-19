package com.tmplayer.data

import com.tmplayer.platform.sortedDistinct
import com.tmplayer.online.MetaAiring
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaSeason

/**
 * A show's seasons and episodes for the detail page: what the chat holds, laid over what the
 * provider lists, so the page can play the first, grey out the episodes the chat lacks, and mark
 * those still to air as upcoming rather than missing.
 *
 * Dates are the providers' own `yyyy-MM-dd`, compared as text against [today] in the same form.
 */
object EpisodeGuide {

    enum class State {
        /** In the chat: plays. */
        InChat,

        /** Aired, but nobody posted it here. */
        Missing,

        /** Not aired yet. */
        Upcoming,
    }

    data class Season(
        val number: Int,
        /** Episodes of it the chat holds. */
        val inChat: Int,
        /** Episodes the provider lists, when it lists them. */
        val total: Int?,
        val state: State,
        val airDate: String? = null,
    ) {
        /** Season 0: the specials, listed after the numbered seasons and only when the chat holds one. */
        val special: Boolean get() = number == 0
    }

    data class Episode(
        val season: Int,
        val number: Int,
        val inChat: SeriesEpisode?,
        val meta: MetaEpisode?,
        val state: State,
        /** When it airs, for an upcoming one the provider dated (its own, else the next-episode date). */
        val airDate: String? = meta?.airDate,
    ) {
        val code: String get() = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
    }

    /**
     * One line of a season on the series page: a video the chat holds ([Have], plays), or an
     * episode only the provider knows ([Gap], missing or upcoming, never a Play).
     */
    sealed interface Row {
        val key: String

        data class Have(val episode: SeriesEpisode, val meta: MetaEpisode?) : Row {
            override val key: String get() = episode.item.id
        }

        data class Gap(val episode: Episode) : Row {
            override val key: String get() = "gap-${episode.season}-${episode.number}"
        }

        /**
         * A whole season (or several) posted as one file, after the season's episodes. [after] is
         * the number of the episode it follows, for the list's ranges and its fast scroller.
         */
        data class Pack(val pack: SeriesPack, val after: Int = 0) : Row {
            override val key: String get() = pack.key
        }
    }

    /** More seasons than any show has; the rest of a garbage answer is dropped. */
    const val MAX_SEASONS = 200

    /** The longest season listed from a count alone: a long anime is one season of about a thousand. */
    const val MAX_EPISODES = 3000

    /** The largest episode number taken from the provider; an absurd one is dropped, not listed. */
    const val MAX_NUMBER = 9999

    /**
     * Whether the provider's seasons describe the show the chat holds, so that what the chat lacks
     * can be called missing. A wrong match (another show of the same name) or a release numbered
     * another way (anime counted straight through, "S01E57" in a 12 episode season) disagrees: then
     * the page lists what the chat holds, as it did before the provider's list was read, and calls
     * nothing missing. True when there is nothing to compare, and when most of the chat's episodes
     * sit where the provider says they should.
     */
    fun agrees(series: Series?, listed: List<MetaSeason>): Boolean {
        if (series == null || listed.isEmpty()) return true
        val provider = providerSeasons(listed)
        var held = 0
        var off = 0
        for (season in series.seasons) {
            if (season.number <= 0) continue
            val numbers = season.episodes.mapTo(HashSet()) { it.episode }
            held += numbers.size
            val info = provider[season.number]
            if (info == null || (info.episodeCount > 0 && (numbers.maxOrNull() ?: 0) > info.episodeCount)) off += numbers.size
        }
        return held == 0 || off * 2 <= held
    }

    /**
     * The seasons and next episode to lay the chat over, and whether they are TMDB's season split
     * ([com.tmplayer.online.SeasonSplit]): ask [com.tmplayer.online.OnlineMetadata.season] with
     * the same [split], so the episodes are numbered the same way.
     */
    data class Numbering(val seasons: List<MetaSeason>, val next: MetaAiring?, val split: Boolean)

    /**
     * Which of the show's numberings the chat follows. A show TMDB lists as one season that its
     * episode group splits in several has two: the split (S02E01, as most releases number it) and
     * TMDB's own (S01E26, as a release counting straight through does). The split is taken unless
     * the chat disagrees with it and agrees with TMDB's own; every other show has one numbering.
     */
    fun numbering(series: Series?, extras: MetaExtras?): Numbering {
        if (extras == null) return Numbering(emptyList(), null, false)
        val split = extras.seasonGroup != null && extras.listedSeasons.isNotEmpty()
        val first = Numbering(extras.seasonList, extras.nextEpisode, split)
        if (!split || agrees(series, extras.seasonList)) return first
        return if (agrees(series, extras.listedSeasons)) Numbering(extras.listedSeasons, extras.listedNext, false) else first
    }

    private fun providerSeasons(listed: List<MetaSeason>): Map<Int, MetaSeason> =
        listed.asSequence().filter { it.number in 1..MAX_SEASONS }
            .map { if (it.episodeCount in 0..MAX_EPISODES) it else it.copy(episodeCount = 0) }
            .take(MAX_SEASONS).associateBy { it.number }

    /**
     * Every season either side knows, in order, the specials last. A season the chat has any of is
     * [State.InChat]; one it has none of is [State.Upcoming] when it has not begun (or the next
     * episode opens it), else [State.Missing]. Specials come from the chat alone: the providers'
     * season 0 is mostly featurettes, so it is not offered as missing.
     */
    fun seasons(series: Series?, listed: List<MetaSeason>, next: MetaAiring?, today: String): List<Season> {
        val held = series?.seasons.orEmpty().associate { it.number to it.episodes.size }
        // A season the chat holds only as a whole-season file is held too, though it has no episode.
        val packed = series?.packs.orEmpty().mapTo(HashSet()) { it.season }
        val provider = providerSeasons(listed)
        val nextSeason = next?.season?.takeIf { it in 1..MAX_SEASONS }
        val numbers = (held.keys.filter { it > 0 } + packed.filter { it in 1..MAX_SEASONS } + provider.keys + listOfNotNull(nextSeason)).sortedDistinct()
        val seasons = numbers.map { n ->
            val listedSeason = provider[n]
            val have = held[n] ?: 0
            val airDate = listedSeason?.airDate ?: next?.takeIf { it.season == n && it.episode == 1 }?.airDate
            val state = when {
                have > 0 || n in packed -> State.InChat
                airDate == null || airDate > today -> State.Upcoming
                else -> State.Missing
            }
            Season(n, have, listedSeason?.episodeCount?.takeIf { it > 0 }, state, airDate)
        }
        val specials = held[0]?.takeIf { it > 0 }?.let { Season(0, it, null, State.InChat) }
        return if (specials == null) seasons else seasons + specials
    }

    /**
     * [season]'s episodes: the provider's list (else [info]'s count, numbered from one) with the
     * chat's episodes put in their places, and any the chat holds that the provider does not list
     * (a special numbered as an episode) kept. An episode with no air date that the provider lists
     * but the chat lacks counts as upcoming: providers list announced episodes before they are
     * dated. One counted but not listed is missing unless the season has not begun or [next] says
     * it has not aired. The second episode of a file holding two ("S01E09E10") is not listed as
     * missing: its file is the first one's row.
     *
     * A season whose episodes the chat numbers beyond what the provider lists keeps the chat's own
     * list alone: the numbering differs, and nothing in it can be called missing.
     */
    fun episodes(
        season: Int,
        series: Series?,
        listed: List<MetaEpisode>?,
        today: String,
        info: MetaSeason? = null,
        next: MetaAiring? = null,
    ): List<Episode> {
        // The halves of a split episode (E06a, E06b) share a number: the guide lists the number once,
        // on its first half, which is the one the seasons list sorts first.
        val held = series?.seasons?.firstOrNull { it.number == season }?.episodes.orEmpty().groupBy { it.episode }.mapValues { it.value.first() }
        val provider = listed.orEmpty().asSequence()
            .filter { it.season == season && it.number in 1..MAX_NUMBER }
            .take(MAX_EPISODES * 2)
            .associateBy { it.number }
        val count = info?.episodeCount?.takeIf { it in 1..MAX_EPISODES } ?: 0
        val known = if (provider.isNotEmpty()) provider.keys.max() else count
        // The chat's numbers run past the provider's list: another numbering, so nothing is missing here.
        val differs = known > 0 && (held.keys.maxOrNull() ?: 0) > known
        val counted = if (provider.isEmpty() && !differs && season > 0) (1..count).toList() else emptyList()
        val covered = if (held.isEmpty()) emptySet() else coveredByLongerFiles(held.values)
        val numbers = (held.keys + (if (differs) emptyList() else provider.keys) + counted).sortedDistinct()
        val begun = info?.airDate?.let { it <= today } ?: true
        return numbers.mapNotNull { n ->
            val mine = held[n]
            if (mine == null && n in covered) return@mapNotNull null
            val meta = provider[n]
            val dated = meta?.airDate ?: next?.takeIf { it.season == season && it.episode == n }?.airDate
            val state = when {
                mine != null -> State.InChat
                !begun -> State.Upcoming
                meta != null -> if (meta.airDate == null) State.Upcoming else if (meta.airDate > today) State.Upcoming else State.Missing
                next != null && next.season == season && n >= next.episode -> State.Upcoming
                else -> State.Missing
            }
            Episode(season, n, mine, meta, state, dated)
        }
    }

    /** The numbers of [season]'s episodes held inside another episode's file ("S01E09E10" holds 10). */
    fun covered(season: SeriesSeason): Set<Int> = coveredByLongerFiles(season.episodes)

    /** The numbers of episodes held inside another episode's file ("S01E09E10" holds 10). */
    private fun coveredByLongerFiles(rows: Collection<SeriesEpisode>): Set<Int> {
        var out: HashSet<Int>? = null
        for (row in rows) for (copy in row.copies) {
            val last = SeriesShelf.episodeOf(copy).lastEpisode ?: continue
            if (last <= row.episode || last - row.episode > MAX_COVERED) continue
            val set = out ?: HashSet<Int>().also { out = it }
            for (n in row.episode + 1..last) set += n
        }
        return out ?: emptySet()
    }

    /** A file naming more episodes than this after its first is not read as holding them. */
    private const val MAX_COVERED = 100

    /**
     * [season]'s lines for the series page: each video the chat holds (every half of a split
     * episode, as before), with a [Row.Gap] between them for each episode the provider knows and
     * the chat lacks, in episode order.
     */
    fun rows(
        season: Int,
        series: Series?,
        listed: List<MetaEpisode>?,
        today: String,
        info: MetaSeason? = null,
        next: MetaAiring? = null,
    ): List<Row> {
        val heldRows = series?.seasons?.firstOrNull { it.number == season }?.episodes.orEmpty().groupBy { it.episode }
        val packs = series?.packs.orEmpty().filter { it.season == season }
        // A season held only as a whole-season file lists that file, not a gap for each of its episodes.
        if (packs.isNotEmpty() && heldRows.isEmpty()) return packs.map { Row.Pack(it) }
        val rows = episodes(season, series, listed, today, info, next).flatMap { e ->
            if (e.inChat != null) heldRows[e.number].orEmpty().map { Row.Have(it, e.meta) } else listOf(Row.Gap(e))
        }
        if (packs.isEmpty()) return rows
        val after = when (val last = rows.lastOrNull()) {
            is Row.Have -> last.episode.episode
            is Row.Gap -> last.episode.number
            else -> 0
        }
        return rows + packs.map { Row.Pack(it, after) }
    }

    /** The show [item] belongs to among [chatItems] (the chat's videos), or null when it stands alone. */
    fun seriesOf(item: MediaItem, chatItems: Collection<MediaItem>): Series? {
        val items = (chatItems.filter { it.chatId == item.chatId } + item).distinctBy { it.id }
        return SeriesShelf.arrange(items).asSequence()
            .mapNotNull { (it as? ShelfEntry.Show)?.series }
            .firstOrNull { show -> show.episodes.any { ep -> ep.copies.any { it.id == item.id } } }
    }
}
