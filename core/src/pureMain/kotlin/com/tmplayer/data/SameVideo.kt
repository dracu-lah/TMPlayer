package com.tmplayer.data

import com.tmplayer.platform.sortedByKey
import kotlin.math.abs

/**
 * Which files of a listing are one video: the one place that decides it, for the chat's grid
 * ([SeriesShelf]), the copy picker, progress kept per video and search.
 *
 * A file name is a guess, so a name alone never makes two files one video. Something has to back
 * it up:
 * - **The same file.** One Telegram file forwarded into two posts ([MediaItem.fileId]), or the
 *   same name at the same size posted twice.
 * - **Matching lengths.** Within [SAME_LENGTH_SEC], and on the same side of [SHORT_SEC], so a
 *   clip is never a copy of a feature. The length the player learned on an earlier play counts as
 *   much as the one Telegram states (see [group]'s `durationOf`). A length nobody knows is no
 *   evidence either way: never taken for equal.
 * - **A year or an episode code** in the name, with no length to contradict it: "Night Train
 *   (2019) 720p" and "Night Train 2019 1080p" sent as documents (which state no length) are one
 *   film. A tiny file beside a large one, or a short one beside a feature sized one, is still not.
 *
 * What the viewer said by hand wins: a file set apart ([VideoCorrection.separate]) is a video of
 * its own whatever the evidence. Saved data (positions, Watched, corrections) stays keyed per
 * message; this is worked out again whenever a listing is read, so nothing stored depends on it.
 */
object SameVideo {

    /** Two copies whose lengths are further apart than this are two videos: three minutes. */
    const val SAME_LENGTH_SEC = 180

    /** Under ten minutes is a clip, a trailer or a sample, and never one video with a feature. */
    const val SHORT_SEC = 600

    /** With no length to go on, a file this size or larger is taken for a feature. */
    const val FEATURE_BYTES = 300L * 1024 * 1024

    /** With no lengths to go on, a file under this size beside one ten times larger is no copy of it. */
    const val TINY_BYTES = 100L * 1024 * 1024

    /** The length Telegram states, the one this app reads for a file when nothing better is known. */
    val statedLength: (MediaItem) -> Int = { it.durationSec }

    /**
     * [items] in groups of one video each, every file in exactly one group, the groups in the order
     * of their newest file and each group newest first. Episodes of a show and films alike: the
     * candidate key ([SeriesShelf.sameVideoKey]) says which files may be one video, and the evidence
     * above says which are.
     *
     * @param durationOf a file's length in seconds, 0 when unknown: the stated one by default; a
     *   caller with the player's learned lengths (`duration_<chat>_<message>`) passes those first.
     */
    fun group(
        items: List<MediaItem>,
        corrections: Map<String, VideoCorrection> = VideoCorrections.current,
        durationOf: (MediaItem) -> Int = statedLength,
    ): List<List<MediaItem>> {
        val keyed = SplitPieces.join(items).map { item -> item to SeriesShelf.candidateKey(item, corrections) }
        val byKey = keyed.filter { it.second != null }.groupBy({ it.second!! }, { it.first })
        val groupOf = HashMap<String, List<MediaItem>>()
        for ((key, files) in byKey) {
            val dated = key.startsWith("episode:") || key.startsWith("date:") || !key.endsWith("|")
            videosOf(files.map { it to SeriesShelf.episodeOf(it, corrections) }, dated, durationOf).forEach { video ->
                video.members.forEach { groupOf[it.id] = video.members }
            }
        }
        val seen = HashSet<String>()
        return keyed.map { it.first }.mapNotNull { item ->
            val members = groupOf[item.id] ?: listOf(item)
            if (seen.add(members.first().id)) members.sortedWith(NEWEST_FIRST) else null
        }
    }

    /** [a] and [b] as one video, by the rules above, for a caller holding two files. */
    fun same(
        a: MediaItem,
        b: MediaItem,
        corrections: Map<String, VideoCorrection> = VideoCorrections.current,
        durationOf: (MediaItem) -> Int = statedLength,
    ): Boolean {
        if (a.id == b.id) return true
        return group(listOf(a, b), corrections, durationOf).size == 1
    }

    internal val NEWEST_FIRST = compareByDescending<MediaItem> { it.date }.thenBy { it.messageId }

    /**
     * What may be one video: a whole file, or the pieces of a split one ([members], in order, the
     * first leading), with their length added up (0 when any piece's is unknown) and their size.
     */
    internal class Candidate(val members: List<MediaItem>, val split: Boolean, durationOf: (MediaItem) -> Int) {
        val lead: MediaItem get() = members.first()
        val durationSec: Int = members.map(durationOf).let { lengths -> if (lengths.any { it <= 0 }) 0 else lengths.sum() }
        val sizeBytes: Long = members.sumOf { it.sizeBytes.coerceAtLeast(0) }
    }

    /** One video once the evidence is weighed: every file in it, and the tile that stands for it. */
    internal class Video(val members: List<MediaItem>, val lead: MediaItem)

    /**
     * [files], which share one candidate key, sorted into the videos they are. The pieces of a split
     * film come together first, one set per way of making them (a 720p CD1 and CD2, a 1080p CD1 and
     * CD2), and only when they are alike in size; then whole files and sets fold as copies of each
     * other where [sameVideo] finds the evidence. [dated] is whether a year or an episode code names
     * the video.
     */
    internal fun videosOf(
        files: List<Pair<MediaItem, ParsedName>>,
        dated: Boolean,
        durationOf: (MediaItem) -> Int = statedLength,
    ): List<Video> {
        // A file alone is the one video, whatever it is: most keys in a chat have one file.
        if (files.size == 1) return files.first().first.let { listOf(Video(listOf(it), it)) }
        val newestFirst = files.sortedWith(compareBy(NEWEST_FIRST) { it.first })
        val (pieces, wholes) = newestFirst.partition { it.second.part != null }
        val pieceIds = pieces.mapTo(HashSet()) { it.first.id }
        val sets = pieces.groupBy { it.first.nameMarkers }.values.flatMap { set ->
            // The newest file of each number, in order; pieces far apart in size are not one cut.
            val ordered = set.groupBy { it.second.part!! }.sortedByKey().values.map { it.first().first }
            if (ordered.size > 1 && alikeInSize(ordered)) {
                listOf(Candidate(ordered, split = true, durationOf))
            } else {
                ordered.map { Candidate(listOf(it), split = false, durationOf) }
            }
        }
        val candidates = (wholes.map { Candidate(listOf(it.first), split = false, durationOf) } + sets)
            .sortedWith(compareByDescending<Candidate> { c -> c.members.maxOf { it.date } }.thenBy { it.lead.messageId })
        val clusters = ArrayList<MutableList<Candidate>>()
        for (candidate in candidates) {
            // One piece on its own is not the film, so it is never a copy of the film either.
            val lonePiece = !candidate.split && candidate.lead.id in pieceIds
            val home = if (lonePiece) null else clusters.firstOrNull { cluster ->
                cluster.none { !it.split && it.lead.id in pieceIds } && cluster.all { sameVideo(it, candidate, dated) }
            }
            if (home != null) home += candidate else clusters += mutableListOf(candidate)
        }
        return clusters.map { cluster ->
            // The recommended copy first, not the newest: the card's badge, its count and the copy Play opens are its (HX38).
            val leads = GlobalSearch.copyOrder(cluster.map { if (it.split) it.lead.copy(parts = it.members).keptReads(it.lead) else it.lead })
            val lead = if (leads.size > 1) leads.first().copy(versions = leads).keptReads(leads.first()) else leads.first()
            Video(cluster.flatMap { it.members }, lead)
        }
    }

    /** Whether [item]'s own file name says what it is, rather than only its (possibly borrowed) caption. */
    private fun namedByFile(item: MediaItem): Boolean =
        item.fileName.isNotBlank() && !item.genericFileName

    /** Pieces of one cut are about the same size: none more than twice another. Unknown sizes pass. */
    private fun alikeInSize(pieces: List<MediaItem>): Boolean {
        val sizes = pieces.map { it.sizeBytes }.filter { it > 0 }
        if (sizes.size < 2) return true
        return sizes.max() <= sizes.min() * 2
    }

    /** Whether two candidates are one video: see [SameVideo] for the rules. */
    private fun sameVideo(a: Candidate, b: Candidate, dated: Boolean): Boolean {
        // Channels send a film's 720p, 1080p and 4K together as one album, so an album is no
        // reason to keep files apart. What is: a file named only by the caption it borrowed from
        // the album's first post ([MediaMapper.shareAlbumCaptions]), since every such file reads
        // as the same title and episode whatever it holds.
        // Most files were posted on their own: no set of albums made for them on every comparison.
        if (a.members.any { it.albumId != 0L } && b.members.any { it.albumId != 0L }) {
            val albums = a.members.mapNotNullTo(HashSet()) { it.albumId.takeIf { id -> id != 0L } }
            if (b.members.any { it.albumId != 0L && it.albumId in albums } &&
                (a.members + b.members).any { !namedByFile(it) }
            ) {
                return false
            }
        }
        if (!a.split && !b.split && a.lead.fileId != 0 && a.lead.fileId == b.lead.fileId) return true
        val x = a.durationSec
        val y = b.durationSec
        if (x > 0 && y > 0) {
            if ((x < SHORT_SEC) != (y < SHORT_SEC)) return false
            return abs(x - y) <= SAME_LENGTH_SEC
        }
        // The same name at the same size is the same file posted twice.
        if (!a.split && !b.split && a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes &&
            a.lead.nameForParsing.equals(b.lead.nameForParsing, ignoreCase = true)
        ) {
            return true
        }
        if (!dated) return false
        val known = if (x > 0) x else y
        val other = if (x > 0) b else a
        if (known in 1 until SHORT_SEC && other.sizeBytes >= FEATURE_BYTES) return false
        val small = minOf(a.sizeBytes, b.sizeBytes)
        val large = maxOf(a.sizeBytes, b.sizeBytes)
        return !(small in 1 until TINY_BYTES && large >= small * 10)
    }
}
