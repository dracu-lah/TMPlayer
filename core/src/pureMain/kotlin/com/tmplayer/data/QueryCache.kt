package com.tmplayer.data

import com.tmplayer.platform.nowMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.yield

/**
 * What a page's data is filed under in the [QueryCache]. Each is one thing a screen asks Telegram
 * for, named by everything that changes the answer, so two screens asking the same thing share it.
 */
sealed interface QueryKey {
    /** The chat the answer comes from, for the updates that touch one chat; null for none. */
    val chatId: Long? get() = null

    /** A chat's video listing ([ChatGrid]): the whole chat or one forum topic, browsed or searched. */
    data class ChatMedia(override val chatId: Long, val topicId: Int = 0, val query: String = "") : QueryKey

    /** A chat's forum topics, as the chips over its listing show them. */
    data class Topics(override val chatId: Long) : QueryKey

    /** What sits over a chat's listing besides the videos: its audio, round videos and pinned post. */
    data class ChatExtras(override val chatId: Long) : QueryKey

    /** A chat's info, for its sheet or panel. */
    data class ChatInfo(override val chatId: Long) : QueryKey

    /** A channel post's comment count, for a detail page's "Videos in the comments". */
    data class CommentCount(override val chatId: Long, val messageId: Long) : QueryKey

    /** A paged list hanging off one chat or post: its round videos, a post's comments. */
    data class Paged(val kind: String, override val chatId: Long, val messageId: Long = 0) : QueryKey

    /** A show's episodes as one detail page gathers them from what is known, by the video's id. */
    data class SeriesOf(val itemId: String) : QueryKey

    /** The search across every chat: Telegram's pages for [query] under the server side [scope]. */
    data class Search(val query: String, val scope: String) : QueryKey

    /** The chat list of one folder, 0 for every chat. */
    data class ChatList(val folder: Int = 0) : QueryKey
}

/**
 * A small page cache in the manner of TanStack Query, shared by every screen on every platform, so
 * a page visited before draws at once from what it showed last and is brought up to date behind.
 *
 * - An answer is kept by its [QueryKey] with when it arrived. [peek] hands it back at once, saying
 *   whether it is stale: older than the staleTime the caller gives, or [invalidate]d since.
 * - [fetch] asks once for any number of callers: a second screen asking the same key while the
 *   first request runs waits on that one rather than sending its own. The request runs in this
 *   cache's [scope], so a screen closed half way does not throw away an answer the next visit wants.
 * - [prefetch] is the same at low priority, at most [prefetchLimit] at a time and dropped rather
 *   than queued when that many are already running: a hint, never a backlog.
 * - At most [maxEntries] answers are kept, the least recently used going first, and one nobody has
 *   looked at for [gcTimeMs] goes at the next write.
 *
 * Platform neutral: no locks and no JDK. The table is one immutable map swapped whole with a
 * compare and set ([MutableStateFlow.compareAndSet]), which is safe from any thread on the JVM and
 * costs nothing on the browser's one thread. Every write is a copy of a map of a few dozen entries.
 *
 * What is kept is what Telegram answered, before the app's own rules (size limits, the kids lock,
 * hidden chats): the screens apply those on reading, so a setting changed since is right from the
 * cached copy too.
 */
class QueryCache(
    val scope: CoroutineScope,
    private val maxEntries: Int = 160,
    private val gcTimeMs: Long = 30 * 60_000L,
    private val prefetchLimit: Int = 3,
    private val clock: () -> Long = ::nowMillis,
) {
    /** One answer, with when it arrived and when it was last asked for. */
    class Entry(
        val data: Any?,
        val updatedAt: Long,
        val invalidated: Boolean,
        val usedAt: Long,
    )

    /** An answer as [peek] hands it out. */
    data class Cached<T>(val data: T, val updatedAt: Long, val stale: Boolean)

    /**
     * The answers and the epoch they belong to. [clear] starts a new epoch, and an answer asked for
     * in an older one is never kept ([putIf]): a request still on its way when the account changed
     * (a sign out, another profile opened, HX88) lands nowhere. A class compared by identity, so a
     * compare and set never mistakes a new empty table for an old one.
     */
    private class Table(val epoch: Long, val map: Map<QueryKey, Entry>)

    private val table = MutableStateFlow(Table(0L, emptyMap()))
    private val running = MutableStateFlow<Map<QueryKey, Deferred<Any?>>>(emptyMap())
    private val prefetching = Semaphore(prefetchLimit)

    private val _changes = MutableSharedFlow<QueryKey>(extraBufferCapacity = 64)

    /** Each key whose answer was written, patched, invalidated or dropped, for a screen showing it. */
    val changes: SharedFlow<QueryKey> = _changes.asSharedFlow()

    /** How many answers are kept, for tests and the logs. */
    val size: Int get() = table.value.map.size

    /** Every key kept, most recently used last. */
    fun keys(): List<QueryKey> = table.value.map.entries.sortedBy { it.value.usedAt }.map { it.key }

    /**
     * The answer kept for [key], or null; stale once older than [staleTimeMs] or invalidated.
     * Counts as a use, so it is the last to go when the cache is full.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: QueryKey, staleTimeMs: Long = 0): Cached<T>? {
        val entry = table.value.map[key] ?: return null
        val now = clock()
        edit { map -> map[key]?.let { map + (key to Entry(it.data, it.updatedAt, it.invalidated, now)) } ?: map }
        return Cached(entry.data as T, entry.updatedAt, entry.invalidated || now - entry.updatedAt >= staleTimeMs)
    }

    /** What is kept for [key], with its times, without counting a use; null when nothing is. */
    fun entry(key: QueryKey): Entry? = table.value.map[key]

    /** The data kept for [key] without counting a use, or null. */
    @Suppress("UNCHECKED_CAST")
    fun <T> data(key: QueryKey): T? = table.value.map[key]?.data as T?

    /** Keeps [data] as the answer for [key], fresh as of [at]. */
    fun <T> put(key: QueryKey, data: T, at: Long = clock()) {
        val now = clock()
        edit { map -> trim(map + (key to Entry(data, at, false, now)), now) }
        _changes.tryEmit(key)
    }

    /**
     * Changes the answer kept for [key] in place, as an update from Telegram does: [change] gets the
     * current data (null when none is kept) and returns the new, or null to drop it. Its age is
     * kept: a patched list is as fresh as the answer it was patched from.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> update(key: QueryKey, change: (T?) -> T?) {
        val now = clock()
        var touched = false
        edit { map ->
            val before = map[key]
            val after = change(before?.data as T?)
            touched = after !== before?.data
            when {
                !touched -> map
                after == null -> map - key
                else -> trim(map + (key to Entry(after, before?.updatedAt ?: now, before?.invalidated ?: false, now)), now)
            }
        }
        if (touched) _changes.tryEmit(key)
    }

    /** Marks every kept answer whose key passes [which] stale, keeping it to draw until the refetch lands. */
    fun invalidate(which: (QueryKey) -> Boolean) {
        val hit = ArrayList<QueryKey>()
        edit { map ->
            hit.clear()
            map.mapValues { (key, entry) ->
                if (which(key) && !entry.invalidated) {
                    hit += key
                    Entry(entry.data, entry.updatedAt, true, entry.usedAt)
                } else {
                    entry
                }
            }
        }
        hit.forEach { _changes.tryEmit(it) }
    }

    /** Forgets every answer whose key passes [which]. */
    fun remove(which: (QueryKey) -> Boolean) {
        edit { map -> map.filterKeys { !which(it) } }
    }

    /**
     * Forgets every answer nobody asked for in the last [idleMs], except one still being
     * fetched: the system is short of memory ([MemoryTrim]). What a screen shows now was asked for
     * moments ago and stays; the rest is asked again when it is next needed. How many went.
     */
    fun dropIdle(idleMs: Long): Int {
        val cutoff = clock() - idleMs.coerceAtLeast(0)
        var dropped = 0
        edit { map ->
            val kept = map.filter { (key, entry) -> entry.usedAt >= cutoff || running.value.containsKey(key) }
            dropped = map.size - kept.size
            if (dropped == 0) map else kept
        }
        return dropped
    }

    /** Forgets everything and starts a new [epoch]: for sign out or a switch, when every answer is the last account's. */
    fun clear() {
        while (true) {
            val before = table.value
            if (table.compareAndSet(before, Table(before.epoch + 1, emptyMap()))) break
        }
        running.value = emptyMap()
    }

    /** Which [clear] the cache is on: taken before asking for something, handed to [putIf] with the answer. */
    fun epoch(): Long = table.value.epoch

    /** [put], unless the cache was cleared since [epoch]. Returns whether it was kept. */
    fun <T> putIf(epoch: Long, key: QueryKey, data: T, at: Long = clock()): Boolean {
        val now = clock()
        var kept = false
        while (true) {
            val before = table.value
            if (before.epoch != epoch) break
            if (table.compareAndSet(before, Table(before.epoch, trim(before.map + (key to Entry(data, at, false, now)), now)))) {
                kept = true
                break
            }
        }
        if (kept) _changes.tryEmit(key)
        return kept
    }

    /**
     * Asks [fetcher] for [key] and keeps its answer, unless the same key is already being asked,
     * in which case that request's answer is awaited instead. The caller's cancellation leaves
     * the request running for whoever else waits on it, and for the next visit.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> fetch(key: QueryKey, fetcher: suspend () -> T): T {
        val existing = running.value[key]
        if (existing != null) return existing.await() as T
        val started = CompletableDeferred<Any?>()
        while (true) {
            val now = running.value
            now[key]?.let { return it.await() as T }
            if (running.compareAndSet(now, now + (key to started))) break
        }
        // The failure goes to the callers through [started], never up the cache's scope.
        val asked = epoch()
        val request = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val answer = fetcher()
                putIf(asked, key, answer)
                started.complete(answer)
            } catch (e: Throwable) {
                started.completeExceptionally(e)
            } finally {
                while (true) {
                    val now = running.value
                    if (now[key] !== started || running.compareAndSet(now, now - key)) break
                }
            }
        }
        return started.await().also { request.join() } as T
    }

    /** Whether [key] is being asked for right now. */
    fun isFetching(key: QueryKey): Boolean = running.value.containsKey(key)

    /**
     * Fetches [key] in the background when what is kept is missing or older than [staleTimeMs]:
     * the stale while revalidate half of [peek]. Returns the job, or null when nothing was needed.
     */
    fun <T> revalidate(key: QueryKey, staleTimeMs: Long, fetcher: suspend () -> T): Job? {
        val entry = table.value.map[key]
        if (entry != null && !entry.invalidated && clock() - entry.updatedAt < staleTimeMs) return null
        if (isFetching(key)) return null
        return scope.launch { runCatching { fetch(key, fetcher) } }
    }

    /**
     * A hint that [key] is about to be wanted (a chat row focused, a chat shown on Home): fetched
     * when missing or stale, behind everything else, and skipped when [prefetchLimit] prefetches
     * are already running or the key is being asked anyway.
     */
    fun <T> prefetch(key: QueryKey, staleTimeMs: Long, fetcher: suspend () -> T): Boolean {
        val entry = table.value.map[key]
        if (entry != null && !entry.invalidated && clock() - entry.updatedAt < staleTimeMs) return false
        if (isFetching(key)) return false
        if (!prefetching.tryAcquire()) return false
        scope.launch {
            try {
                // Low priority: whatever was already queued on the dispatcher goes first.
                yield()
                runCatching { fetch(key, fetcher) }
            } finally {
                prefetching.release()
            }
        }
        return true
    }

    private inline fun edit(change: (Map<QueryKey, Entry>) -> Map<QueryKey, Entry>) {
        while (true) {
            val before = table.value
            val after = change(before.map)
            if (after === before.map || table.compareAndSet(before, Table(before.epoch, after))) return
        }
    }

    /** Drops what has gone unused for [gcTimeMs], then the least recently used past [maxEntries]. */
    private fun trim(map: Map<QueryKey, Entry>, now: Long): Map<QueryKey, Entry> {
        var kept = map
        if (kept.values.any { now - it.usedAt > gcTimeMs }) kept = kept.filterValues { now - it.usedAt <= gcTimeMs }
        if (kept.size <= maxEntries) return kept
        val over = kept.size - maxEntries
        val oldest = kept.entries.sortedBy { it.value.usedAt }.take(over).mapTo(HashSet()) { it.key }
        return kept.filterKeys { it !in oldest }
    }
}

/**
 * A chat's listing as Telegram gave it, page after page, kept by [QueryKey.ChatMedia]: the videos
 * before the size limits (which the grid applies on reading), the cursors to carry on from, and
 * where the grid was scrolled to when it was left, so coming back finds the same pages at the same
 * place.
 */
data class ChatGrid(
    /** Newest first, each once. Downloaded ones already marked as such. */
    val items: List<MediaItem>,
    val cursors: MediaCursors,
    val endReached: Boolean,
    val archives: List<ArchiveFile> = emptyList(),
    /** Self-destructing videos the pages read so far left out. */
    val selfDestructing: Int = 0,
    /** What Telegram was asked for a search, which may be one word of what was typed. */
    val serverQuery: String = "",
    /** The first item shown when the grid was left, and how far it was scrolled past. */
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
) {
    /** The next page appended. */
    fun plus(page: MediaPage): ChatGrid = copy(
        items = (items + page.items).distinctBy { it.messageId },
        cursors = page.cursors,
        endReached = page.endReached,
        archives = Archives.merge(archives, page.archives),
        selfDestructing = selfDestructing + page.hiddenSelfDestructing,
    )

    companion object {
        /** A listing of one page. */
        fun of(page: MediaPage, serverQuery: String = ""): ChatGrid = ChatGrid(
            items = page.items.distinctBy { it.messageId },
            cursors = page.cursors,
            endReached = page.endReached,
            archives = page.archives,
            selfDestructing = page.hiddenSelfDestructing,
            serverQuery = serverQuery,
        )

        /**
         * [old], the listing kept, brought up to date by [fresh], its first page asked again.
         *
         * The first page is the truth for the newest messages, down to the oldest message any of
         * its three searches (videos, files, animations) reached: within that, what [old] holds
         * and [fresh] does not was deleted. Below it, [old]'s pages are kept with their cursors,
         * so the pages scrolled through stay listed. Where nothing of [old] lies below (every
         * search ran out, or so much was posted that the two do not meet), [fresh] stands alone.
         */
        fun refreshed(old: ChatGrid, fresh: MediaPage): ChatGrid {
            val c = fresh.cursors
            val reached = listOfNotNull(
                c.video.takeIf { !c.videoDone },
                c.document.takeIf { !c.documentDone },
                c.animation.takeIf { !c.animationDone },
            ).maxOrNull() ?: 0L
            val freshIds = fresh.items.mapTo(HashSet()) { it.messageId }
            val tail = if (reached <= 0L) emptyList() else old.items.filter { it.messageId < reached && it.messageId !in freshIds }
            // The two meet only when [old] reaches up into the first page: otherwise what was posted
            // between [old]'s newest and the first page's oldest is in neither, a gap in the listing.
            val meets = old.items.any { it.messageId >= reached }
            if (tail.isEmpty() || !meets) {
                return of(fresh, old.serverQuery).copy(scrollIndex = old.scrollIndex, scrollOffset = old.scrollOffset)
            }
            return old.copy(
                items = fresh.items.distinctBy { it.messageId } + tail,
                archives = Archives.merge(fresh.archives, old.archives),
            )
        }
    }
}

/** A chat's listing as it is kept between launches by a [GridStore]. */
data class SavedGrid(val chatId: Long, val topicId: Int, val grid: ChatGrid, val savedAt: Long)

/**
 * Where the chat listings opened most recently are kept between launches, so a cold start opens a
 * chat at once too: a file on the JVM, nothing on the browser. Each call may block on the disk.
 */
interface GridStore {
    fun load(): List<SavedGrid>

    /** Writes [grids], gathered for the profile of [stamp] (HX88): dropped when it is no longer open. */
    fun save(grids: List<SavedGrid>, stamp: AccountStamp)
    fun clear()
}
