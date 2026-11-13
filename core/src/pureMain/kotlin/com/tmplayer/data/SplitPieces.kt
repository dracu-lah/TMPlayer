package com.tmplayer.data

import com.tmplayer.platform.putIfMissing
import com.tmplayer.platform.sortedByKey
import com.tmplayer.platform.sortedDistinct
import com.tmplayer.i18n.L

/**
 * What a file name says about one piece of a file cut into raw byte pieces: the name the pieces
 * share ([base], the cut file's own name, "Night.Train.2019.mkv") and which piece this is.
 *
 * [certain] is false for a name that could also be an ordinary file ("Night.Train.part001.mkv",
 * "Night.Train.001"): such pieces count only when the set of them looks cut, see [SplitPieces].
 */
data class SplitPiece(val base: String, val number: Int, val certain: Boolean) {
    /** What pieces of one file share, whatever separators or case each was written with. */
    val key: String get() = SplitPieces.keyOf(base)
}

/**
 * Videos cut into raw byte pieces to get past Telegram's 4 GB limit: one MKV split into
 * "Name.part001" to "Name.part007", or "Name.mkv.001", "Name.mkv.002". Only the first piece holds
 * the container's header, so none of them plays alone: they are one video, played as one stream of
 * their bytes joined in order.
 *
 * Not to be confused with a film posted as "CD1" and "CD2" ([ParsedName.part], [MediaItem.parts]),
 * where every file is a whole video with its own header and they play one after another.
 *
 * The names recognised, as splitters write them:
 * - `Name.ext.001`, `.002` (HJSplit, 7-Zip's "Split file", Total Commander, GSplit set to number
 *   its pieces), and `Name.ext.00`, `.01` (`split -d`, `telegram-upload`), which may start at 0.
 * - `Name.part001`, `Name.part7`, `Name.mkv.part1`: the splitter bots that post a large video to
 *   Telegram in pieces, which often drop the extension.
 * - `Name.001` and `Name.part001.mkv`, which could also be an ordinary file, count only when the
 *   pieces are cut alike: every one the same size but the last, which is no larger.
 *
 * An archive's volumes (`.7z.001`, `.zip.001`, `.part1.rar`, `.r00`) are never pieces of a video:
 * unpacking them needs the archive tool, not a player.
 */
object SplitPieces {

    /**
     * The piece [fileName] names, or null when it is not one. Pure and cheap enough to ask of every
     * file in a listing; [MediaItem.splitPiece] keeps the answer.
     */
    fun pieceOf(fileName: String): SplitPiece? {
        val name = fileName.trim()
        if (name.isEmpty()) return null
        // Nearly every name ends in a media extension and holds no "part": no regex for those, as
        // this is asked of every name each time a chat's listing is arranged.
        val endsInDigit = name.last().isDigit()
        if (!endsInDigit && !name.contains("part", ignoreCase = true)) return null
        if (endsInDigit) PART_SUFFIX.matchEntire(name)?.let { match ->
            val base = match.groupValues[1]
            val separator = match.groupValues[2]
            val digits = match.groupValues[3]
            // "Night_Train_Part_2" is a title written with underscores; only a dot ("Name.part7")
            // is a splitter's own suffix whatever the number, any other separator needs it padded.
            if (separator != "." && !padded(digits)) return null
            return piece(base, digits, certain = true)
        }
        PART_THEN_EXT.matchEntire(name)?.let { match ->
            val extension = match.groupValues[3].lowercase()
            if (extension !in MediaText.VIDEO_EXTENSIONS) return null
            return piece(match.groupValues[1] + "." + extension, match.groupValues[2], certain = false)
        }
        NUMBER_EXT.matchEntire(name)?.let { match ->
            val base = match.groupValues[1]
            val digits = match.groupValues[2]
            val inner = base.substringAfterLast('.', "").lowercase()
            return when {
                inner in MediaText.VIDEO_EXTENSIONS -> piece(base, digits, certain = true)
                // "Name.1080p.zip.001": a splitter's own suffix on a zip, whatever the digits.
                inner == "zip" -> piece(base, digits, certain = true)
                // "Night.Train.2019.001": a splitter that kept no extension pads to three digits;
                // "Night.Train.1080" or "Movie.720" is a height, not a piece.
                digits.length >= 3 && padded(digits) -> piece(base, digits, certain = false)
                else -> null
            }
        }
        return null
    }

    /** Whether [fileName] is a volume of an archive, which no player opens: never listed as a video. */
    fun isArchiveVolume(fileName: String): Boolean {
        val name = fileName.trim().lowercase()
        return ARCHIVE_VOLUME.containsMatchIn(name)
    }

    /** [fileName] without its piece suffix ("Name.mkv" for "Name.mkv.001"), or as it is. */
    fun baseOf(fileName: String): String = pieceOf(fileName)?.base ?: fileName

    /** What pieces of one file share: the base, lower-cased, its separators as spaces, no extension. */
    fun keyOf(base: String): String {
        val lower = base.trim().lowercase()
        val extension = lower.substringAfterLast('.', "")
        val stem = if (extension in MediaText.VIDEO_EXTENSIONS) lower.substringBeforeLast('.') else lower
        return stem.replace(SEPARATORS, " ").trim()
    }

    /**
     * [items] with the pieces of each cut video folded into one item: the first piece, carrying
     * every piece in order as [MediaItem.pieces], its size the pieces' added up. It sits where its
     * newest piece was, so the listing still reads newest first. Everything else is untouched.
     *
     * Pieces are grouped by the name they share within one chat, whoever sent them. A number
     * posted twice keeps its newest post. A set whose name could be an ordinary file's needs its
     * sizes to say it was cut ([cutAlike]), else its files are left as they are. A lone piece of a
     * set whose other pieces are not listed (yet) is still folded, as a set of one, so it never
     * reads as a copy of anything.
     */
    fun join(items: List<MediaItem>): List<MediaItem> {
        if (items.none { it.splitPiece != null }) return items
        // A list that holds the joined video already holds its pieces in it.
        val done = items.filter { it.isJoined && it.splitPiece != null }.mapTo(HashSet()) { it.chatId to it.splitPiece!!.key }
        val sets = items.withIndex()
            .filter { it.value.splitPiece != null && it.value.pieces.isEmpty() && (it.value.chatId to it.value.splitPiece!!.key) !in done }
            .groupBy { (_, item) -> item.chatId to item.splitPiece!!.key }
        val replaced = HashMap<String, MediaItem?>()
        for ((_, members) in sets) {
            val pieces = members.map { it.value }
            val ordered = ordered(pieces)
            val certain = pieces.all { it.splitPiece!!.certain }
            if (!certain && (ordered.size < 2 || !cutAlike(ordered))) continue
            val joined = joined(ordered)
            // The newest piece's place in the listing takes the joined video, the others go.
            val at = members.map { it.value }.maxWith(compareBy<MediaItem> { it.date }.thenBy { it.messageId })
            pieces.forEach { replaced[it.id] = null }
            replaced[at.id] = joined
        }
        if (replaced.isEmpty() && done.isEmpty()) return items
        return items.mapNotNull { item ->
            val piece = item.splitPiece
            when {
                item.id in replaced -> replaced[item.id]
                piece != null && !item.isJoined && (item.chatId to piece.key) in done -> null
                else -> item
            }
        }
    }

    /**
     * One item for [pieces] (one cut video's pieces, in order, see [ordered]): the first, with all
     * of them as its [MediaItem.pieces]. Its size is the sum, and it is on the device only when
     * every piece is, and downloaded only when every piece is.
     */
    fun joined(pieces: List<MediaItem>): MediaItem {
        val lead = pieces.first()
        val locality = when {
            pieces.all { it.locality == MediaItem.Locality.Downloaded } -> MediaItem.Locality.Downloaded
            pieces.all { it.onDevice } -> MediaItem.Locality.Cached
            else -> MediaItem.Locality.Remote
        }
        return lead.copy(
            pieces = pieces,
            sizeBytes = pieces.sumOf { it.sizeBytes.coerceAtLeast(0) },
            canBeSaved = pieces.all { it.canBeSaved },
        ).withLocality(locality)
    }

    /**
     * [pieces] of one cut video in order of their number, a number posted twice taking its newest
     * post. See [duplicates] for the posts left out.
     */
    fun ordered(pieces: List<MediaItem>): List<MediaItem> =
        pieces.groupBy { it.splitPiece?.number ?: 0 }
            .sortedByKey()
            .values
            .map { same -> same.maxWith(compareBy<MediaItem> { it.date }.thenBy { it.messageId }) }

    /** The posts of [pieces] [ordered] leaves out: a piece number posted more than once. */
    fun duplicates(pieces: List<MediaItem>): List<MediaItem> {
        val kept = ordered(pieces).mapTo(HashSet()) { it.id }
        return pieces.filter { it.id !in kept }
    }

    /**
     * Whether [ordered] pieces were cut by a splitter: every piece the same size but the last,
     * which is no larger. Unknown sizes say nothing either way.
     */
    fun cutAlike(ordered: List<MediaItem>): Boolean {
        if (ordered.size < 2) return true
        val sizes = ordered.map { it.sizeBytes }
        if (sizes.any { it <= 0 }) return true
        val full = sizes.first()
        return sizes.dropLast(1).all { it == full } && sizes.last() <= full
    }

    /** What is known about a cut video's pieces: how many there are, which are missing, how many play. */
    data class Report(
        /** The pieces the numbers say there are: the highest number seen, counted from the first. */
        val total: Int,
        /** The pieces missing below the highest, numbered from 1 as a viewer counts. */
        val missing: List<Int>,
        /** How many pieces from the first play on without a gap: 0 when the first is missing. */
        val playable: Int,
    ) {
        val complete: Boolean get() = missing.isEmpty()

        /** The first piece missing, numbered from 1, or null when none is. */
        val firstMissing: Int? get() = missing.firstOrNull()
    }

    /**
     * The [Report] for a joined video's [pieces] ([MediaItem.pieces]). A splitter that numbers from
     * 0 (`split -d`) is read as such when a piece 0 is among them; otherwise the first is 1.
     */
    fun report(pieces: List<MediaItem>): Report {
        val numbers = pieces.mapNotNull { it.splitPiece?.number }.sortedDistinct()
        if (numbers.isEmpty()) return Report(total = pieces.size, missing = emptyList(), playable = pieces.size)
        val first = if (0 in numbers) 0 else 1
        val last = maxOf(numbers.last(), first)
        val missing = (first..last).filter { it !in numbers }.map { it - first + 1 }
        val playable = (first..last).takeWhile { it in numbers }.count()
        return Report(total = last - first + 1, missing = missing, playable = playable)
    }

    /** The report for a joined video, or null for an item that is not one. */
    fun report(item: MediaItem): Report? = if (item.pieces.isEmpty()) null else report(item.pieces)

    /**
     * The pieces that play as one stream: from the first, up to the first gap. Empty when the first
     * piece is missing, which leaves nothing to start from.
     */
    fun playablePieces(pieces: List<MediaItem>): List<MediaItem> = pieces.take(report(pieces).playable)

    /**
     * "7 parts", for a joined video's facts: how many pieces it was cut into, with no word on what
     * is missing, which [missingLine] says.
     */
    fun countLabel(item: MediaItem): String? = report(item)?.takeIf { it.total > 1 }?.let { L.gridPartsCount(it.total) }

    /**
     * "Part 4 of 7 is not in this chat", or null when every piece is. With the first piece missing
     * nothing can play, and that is what is said.
     */
    fun missingLine(item: MediaItem): String? {
        val report = report(item) ?: return null
        val gap = report.firstMissing ?: return null
        return if (gap == 1) L.gridPieceFirstMissing(total = report.total) else L.gridPieceMissing(number = gap, total = report.total)
    }

    /**
     * What the player says before a joined video starts, or null when every piece is here: the
     * first missing means nothing can play, a later gap means it plays up to there.
     */
    fun playerNotice(pieces: List<MediaItem>): String? {
        val report = report(pieces)
        val gap = report.firstMissing ?: return null
        return if (gap == 1) L.playerPiecesFirstMissing(total = report.total) else L.playerPiecesUpTo(number = gap, total = report.total)
    }

    /** A joined video's size fact, "7 parts  ·  27 GB", for a card or a page that shows [size]. */
    fun sizeFact(item: MediaItem, size: String): String =
        countLabel(item)?.let { count -> if (size.isEmpty()) count else "$count  ·  $size" } ?: size

    /**
     * Every piece of [lead]'s video found by [fetch], which answers a page of the chat's videos and
     * documents for a search, a page at a time: for a player opened on one piece (Continue
     * watching, a link, a copy chosen elsewhere) or on a set a window of the chat cut short (Home
     * lists the newest messages only, so a set posted as 18 pieces can arrive as the last 17).
     * Null when [lead] is not a piece.
     *
     * [known] are the pieces already in hand (the set the grid or Home handed over): returned as
     * they are when nothing is missing from them, else searched for what is. The search by the
     * video's title goes first; one that does not bring every piece (Telegram matches a name by
     * its words, and a splitter's first piece can be named differently) is followed by the chat's
     * plain listing, unless [deep] is false: Home asks for the title search only, the player for
     * both. Stops once the first piece is met and the pieces from it run on without a gap.
     */
    suspend fun lookUp(
        lead: MediaItem,
        known: List<MediaItem> = lead.pieces,
        deep: Boolean = true,
        fetch: suspend (query: String, cursors: MediaCursors) -> MediaPage?,
    ): List<MediaItem>? {
        val wanted = lead.splitPiece ?: return null
        if (known.size > 1 && report(known).complete) return ordered(known)
        val query = MediaName.parse(lead.fileName).title.ifBlank { wanted.key }
        val found = LinkedHashMap<String, MediaItem>()
        found[lead.id] = lead
        known.forEach { found[it.id] = it }
        fun whole() = found.size > 1 && report(ordered(found.values.toList())).complete
        // Searches run newest first, so once the first piece is met every newer one was passed.
        var metFirst = false
        val searches = if (deep) listOf(query, "") else listOf(query)
        for (search in searches) {
            var cursors = MediaCursors()
            for (read in 0 until EpisodeNeighbours.MAX_PAGES) {
                val page = fetch(search, cursors) ?: break
                val same = page.items.filter { it.chatId == lead.chatId && it.splitPiece?.key == wanted.key }
                same.forEach { found.putIfMissing(it.id, it) }
                if (same.any { (it.splitPiece?.number ?: 2) <= 1 }) metFirst = true
                if ((metFirst && whole()) || page.endReached || page.cursors.allDone || page.cursors == cursors) break
                cursors = page.cursors
            }
            if (whole()) break
        }
        return ordered(found.values.toList())
    }

    /**
     * The sets among [items] (a window of one chat's newest messages) that are not whole there: a
     * first piece missing or a gap in the numbers, a window having cut the set where it ends. One
     * lead for each, the set's newest piece, newest set first. Only sets named by a splitter
     * beyond doubt ([SplitPiece.certain]) count, as a set that could be ordinary files is
     * judged by [join] on what it has.
     */
    fun openSets(items: List<MediaItem>): List<MediaItem> {
        if (items.none { it.splitPiece?.certain == true && it.pieces.isEmpty() }) return emptyList()
        val whole = items.filter { it.isJoined }.mapNotNullTo(HashSet()) { item -> item.splitPiece?.let { item.chatId to it.key } }
        return items.asSequence()
            .filter { it.pieces.isEmpty() && it.splitPiece?.certain == true }
            .groupBy { it.chatId to it.splitPiece!!.key }
            .filterKeys { it !in whole }
            .values
            .filter { members -> !report(ordered(members)).complete }
            .map { members -> members.maxWith(compareBy<MediaItem> { it.date }.thenBy { it.messageId }) }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
    }

    /**
     * The pieces [find] answers for the [openSets] of [items], that [items] does not hold: what a
     * window of a chat needs added so its sets are whole. At most [limit] sets are looked at, the
     * newest first; [find] gets the set's lead and the pieces [items] holds of it, and is where a
     * caller bounds the cost ([lookUp] with deep off, a memory of sets already searched). A set
     * whose search fails is left as it is.
     */
    suspend fun missingFrom(
        items: List<MediaItem>,
        limit: Int = 3,
        find: suspend (lead: MediaItem, known: List<MediaItem>) -> List<MediaItem>?,
    ): List<MediaItem> {
        val have = items.mapTo(HashSet()) { it.id }
        val extra = ArrayList<MediaItem>()
        for (lead in openSets(items).take(limit)) {
            val key = lead.splitPiece?.key
            val known = items.filter { it.chatId == lead.chatId && it.splitPiece?.key == key }
            val found = try {
                find(lead, known).orEmpty()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                emptyList()
            }
            found.filter { have.add(it.id) }.forEach { extra += it }
        }
        return extra
    }

    /**
     * The best of several readings of one video's pieces: the one that plays furthest from the
     * first piece, then the one with most pieces; the earlier of two equal ones. Empty when none has any.
     */
    fun best(vararg candidates: List<MediaItem>?): List<MediaItem> =
        candidates.filterNotNull()
            .filter { it.isNotEmpty() }
            .maxWithOrNull(compareBy<List<MediaItem>> { report(it).playable }.thenBy { it.size })
            .orEmpty()

    /**
     * The pieces of [lead]'s video among [items] (the Downloads list, for a player with no
     * connection to search the chat), in order, [lead] among them; empty when [lead] is not a piece.
     */
    fun among(lead: MediaItem, items: List<MediaItem>): List<MediaItem> {
        val wanted = lead.splitPiece ?: return emptyList()
        val same = items.filter { it.chatId == lead.chatId && it.splitPiece?.key == wanted.key && it.id != lead.id }
        return ordered(listOf(lead) + same)
    }

    private fun piece(base: String, digits: String, certain: Boolean): SplitPiece? {
        if (base.isBlank()) return null
        // 7z, RAR and the rest are never a video's pieces. A ZIP's are: its pieces join into the one
        // archive a stored video is played out of ([ZipArchive]).
        val extension = base.substringAfterLast('.', "").lowercase()
        if (extension in ARCHIVES && extension != "zip") return null
        val number = digits.toIntOrNull() ?: return null
        return SplitPiece(base = base, number = number, certain = certain)
    }

    /** "001", "07": zero padded, the way splitters number so their pieces sort. */
    private fun padded(digits: String) = digits.length >= 2 && digits.first() == '0'

    /** "Name.part001", "Name.mkv.part7", "Name_part_03", "Name part001". */
    private val PART_SUFFIX = Regex("""^(.+?)([._\s-])part[._-]?(\d{1,4})$""", RegexOption.IGNORE_CASE)

    /** "Name.part001.mkv". */
    private val PART_THEN_EXT = Regex("""^(.+?)[._\s-]part[._-]?(\d{3,4})\.([A-Za-z0-9]{2,4})$""", RegexOption.IGNORE_CASE)

    /** "Name.mkv.001", "Name.001", "Name.mkv.00". */
    private val NUMBER_EXT = Regex("""^(.+?)\.(\d{2,4})$""")

    private val ARCHIVES = setOf(
        "7z", "zip", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "lzma", "cab", "arj", "lzh",
        "iso", "img", "exe", "apk",
    )

    /** ".7z.001", ".zip.002", ".part1.rar", ".r00", ".z01", ".rar". */
    private val ARCHIVE_VOLUME = Regex(
        """\.(7z|zip|rar|tar|gz|tgz|bz2|xz|zst)\.(part)?\d{1,4}$|\.part\d{1,4}\.rar$|\.[rz]\d{2}$|\.(rar|7z|zip)$""",
    )

    private val SEPARATORS = Regex("""[._\s-]+""")
}
