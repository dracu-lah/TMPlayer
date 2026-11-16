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

    /** A bookmark, named `<chat>_<message>_<id>`. */
    Bookmark("bookmark"),

    /** A viewer's correction of a title or poster, named `<chat>_<message>`. */
    Correction("correction"),
    ;

    companion object {
        fun ofCode(code: String?): SyncList? = entries.firstOrNull { it.code == code }
    }
}

/** A bookmark with when it was last made or changed: its note's edit time, else its own id (the moment it was made). */
data class SyncBookmark(val bookmark: Bookmark, val at: Long) {
    /** The name removals use: `<chat>_<message>_<id>`. */
    val key: String get() = keyOf(bookmark)

    companion object {
        fun keyOf(b: Bookmark): String = "${b.chatId}_${b.messageId}_${b.id}"
    }
}

/**
 * A correction the viewer made by hand ([VideoCorrection]) as [VideoCorrection.encode] writes it,
 * with when it was last changed on the device that made it.
 */
data class SyncCorrection(val chatId: Long, val messageId: Long, val text: String, val at: Long) {
    val key: String get() = ProgressKeys.of(chatId, messageId)
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
 * Settings, memories and the app and kids locks never travel here. Since version 3 the viewer's
 * bookmarks (position and the short note they wrote) and corrections (their own renames and
 * picks) do, each with the time it was last changed and a removal to tell it was taken off.
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
    /** The viewer's bookmarks (version 3), notes cut to [BookmarkCodec.MAX_NOTE]. */
    val bookmarks: List<SyncBookmark> = emptyList(),
    /** The viewer's corrections (version 3): "Not the same video", "Wrong match", a rename, a poster pick. */
    val corrections: List<SyncCorrection> = emptyList(),
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
    val bookmarks: Int = 0,
    val corrections: Int = 0,
) {
    val total: Int get() = resume + watched + watchLater + removals + listEntries + bookmarks + corrections

    operator fun plus(other: SyncDropped) = SyncDropped(
        resume + other.resume,
        watched + other.watched,
        watchLater + other.watchLater,
        removals + other.removals,
        listEntries + other.listEntries,
        bookmarks + other.bookmarks,
        corrections + other.corrections,
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
    /** Bookmarks newer than this device's, or ones it has no copy of. */
    val bookmarksIn: List<SyncBookmark> = emptyList(),
    /** Bookmarks removed on another device after this one last changed them: [SyncBookmark.key] to removal time. */
    val bookmarksOut: Map<String, Long> = emptyMap(),
    val correctionsIn: List<SyncCorrection> = emptyList(),
    /** Corrections cleared on another device after this one last changed them: [SyncCorrection.key] to removal time. */
    val correctionsOut: Map<String, Long> = emptyMap(),
) {
    val received: Int get() = resumeIn.size + watchedIn.size + laterIn.size + favouritesIn.size + bookmarksIn.size + correctionsIn.size
    val removing: Int get() = resumeOut.size + watchedOut.size + laterOut.size + favouritesOut.size + bookmarksOut.size + correctionsOut.size
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
    const val VERSION = 3

    /** What a file without named lists is written as: what every build since CP89 reads. */
    const val LISTLESS_VERSION = 1

    /** What a file with named lists but no bookmarks or corrections is written as (HX43). */
    const val LISTS_VERSION = 2

    /** At most this many bookmarks travel, the newest changed (a video keeps [BookmarkCodec.MAX_PER_VIDEO]). */
    const val MAX_BOOKMARKS = 2_000
    const val MAX_CORRECTIONS = 1_000

    /**
     * A time further ahead than this is a clock that runs fast, not an edit made in the future: it
     * counts as [mergeCounting]'s `now`, so one wrong clock cannot make its entries win for ever.
     */
    const val MAX_SKEW_MS = 24 * 60 * 60 * 1000L

    /** The names the settings and bookmark files keep corrections' and bookmarks' times and removals under. */
    const val CORRECTION_AT_PREFIX = "fixat_"
    const val CORRECTION_REMOVAL_PREFIX = "xfix_"
    const val BOOKMARK_AT_PREFIX = "bt_"
    const val BOOKMARK_REMOVAL_PREFIX = "xb_"

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
        bookmarks: Map<String, Any> = emptyMap(),
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
            addAll(extraRemovals(settings, bookmarks))
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
            bookmarks = bookmarksOf(bookmarks),
            corrections = correctionsOf(settings),
        )
    }

    /**
     * The bookmarks in the bookmark file's raw entries, each with its time: the note's last edit
     * where one was made (`bt_<chat>_<message>_<id>`), else the bookmark's own id, which is the
     * moment it was made.
     */
    fun bookmarksOf(raw: Map<String, Any>): List<SyncBookmark> = buildList {
        for ((name, value) in raw) {
            val ids = name.removePrefixOrNull(BookmarkCodec.PREFIX)?.split('_') ?: continue
            if (ids.size != 2) continue
            val chatId = ids[0].toLongOrNull() ?: continue
            val messageId = ids[1].toLongOrNull() ?: continue
            for (mark in BookmarkCodec.decode(chatId, messageId, value as? String)) {
                val stripped = strip(mark) ?: continue
                val edited = (raw[BOOKMARK_AT_PREFIX + SyncBookmark.keyOf(mark)] as? Long) ?: 0L
                add(SyncBookmark(stripped, maxOf(edited, mark.id)))
            }
        }
    }.sortedWith(compareByDescending<SyncBookmark> { it.at }.thenBy { it.key }).take(MAX_BOOKMARKS * 2)

    /** The corrections in the settings' raw entries: `fix_<chat>_<message>` with `fixat_` for when. */
    fun correctionsOf(raw: Map<String, Any>): List<SyncCorrection> = buildList {
        for ((name, value) in raw) {
            val id = VideoCorrections.idOf(name) ?: continue
            val ids = id.split(':')
            val correction = stripCorrection(VideoCorrection.decode(value as? String)) ?: continue
            val at = (raw[CORRECTION_AT_PREFIX + name.removePrefix(VideoCorrections.PREFIX)] as? Long) ?: 0L
            add(SyncCorrection(ids[0].toLongOrNull() ?: continue, ids[1].toLongOrNull() ?: continue, correction.encode(), maxOf(at, 1L)))
        }
    }.sortedWith(compareByDescending<SyncCorrection> { it.at }.thenBy { it.key }).take(MAX_CORRECTIONS * 2)

    /** The removals of bookmarks (`xb_`, in the bookmark file) and corrections (`xfix_`, in the settings). */
    fun extraRemovals(settings: Map<String, Any>, bookmarks: Map<String, Any>): List<SyncRemoval> = buildList {
        for ((name, value) in bookmarks) {
            val at = (value as? Long)?.takeIf { it > 0 } ?: continue
            val key = name.removePrefixOrNull(BOOKMARK_REMOVAL_PREFIX) ?: continue
            if (validKey(SyncList.Bookmark, key)) add(SyncRemoval(SyncList.Bookmark, key, at))
        }
        for ((name, value) in settings) {
            val at = (value as? Long)?.takeIf { it > 0 } ?: continue
            val key = name.removePrefixOrNull(CORRECTION_REMOVAL_PREFIX) ?: continue
            if (validKey(SyncList.Correction, key)) add(SyncRemoval(SyncList.Correction, key, at))
        }
    }

    private fun String.removePrefixOrNull(prefix: String): String? = if (startsWith(prefix)) removePrefix(prefix) else null

    /** A bookmark as it travels: a note of a line, a position and an id that mean something. */
    private fun strip(mark: Bookmark): Bookmark? {
        if (mark.id <= 0 || mark.positionMs < 0) return null
        return mark.copy(note = BookmarkCodec.cleanNote(mark.note))
    }

    /** A correction as it travels: a rename cut to [TITLE_MAX]; one that says nothing is no correction. */
    private fun stripCorrection(c: VideoCorrection): VideoCorrection? =
        c.copy(title = c.title?.trim()?.take(TITLE_MAX)?.trim()?.ifEmpty { null }).takeIf { !it.isEmpty }

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
        SyncList.Resume, SyncList.Watched, SyncList.Correction ->
            key.split('_').let { it.size == 2 && it[0].toLongOrNull() != null && it[1].toLongOrNull() != null }
        SyncList.Bookmark -> key.split('_').let { it.size == 3 && it.all { part -> part.toLongOrNull() != null } }
    }

    /** [doc] with every time past [now] plus [MAX_SKEW_MS] read as [now]: see [MAX_SKEW_MS]. */
    internal fun clamped(doc: SyncDocument, now: Long): SyncDocument {
        val limit = now + MAX_SKEW_MS
        fun t(at: Long) = if (at > limit) now else at
        return doc.copy(
            resume = doc.resume.map { if (it.updatedAt > limit) it.copy(updatedAt = now) else it },
            watched = doc.watched.map { if (it.watchedAt > limit) it.copy(watchedAt = now) else it },
            watchLater = doc.watchLater.map { if (it.addedAt > limit) it.copy(addedAt = now) else it },
            favourites = doc.favourites.mapValues { t(it.value) },
            removed = doc.removed.map { if (it.at > limit) it.copy(at = now) else it },
            bookmarks = doc.bookmarks.map { if (it.at > limit) it.copy(at = now) else it },
            corrections = doc.corrections.map { if (it.at > limit) it.copy(at = now) else it },
        )
    }

    // ---- merging ----------------------------------------------------------------------------

    /** [a] and [b] as one file. See the class's own words for the rules. */
    fun merge(a: SyncDocument, b: SyncDocument, now: Long): SyncDocument = mergeCounting(a, b, now).first

    /** [merge], with how many entries the caps left out. */
    fun mergeCounting(left: SyncDocument, right: SyncDocument, now: Long): Pair<SyncDocument, SyncDropped> {
        val a = clamped(left, now)
        val b = clamped(right, now)
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

        // Of two copies of one bookmark the one changed last; of two with the same time, the longer
        // note, so the choice does not depend on which file is read first.
        val bookmarksAll = (a.bookmarks + b.bookmarks)
            .groupBy { it.key }
            .mapNotNull { (key, all) ->
                all.filter { it.at >= removedAt(SyncList.Bookmark, key) }
                    .maxWithOrNull(compareBy<SyncBookmark>({ it.at }, { it.bookmark.note.length }).thenBy { it.toString() })
            }
            .sortedWith(compareByDescending<SyncBookmark> { it.at }.thenBy { it.key })
        val bookmarks = bookmarksAll.take(MAX_BOOKMARKS)

        val correctionsAll = (a.corrections + b.corrections)
            .groupBy { it.key }
            .mapNotNull { (key, all) ->
                all.filter { it.at >= removedAt(SyncList.Correction, key) }
                    .maxWithOrNull(compareBy<SyncCorrection> { it.at }.thenBy { it.text })
            }
            .sortedWith(compareByDescending<SyncCorrection> { it.at }.thenBy { it.key })
        val corrections = correctionsAll.take(MAX_CORRECTIONS)

        // A removal with a newer entry kept over it has done its job; one whose entry a cap left
        // out of the file is kept, so that entry cannot come back from an older copy.
        val live: Map<Pair<SyncList, String>, Long> = buildMap {
            bookmarks.forEach { put(SyncList.Bookmark to it.key, it.at) }
            corrections.forEach { put(SyncList.Correction to it.key, it.at) }
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
            bookmarks = bookmarks,
            corrections = corrections,
        )
        val dropped = SyncDropped(
            resume = resumeAll.size - resume.size,
            watched = watchedAll.size - watched.size,
            watchLater = laterAll.size - later.size,
            removals = removalsAll.size - removed.size,
            bookmarks = bookmarksAll.size - bookmarks.size,
            corrections = correctionsAll.size - corrections.size,
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
        val localBookmarks = local.bookmarks.associateBy { it.key }
        val mergedBookmarks = merged.bookmarks.associateBy { it.key }
        val bookmarksIn = merged.bookmarks.filter { m ->
            val mine = localBookmarks[m.key]
            mine == null || mine.at < m.at
        }
        val bookmarksOut = localBookmarks.mapNotNull { (key, mine) ->
            if (key in mergedBookmarks) return@mapNotNull null
            val at = removedAt(SyncList.Bookmark, key) ?: return@mapNotNull null
            if (at > mine.at) key to at else null
        }.toMap()

        val localCorrections = local.corrections.associateBy { it.key }
        val mergedCorrections = merged.corrections.associateBy { it.key }
        val correctionsIn = merged.corrections.filter { m ->
            val mine = localCorrections[m.key]
            mine == null || mine.at < m.at
        }
        val correctionsOut = localCorrections.mapNotNull { (key, mine) ->
            if (key in mergedCorrections) return@mapNotNull null
            val at = removedAt(SyncList.Correction, key) ?: return@mapNotNull null
            if (at > mine.at) key to at else null
        }.toMap()
        return SyncApply(
            resumeIn, resumeOut, watchedIn, watchedOut, laterIn, laterOut, favouritesIn, favouritesOut,
            bookmarksIn, bookmarksOut, correctionsIn, correctionsOut,
        )
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
            // What the viewer wrote or fixed by hand goes after what the app counted for them.
            doc.corrections.isNotEmpty() -> {
                val n = quarter(doc.corrections.size)
                doc.copy(corrections = doc.corrections.sortedByDescending { it.at }.dropLast(n)) to SyncDropped(corrections = n)
            }
            doc.bookmarks.isNotEmpty() -> {
                val n = quarter(doc.bookmarks.size)
                doc.copy(bookmarks = doc.bookmarks.sortedByDescending { it.at }.dropLast(n)) to SyncDropped(bookmarks = n)
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
        val extras = doc.bookmarks.isNotEmpty() || doc.corrections.isNotEmpty() ||
            doc.removed.any { it.list == SyncList.Bookmark || it.list == SyncList.Correction }
        put("version", if (extras) VERSION else if (doc.lists.isEmpty) LISTLESS_VERSION else LISTS_VERSION)
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
        if (doc.bookmarks.isNotEmpty()) {
            put(
                "bookmarks",
                JSONArray(
                    doc.bookmarks.map {
                        JSONObject().put("chat", it.bookmark.chatId).put("msg", it.bookmark.messageId).put("id", it.bookmark.id)
                            .put("pos", it.bookmark.positionMs).put("note", it.bookmark.note).put("at", it.at)
                    },
                ),
            )
        }
        if (doc.corrections.isNotEmpty()) {
            put(
                "corrections",
                JSONArray(doc.corrections.map { JSONObject().put("chat", it.chatId).put("msg", it.messageId).put("fix", it.text).put("at", it.at) }),
            )
        }
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
                // Version 3's: absent in older files.
                bookmarks = Backups.objects(root.optJSONArray("bookmarks")).take(MAX_BOOKMARKS * 2).mapNotNull { json ->
                    val chat = (json.opt("chat") as? Number)?.toLong() ?: return@mapNotNull null
                    val msg = (json.opt("msg") as? Number)?.toLong() ?: return@mapNotNull null
                    val id = (json.opt("id") as? Number)?.toLong() ?: return@mapNotNull null
                    val pos = (json.opt("pos") as? Number)?.toLong() ?: return@mapNotNull null
                    val note = (json.opt("note") as? String).orEmpty()
                    val at = (json.opt("at") as? Number)?.toLong() ?: id
                    strip(Bookmark(chat, msg, id, pos, note))?.let { SyncBookmark(it, at) }
                },
                corrections = Backups.objects(root.optJSONArray("corrections")).take(MAX_CORRECTIONS * 2).mapNotNull { json ->
                    val chat = (json.opt("chat") as? Number)?.toLong() ?: return@mapNotNull null
                    val msg = (json.opt("msg") as? Number)?.toLong() ?: return@mapNotNull null
                    val fix = (json.opt("fix") as? String)?.take(1_000) ?: return@mapNotNull null
                    val at = (json.opt("at") as? Number)?.toLong() ?: 0L
                    stripCorrection(VideoCorrection.decode(fix))?.let { SyncCorrection(chat, msg, it.encode(), at) }
                },
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
