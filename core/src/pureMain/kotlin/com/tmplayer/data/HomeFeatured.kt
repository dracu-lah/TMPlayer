package com.tmplayer.data

/**
 * One page of the phone's featured carousel on Home: a film or a video ([item]), or a whole show
 * ([series], with [item] its newest episode, the one that put it there).
 */
data class FeaturedPick(val entry: ShelfEntry) {
    /** Stable while the carousel refills, so a pager can key on it and keep the page it is on. */
    val key: String get() = entry.key

    val series: Series? get() = (entry as? ShelfEntry.Show)?.series

    /**
     * The name of a lone episode standing for its show (a page from Continue watching, or a
     * show with one episode in the chat), null for a whole show or a film. Such a page is drawn
     * as its show: the show's name, logo and picture, not the file's name and a still.
     */
    val loneEpisode: ParsedName?
        get() = if (series != null) null else SeriesShelf.episodeOf(item).takeIf { it.isEpisode && it.extra == null && it.title.isNotBlank() }

    /** [loneEpisode]'s "S01E02", as [SeriesEpisode.code] writes it. */
    val loneEpisodeCode: String?
        get() = loneEpisode?.let { name ->
            name.episode?.let { "S${(name.season ?: 1).toString().padStart(2, '0')}E${it.toString().padStart(2, '0')}" }
        }

    /** A show's page, whole or by one of its episodes ([loneEpisode]). */
    val isShow: Boolean get() = series != null || loneEpisode != null

    /** The file the page stands for: the film (its copies in [MediaItem.versions]), or the show's newest episode. */
    val item: MediaItem
        get() = when (entry) {
            is ShelfEntry.File -> entry.item
            is ShelfEntry.Show -> entry.series.cover
        }

    /** When it was posted: a show by its newest episode. */
    val date: Int get() = item.date
}

/**
 * What the phone's featured carousel at the top of Home shows: the newest [LIMIT] different films,
 * shows and videos in the starred chats, which is what the "Recently added in starred chats" row
 * holds ([HomeRow.Recent]), so the carousel asks Telegram for nothing of its own.
 *
 * - Different means different: copies of one film (a 720p and a 1080p, in one chat or two) are one
 *   page, as [SeriesShelf.sameVideoKey] keys them, and a show is one page however many of its
 *   episodes arrived, whether they came folded into a show or as a lone episode. Copies the
 *   names do not tie together are one page too when they share an online match or a film's name
 *   and year ([SameTitle]), the best copy leading.
 * - Never an extra: a trailer, a teaser or a sample is not something to put across the top of Home.
 * - Only films and shows. A clip, a recording or a lecture never makes a page, even when that
 *   leaves the carousel short or empty: across the top of Home it reads as a featured title, and
 *   a home video there looked out of place (user, 2026-10-21).
 * - Newest first, all from one chat or from several, as they came.
 *
 * [rank] then puts the pages with a sharp online picture first, since a page is a large picture
 * and Telegram's own thumbnails are small and soft.
 */
object HomeFeatured {

    /** Pages in the carousel at most. */
    const val LIMIT = 5

    /** The newest [limit] different films and shows in [entries] (see [HomeFeatured]). */
    fun pick(
        entries: List<ShelfEntry>,
        limit: Int = LIMIT,
        online: (MediaItem) -> String? = SameTitle::onlineKey,
    ): List<FeaturedPick> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val newestFirst = entries.sortedWith(
            compareByDescending<ShelfEntry> { FeaturedPick(it).date }.thenByDescending { FeaturedPick(it).item.messageId },
        )
        // Copies the shelf did not fold (another chat, another name, the same online match) as one
        // page, led by the best copy, where the newest of them stood.
        return SameTitle.dedupe(newestFirst, online)
            .map(::FeaturedPick)
            .filter { pick -> fresh(pick, seen, online) }
            .take(limit)
    }

    /** Whether [pick] is a film or show not yet in [seen], by any key it goes by; its keys are added when it is. */
    private fun fresh(pick: FeaturedPick, seen: MutableSet<String>, online: (MediaItem) -> String?): Boolean {
        val (key, isTitled) = identity(pick) ?: return false
        if (!isTitled) return false
        val keys = listOf(key) + SameTitle.keysOf(pick.entry, online)
        if (keys.any { it in seen }) return false
        seen += keys
        return true
    }

    /**
     * The desktop's carousel: [recent] (what [pick] chose from Recently added) and [resume] (the
     * videos of Continue watching, newest first) taken in turn, a new one first, so the carousel
     * holds some of each: at most [resumeShare] of what the viewer is part way through, the rest
     * new, and either fills the other's places when it has too few. A video or show in both is
     * one page, the newer one's. Only films and shows, and only pages with a picture of their own ([hasArt], or [online]
     * saying the providers have one) are taken: a page across the top of Home is a large picture,
     * and a generated cover there is a placeholder.
     */
    fun mix(
        recent: List<FeaturedPick>,
        resume: List<MediaItem>,
        limit: Int = LIMIT,
        resumeShare: Int = RESUME_SHARE,
        online: (FeaturedPick) -> Boolean = { false },
        matched: (MediaItem) -> String? = SameTitle::onlineKey,
    ): List<FeaturedPick> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        fun usable(pick: FeaturedPick): Boolean {
            if (!hasArt(pick.item) && !online(pick)) return false
            return fresh(pick, seen, matched)
        }
        val fresh = ArrayDeque(recent)
        val going = ArrayDeque(resume.map { FeaturedPick(ShelfEntry.File(it)) })
        val out = ArrayList<FeaturedPick>()
        var resumed = 0
        fun next(queue: ArrayDeque<FeaturedPick>): FeaturedPick? {
            while (queue.isNotEmpty()) {
                val pick = queue.removeFirst()
                if (usable(pick)) return pick
            }
            return null
        }
        while (out.size < limit) {
            // New, part way through, new, part way through, new: until one side runs out.
            val resumeTurn = out.size % 2 == 1 && resumed < resumeShare
            val fromResume = if (resumeTurn) next(going) else null
            val pick = fromResume ?: next(fresh) ?: next(going)?.also { resumed++ } ?: break
            if (fromResume != null) resumed++
            out += pick
        }
        return out
    }

    /**
     * Whether [item] has a picture of its own from Telegram: a thumbnail to fetch, or at least the
     * inline preview. Without either, all there is to show is the generated cover.
     */
    fun hasArt(item: MediaItem): Boolean =
        item.thumbnailFileId > 0 || (item.miniThumbnail?.isNotEmpty() == true)

    /** [hasArt] for a shelf's tile: a show by its newest episode, the one its tile shows. */
    fun hasArt(entry: ShelfEntry): Boolean = when (entry) {
        is ShelfEntry.File -> hasArt(entry.item)
        is ShelfEntry.Show -> hasArt(entry.series.cover) || entry.series.episodes.any { hasArt(it.item) }
    }

    /** At most this many of the desktop carousel's pages are from Continue watching. */
    const val RESUME_SHARE = 2

    /**
     * [picks] with those [sharp] says have a sharp online picture first, each half in the order it
     * had. Worked out once when the carousel is filled, never as pictures arrive, so a page does
     * not move while it is being looked at.
     */
    fun rank(picks: List<FeaturedPick>, sharp: (FeaturedPick) -> Boolean): List<FeaturedPick> {
        val (first, rest) = picks.partition(sharp)
        return first + rest
    }

    /**
     * Which film or show [pick] is, and whether it is a film or a show at all; null for an extra.
     * A show's key is its name, so a lone episode of a show already in the carousel is the same page.
     */
    private fun identity(pick: FeaturedPick): Pair<String, Boolean>? {
        val series = pick.series
        if (series != null) return "show:${SeriesShelf.keyOf(series.title)}" to true
        val item = pick.item
        if (item.extra != null) return null
        val name = SeriesShelf.episodeOf(item)
        if (name.extra != null) return null
        if (name.isEpisode) {
            val show = SeriesShelf.keyOf(name.title)
            if (show.isNotEmpty()) return "show:$show" to true
        }
        val key = SeriesShelf.sameVideoKey(item)?.let { "film:$it" } ?: "item:${item.id}"
        return key to SeriesShelf.isFilm(item)
    }
}
