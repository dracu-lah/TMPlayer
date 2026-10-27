package com.tmplayer.data

import com.tmplayer.online.MetaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
 * What the featured carousel at the top of Home shows, on the phone, the television and the
 * desktop alike: the newest [LIMIT] different films and shows in the starred chats, taken from the
 * "Recently added in starred chats" row ([HomeRow.Recent]), so the carousel asks Telegram for
 * nothing of its own (the rules below: user, 2026-10-27).
 *
 * - Different means different: copies of one film (a 720p and a 1080p, in one chat or two) are one
 *   page, as [SeriesShelf.sameVideoKey] keys them, and a show is one page however many of its
 *   episodes arrived, whether they came folded into a show or as a lone episode. Copies the
 *   names do not tie together are one page too when they share an online match or a film's name
 *   and year ([SameTitle]), the best copy leading.
 * - Only films and shows: never an extra (a trailer, a teaser, a sample), a clip, a recording or a
 *   lecture, even when that leaves the carousel short or empty. Across the top of Home a page reads
 *   as a featured title, and a home video there looked out of place (user, 2026-10-21).
 * - Only big files: at least [MIN_BYTES], a show by its episodes' files ([bigEnough]), which
 *   filters out most of what is not a film or a show. The chat size filter setting has no say.
 * - Only complete pages: an online match with a wide picture and an overview ([isComplete]). A
 *   page without them is left out rather than drawn with a placeholder. Which pages are complete
 *   is worked out once per fill ([completeKeys]), so no page comes or goes while it is looked at.
 * - Nothing to show hides the carousel: no empty frame.
 * - Newest first, all from one chat or from several, as they came.
 */
object HomeFeatured {

    /** Pages in the carousel at most. */
    const val LIMIT = 5

    /**
     * Pages looked at for the carousel at most, so a few without an online match or a picture
     * still leave it full, and the lookups made for it stay a handful.
     */
    const val CANDIDATES = LIMIT * 3

    /**
     * The smallest file that makes a carousel page: 400 MB. A film or an episode worth featuring
     * is rarely smaller, and clips, recordings and samples nearly always are (user, 2026-10-27).
     * A file of unknown size (0) does not pass.
     */
    const val MIN_BYTES = 400 * SizeFilter.MB

    /** How long a fill waits for the online lookups before it shows what is known complete. */
    const val FILL_WAIT_MS = 1_500L

    /** Lookups a fill makes at once, under TMDB's spacing, which the tiles share. */
    const val LOOKUPS_AT_ONCE = 2

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

    /**
     * Whether [pick] may be a page at all, before anything is looked up: a film or a show
     * ([identity]) of at least [MIN_BYTES] ([bigEnough]).
     */
    fun eligible(pick: FeaturedPick): Boolean = identity(pick)?.second == true && bigEnough(pick)

    /**
     * Whether [pick]'s file is at least [MIN_BYTES]: a film by its biggest copy, a show by its
     * biggest episode file (a lone episode by its own). An unknown size, 0, is not big enough.
     */
    fun bigEnough(pick: FeaturedPick): Boolean {
        val series = pick.series
        if (series != null) {
            return series.episodes.any { episode -> episode.item.sizeBytes >= MIN_BYTES || episode.copies.any { it.sizeBytes >= MIN_BYTES } }
        }
        val item = pick.item
        return item.sizeBytes >= MIN_BYTES || item.versions.any { it.sizeBytes >= MIN_BYTES }
    }

    /**
     * Whether the online match [info] makes a complete page: a wide picture (the backdrop, or an
     * episode's still) and an overview. A poster alone does not count: the television, the desktop
     * and a phone on its side draw a 16:9 frame, which cuts a 2:3 poster down to a soft strip.
     * Whether the match is the right title is for the caller to say (the page's own check).
     */
    fun isComplete(info: MetaInfo?): Boolean =
        info != null && !info.wideUrl.isNullOrBlank() && info.overview.isNotBlank()

    /**
     * The keys of [candidates] that are complete pages, worked out once for a fill. [known]
     * answers from what is on this device (true, false, or null for not looked up yet), and the
     * rest are asked with [ask], [LOOKUPS_AT_ONCE] at a time in [scope] on [context], for at most
     * [waitMs] in all. A candidate still unanswered then is left out of this fill; its lookup goes
     * on in [scope], so the answer is on the device for the next fill. The wait ends early once
     * the first [limit] complete pages, in the candidates' order, are settled.
     */
    suspend fun completeKeys(
        candidates: List<FeaturedPick>,
        known: (FeaturedPick) -> Boolean?,
        ask: suspend (FeaturedPick) -> Boolean?,
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        waitMs: Long = FILL_WAIT_MS,
        limit: Int = LIMIT,
    ): Set<String> {
        val verdicts = HashMap<String, Boolean?>()
        for (pick in candidates) verdicts[pick.key] = guarded { known(pick) }
        fun settled(): Boolean {
            var complete = 0
            for (pick in candidates) {
                when (verdicts[pick.key]) {
                    true -> if (++complete >= limit) return true
                    false -> Unit
                    null -> return false
                }
            }
            return true
        }
        fun keys() = candidates.filter { verdicts[it.key] == true }.mapTo(LinkedHashSet()) { it.key }
        val open = candidates.filter { verdicts[it.key] == null }.distinctBy { it.key }
        if (open.isEmpty() || settled()) return keys()
        val answers = Channel<Pair<String, Boolean?>>(Channel.UNLIMITED)
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        for (pick in open) {
            scope.launch(context) {
                val verdict = gate.withPermit { guarded { ask(pick) } }
                answers.trySend(pick.key to verdict)
            }
        }
        withTimeoutOrNull(waitMs) {
            repeat(open.size) {
                val (key, verdict) = answers.receive()
                verdicts[key] = verdict ?: false
                if (settled()) return@withTimeoutOrNull
            }
        }
        // Waited for long enough: what is still unanswered is not complete, for this fill.
        return keys()
    }

    /** [block]'s answer, or null when it failed: outside data never takes the carousel down. */
    private inline fun guarded(block: () -> Boolean?): Boolean? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Whether [pick] is a film or show not yet in [seen], by any key it goes by; its keys are added when it is. */
    private fun fresh(pick: FeaturedPick, seen: MutableSet<String>, online: (MediaItem) -> String?): Boolean {
        val (key, isTitled) = identity(pick) ?: return false
        if (!isTitled || !bigEnough(pick)) return false
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
     * one page, the newer one's. Only films and shows of at least [MIN_BYTES], and only the pages
     * [complete] says are complete ([completeKeys]): never one drawn with a placeholder.
     */
    fun mix(
        recent: List<FeaturedPick>,
        resume: List<MediaItem>,
        limit: Int = LIMIT,
        resumeShare: Int = RESUME_SHARE,
        complete: (FeaturedPick) -> Boolean,
        matched: (MediaItem) -> String? = SameTitle::onlineKey,
    ): List<FeaturedPick> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        fun usable(pick: FeaturedPick): Boolean {
            if (!complete(pick)) return false
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
        return key to (SeriesShelf.isFilm(item) && filmWorthy(item, name.title))
    }

    /**
     * Whether a file [SeriesShelf.isFilm] accepts may stand across the top of Home as a film:
     * at least [FILM_MIN_SEC] long when its length is known, and a real title rather than one a
     * camera or a messenger wrote. A one minute "Video" got in as "Film  ·  1m" (desktop,
     * 2026-10-27); it stays in the rows.
     */
    internal fun filmWorthy(item: MediaItem, title: String): Boolean {
        if (item.durationSec in 1 until FILM_MIN_SEC) return false
        return !isGenericTitle(title)
    }

    /**
     * A title that names nothing: blank, "Video", "Untitled", a camera's "VID_20241013", "IMG 1234"
     * or a date. A bare number stays a title ("1917", "300").
     */
    internal fun isGenericTitle(title: String): Boolean {
        val plain = title.trim().lowercase()
        if (plain.none(Char::isLetterOrDigit)) return true
        return GENERIC_TITLES.any { it.matches(plain) }
    }

    /** Twenty minutes: shorter is a clip, an extra or a short, not a film page. */
    const val FILM_MIN_SEC = 20 * 60

    private val GENERIC_TITLES = listOf(
        Regex("""(video|vid|movie|clip|file|untitled|new video|new movie|telegram video)([ _-]*\(?\d+\)?)?"""),
        Regex("""(vid|img|mov|pxl|mvimg|video|movie|clip|file|document|telegram|tg|record|recording|screen ?record(ing)?|wa)[ _-]*\d[\d _.:-]*(wa\d+)?"""),
        // A date, with or without the time: "2024-10-13", "13.10.2024 12.00", "20241013 120000".
        Regex("""\d{4}[ _.-]\d{1,2}[ _.-]\d{1,2}([ _.:-]+\d[\d _.:-]*)?"""),
        Regex("""\d{1,2}[ _.-]\d{1,2}[ _.-]\d{4}([ _.:-]+\d[\d _.:-]*)?"""),
        Regex("""\d{8}([ _-]?\d{4,6})?"""),
    )
}
