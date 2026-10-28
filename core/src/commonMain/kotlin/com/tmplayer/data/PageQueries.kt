package com.tmplayer.data

import com.tmplayer.platform.Background
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's one [QueryCache], with what ties it to Telegram: the pages' staleTimes, the updates that
 * patch or invalidate what is kept, sign out forgetting it all, prefetching a chat's first page,
 * and the chat listings kept on the disk between launches ([store]).
 *
 * Started on first use: the first screen to read the cache starts following TDLib's updates.
 */
@OptIn(FlowPreview::class)
object PageQueries {

    /** A chat's listing older than this is asked again when it is opened, behind the kept copy. */
    const val GRID_STALE_MS = 60_000L

    /** A search across every chat this old is asked again; younger, it is listed as it was. */
    const val SEARCH_STALE_MS = 5 * 60_000L

    /** A chat's topics, extras and info change rarely: asked again after this long. */
    const val CHAT_STALE_MS = 10 * 60_000L

    /** A computed page (a show's episodes) is worked out again after this long. */
    const val COMPUTED_STALE_MS = 60_000L

    /** How long a chat row is focused or pointed at before its first page is prefetched. */
    const val PREFETCH_DWELL_MS = 300L

    /** How many chat listings are kept on the disk, and how many videos of each. */
    private const val DISK_GRIDS = 12
    private const val DISK_ITEMS = 240

    /** Writes to the disk wait for the listing to settle this long, so a burst is one write. */
    private const val SAVE_SETTLE_MS = 2_000L

    /** Live posts are gathered this long before the kept listings are patched. */
    private const val LIVE_WINDOW_MS = 2_000L

    val cache = QueryCache(Background.scope)

    /**
     * Where the chat listings opened most recently are kept between launches; null keeps none
     * (the browser, a test). Set once at start, before [preload].
     */
    @Volatile
    var store: GridStore? = null

    /** The topic last listed in each group, so reopening one starts on it without waiting for a read. */
    private val topics = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** The topic [chatId] was last listed on, or null when not known this run. */
    fun lastTopic(chatId: Long): Int? = topics.value[chatId]

    fun noteTopic(chatId: Long, topicId: Int) {
        topics.update { if (it[chatId] == topicId) it else it + (chatId to topicId) }
    }

    init {
        followUpdates()
    }

    /** Reads the listings kept on the disk into the cache. Blocks on the disk: off the main thread. */
    fun preload() {
        val saved = runCatching { store?.load() }.getOrNull().orEmpty()
        // Signed out meanwhile: they are nobody's now.
        val auth = Td.auth.value
        if (auth != AuthState.Ready && auth != AuthState.Connecting && auth !is AuthState.Failed) return
        saved.forEach { kept ->
            val key = QueryKey.ChatMedia(kept.chatId, kept.topicId)
            if (cache.data<ChatGrid>(key) == null) cache.put(key, kept.grid, at = kept.savedAt)
        }
    }

    /** Forgets every page, in memory and on the disk: the account they belong to has gone. */
    fun clear() {
        cache.clear()
        topics.value = emptyMap()
        GoneVideos.clear()
        val disk = store ?: return
        Background.scope.launch { runCatching { disk.clear() } }
    }

    /**
     * A chat about to be opened (its row focused or pointed at, its row shown on Home): its first
     * page fetched now, at low priority, unless what is kept is still fresh. Nothing while signed
     * out or still connecting.
     */
    fun prefetchChat(chatId: Long) {
        if (Td.auth.value !is AuthState.Ready) return
        val key = QueryKey.ChatMedia(chatId, lastTopic(chatId) ?: 0)
        cache.prefetch(key, GRID_STALE_MS) { refreshGrid(key) }
    }

    /**
     * [key]'s first page asked again and laid over what is kept ([ChatGrid.refreshed]): the
     * revalidation of a browsed chat, shared by a prefetch and an opened grid. Not for a search,
     * whose first page has its own retries.
     */
    suspend fun refreshGrid(key: QueryKey.ChatMedia): ChatGrid {
        val session = Td.awaitAuthorizedSession()
        val page = ChatRepository(session.client).mediaPage(key.chatId, MediaCursors(), key.query, topicId = key.topicId)
        // An answer for an account that has since gone is nobody's to keep.
        if (!session.isCurrent()) throw CancellationException("signed out")
        val old = cache.data<ChatGrid>(key)
        return if (old == null) ChatGrid.of(page, key.query) else ChatGrid.refreshed(old, page)
    }

    private fun followUpdates() {
        // A video posted, edited or deleted in a chat: its kept listings are patched in place, so
        // the next visit shows it without asking. An edit has to be read again, so it marks them
        // stale instead, and the next visit asks behind the kept copy.
        Background.scope.launch {
            Td.videoChanges.batched(LIVE_WINDOW_MS).collect { batches -> batches.forEach(::apply) }
        }
        // Sign out, or a session lost: every page was the last account's.
        Background.scope.launch {
            Td.auth.collect { state ->
                when (state) {
                    AuthState.Ready, AuthState.Connecting, is AuthState.Failed -> Unit
                    // Each time, not only when something is held: the disk may still have the
                    // listings of an account whose session ended while the app was closed.
                    else -> clear()
                }
            }
        }
        // The chat listings most recently used, written to the disk once they settle.
        Background.scope.launch {
            cache.changes
                .filter { it is QueryKey.ChatMedia && it.query.isEmpty() }
                .debounce(SAVE_SETTLE_MS)
                .collect { saveGrids() }
        }
    }

    private fun apply(batch: VideoBatch) {
        val keys = cache.keys().filter { it.chatId == batch.chatId }
        for (key in keys) {
            when (key) {
                is QueryKey.ChatMedia -> cache.update<ChatGrid>(key) { grid ->
                    if (grid == null) return@update null
                    // A search lists what matched: a new post is not part of it, a deleted one goes.
                    val added = if (key.query.isNotEmpty()) emptyList() else batch.added.filter { key.topicId == 0 || it.topicId == key.topicId }
                    val items = VideoChanges.merge(grid.items, batch, added)
                    if (items === grid.items) grid else grid.copy(items = items)
                }
                else -> Unit
            }
        }
        if (batch.edited.isNotEmpty()) cache.invalidate { it is QueryKey.ChatMedia && it.chatId == batch.chatId }
        // A new post may be the pinned one, a deleted one may have been: counted again on the next visit.
        cache.invalidate { it is QueryKey.ChatExtras && it.chatId == batch.chatId }
    }

    private suspend fun saveGrids() {
        val disk = store ?: return
        val grids = cache.keys().asReversed()
            .filterIsInstance<QueryKey.ChatMedia>()
            .filter { it.query.isEmpty() }
            .take(DISK_GRIDS)
            .mapNotNull { key ->
                val entry = cache.entry(key) ?: return@mapNotNull null
                val grid = entry.data as? ChatGrid ?: return@mapNotNull null
                val trimmed = if (grid.items.size <= DISK_ITEMS) grid else trimForDisk(grid)
                SavedGrid(key.chatId, key.topicId, trimmed, entry.updatedAt)
            }
        if (Td.auth.value !is AuthState.Ready && Td.auth.value !is AuthState.Connecting) return
        withContext(Dispatchers.IO) { runCatching { disk.save(grids) } }
    }

    /**
     * The first [DISK_ITEMS] videos of a long listing. Its cursors no longer follow on from the
     * last of them, so it is written as one that needs its pages asked again: the first page is
     * revalidated on opening anyway, and scrolling on reads the rest.
     */
    private fun trimForDisk(grid: ChatGrid): ChatGrid {
        val items = grid.items.take(DISK_ITEMS)
        val oldest = items.minOf { it.messageId }
        return grid.copy(
            items = items,
            cursors = MediaCursors(video = oldest, document = oldest, animation = oldest),
            endReached = false,
            scrollIndex = 0,
            scrollOffset = 0,
        )
    }
}
