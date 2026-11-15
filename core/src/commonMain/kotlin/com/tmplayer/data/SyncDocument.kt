package com.tmplayer.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Which of the synced lists a [SyncRemoval] takes something off. */
enum class SyncList(val code: String) {
    Resume("resume"),
    Watched("watched"),
    WatchLater("later"),
    Favourite("favourite"),
    ;

    companion object {
        fun ofCode(code: String?): SyncList? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Something taken off a list on one device at [at], kept in the file so another device holding an
 * older copy of it drops it too, rather than bringing it back. [key] is the entry's own name:
 * [ProgressKeys] for a video, [WatchLaterRecord.id] on Watch later, the chat id for a favourite.
 */
data class SyncRemoval(val list: SyncList, val key: String, val at: Long)

/**
 * What resume on other devices (U7) keeps in the viewer's own Saved Messages: where each video
 * stopped, what was watched, Watch later and the favourite chats, plus what was taken off them.
 *
 * Its records are the backup file's ([Backups]), field for field, so one reader serves both; what
 * the sync file leaves out is everything a list on another device can do without: no chat names,
 * no file names, no captions, no paths, and a title cut to [SyncDocuments.TITLE_MAX] characters.
 * Settings, memories, bookmarks and the app and kids locks never travel here.
 */
data class SyncDocument(
    /** The Telegram account the file belongs to; 0 when not known. Never merged across accounts. */
    val account: Long = 0L,
    val updatedAt: Long = 0L,
    val appVersion: String = "",
    val resume: List<ResumeRecord> = emptyList(),
    val watched: List<WatchedRecord> = emptyList(),
    val watchLater: List<WatchLaterRecord> = emptyList(),
    /** A favourite chat's id to when it was starred, as far as the devices know. */
    val favourites: Map<Long, Long> = emptyMap(),
    val removed: List<SyncRemoval> = emptyList(),
    /**
     * The viewer's named lists (HX43) with their own removals ([NamedListRules.merge]). A file that
     * has any is written as [SyncDocuments.VERSION]; one without stays readable by older builds.
     */
    val lists: NamedLists = NamedLists(),
    val version: Int = SyncDocuments.VERSION,
)

/** What reading a sync file came to. */
sealed interface SyncRead {
    data class Ok(val doc: SyncDocument) : SyncRead

    /** Written by a newer TMPlayer: neither read nor written over, so nothing it holds is lost. */
    data class TooNew(val version: Int) : SyncRead

    /** Another account's file: ignored, never merged. */
    data class OtherAccount(val account: Long) : SyncRead

    /** Not a sync file, cut short, or too large to be one. */
    data object NotASyncFile : SyncRead
}

/** How many entries a cap left out, by list, so the viewer can be told. */
data class SyncDropped(
    val resume: Int = 0,
    val watched: Int = 0,
    val watchLater: Int = 0,
    val removals: Int = 0,
    /** Lines of the named lists, the oldest added first: the last thing cut to fit. */
    val listEntries: Int = 0,
) {
    val total: Int get() = resume + watched + watchLater + removals + listEntries

    operator fun plus(other: SyncDropped) = SyncDropped(
        resume + other.resume,
        watched + other.watched,
        watchLater + other.watchLater,
        removals + other.removals,
        listEntries + other.listEntries,
    )
}

/** The file's bytes, the document they hold (after any cut) and what was cut to fit. */
class SyncEncoded(val bytes: ByteArray, val doc: SyncDocument, val dropped: SyncDropped)

/**
 * What a device changes in its own lists to match a merged file. Only ever what the file says
 * newer: an entry missing from the file is never a reason to drop one here, only a removal newer
 * than this device's copy is.
 */
data class SyncApply(
    /** Places newer than this device's, to be read from Telegram for this device's file id. */
    val resumeIn: List<ResumeRecord> = emptyList(),
    /** Places another device removed after this one last saved them: key to removal time. */
    val resumeOut: Map<String, Long> = emptyMap(),
    val watchedIn: List<WatchedRecord> = emptyList(),
    /** Marks taken off on another device after they were made here: key to removal time. */
    val watchedOut: Map<String, Long> = emptyMap(),
    val laterIn: List<WatchLaterRecord> = emptyList(),
    /** Watch later lines to drop: [WatchLaterRecord.id] to the time they were removed or watched. */
    val laterOut: Map<String, Long> = emptyMap(),
    val favouritesIn: Set<Long> = emptySet(),
    val favouritesOut: Set<Long> = emptySet(),
) {
    val received: Int get() = resumeIn.size + watchedIn.size + laterIn.size + favouritesIn.size
    val removing: Int get() = resumeOut.size + watchedOut.size + laterOut.size + favouritesOut.size
    val nothing: Boolean get() = received + removing == 0
}

/** What this device synced last, for telling what it removed since. */
data class SyncBase(
    /** The resume keys this device held after its last sync. */
    val resumeKeys: Set<String> = emptySet(),
    /** The favourite chats this device held after its last sync. */
    val favourites: Set<Long> = emptySet(),
    /** When that was; 0 before the first. */
    val at: Long = 0L,
)

/**
 * The sync file's format and its merge. Pure: no Telegram, no stores, so every rule is tested
 * without either ([ResumeSync] does the reading and writing).
 *
 * The merge is "the newest wins" for each video and each list entry, with [SyncRemoval]s standing
 * for what was taken off: an entry survives a removal of the same age or older, so two devices
 * writing in the same moment never lose a watched mark. It is commutative, idempotent and, below
 * the caps, associative, so the order the devices sync in does not matter.
 */
object SyncDocuments {

    /**
     * Raised whenever a file this build writes could not be read by an older one. 2 added the named
     * lists (HX43): a file with lists in it is version 2, which an older build reads as too new and
     * leaves alone; a file without them is still written as [LISTLESS_VERSION], so devices on older
     * builds keep syncing until somebody makes a list.
     */
    const val VERSION = 2

    /** What a file without named lists is written as: what every build since CP89 reads. */
    const val LISTLESS_VERSION = 1

    /** The first field of every file, so a backup or any other JSON is told apart from it. */
    const val FORMAT = "tmplayer-sync"

    /** A title past this is cut: enough for a card, no more of the viewer's text than that. */
    const val TITLE_MAX = 60

    /** At most this many places travel, the newest; a device keeps [SettingsStore.MAX_HISTORY]. */
    const val MAX_RESUME = SettingsStore.MAX_HISTORY

    const val MAX_WATCHED = WatchedStore.MAX_WATCHED
    const val MAX_WATCH_LATER = WatchLaterStore.MAX_WATCH_LATER
    const val MAX_FAVOURITES = 1_000
    const val MAX_REMOVALS = 5_000

    /** How long a removal is remembered: a device away for longer may bring an entry back. */
    const val REMOVAL_DAYS = 90L

    /** The compressed file is cut to fit this, oldest places first. */
    const val MAX_BYTES = 1024 * 1024

    /** A file larger than this, packed or unpacked, is not read at all. */
    const val MAX_READ_BYTES = 2 * 1024 * 1024
    const val MAX_TEXT_BYTES = 16 * 1024 * 1024

    /** The names the watched store keeps removals under, beside its marks ("w_") and Watch later ("l_"). */
    const val WATCHED_REMOVAL_PREFIX = "xw_"
    const val LATER_REMOVAL_PREFIX = "xl_"

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    // ---- collecting -------------------------------------------------------------------------

    /**
     * This device's lists as a sync file, from the raw contents of its settings and watched
     * stores (as [Backups.collect] reads them): the kids lock does not filter what is read, so a
     * place hidden on this device is never mistaken for one removed.
     *
     * Removals of places and favourites are told from [base], what this device held after its
     * last sync; removals of marks and Watch later lines are written by the stores as they happen.
     * A favourite's time is the file's own for it ([remoteFavourites]), the last sync's for one
     * this device already had then, and [now] for one starred since.
     */
    fun collect(
        settings: Map<String, Any>,
        watched: Map<String, Any>,
        base: SyncBase,
        remoteFavourites: Map<Long, Long>,
        account: Long,
        now: Long,
        appVersion: String,
    ): SyncDocument {
        val resume = Backups.resumeOf(settings).mapNotNull(::strip)
        val resumeKeys = resume.mapTo(HashSet()) { ProgressKeys.of(it.chatId, it.messageId) }
        val favouriteIds = Backups.favouritesOf(settings)
        val favourites = favouriteIds.associateWith { id ->
            remoteFavourites[id] ?: if (id in base.favourites && base.at > 0) base.at else now
        }
        val removed = buildList {
            for (key in base.resumeKeys) if (key !in resumeKeys) add(SyncRemoval(SyncList.Resume, key, now))
            for (id in base.favourites) if (id !in favouriteIds) add(SyncRemoval(SyncList.Favourite, id.toString(), now))
            addAll(storedRemovals(watched))
        }
        return SyncDocument(
            account = account,
            updatedAt = now,
            appVersion = appVersion,
            resume = resume,
            watched = Backups.watchedOf(watched).map(::strip),
            watchLater = Backups.watchLaterOf(watched).map(::strip),
            favourites = favourites,
            removed = removed,
            lists = strip(NamedListKeys.read(watched)),
        )
    }

    /** The lists as they travel: each line with only what a card needs, as Watch later's do. */
    internal fun strip(lists: NamedLists): NamedLists =
        if (lists.isEmpty) lists else lists.copy(entries = lists.entries.map { it.copy(record = strip(it.record)) })

    /** The removals the watched store keeps under [WATCHED_REMOVAL_PREFIX] and [LATER_REMOVAL_PREFIX]. */
    fun storedRemovals(watched: Map<String, Any>): List<SyncRemoval> = watched.mapNotNull { (name, value) ->
        val at = (value as? Long)?.takeIf { it > 0 } ?: return@mapNotNull null
        when {
            name.startsWith(WATCHED_REMOVAL_PREFIX) -> SyncRemoval(SyncList.Watched, name.removePrefix(WATCHED_REMOVAL_PREFIX), at)
            name.startsWith(LATER_REMOVAL_PREFIX) -> SyncRemoval(SyncList.WatchLater, name.removePrefix(LATER_REMOVAL_PREFIX), at)
            else -> null
        }?.takeIf { validKey(it.list, it.key) }
    }

    /** Only what a card needs: the title cut short, no chat name, file name, caption or path. */
    private fun strip(r: ResumeRecord): ResumeRecord? {
        val title = r.title.trim().take(TITLE_MAX).trim()
        if (title.isEmpty() || r.fileId <= 0) return null
        return r.copy(title = title, chatTitle = "", localPath = null, fileName = null, caption = null)
    }

    /** A mark's file id is the writing device's own, so it travels as 0 ([SyncApply] heals it). */
    private fun strip(w: WatchedRecord): WatchedRecord =
        w.copy(fileId = 0, title = w.title.trim().take(TITLE_MAX).trim(), chatTitle = "", fileName = null, caption = null)

    private fun strip(w: WatchLaterRecord): WatchLaterRecord = w.copy(
        fileId = 0,
        title = w.title.trim().take(TITLE_MAX).trim(),
        fileName = "",
        chatTitle = "",
        series = w.series.take(TITLE_MAX * 2),
    )

    private fun validKey(list: SyncList, key: String): Boolean = when (list) {
        SyncList.Favourite -> key.toLongOrNull() != null
        SyncList.WatchLater -> key.length in 3..300 && '_' in key
        SyncList.Resume, SyncList.Watched -> key.split('_').let { it.size == 2 && it[0].toLongOrNull() != null && it[1].toLongOrNull() != null }
    }

    // ---- merging ----------------------------------------------------------------------------

    /** [a] and [b] as one file. See the class's own words for the rules. */
    fun merge(a: SyncDocument, b: SyncDocument, now: Long): SyncDocument = mergeCounting(a, b, now).first

    /** [merge], with how many entries the caps left out. */
    fun mergeCounting(a: SyncDocument, b: SyncDocument, now: Long): Pair<SyncDocument, SyncDropped> {
        val oldest = now - REMOVAL_DAYS * DAY_MS
        val removalAt = (a.removed + b.removed)
            .filter { it.at >= oldest && validKey(it.list, it.key) }
            .groupBy { it.list to it.key }
            .mapValues { (_, all) -> all.maxOf { it.at } }
        fun removedAt(list: SyncList, key: String) = removalAt[list to key] ?: Long.MIN_VALUE

        val resumeAll = (a.resume + b.resume)
            .groupBy { ProgressKeys.of(it.chatId, it.messageId) }
            .mapNotNull { (key, all) ->
                all.filter { it.updatedAt >= removedAt(SyncList.Resume, key) }
                    .maxWithOrNull(compareBy<ResumeRecord>({ it.updatedAt }, { it.positionMs }).thenBy { it.toString() })
            }
            .sortedWith(compareByDescending<ResumeRecord> { it.updatedAt }.thenBy { ProgressKeys.of(it.chatId, it.messageId) })
        val resume = resumeAll.take(MAX_RESUME)

        val watchedAll = (a.watched + b.watched)
            .groupBy { it.key }
            .mapNotNull { (key, all) ->
                // Of two marks at the same time the one made by hand; a device that held a manual
                // mark keeps it manual when a newer one comes in (apply), as markWatched does.
                all.filter { it.watchedAt >= removedAt(SyncList.Watched, key) }
                    .maxWithOrNull(compareBy<WatchedRecord>({ it.watchedAt }, { it.manual }).thenBy { it.toString() })
            }
            .sortedWith(compareByDescending<WatchedRecord> { it.watchedAt }.thenBy { it.key })
        val watched = watchedAll.take(MAX_WATCHED)

        val laterAll = (a.watchLater + b.watchLater)
            .groupBy { it.id }
            .mapNotNull { (id, all) ->
                all.filter { it.addedAt >= removedAt(SyncList.WatchLater, id) }
                    .maxWithOrNull(compareBy<WatchLaterRecord> { it.addedAt }.thenBy { it.toString() })
            }
            .sortedWith(compareByDescending<WatchLaterRecord> { it.addedAt }.thenBy { it.id })
        val later = laterAll.take(MAX_WATCH_LATER)

        val favourites = (a.favourites.entries + b.favourites.entries)
            .groupBy { it.key }
            .mapNotNull { (id, all) ->
                val at = all.maxOf { it.value }
                if (at >= removedAt(SyncList.Favourite, id.toString())) id to at else null
            }
            .sortedWith(compareByDescending<Pair<Long, Long>> { it.second }.thenBy { it.first })
            .take(MAX_FAVOURITES)
            .toMap()

        // A removal with a newer entry kept over it has done its job; one whose entry a cap left
        // out of the file is kept, so that entry cannot come back from an older copy.
        val live: Map<Pair<SyncList, String>, Long> = buildMap {
            resume.forEach { put(SyncList.Resume to ProgressKeys.of(it.chatId, it.messageId), it.updatedAt) }
            watched.forEach { put(SyncList.Watched to it.key, it.watchedAt) }
            later.forEach { put(SyncList.WatchLater to it.id, it.addedAt) }
            favourites.forEach { (id, at) -> put(SyncList.Favourite to id.toString(), at) }
        }
        val removalsAll = removalAt
            .filter { (id, at) -> (live[id] ?: Long.MIN_VALUE) < at }
            .map { (id, at) -> SyncRemoval(id.first, id.second, at) }
            .sortedWith(compareByDescending<SyncRemoval> { it.at }.thenBy { it.list.ordinal }.thenBy { it.key })
        val removed = removalsAll.take(MAX_REMOVALS)

        val lists = NamedListRules.merge(a.lists, b.lists, now)
        val newest = listOf(a, b).maxWith(compareBy<SyncDocument>({ it.updatedAt }, { it.appVersion }))
        val doc = SyncDocument(
            account = maxOf(a.account, b.account),
            updatedAt = newest.updatedAt,
            appVersion = newest.appVersion,
            resume = resume,
            watched = watched,
            watchLater = later,
            favourites = favourites,
            removed = removed,
            lists = lists,
        )
        val dropped = SyncDropped(
            resume = resumeAll.size - resume.size,
            watched = watchedAll.size - watched.size,
            watchLater = laterAll.size - later.size,
            removals = removalsAll.size - removed.size,
        )
        return doc to dropped
    }

    /**
     * What this device writes to its own lists to match [merged], given its [local] file. Never
     * removes an entry only because [merged] lacks it: a removal newer than this device's copy has
     * to say so. A mark this device lacks arrives with no file id (the healing lookup finds it);
     * one it has keeps its own names and id. A Watch later line a newer watched mark covers goes,
     * as [WatchedStore.markWatched] takes it off on the device that marked it.
     */
    fun apply(local: SyncDocument, merged: SyncDocument): SyncApply {
        val removalAt = merged.removed.associate { (it.list to it.key) to it.at }
        fun removedAt(list: SyncList, key: String) = removalAt[list to key]

        val localResume = local.resume.associateBy { ProgressKeys.of(it.chatId, it.messageId) }
        val mergedResume = merged.resume.associateBy { ProgressKeys.of(it.chatId, it.messageId) }
        val resumeIn = merged.resume.filter { m ->
            val mine = localResume[ProgressKeys.of(m.chatId, m.messageId)]
            mine == null || mine.updatedAt < m.updatedAt
        }
        val resumeOut = localResume.mapNotNull { (key, mine) ->
            if (key in mergedResume) return@mapNotNull null
            val at = removedAt(SyncList.Resume, key) ?: return@mapNotNull null
            if (at > mine.updatedAt) key to at else null
        }.toMap()

        val localWatched = local.watched.associateBy { it.key }
        val mergedWatched = merged.watched.associateBy { it.key }
        val watchedIn = merged.watched.mapNotNull { m ->
            val mine = localWatched[m.key]
            when {
                mine == null -> m.copy(fileId = 0)
                m.watchedAt > mine.watchedAt -> mine.copy(watchedAt = m.watchedAt, manual = mine.manual || m.manual)
                m.manual && !mine.manual -> mine.copy(manual = true)
                else -> null
            }
        }
        val watchedOut = localWatched.mapNotNull { (key, mine) ->
            if (key in mergedWatched) return@mapNotNull null
            val at = removedAt(SyncList.Watched, key) ?: return@mapNotNull null
            if (at > mine.watchedAt) key to at else null
        }.toMap()

        // The newest mark for a video once the merge is in, here or in the file.
        fun watchedAt(key: String): Long? {
            if (key in watchedOut) return null
            return listOfNotNull(localWatched[key]?.watchedAt, mergedWatched[key]?.watchedAt).maxOrNull()
        }
        fun covered(line: WatchLaterRecord): Boolean = !line.isShow && (watchedAt(line.key) ?: Long.MIN_VALUE) >= line.addedAt

        val localLater = local.watchLater.associateBy { it.id }
        val mergedLater = merged.watchLater.associateBy { it.id }
        val laterIn = merged.watchLater.mapNotNull { m ->
            if (covered(m)) return@mapNotNull null
            val mine = localLater[m.id]
            when {
                mine == null -> m.copy(fileId = 0)
                m.addedAt > mine.addedAt -> mine.copy(addedAt = m.addedAt)
                else -> null
            }
        }
        val laterOut = localLater.mapNotNull { (id, mine) ->
            val removed = removedAt(SyncList.WatchLater, id)?.takeIf { id !in mergedLater && it > mine.addedAt }
            // Only a mark that came in from the file: one made here already took the line off.
            val coveredBy = mergedWatched[mine.key]?.watchedAt?.takeIf {
                !mine.isShow && it >= mine.addedAt && mine.key !in watchedOut
            }
            (removed ?: coveredBy)?.let { id to it }
        }.toMap()

        val favouritesIn = merged.favourites.keys - local.favourites.keys
        val favouritesOut = local.favourites.mapNotNullTo(HashSet()) { (id, at) ->
            if (id in merged.favourites) return@mapNotNullTo null
            val removed = removedAt(SyncList.Favourite, id.toString()) ?: return@mapNotNullTo null
            if (removed > at) id else null
        }
        return SyncApply(resumeIn, resumeOut, watchedIn, watchedOut, laterIn, laterOut, favouritesIn, favouritesOut)
    }

    // ---- writing ----------------------------------------------------------------------------

    /**
     * The file's bytes: the JSON, gzipped. Over [maxBytes] it is cut until it fits, the oldest
     * places first, then the oldest removals, Watch later lines and marks, and the cut is counted.
     */
    fun encode(doc: SyncDocument, maxBytes: Int = MAX_BYTES): SyncEncoded {
        var current = doc
        var dropped = SyncDropped()
        repeat(MAX_CUTS) {
            val bytes = gzip(json(current).toString().encodeToByteArray())
            if (bytes.size <= maxBytes) return SyncEncoded(bytes, current, dropped)
            val (next, cut) = cut(current) ?: return SyncEncoded(bytes, current, dropped)
            current = next
            dropped += cut
        }
        return SyncEncoded(gzip(json(current).toString().encodeToByteArray()), current, dropped)
    }

    private const val MAX_CUTS = 64

    /** A quarter of the oldest of the first list that still has anything, or null when all are empty. */
    private fun cut(doc: SyncDocument): Pair<SyncDocument, SyncDropped>? {
        fun quarter(size: Int) = (size / 4).coerceAtLeast(1)
        return when {
            doc.resume.isNotEmpty() -> {
                val n = quarter(doc.resume.size)
                doc.copy(resume = doc.resume.sortedByDescending { it.updatedAt }.dropLast(n)) to SyncDropped(resume = n)
            }
            doc.removed.isNotEmpty() -> {
                val n = quarter(doc.removed.size)
                doc.copy(removed = doc.removed.sortedByDescending { it.at }.dropLast(n)) to SyncDropped(removals = n)
            }
            doc.watchLater.isNotEmpty() -> {
                val n = quarter(doc.watchLater.size)
                doc.copy(watchLater = doc.watchLater.sortedByDescending { it.addedAt }.dropLast(n)) to SyncDropped(watchLater = n)
            }
            doc.watched.isNotEmpty() -> {
                val n = quarter(doc.watched.size)
                doc.copy(watched = doc.watched.sortedByDescending { it.watchedAt }.dropLast(n)) to SyncDropped(watched = n)
            }
            // The viewer's own lists go last, the oldest added lines first; the lists themselves stay.
            doc.lists.entries.isNotEmpty() -> {
                val n = quarter(doc.lists.entries.size)
                val kept = doc.lists.entries.sortedWith(compareByDescending<ListEntry> { it.at }.thenBy { it.listId }.thenBy { it.id }).dropLast(n)
                doc.copy(lists = doc.lists.copy(entries = kept)) to SyncDropped(listEntries = n)
            }
            doc.lists.removed.isNotEmpty() -> {
                val n = quarter(doc.lists.removed.size)
                doc.copy(lists = doc.lists.copy(removed = doc.lists.removed.sortedByDescending { it.at }.dropLast(n))) to SyncDropped(removals = n)
            }
            else -> null
        }
    }

    internal fun json(doc: SyncDocument): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", if (doc.lists.isEmpty) LISTLESS_VERSION else VERSION)
        put("account", doc.account)
        put("updatedAt", doc.updatedAt)
        put("app", doc.appVersion)
        put("resume", JSONArray(doc.resume.map(Backups::resumeJson)))
        put("watched", JSONArray(doc.watched.map(Backups::watchedJson)))
        put("watchLater", JSONArray(doc.watchLater.map(Backups::watchLaterJson)))
        put(
            "favourites",
            JSONArray(doc.favourites.entries.sortedBy { it.key }.map { (id, at) -> JSONObject().put("chat", id).put("at", at) }),
        )
        put(
            "removed",
            JSONArray(doc.removed.map { JSONObject().put("list", it.list.code).put("key", it.key).put("at", it.at) }),
        )
        if (!doc.lists.isEmpty) put("lists", NamedListsJson.json(doc.lists))
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    // ---- reading ----------------------------------------------------------------------------

    /**
     * Reads a file, gzipped or plain. Anything malformed inside a well formed file (an entry
     * missing its ids, a number where a name was) is skipped on its own; the rest still reads.
     * A file of [account]'s is read; one naming another account is refused.
     */
    fun decode(bytes: ByteArray, account: Long): SyncRead {
        if (bytes.size > MAX_READ_BYTES) return SyncRead.NotASyncFile
        val text = (if (isGzip(bytes)) gunzip(bytes) else bytes)?.decodeToString() ?: return SyncRead.NotASyncFile
        val root = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return SyncRead.NotASyncFile
        } catch (_: StackOverflowError) {
            // A file of nothing but nested brackets: org.json reads nesting by recursion.
            return SyncRead.NotASyncFile
        }
        if (root.optString("format") != FORMAT) return SyncRead.NotASyncFile
        val version = (root.opt("version") as? Number)?.toDouble() ?: return SyncRead.NotASyncFile
        if (version.isNaN() || version < 1) return SyncRead.NotASyncFile
        if (version > VERSION) return SyncRead.TooNew(version.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())
        val owner = (root.opt("account") as? Number)?.toLong() ?: 0L
        if (owner != 0L && account != 0L && owner != account) return SyncRead.OtherAccount(owner)
        return SyncRead.Ok(
            SyncDocument(
                account = owner,
                updatedAt = (root.opt("updatedAt") as? Number)?.toLong() ?: 0L,
                appVersion = (root.opt("app") as? String).orEmpty().take(40),
                resume = Backups.objects(root.optJSONArray("resume")).take(MAX_RESUME * 2).mapNotNull { json ->
                    Backups.resumeOf(json)?.let(::strip)
                },
                watched = Backups.objects(root.optJSONArray("watched")).take(MAX_WATCHED * 2).mapNotNull { json ->
                    Backups.watchedOf(json)?.let(::strip)
                },
                watchLater = Backups.objects(root.optJSONArray("watchLater")).take(MAX_WATCH_LATER * 2).mapNotNull { json ->
                    Backups.watchLaterOf(json)?.let(::strip)
                },
                favourites = Backups.objects(root.optJSONArray("favourites")).take(MAX_FAVOURITES * 2).mapNotNull { json ->
                    val id = (json.opt("chat") as? Number)?.toLong() ?: return@mapNotNull null
                    val at = (json.opt("at") as? Number)?.toLong() ?: 0L
                    id to at
                }.groupBy({ it.first }, { it.second }).mapValues { (_, ats) -> ats.max() },
                removed = Backups.objects(root.optJSONArray("removed")).take(MAX_REMOVALS * 2).mapNotNull { json ->
                    val list = SyncList.ofCode(json.opt("list") as? String) ?: return@mapNotNull null
                    val key = json.opt("key") as? String ?: return@mapNotNull null
                    val at = (json.opt("at") as? Number)?.toLong() ?: return@mapNotNull null
                    SyncRemoval(list, key, at).takeIf { validKey(list, key) }
                },
                // A version 1 file has none: the field is new with version 2.
                lists = strip(NamedListsJson.of(root.optJSONObject("lists"))),
                version = VERSION,
            ),
        )
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** Unpacked, or null when cut short, broken, or unpacking past [MAX_TEXT_BYTES]. */
    private fun gunzip(bytes: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_TEXT_BYTES) return null
            }
            out.toByteArray()
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
