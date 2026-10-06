package com.tmplayer.data

import com.tmplayer.online.MetaAiring
import com.tmplayer.online.MetaEpisode
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
    )

    data class Episode(
        val season: Int,
        val number: Int,
        val inChat: SeriesEpisode?,
        val meta: MetaEpisode?,
        val state: State,
    ) {
        val code: String get() = "S%02dE%02d".format(java.util.Locale.ROOT, season, number)
    }

    /**
     * Every season either side knows, in order. A season the chat has any of is [State.InChat];
     * one it has none of is [State.Upcoming] when it has not begun (or the next episode opens it),
     * else [State.Missing].
     */
    fun seasons(series: Series?, listed: List<MetaSeason>, next: MetaAiring?, today: String): List<Season> {
        val held = series?.seasons.orEmpty().associate { it.number to it.episodes.size }
        val provider = listed.associateBy { it.number }
        val numbers = (held.keys + provider.keys + listOfNotNull(next?.season)).filter { it > 0 }.toSortedSet()
        return numbers.map { n ->
            val listedSeason = provider[n]
            val have = held[n] ?: 0
            val airDate = listedSeason?.airDate ?: next?.takeIf { it.season == n && it.episode == 1 }?.airDate
            val state = when {
                have > 0 -> State.InChat
                airDate == null || airDate > today -> State.Upcoming
                else -> State.Missing
            }
            Season(n, have, listedSeason?.episodeCount?.takeIf { it > 0 }, state, airDate)
        }
    }

    /**
     * [season]'s episodes: the provider's list with the chat's episodes put in their places, and
     * any the chat holds that the provider does not list (a special numbered as an episode) kept.
     * An episode with no air date that is not in the chat counts as upcoming: providers list
     * announced episodes before they are dated.
     */
    fun episodes(season: Int, series: Series?, listed: List<MetaEpisode>?, today: String): List<Episode> {
        val held = series?.seasons?.firstOrNull { it.number == season }?.episodes.orEmpty().associateBy { it.episode }
        val provider = listed.orEmpty().filter { it.season == season }.associateBy { it.number }
        val numbers = (held.keys + provider.keys).toSortedSet()
        return numbers.map { n ->
            val mine = held[n]
            val meta = provider[n]
            val state = when {
                mine != null -> State.InChat
                meta?.airDate == null || meta.airDate > today -> State.Upcoming
                else -> State.Missing
            }
            Episode(season, n, mine, meta, state)
        }
    }

    /** The show [item] belongs to among [chatItems] (the chat's videos), or null when it stands alone. */
    fun seriesOf(item: MediaItem, chatItems: Collection<MediaItem>): Series? {
        val items = (chatItems.filter { it.chatId == item.chatId } + item).distinctBy { it.id }
        return SeriesShelf.arrange(items).asSequence()
            .mapNotNull { (it as? ShelfEntry.Show)?.series }
            .firstOrNull { show -> show.episodes.any { ep -> ep.copies.any { it.id == item.id } } }
    }
}
