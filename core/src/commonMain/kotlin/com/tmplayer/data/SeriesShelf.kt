package com.tmplayer.data

import java.util.Locale

/**
 * One file of a series, with the numbers [MediaName] read off its name or its caption.
 *
 * [episode] is never null here: a file that names a season but no episode (a whole-season pack)
 * stays a loose file, since there is nowhere in an episode list to put it.
 */
data class SeriesEpisode(val item: MediaItem, val season: Int, val episode: Int) {
    /** "S01E02"; a release with no season of its own counts as season one. */
    val code: String
        get() = "S%02dE%02d".format(Locale.ROOT, season, episode)
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
) {
    val finished: Boolean get() = total > 0 && watched >= total
}

/**
 * Folds a chat's videos into one tile per show.
 *
 * Grouped on the cleaned title, lower-cased and stripped of punctuation, so "Harbour.Notes.S01E01"
 * and "Harbour Notes - 02" land together. A show needs [MIN_EPISODES] files before it earns a tile
 * of its own; a lone episode is clearer as the file it is.
 *
 * Order follows the chat: a show sits where its newest file would have been, so the listing still
 * reads newest first and nothing jumps when the Series view is turned on.
 */
object SeriesShelf {

    const val MIN_EPISODES = 2

    /** The parse a file is grouped by: its name first, its caption second. */
    fun episodeOf(item: MediaItem): ParsedName =
        MediaName.parse(item.fileName.ifBlank { item.title }, item.caption)

    /** "harbour notes" for "Harbour.Notes", "Harbour Notes:" and "HARBOUR NOTES". */
    fun keyOf(title: String): String =
        title.lowercase(Locale.ROOT)
            .replace(NOT_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    fun arrange(items: List<MediaItem>): List<ShelfEntry> {
        val parsed = items.map { item ->
            val name = episodeOf(item)
            val key = keyOf(name.title)
            val number = name.episode
            if (key.isEmpty() || number == null) null else Triple(key, name, item)
        }
        val byShow = parsed.filterNotNull().groupBy { it.first }
        val shows = byShow
            .filterValues { it.size >= MIN_EPISODES }
            .mapValues { (key, files) -> series(key, files.map { it.second to it.third }) }

        val placed = HashSet<String>()
        return items.mapIndexedNotNull { index, item ->
            val key = parsed[index]?.first
            val show = key?.let { shows[it] }
            when {
                show == null -> ShelfEntry.File(item)
                placed.add(key) -> ShelfEntry.Show(show)
                else -> null
            }
        }
    }

    private fun series(key: String, files: List<Pair<ParsedName, MediaItem>>): Series {
        val episodes = files.map { (name, item) ->
            // A release with no season (the fansub form, a bare "Ep 04") is season one.
            SeriesEpisode(item, name.season ?: 1, name.episode ?: 0)
        }
        val seasons = episodes
            .groupBy { it.season }
            .toSortedMap()
            .map { (number, list) ->
                SeriesSeason(
                    number,
                    // Two copies of one episode (720p and 1080p) sit side by side, the newer first.
                    list.sortedWith(compareBy<SeriesEpisode> { it.episode }.thenByDescending { it.item.date }.thenBy { it.item.messageId }),
                )
            }
        // "harbour_notes_720p" is the same show as "Harbour.Notes", but the second is how to write it.
        val titles = files.map { it.first.title }
        val title = titles.firstOrNull { it != it.lowercase(Locale.ROOT) } ?: titles.first()
        return Series(key = key, title = title, seasons = seasons)
    }

    /**
     * Where the viewer is in [series].
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
        val done = all.map { finished(it.item) }
        val watched = done.count { it }
        // Partway through one beats everything: it is literally where the viewer stopped.
        val inProgress = all.indices.lastOrNull { !done[it] && position(all[it].item) > 0f }
        val next = when {
            inProgress != null -> all[inProgress]
            else -> {
                val furthest = done.lastIndexOf(true)
                (furthest + 1 until all.size).firstOrNull { !done[it] }?.let { all[it] }
                    ?: all.indices.firstOrNull { !done[it] }?.let { all[it] }
            }
        }
        return SeriesProgress(watched = watched, total = all.size, next = next)
    }

    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
