package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FeedLane
import com.tmplayer.data.FeedMerge
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaPage
import com.tmplayer.data.HomeCache
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.NewEpisode
import com.tmplayer.data.NewEpisodes
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShowReached
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedRecord
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import com.tmplayer.data.WatchLaterRecord
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import com.tmplayer.data.batched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where Home's videos come from: Telegram in the app, a fixture in the promo build and the tests. */
interface HomeSource {
    /** A starred chat's newest videos, newest first. */
    suspend fun chatMedia(chatId: Long): List<MediaItem>

    /**
     * [chatMedia], and whether that reached the chat's oldest video. A source that cannot tell says
     * it did, so a row only offers "See all" for what it can show is there.
     */
    suspend fun chatMediaPage(chatId: Long): Pair<List<MediaItem>, Boolean> = chatMedia(chatId) to true

    /**
     * [chatMediaPage], with where its read stopped ([FeedLane]: the cursors and the oldest date
     * read), so a See all page can read on from there. A source that cannot tell leaves the lane
     * null, and the page starts that chat from its newest video again.
     */
    suspend fun chatMediaRead(chatId: Long): Pair<List<MediaItem>, FeedLane?> {
        val (items, ended) = chatMediaPage(chatId)
        return items to if (ended) FeedLane(chatId, oldest = items.minOfOrNull { it.date }, done = true) else null
    }

    /** One page of [chatId]'s videos from [cursors], newest first, for a See all page reading on. */
    suspend fun chatMediaFrom(chatId: Long, cursors: MediaCursors): MediaPage =
        MediaPage(emptyList(), cursors, endReached = true)

    /** The newest videos across every chat. */
    suspend fun recentMedia(): List<MediaItem>

    /** The video behind a Continue watching record, for its picture; null when Telegram has no answer. */
    suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem?

    /** Whether [item]'s file is whole on this device now, rather than when it was fetched. */
    suspend fun wholeOnDevice(item: MediaItem): Boolean = item.onDevice

    /** The chats whose videos changed while Home is open, a window at a time. None by default. */
    fun changedChats(): Flow<Set<Long>> = emptyFlow()

    /** Asks for [chatId]'s new posts as they happen until the handle is closed; null where nothing follows them. */
    suspend fun follow(chatId: Long): AutoCloseable? = null

    /**
     * A show's newest videos in [chatId], found by [query] and kept to the forum topic [messageId]
     * (an episode the viewer watched) was posted in, newest first: one small search, for New
     * episodes. None by default.
     */
    suspend fun showMedia(chatId: Long, messageId: Long, query: String): List<MediaItem> = emptyList()
}

/** The signed in account, through TDLib. */
object TdHomeSource : HomeSource {
    private suspend fun repository() = ChatRepository(Td.awaitAuthorizedSession().client)

    override suspend fun chatMedia(chatId: Long): List<MediaItem> =
        repository().mediaPage(chatId, pageSize = CHAT_PAGE).items

    override suspend fun chatMediaPage(chatId: Long): Pair<List<MediaItem>, Boolean> =
        chatMediaRead(chatId).let { (items, lane) -> items to (lane?.done == true) }

    override suspend fun chatMediaRead(chatId: Long): Pair<List<MediaItem>, FeedLane?> {
        // Telegram's first answer is often short of the page asked for (Saved Messages gave 8
        // of 20 with more to come), so the next pages are read until a row's worth has come,
        // the chat runs out, or HOME_PAGES were read: a show a few uploads down was missing.
        val repository = repository()
        val items = ArrayList<MediaItem>()
        var lane = FeedLane(chatId)
        repeat(HOME_PAGES) {
            val page = repository.mediaPage(chatId, lane.cursors, pageSize = CHAT_PAGE)
            items += page.items
            lane = FeedMerge.advance(listOf(lane), chatId, page).single()
            if (lane.done || items.size >= CHAT_PAGE) return items to lane
        }
        return items to lane
    }

    override suspend fun chatMediaFrom(chatId: Long, cursors: MediaCursors): MediaPage =
        repository().mediaPage(chatId, cursors, pageSize = MORE_PAGE)

    override suspend fun recentMedia(): List<MediaItem> = repository().recentMedia()

    override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? =
        repository().mediaItem(chatId, messageId)

    override suspend fun wholeOnDevice(item: MediaItem): Boolean =
        Td.localFileAvailability(item.fileId) == LocalFileAvailability.Complete

    override fun changedChats(): Flow<Set<Long>> =
        // Not for every message edited into text: a busy group edits all day, and each would be a
        // search of that chat. A row showing a video edited away catches up at the next refresh.
        Td.videoChanges.filterNot { it is com.tmplayer.data.VideoChange.Removed && it.byEdit }.batched(LIVE_WINDOW_MS).map { batches -> batches.mapTo(HashSet()) { it.chatId } }

    override suspend fun follow(chatId: Long): AutoCloseable? {
        Td.awaitAuthorizedSession()
        return Td.watchChat(chatId)
    }

    override suspend fun showMedia(chatId: Long, messageId: Long, query: String): List<MediaItem> {
        val repository = repository()
        // In a group with topics the show is looked for in its own topic, as the player does, or
        // another section's "Lecture 12" would pass for the next one of this course.
        val topic = runCatching { repository.topicOf(chatId, messageId) }.getOrDefault(0)
        return repository.mediaPage(chatId, query = query, pageSize = SHOW_PAGE, topicId = topic).items
    }

    /** Per search, for a show in progress: what arrived since a visit is the newest handful. */
    private const val SHOW_PAGE = 10

    /** Longer than a grid's: a row fetched again is a search, and Home has several rows to keep. */
    private const val LIVE_WINDOW_MS = 3_000L

    /**
     * Per search, three searches a chat: room for a row of ten after the size limits and a show
     * folding several episodes into one tile, and a fraction of a full grid page.
     */
    private const val CHAT_PAGE = 20

    /** The most pages Home reads of one chat for its rows. */
    private const val HOME_PAGES = 4

    /** Per search when a See all page reads on: a grid's page, as a chat's own grid reads. */
    private const val MORE_PAGE = 40
}

/**
 * Home's videos, fetched a row at a time as the rows come on screen.
 *
 * Nothing is asked for up front: a row asks with [request] when the lazy list first composes it,
 * so somebody with thirty starred chats pays for the three on screen, and a 1 GB television is
 * never holding thirty pages of thumbnails at once. At most [PARALLEL] fetches run together.
 *
 * What arrives is kept for the life of the screen, so scrolling back up does not ask again;
 * [refresh] starts over.
 */
class HomeViewModel(
    private val source: HomeSource = TdHomeSource,
    /** The size limits from Settings, applied as in a chat's grid; read per fetch so a change lands on refresh. */
    private val sizeLimits: suspend () -> Pair<Long, Long> = { SizeFilter.DEFAULT_MIN to SizeFilter.DEFAULT_MAX },
    /** Where the shows in progress and their new episodes are worked out: off the main thread. */
    private val work: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
    /**
     * Whether Home is drawn from [HomeCache] and written back to it. Off in the tests and the
     * promo build, whose sources are fixtures.
     */
    private val cached: Boolean = false,
    /**
     * True while a video plays (the Android app's player): Home's own refetches, set off by a
     * post in one of its chats or by the line coming back, wait until it is false, so they never
     * compete with playback for the line and a weak CPU. Coming back from the player refreshes
     * Home anyway ([refreshIfStale]).
     */
    private val busy: () -> Boolean = { false },
    /** Where the Cached badges are read off the disk again ([refreshLocalAvailability]): off the main thread. */
    private val disk: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : ViewModel(com.tmplayer.platform.JobFailures.viewModelScope("Home")) {

    /** The rows [rememberHomeRows] built last, drawn again at once when Home comes back. */
    var lastRows: List<HomeRow> = emptyList()
        internal set

    /**
     * True once the rows have been built from an answer about the chats' videos: until then an
     * empty Home means "not built yet", not "nothing to show". Since the rows are built off the
     * main thread, Home opened empty for a moment on every cold start and showed the first run's
     * "Open Saved Messages" card to accounts full of videos.
     */
    var rowsSettled: Boolean by androidx.compose.runtime.mutableStateOf(false)
        internal set

    private val _loaded = MutableStateFlow<Map<Long, List<MediaItem>>>(emptyMap())

    /** Each starred chat's videos, once fetched. A chat missing here has not answered yet. */
    val loaded: StateFlow<Map<Long, List<MediaItem>>> = _loaded.asStateFlow()

    private val _partial = MutableStateFlow<Set<Long>>(emptySet())

    /** The starred chats whose fetch stopped short of their oldest video: they have more than Home holds. */
    val partial: StateFlow<Set<Long>> = _partial.asStateFlow()

    private val _recent = MutableStateFlow<List<MediaItem>?>(null)

    /** The newest videos across every chat, or null until they arrive. */
    val recent: StateFlow<List<MediaItem>?> = _recent.asStateFlow()

    private val _art = MutableStateFlow<Map<String, MediaItem>>(emptyMap())

    /** Continue watching and Watch later videos as Telegram describes them, by progress key, for their pictures. */
    val art: StateFlow<Map<String, MediaItem>> = _art.asStateFlow()

    /** The See all pages opened, by row key, kept while Home lives so going back and in again keeps their pages. */
    private val feeds = com.tmplayer.platform.LruMap<String, RowFeed>(FEEDS_KEPT)

    private val gate = Semaphore(PARALLEL)
    private val asked = HashSet<String>()
    private val jobs = mutableListOf<Job>()

    /** The starred chats held open in TDLib for their new posts, as their rows were fetched. */
    private val following = LinkedHashMap<Long, AutoCloseable>()

    /** The shows in progress, and every video the viewer has watched or started, by progress key. */
    private class Tracked(val shows: List<ShowReached>, val touched: Set<String>)

    /** Everything the New episodes row is drawn from, gathered for one pass of [NewEpisodes.collect]. */
    private class NewInputs(
        val tracked: Tracked,
        val found: Map<String, List<MediaItem>>,
        val loaded: Map<Long, List<MediaItem>>,
        val recent: List<MediaItem>?,
    ) {
        fun newEpisodes(): List<NewEpisode> {
            val pools = tracked.shows.associate { show ->
                val searched = found[show.key].orEmpty()
                // A search kept to a forum topic says which topic the show is in; the rows' videos
                // join only from that topic, or from a chat without topics when nothing says.
                val topic = searched.firstOrNull { it.topicId != 0 }?.topicId ?: 0
                val alongside = (loaded[show.chatId].orEmpty() + recent.orEmpty().filter { it.chatId == show.chatId })
                    .filter { it.topicId == topic }
                show.key to searched + alongside
            }
            return NewEpisodes.collect(tracked.shows, pools) { SettingsStore.progressKey(it.chatId, it.messageId) in tracked.touched }
        }
    }

    private val tracked = MutableStateFlow(Tracked(emptyList(), emptySet()))

    /** What each show's search found, by [ShowReached.key], within the size limits. */
    private val found = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())

    private val _newEpisodes = MutableStateFlow<List<NewEpisode>>(emptyList())

    /** The New episodes row's tiles, empty until a show in progress turns one up. */
    val newEpisodes: StateFlow<List<NewEpisode>> = _newEpisodes.asStateFlow()

    private var showsJob: Job? = null

    /**
     * The rows whose last fetch failed or ran past [ROW_TIMEOUT_MS], asked for again once Telegram
     * is reachable ([retryFailed]): a row lost to a dropped line comes back with the line, rather
     * than staying gone, or a skeleton waiting for good, until the next launch. Declared ahead of
     * `init`, whose collector of [Td.connected] can call [retryFailed] before the constructor ends.
     */
    private val failedChats = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** The chats whose grid Home has prefetched this run, at most [PREFETCH_CHATS] of them. */
    private val prefetched = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    @Volatile private var recentFailed = false

    private val _refreshing = MutableStateFlow(false)

    /** True while a refresh in place is fetching: the refresh control turns, the tiles stay up. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** When the rows were last brought up to date from Telegram, in elapsed real time; 0 for never. */
    @Volatile private var refreshedAt = 0L

    /**
     * Chats with new or changed videos not yet acted on; touched on the main thread only.
     * Declared ahead of `init`, whose collector fills it.
     */
    private val changedWhileBusy = HashSet<Long>()
    private var changedFlush: Job? = null

    private val created = System.nanoTime()
    @Volatile private var firstFresh = false

    init {
        // Home as it was last time, drawn on the first frame (no placeholder strips), then
        // brought up to date in place once Telegram answers: see [restore].
        if (cached) restore()
        // A show's new episodes are worked out again whenever anything they are drawn from moves:
        // a search landing, a starred chat's row or the recent row arriving or refreshing (which
        // is how a post in a starred chat shows up here without a search of its own), or the
        // viewer watching something. Off the main thread, and only the latest answer counts.
        viewModelScope.launch {
            combine(tracked, found, _loaded, _recent) { tracked, found, loaded, recent ->
                NewInputs(tracked, found, loaded, recent)
            }.collectLatest { inputs ->
                if (inputs.tracked.shows.isEmpty()) {
                    _newEpisodes.value = emptyList()
                    return@collectLatest
                }
                _newEpisodes.value = withContext(work) { inputs.newEpisodes() }
            }
        }
        // The line to Telegram back: the rows it cost are fetched again.
        viewModelScope.launch {
            Td.connected.collect { connected ->
                if (!connected) return@collect
                // A conflated state: waiting here drops nothing that matters.
                untilIdle()
                retryFailed()
            }
        }
        // A post in a starred chat refreshes its row, and the recent row that draws from those
        // chats, in place: the old tiles stay up until the new ones arrive.
        viewModelScope.launch {
            source.changedChats().collect { chats ->
                // Gathered while a video plays and acted on once it stops, never suspending the
                // flow of Telegram's updates here (see [busy]).
                changedWhileBusy += chats
                if (changedFlush?.isActive == true) return@collect
                changedFlush = viewModelScope.launch {
                    untilIdle()
                    val batch = changedWhileBusy.toSet()
                    changedWhileBusy.clear()
                    chatsChanged(batch)
                }
            }
        }
    }

    /** Returns once no video is playing ([busy]), checking every [BUSY_RECHECK_MS]. */
    internal suspend fun untilIdle() {
        while (busy()) kotlinx.coroutines.delay(BUSY_RECHECK_MS)
    }

    /** Asks again for the rows of [chats], which had new or changed videos, and the shows in them. */
    private fun chatsChanged(chats: Set<Long>) {
        // A post in the chat of a show in progress asks for that show again, past its cache.
        tracked.value.shows.filter { it.chatId in chats }.forEach { show ->
            showCache.remove(show.key)
            asked -= "show:${show.key}"
            requestShow(show)
        }
        val rows = chats.filter { it in _loaded.value }
        if (rows.isEmpty()) return
        rows.forEach { chatId ->
            asked -= "chat:$chatId"
            request(chatId)
        }
        if (_recent.value != null) {
            asked -= "recent"
            requestRecent()
        }
    }

    /**
     * Fills the rows from [HomeCache], and asks Telegram for each of them again, in place: what
     * was drawn stays up until its fresh copy lands, so nothing blinks, moves or loses focus.
     * The cache is written back a moment after the rows settle ([persist]).
     */
    private fun restore() {
        val snapshot = HomeCache.load()
        if (snapshot != null && !snapshot.isEmpty) {
            _loaded.value = snapshot.loaded
            _recent.value = snapshot.recent
            _partial.value = snapshot.partial
            _art.value = snapshot.art
            snapshot.art.values.forEach(RecordItems::keep)
            com.tmplayer.data.PerfLog.mark("home rows from cache (${snapshot.loaded.size} chats)", com.tmplayer.data.PerfLog.processStart)
            // Written moments ago (the desktop builds Home again on every visit): good as it is.
            val age = System.currentTimeMillis() - snapshot.savedAt
            if (age in 0 until STALE_MS) {
                refreshedAt = System.nanoTime() / 1_000_000 - age
                snapshot.loaded.keys.forEach { asked += "chat:$it" }
                if (snapshot.recent != null) asked += "recent"
            } else {
                refreshInPlace()
            }
        }
        viewModelScope.launch {
            combine(_loaded, _recent, _partial, _art) { loaded, recent, partial, art ->
                HomeCache.Snapshot(loaded, recent, partial, art, System.currentTimeMillis())
            }.collectLatest { snapshot ->
                // Settled, not mid-refresh: a row at a time arriving should not write ten times.
                kotlinx.coroutines.delay(if (com.tmplayer.ui.theme.Motion.television) PERSIST_AFTER_TV_MS else PERSIST_AFTER_MS)
                if (snapshot.isEmpty) return@collectLatest
                withContext(Dispatchers.IO) { HomeCache.save(snapshot) }
            }
        }
    }

    /**
     * Asks Telegram for every row Home holds again, keeping the tiles up meanwhile: the pull to
     * refresh, the television's Refresh, coming back to the app, and [AUTO_REFRESH_MS] while Home
     * is open. Rows answer one by one and replace their own tiles only, keyed as before, so the
     * list does not jump and the remote stays on its tile.
     */
    fun refreshInPlace() {
        refreshedAt = (System.nanoTime() / 1_000_000)
        // Every chat with a row, the ones still on their way and the ones that failed included.
        val chats = (_loaded.value.keys + asked.filter { it.startsWith("chat:") }.mapNotNull { it.removePrefix("chat:").toLongOrNull() } +
            synchronized(failedChats) { failedChats.toList() }).distinct()
        _refreshing.value = true
        chats.forEach { chatId ->
            asked -= "chat:$chatId"
            request(chatId)
        }
        asked -= "recent"
        requestRecent()
        viewModelScope.launch {
            // Done when every fetch asked above has come back.
            jobs.toList().forEach { it.join() }
            _refreshing.value = false
        }
    }

    /** [refreshInPlace] when the rows are older than [STALE_MS]: for the app coming back to the front. */
    fun refreshIfStale() {
        val at = refreshedAt
        if (at == 0L || (System.nanoTime() / 1_000_000) - at > STALE_MS) refreshInPlace()
    }

    /** Asks again for every row whose last fetch failed: see [failedChats]. */
    fun retryFailed() {
        val chats = synchronized(failedChats) { failedChats.toList().also { failedChats.clear() } }
        chats.forEach { chatId ->
            asked -= "chat:$chatId"
            request(chatId)
        }
        if (recentFailed && _recent.value != null) {
            asked -= "recent"
            requestRecent()
        }
    }

    override fun onCleared() {
        following.values.forEach { runCatching { it.close() } }
        following.clear()
    }

    /** Follows [chatId]'s new posts while Home is open, for as many rows as [FOLLOW_MAX] allows. */
    private fun follow(chatId: Long) {
        // Fewer on a television, whose weak cores read every post of every open chat as it comes.
        val most = if (com.tmplayer.ui.theme.Motion.television) FOLLOW_MAX_TV else FOLLOW_MAX
        if (chatId in following || following.size >= most) return
        viewModelScope.launch {
            val handle = runCatching { source.follow(chatId) }.getOrNull() ?: return@launch
            if (chatId in following || following.size >= most) handle.close() else following[chatId] = handle
        }
    }

    /** Fetches a starred chat's row, once (again after a post in it). */
    fun request(chatId: Long) = once("chat:$chatId") {
        follow(chatId)
        val (items, lane) = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(ROW_TIMEOUT_MS) { source.chatMediaRead(chatId) }
                ?: throw java.io.IOException("Home row timed out")
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            failedChats += chatId
            emptyList<MediaItem>() to FeedLane(chatId, done = true)
        }
        val ended = lane?.done == true
        if (items.isNotEmpty()) failedChats -= chatId
        // A failed refresh keeps the tiles it had rather than emptying the row.
        if (items.isEmpty() && chatId in failedChats && !_loaded.value[chatId].isNullOrEmpty()) return@once
        // Where the read stopped, for a See all page to read on from; a failed read leaves none.
        if (chatId in failedChats || lane == null) reads.remove(chatId) else reads[chatId] = lane
        val kept = withinLimits(items)
        _partial.update { if (ended) it - chatId else it + chatId }
        // Unchanged rows are not written again, so nothing recomposes for an answer that is the same.
        _loaded.update { if (it[chatId] == kept) it else it + (chatId to kept) }
        noteFresh()
        // A chat shown on Home is one likely opened next: the first few have their grid's first
        // page fetched behind, at low priority, so opening one draws it at once.
        if (source === TdHomeSource && items.isNotEmpty() && prefetched.size < (if (com.tmplayer.ui.theme.Motion.television) PREFETCH_CHATS_TV else PREFETCH_CHATS) && prefetched.add(chatId)) {
            com.tmplayer.data.PageQueries.prefetchChat(chatId)
        }
    }

    private fun noteFresh() {
        if (firstFresh) return
        firstFresh = true
        com.tmplayer.data.PerfLog.mark("home first fresh row", com.tmplayer.data.PerfLog.processStart)
    }

    /** Fetches the newest videos across every chat, once, for "Recently added in starred chats" (see [HomeRows]). */
    fun requestRecent() = once("recent") {
        recentFailed = false
        val items = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(ROW_TIMEOUT_MS) { source.recentMedia() }
                ?: throw java.io.IOException("Recent row timed out")
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            recentFailed = true
            emptyList()
        }
        // As for a chat's row: a failed refresh keeps what was there.
        if (recentFailed && !_recent.value.isNullOrEmpty()) return@once
        val kept = withinLimits(items)
        if (_recent.value != kept) _recent.value = kept
        noteFresh()
    }

    /** Looks up a Continue watching tile's picture, once. A miss leaves the tile its neutral art. */
    fun requestArt(record: ResumeRecord) = requestArt(record.chatId, record.messageId)

    /** The same for a Watch later tile, whose record keeps no picture either. */
    fun requestArt(record: WatchLaterRecord) = requestArt(record.chatId, record.messageId)

    private fun requestArt(chatId: Long, messageId: Long) {
        val key = SettingsStore.progressKey(chatId, messageId)
        once("art:$key") {
            val item = runCatching { source.mediaItem(chatId, messageId) }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                null
            } ?: return@once
            RecordItems.keep(item)
            _art.update { it + (key to item) }
        }
    }

    /**
     * Works out which shows the viewer is part way through from [resume] and [watched], and asks
     * each one's chat what has arrived since (see [NewEpisodes]): at most [NewEpisodes.SHOW_LIMIT]
     * shows, one small search apiece, each answer kept for [SHOW_TTL_MS] across visits to Home.
     * Called again whenever the records change; a show already asked is not asked again.
     */
    fun followShows(resume: List<ResumeRecord>, watched: Collection<WatchedRecord>) {
        showsJob?.cancel()
        showsJob = viewModelScope.launch {
            val next = withContext(work) {
                Tracked(NewEpisodes.inProgress(resume, watched), NewEpisodes.touchTimes(resume, watched).keys)
            }
            tracked.value = next
            next.shows.forEach(::requestShow)
        }
    }

    private fun requestShow(show: ShowReached) = once("show:${show.key}") {
        val now = System.currentTimeMillis()
        val items = showCache[show.key]?.takeIf { now - it.first < SHOW_TTL_MS }?.second
            ?: runCatching { source.showMedia(show.chatId, show.item.messageId, show.searchFor) }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                // Not cached: a show whose search failed is asked again on the next visit.
                null
            }?.also {
                // Answers past their time are dropped as new ones come in: the map is for the
                // process, and the shows in progress change over a long session.
                showCache.entries.removeAll { (_, entry) -> now - entry.first >= SHOW_TTL_MS }
                showCache[show.key] = now to it
            }
            ?: return@once
        val kept = withinLimits(items)
        found.update { it + (show.key to kept) }
    }

    /**
     * Forgets everything fetched, the cached copy too, so every row on screen asks again: for a
     * sign out, when what Home held was another account's. A refresh the viewer asks for keeps
     * the tiles up: [refreshInPlace].
     */
    fun refresh() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        asked.clear()
        _loaded.value = emptyMap()
        _partial.value = emptySet()
        _recent.value = null
        _art.value = emptyMap()
        reads.clear()
        feeds.values.forEach { it.close() }
        feeds.clear()
        _refreshing.value = false
        refreshedAt = 0L
        if (cached) HomeCache.clear()
        // The shows are asked again past their cache. What they found stays up meanwhile, so the
        // New episodes row does not blink out and back.
        tracked.value.shows.forEach { show ->
            showCache.remove(show.key)
            requestShow(show)
        }
    }

    /**
     * Reads every tile's Cached badge off the disk again, without fetching anything.
     *
     * What arrives is kept for the life of the screen, badges included, and the watch cache holds
     * one video: playing another took the last one's copy away while its tile went on saying
     * Cached. Called when the screen comes back from the player and when the cache record changes.
     */
    fun refreshLocalAvailability() {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            val answers = HashMap<Int, Boolean>()
            suspend fun recheckOne(item: MediaItem): MediaItem {
                val whole = answers.getOrPut(item.fileId) {
                    runCatching { source.wholeOnDevice(item) }.getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        item.onDevice
                    }
                }
                return item.recheckedCache(whole)
            }
            suspend fun recheckAll(items: List<MediaItem>): List<MediaItem> {
                val updated = items.map { recheckOne(it) }
                return if (updated.indices.all { updated[it] === items[it] }) items else updated
            }

            val loadedBefore = _loaded.value
            val recentBefore = _recent.value
            val artBefore = _art.value
            // Off the main thread: every tile is a lookup of its file and a look at the disk, and
            // on a TV stick coming back from the player that burst held the remote up. The answers
            // are written back below, on the main thread, as before.
            val (loaded, recent, art) = withContext(disk) {
                Triple(
                    loadedBefore.mapValues { (_, items) -> recheckAll(items) },
                    recentBefore?.let { recheckAll(it) },
                    artBefore.mapValues { (_, item) -> recheckOne(item) },
                )
            }
            // Written only over what is still the list that was checked: a row fetched meanwhile
            // came after the change and is right as it stands.
            _loaded.update { now ->
                now.mapValues { (chatId, items) ->
                    if (items === loadedBefore[chatId]) loaded.getValue(chatId) else items
                }
            }
            _recent.update { now -> if (now != null && now === recentBefore) recent else now }
            _art.update { now ->
                now.mapValues { (key, item) -> if (item === artBefore[key]) art.getValue(key) else item }
            }
        }
    }

    private var availabilityJob: Job? = null

    /**
     * The videos behind [row]'s "See all" page, read on through its chats a page at a time past
     * what Home holds (see [RowFeed] and [FeedMerge]); null for a row that reads no chats. Kept
     * per row while Home lives, so leaving the page and opening it again finds it as it was.
     * Starts from what Home has: the page draws at once, with the row's own tiles first.
     */
    fun seeAll(row: HomeRow): RowFeed? {
        val chats = when (row) {
            is HomeRow.Recent -> row.chatIds
            is HomeRow.Chat -> listOf(row.chatId)
            else -> return null
        }.filterNot { it in com.tmplayer.data.HiddenChats.ids.value }
        HomeRows.seeAll(row, emptyList()) ?: return null
        val key = row.key
        feeds[key]?.let { return it.also { feed -> feed.reshape(row) } }
        val held = _loaded.value.filterKeys { it in chats }
        val ids = chats.toHashSet()
        val seed = held.values.flatten() + _recent.value.orEmpty().filter { it.chatId in ids }
        val feed = RowFeed(
            scope = viewModelScope,
            lanes = FeedMerge.lanes(chats, reads, held),
            seed = seed,
            row = row,
            read = { chatId, cursors -> source.chatMediaFrom(chatId, cursors) },
            keep = { withinLimits(it) },
            work = work,
        )
        feeds[key] = feed
        return feed
    }

    private fun once(key: String, fetch: suspend () -> Unit) {
        if (!asked.add(key)) return
        jobs += viewModelScope.launch { gate.withPermit { fetch() } }
        jobs.removeAll { it.isCompleted }
    }

    private suspend fun withinLimits(items: List<MediaItem>): List<MediaItem> {
        val (min, max) = runCatching { sizeLimits() }.getOrDefault(SizeFilter.DEFAULT_MIN to SizeFilter.DEFAULT_MAX)
        return items.filter { SizeFilter.matches(it.sizeBytes, min, max) }
    }

    companion object {
        /** Rows fetched at once: enough to fill a screen quickly, few enough to stay polite to Telegram. */
        const val PARALLEL = 3

        /** See all pages whose read is kept at once: going back and in again finds the same pages. */
        const val FEEDS_KEPT = 4

        /** How many of Home's chats have their grid prefetched in a run: the first rows, not all of them. */
        const val PREFETCH_CHATS = 6

        /** The same on a television, where each prefetch competes with the posters on screen. */
        const val PREFETCH_CHATS_TV = 2

        /** How long the inputs stay still before Home's rows are built again ([rememberHomeRows]). */
        const val ROWS_SETTLE_MS = 300L

        /** How often work held back by a video playing ([busy]) looks again. */
        const val BUSY_RECHECK_MS = 5_000L

        /** How often Home brings itself up to date while it is open (user, 2026-10-21: "short auto refresh"). */
        const val AUTO_REFRESH_MS = 3 * 60 * 1000L

        /** Older than this when the app comes back to the front, and Home refreshes in place. */
        const val STALE_MS = 60 * 1000L

        /** How long the rows have to stay still before the cache is written. */
        const val PERSIST_AFTER_MS = 1_500L

        /** The same on a television, where each write is a large encode on a weak core. */
        const val PERSIST_AFTER_TV_MS = 8_000L

        /**
         * Past this a row's fetch counts as failed: its skeleton goes, and it is asked for again
         * when the line comes back ([retryFailed]). Twice the slow notice's wait.
         */
        const val ROW_TIMEOUT_MS = 30_000L

        /**
         * Starred chats held open for their posts at once. An open channel is one TDLib keeps in
         * step with the server, which is not free on a 1 GB stick; the first rows are the ones seen.
         */
        const val FOLLOW_MAX = 12

        /** Chats followed at once on a television. */
        const val FOLLOW_MAX_TV = 4

        /**
         * How long a show's search answers for. Ten minutes, like the forum topic counts: coming
         * back to Home from the player is not worth a fresh round of searches, and a post that
         * arrives while the app is open asks again on its own.
         */
        const val SHOW_TTL_MS = 10 * 60 * 1000L

        /**
         * Where Home's read of each chat stopped, for a See all page to read on from ([seeAll]).
         * For the process, like [showCache]: the desktop builds Home again on every visit, and a
         * Home drawn from its cache does not read its chats again within [STALE_MS].
         */
        private val reads = java.util.concurrent.ConcurrentHashMap<Long, FeedLane>()

        /**
         * Each show's search, by [ShowReached.key], with when it was made. For the process, not
         * the screen: Home is built again on every visit and its searches should not be.
         */
        private val showCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<MediaItem>>>()
    }
}

/**
 * Home's rows for what is on screen now, rebuilt only when one of the inputs changes.
 *
 * @param chats the chat list, which decides the starred rows' order.
 * @param watched the Watched list, which with [continueWatching] says which shows are in progress
 *   for New episodes.
 */
@Composable
fun rememberHomeRows(
    model: HomeViewModel,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    continueWatching: List<ResumeRecord>,
    watchLater: List<WatchLaterRecord> = emptyList(),
    watched: Collection<WatchedRecord> = emptyList(),
    /** See [HomeRows.build]: one on a phone, whose Recently added is the featured carousel. */
    recentMinStarred: Int = HomeRows.RECENT_MIN_STARRED,
    /**
     * Home fills itself from the chats with videos, favourites first ([HomeRows.homeChats]): they
     * feed Recently added, and each starred chat has a row of its own under it.
     * False draws the starred chats alone, as Home did before 2026-10-21.
     */
    autoFill: Boolean = true,
): List<HomeRow> {
    // A hidden chat's videos leave Home with it: its Continue watching and Watch later cards too.
    val hidden by com.tmplayer.data.HiddenChats.ids.collectAsState()
    val continueWatching = remember(continueWatching, hidden) { com.tmplayer.data.HiddenChats.filter(continueWatching, hidden) { it.chatId } }
    val watchLater = remember(watchLater, hidden) { com.tmplayer.data.HiddenChats.filter(watchLater, hidden) { it.chatId } }
    val loadedAll by model.loaded.collectAsState()
    val recentAll by model.recent.collectAsState()
    // What was read before the chat was hidden goes too, without waiting for the next refresh.
    val loaded = remember(loadedAll, hidden) { if (hidden.isEmpty()) loadedAll else loadedAll - hidden }
    val recent = remember(recentAll, hidden) { recentAll?.let { all -> com.tmplayer.data.HiddenChats.filter(all, hidden) { it.chatId } } }
    val partial by model.partial.collectAsState()
    val fresh by model.newEpisodes.collectAsState()
    LaunchedEffect(model, continueWatching, watched) { model.followShows(continueWatching, watched) }
    // Home's chats are picked with the rows below, off the main thread: picked here they were
    // worked out again on every change to the chat list, which a busy account sends often.
    val homeMemo = remember { HomeChatsMemo() }
    // "Not the same video" pulls a copy out of its folded card at once, so the rows are built
    // again when a correction changes.
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    // Built off the main thread, and only once the chats' answers stop arriving for a moment: a
    // filled Home reads a dozen chats, each answer changed the inputs, and every build reads
    // hundreds of names. Built on the main thread, that held a TV stick past the ANR limit
    // (1.28.0, Bell Streamer). The last rows stay on screen meanwhile.
    val rows by produceState(model.lastRows, continueWatching, watchLater, chats, favourites, loaded, recent, partial, fresh, corrections, recentMinStarred, autoFill) {
        val key = listOf(chats, favourites, recent, autoFill)
        val home = homeMemo.home.takeIf { homeMemo.same(homeMemo.key, key) } ?: withContext(Dispatchers.Default) {
            val picked = if (autoFill) HomeRows.homeChats(chats, favourites, recent) else HomeRows.favouriteChats(chats, favourites)
            // The same chats as before keep the list rows were built from, so the check below holds.
            homeMemo.home?.takeIf { it == picked } ?: picked
        }.also { picked ->
            homeMemo.key = key
            if (picked !== homeMemo.home) {
                homeMemo.home = picked
                // Filling itself, Home has no row for a chat it picked to ask for its videos when
                // drawn, so Recently added saw only the newest videos across every chat. Each of
                // Home's chats is asked for its own (once each: the model keeps count).
                if (autoFill) picked.forEach { model.request(it.id) }
            }
        }
        // A new chat list that leaves Home's chats as they were, with nothing else changed, is
        // not a new Home: the rows stand, as they did when the chats were picked in composition.
        // The last run's videos are in but the chat list is not: Home's chats are not known yet.
        val chatsPending = autoFill && chats.isEmpty() && !recent.isNullOrEmpty()
        val inputs = listOf(continueWatching, watchLater, home, favourites, loaded, recent, partial, fresh, corrections, recentMinStarred, autoFill, chatsPending)
        if (value.isNotEmpty() && homeMemo.same(homeMemo.built, inputs)) return@produceState
        if (value.isNotEmpty()) delay(HomeViewModel.ROWS_SETTLE_MS)
        val built = withContext(Dispatchers.Default) {
            HomeRows.build(
                continueWatching, home, loaded, recent, partial = partial, watchLater = watchLater, fresh = fresh,
                recentMinStarred = recentMinStarred, starred = favourites, autoFill = autoFill,
                chatsPending = chatsPending,
            )
        }
        homeMemo.built = inputs
        model.lastRows = built
        value = built
        // Rows that are there, or a list of where the videos are that has come back: either way
        // what Home shows now is the answer. Starred chats alone need no such list.
        if (built.isNotEmpty() || recent != null || !autoFill) model.rowsSettled = true
    }
    PrefetchHomeArt(rows, model)
    return rows
}

/** Home's chats as last picked and what the rows were built from, so a change that alters neither builds nothing. */
private class HomeChatsMemo {
    /** What [home] was picked from. */
    var key: List<Any?>? = null
    var home: List<ChatSummary>? = null

    /** What the rows on screen were built from. */
    var built: List<Any?>? = null

    /**
     * Whether [then] and [now] are the same inputs, by identity (a flag, a number or the starred set by value):
     * comparing the chat lists item by item would put the main thread's work back.
     */
    fun same(then: List<Any?>?, now: List<Any?>): Boolean {
        then ?: return false
        return then.size == now.size && then.indices.all { i ->
            val a = then[i]
            val b = now[i]
            a === b || ((a is Boolean || a is Number || a is Set<*>) && a == b)
        }
    }
}

/**
 * Looks up the posters of the first tiles in each row, and fetches them, before the rows are
 * drawn: a row a screen down is sharp when it scrolls in rather than starting its lookups then.
 * A handful per row, two lookups at a time, only while lookups are on and no video plays
 * ([HomeViewModel.untilIdle]). Everything it finds
 * lands in [OnlineMetadata]'s memory and disk caches, which the tiles read on their first frame.
 */
@Composable
private fun PrefetchHomeArt(rows: List<HomeRow>, model: HomeViewModel) {
    val online = com.tmplayer.online.OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return
    val items = remember(rows) {
        rows.flatMap { row ->
            val entries = when (row) {
                is HomeRow.Chat -> row.entries
                is HomeRow.Recent -> row.entries
                else -> emptyList()
            }
            // The carousel's pages want their title logos too.
            val logo = row is HomeRow.Recent
            entries.take(if (logo) com.tmplayer.data.HomeFeatured.LIMIT else PREFETCH_PER_ROW).map { entry ->
                when (entry) {
                    is com.tmplayer.data.ShelfEntry.File -> Ahead(entry.item, show = false, logo = logo)
                    is com.tmplayer.data.ShelfEntry.Show -> Ahead(entry.series.cover, show = true, logo = logo)
                }
            }
        }.distinctBy { it.item.id }
            // The carousel's first: it is the top of Home, and its logos are what a page turn shows.
            .sortedByDescending { it.logo }
    }
    LaunchedEffect(items) {
        val gate = Semaphore(PREFETCH_PARALLEL)
        kotlinx.coroutines.coroutineScope {
            items.forEach { (item, show, logo) ->
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        // Not while a video plays: Home stays composed under the player, and a
                        // row that changed meanwhile set off lookups, downloads and decodes that
                        // took the line and a weak CPU from playback. They go on once it stops.
                        model.untilIdle()
                        val query = item.metaQuery
                            ?.let { if (show) it.showOnly() else it } ?: return@withPermit
                        val known = (online.peek(query) as? com.tmplayer.online.MetaResult.Found)?.info
                        if (known != null && !logo) return@withPermit
                        val info = known ?: (online.lookup(query) as? com.tmplayer.online.MetaResult.Found)?.info ?: return@withPermit
                        if (known == null) info.posterUrl?.let { runCatching { online.image(it) } }
                        // Decoded too, not only fetched: a page turned to has its logo on the first frame.
                        if (logo) com.tmplayer.ui.online.prefetchTitleLogo(info)
                    }
                }
            }
        }
    }
}

/** A tile looked up ahead: a show by its name alone, and the carousel's with its title logo. */
private data class Ahead(val item: com.tmplayer.data.MediaItem, val show: Boolean, val logo: Boolean)

/** Tiles per row whose posters are looked up ahead: a phone's screen width and a little more. */
private const val PREFETCH_PER_ROW = 4

/** Lookups ahead at once, under the tiles' own: TMDB's spacing is shared with them. */
private const val PREFETCH_PARALLEL = 2
