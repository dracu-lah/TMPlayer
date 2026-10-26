package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.Failures
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.HidingFilter
import com.tmplayer.data.KidsLock
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryCache
import com.tmplayer.data.QueryKey
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchIdentity
import com.tmplayer.data.SearchQuery
import com.tmplayer.data.SearchSort
import com.tmplayer.data.Td
import com.tmplayer.online.KnownMedia
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.online.OnlineMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the search across every chat gets its pages: Telegram in the app, a fixture in the promo build. */
fun interface VideoSearchSource {
    /**
     * One page for [query] from [cursor]. Throws [Offline] when there is no connection to ask
     * over, and anything else when Telegram refused, so the screen can say which.
     */
    suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage

    /**
     * Whether [item] is still there to open: its message not deleted, its chat not left since the
     * search. Null when that cannot be told (offline, the fixture), which opens it as found.
     */
    suspend fun stillThere(item: MediaItem): Boolean? = null

    /** No connection to ask Telegram over. */
    class Offline : java.io.IOException()

    companion object {
        /**
         * The signed in account, through TDLib's `searchMessages`. Only once there is a
         * connection: TDLib holds a search until it can reach Telegram, and offline that left
         * "Searching" spinning with no end and no Retry.
         */
        val Telegram = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
                val session = Td.awaitConnectedSessionOrNull() ?: throw Offline()
                return ChatRepository(session.client).searchAllChats(query, cursor, filters = filters)
            }

            override suspend fun stillThere(item: MediaItem): Boolean? {
                val session = Td.awaitConnectedSessionOrNull() ?: return null
                return ChatRepository(session.client).mediaItem(item.chatId, item.messageId) != null
            }
        }
    }
}

/** The search across every chat, as the screens draw it. */
data class GlobalSearchState(
    /** What was typed, read: see [GlobalSearch.parse]. */
    val query: SearchQuery = GlobalSearch.parse(""),
    /** Every card found, Telegram's and the local ones, in the order they are listed. */
    val groups: List<ResultGroup> = emptyList(),
    val filters: SearchFilters = SearchFilters(),
    val sort: SearchSort = SearchSort.BestMatch,
    /** The chats the results are in, for their avatars and names. */
    val chats: Map<Long, ChatBrief> = emptyMap(),
    /** Telegram has been asked and has not answered yet. */
    val searching: Boolean = false,
    /**
     * The typing has not settled yet ([GlobalSearchViewModel.type]): Telegram is asked once it
     * does. No spinner meanwhile, and no "Nothing found" either; what was listed stays up.
     */
    val settling: Boolean = false,
    /** The first answer is in but thin, and another page is on its way ("Still searching"). */
    val stillSearching: Boolean = false,
    val loadingMore: Boolean = false,
    /** Telegram has nothing more for this query. */
    val endReached: Boolean = false,
    /** No connection: what is listed is what this device already knew. Asked again on reconnect. */
    val offline: Boolean = false,
    /** Telegram asked to wait: the seconds left before the search asks again by itself. */
    val floodWait: Int = 0,
    /** The search failed, in words; null when it did not. */
    val error: String? = null,
) {
    /** What the list shows: [groups] through the filters, the kids lock already applied. */
    val shown: List<ResultGroup> by lazy(LazyThreadSafetyMode.PUBLICATION) { GlobalSearch.filter(groups, filters, query) }

    /** For an empty list: which filters hide what was found. */
    val hiddenBy: List<HidingFilter> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (shown.isNotEmpty() || searching || settling) emptyList() else GlobalSearch.hiddenBy(groups, filters, query)
    }

    val busy: Boolean get() = searching || stillSearching || settling
}

/**
 * The search across every chat (CP73), shared by the phone, the television and the desktop.
 *
 * Each change of the text matches at once what the app already knows ([GlobalSearch.localVideos]:
 * chat listings, Continue watching, downloads) and asks Telegram once the typing has settled for
 * [settleMs], or at once on the keyboard's search key ([submit]). Meanwhile what was listed and
 * still matches stays up ([GlobalSearchState.settling]), and the spinner shows only once Telegram
 * is actually asked. Pages arrive merged into the
 * same cards by message id, so a video found both ways is listed once. The first answer keeps
 * asking until it has [GlobalSearch.FIRST_GROUPS] cards or has asked [GlobalSearch.FIRST_PAGES]
 * times, and a query of several words that found nothing tries its underscored and dotted
 * spellings, then its longest word.
 *
 * Changing a filter that only screens the answer, or the sort, never asks Telegram again; the
 * chat type and the archive switch do.
 */
class GlobalSearchViewModel(
    private val source: VideoSearchSource = VideoSearchSource.Telegram,
    /** Told about a search worth remembering: see [remember]. */
    private val onSearched: (suspend (String) -> Unit)? = null,
    /** What the app already knows, for the local half: chat listings, Continue watching, downloads. */
    private val known: () -> List<MediaItem> = { KnownMedia.all() },
    private val settleMs: Long = SETTLE_MS,
    private val rememberMs: Long = REMEMBER_MS,
    /** What makes two results one card: see [SearchIdentity]. */
    private val identity: SearchIdentity = SearchIdentity.Default,
    /**
     * Where Telegram's pages are kept by query ([PageQueries]), so searching the same words again
     * lists them at once and asks again only once they are [PageQueries.SEARCH_STALE_MS] old.
     * Telegram's own source only: a fixture's pages (the promo build, the tests) are not kept.
     */
    private val cache: QueryCache? = if (source === VideoSearchSource.Telegram) PageQueries.cache else null,
    /**
     * Where the local half, the merging and the ranking run: they read every known video's name,
     * and on the main thread each letter typed on a phone waited for them (2026-10-23).
     */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** Telegram's pages for one query, as the [cache] keeps them: before the kids lock and hidden chats. */
    private data class Answer(val serverQuery: String, val pages: List<AllChatsPage>, val cursor: AllChatsCursor)

    private fun keyOf(query: SearchQuery, filters: SearchFilters) =
        QueryKey.Search(query.server, "${filters.serverSide.first}:${filters.serverSide.second}")

    /** A first page, kept as the start of a fresh answer for [query]. */
    private fun keepFirst(query: SearchQuery, filters: SearchFilters, page: AllChatsPage) {
        cache?.put(keyOf(query, filters), Answer(serverQuery, listOf(page), page.cursor))
    }

    /** A further page, appended to the answer kept for [query] while it is still there. */
    private fun keepNext(query: SearchQuery, filters: SearchFilters, page: AllChatsPage) {
        cache?.update<Answer>(keyOf(query, filters)) { it?.copy(pages = it.pages + page, cursor = page.cursor) }
    }

    private val _state = MutableStateFlow(GlobalSearchState())
    val state: StateFlow<GlobalSearchState> = _state.asStateFlow()

    /**
     * Videos the screen holds that [known] does not: Continue watching, Watch later, downloads.
     * Set by the screen as those lists change; read on each keystroke.
     */
    @Volatile
    var alsoKnown: List<MediaItem> = emptyList()

    private var job: Job? = null
    private var localJob: Job? = null
    private var rememberJob: Job? = null
    private var cursor = AllChatsCursor()

    /** What Telegram is being asked: the query, or the spelling of it that found something. */
    private var serverQuery = ""

    /**
     * The words Telegram has been asked for and is answering, or has answered without a failure,
     * for the query on screen; empty when it has not been asked. What keeps the same words from
     * being asked twice: see [submit] and [start].
     */
    private var asked = ""

    /** What the app already knew that answers the query on screen: the local half of [start]. */
    private var localNow: List<MediaItem> = emptyList()

    init {
        // The kids lock coming on while results are listed takes its chats out of them.
        viewModelScope.launch {
            ChatGate.allowed.collect { allowed ->
                if (allowed != null) _state.update { s -> s.copy(groups = s.groups.filter { g -> g.copies.all { KidsLock.allows(allowed, it.chatId) } }) }
            }
        }
        // So does hiding a chat from its menu while results from it are listed.
        viewModelScope.launch {
            com.tmplayer.data.HiddenChats.ids.collect { hidden ->
                if (hidden.isNotEmpty()) _state.update { s -> s.copy(groups = s.groups.filter { g -> g.copies.none { it.chatId in hidden } }) }
            }
        }
    }

    /** The text changed: local matches now, Telegram after the settle. Blank clears it all. */
    fun type(text: String) = start(text, settle = settleMs)

    /**
     * The keyboard's search key, or a recent search picked: Telegram at once, and remembered.
     * Words already asked about and answered (or still being answered) are not asked again.
     */
    fun submit(text: String = _state.value.query.text) {
        val query = GlobalSearch.parse(text)
        val now = _state.value
        val answering = query == now.query && !query.isEmpty && asked == query.server && !now.settling &&
            now.error == null && !now.offline && now.floodWait == 0
        if (!answering) start(text, settle = 0, force = true)
        remember(text)
    }

    /** A result was opened, or a voice search heard: the query is worth keeping. */
    fun remember(text: String = _state.value.query.text) {
        val clean = GlobalSearch.clean(text)
        if (!GlobalSearch.searchable(clean)) return
        viewModelScope.launch { runCatching { onSearched?.invoke(clean) } }
    }

    fun setFilters(filters: SearchFilters) {
        val before = _state.value.filters
        _state.update { it.copy(filters = filters) }
        if (filters.serverSide != before.serverSide && !_state.value.query.isEmpty) start(_state.value.query.text, settle = 0, force = true)
    }

    fun setSort(sort: SearchSort) {
        _state.update { it.copy(sort = sort, groups = GlobalSearch.order(it.groups, it.query, sort)) }
    }

    /** Clears every filter, from the empty state's one tap. */
    fun clearFilters() = setFilters(SearchFilters())

    /** The next page, when the list nears its end. */
    fun loadMore() {
        val now = _state.value
        if (now.query.isEmpty || now.busy || now.loadingMore || now.endReached || now.error != null || now.offline) return
        if (job?.isActive == true) return
        if (now.groups.size >= GlobalSearch.MAX_GROUPS) return
        _state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            runCatching { source.page(serverQuery, cursor, now.filters) }
                .onSuccess { page ->
                    cursor = page.cursor
                    keepNext(now.query, now.filters, page)
                    absorb(page)
                    _state.update { it.copy(loadingMore = false, endReached = page.cursor.done) }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    // A page that failed half way down keeps what is on screen; the next scroll
                    // to the end asks again.
                    _state.update { s -> s.copy(loadingMore = false) }
                }
        }
    }

    /** After a failure: the same query again, without the wait. */
    fun retry() {
        val text = _state.value.query.text
        if (text.isNotBlank()) start(text, settle = 0, force = true)
    }

    /** The connection came back: a search that could only list this device's videos asks Telegram. */
    fun reconnected() {
        if (_state.value.offline) retry()
    }

    /**
     * Whether [item] can still be opened: false when its message was deleted or its chat left
     * since the search, and then its card goes. Null and true both open it.
     */
    suspend fun check(item: MediaItem): Boolean {
        val there = runCatching { source.stillThere(item) }.getOrNull()
        if (there == false) {
            _state.update { s ->
                s.copy(groups = s.groups.mapNotNull { g ->
                    val left = g.copies.filterNot { it.id == item.id }
                    if (left.isEmpty()) null else g.copy(copies = left)
                })
            }
        }
        return there != false
    }

    private fun start(text: String, settle: Long, force: Boolean = false) {
        val query = GlobalSearch.parse(text)
        val now = _state.value
        if (!force && query == now.query) return
        val allowed = ChatGate.allowed.value
        // Only the words around Telegram's part changed (a quality marker, a space): the answer
        // asked for, or about to be, stands, and the new words only screen and rank it.
        if (!force && !query.isEmpty && query.server == now.query.server && (asked == query.server || now.settling) &&
            now.error == null && !now.offline && now.floodWait == 0
        ) {
            _state.update { it.copy(query = query) }
            localJob?.cancel()
            localJob = viewModelScope.launch {
                val local = withContext(work) { GlobalSearch.localVideos(known() + alsoKnown, query, allowed, ::onlineNames) }
                localNow = local
                regroup { s -> GlobalSearch.merge(GlobalSearch.order(s.groups, query, s.sort), local, query, s.sort, identity) }
            }
            return
        }
        job?.cancel()
        localJob?.cancel()
        rememberJob?.cancel()
        cursor = AllChatsCursor()
        asked = ""
        if (query.isEmpty) {
            // Cleared, or down to one letter: whatever was waiting to be asked is dropped, and the
            // screen goes back to its recent searches.
            localNow = emptyList()
            _state.value = GlobalSearchState(query = query, filters = now.filters, sort = now.sort)
            return
        }
        localNow = emptyList()
        // The new words and what is going on at once; the cards stay as they were for the moment
        // the local half takes, so the list never flashes empty between one letter and the next.
        _state.value = GlobalSearchState(
            query = query,
            groups = now.groups,
            filters = now.filters,
            sort = now.sort,
            chats = now.chats,
            searching = settle <= 0,
            settling = settle > 0,
        )
        val kept = cache?.peek<Answer>(keyOf(query, now.filters), PageQueries.SEARCH_STALE_MS)
        if (kept != null) {
            serverQuery = kept.data.serverQuery
            cursor = kept.data.cursor
            asked = query.server
        }
        job = viewModelScope.launch {
            // The local half, off the main thread: no connection needed and no wait. What was
            // listed and still answers the new words stays up until Telegram's answer takes its
            // place.
            val local = withContext(work) { GlobalSearch.localVideos(known() + alsoKnown, query, allowed, ::onlineNames) }
            localNow = local
            regroup { s ->
                val carried = GlobalSearch.order(
                    s.groups.filter { g -> g.copies.any { GlobalSearch.matchesLocally(it, query, onlineNames(it)) } },
                    query,
                    s.sort,
                )
                GlobalSearch.merge(carried, local, query, s.sort, identity)
            }
            // The same words searched before: Telegram's pages from then, listed at once through
            // the kids lock and the hidden chats as they are now, and asked again only once stale.
            if (kept != null) {
                val hidden = com.tmplayer.data.HiddenChats.ids.value
                kept.data.pages.forEachIndexed { index, page ->
                    absorb(page.copy(items = page.items.filter { KidsLock.allows(allowed, it.chatId) && it.chatId !in hidden }), keepTo = query, fresh = index == 0)
                }
                _state.update { it.copy(searching = false, settling = false, endReached = kept.data.cursor.done) }
                if (!kept.stale) return@launch
            }
            if (settle > 0) {
                delay(settle)
                // Asked only now, so only now does the screen say it is searching.
                _state.update { it.copy(settling = false, searching = kept == null) }
            }
            firstAnswer(query, fresh = kept == null)
        }
    }

    /**
     * Telegram's first answer for [query]. A [fresh] one takes the place of whatever was carried
     * over from the words before ([start]); a revalidation of kept pages merges into them.
     */
    private suspend fun firstAnswer(query: SearchQuery, fresh: Boolean = true, floodRetries: Int = 0) {
        val filters = _state.value.filters
        asked = query.server
        try {
            var pages = 0
            serverQuery = query.server
            var page = source.page(serverQuery, AllChatsCursor(), filters)
            pages++
            if (page.items.isEmpty()) {
                // Telegram matches whole words, and to it "Harbour_Notes" is one: try the query
                // spelled as release names spell it, then its longest word alone.
                val tries = GlobalSearch.variants(query.server) + listOfNotNull(com.tmplayer.data.AllChatsSearch.fallbackQuery(query.server))
                for (spelling in tries) {
                    val retry = source.page(spelling, AllChatsCursor(), filters)
                    pages++
                    if (retry.items.isNotEmpty()) {
                        serverQuery = spelling
                        page = retry
                        break
                    }
                }
            }
            cursor = page.cursor
            keepFirst(query, filters, page)
            absorb(page, keepTo = query, fresh = fresh)
            _state.update { it.copy(searching = false, endReached = page.cursor.done) }
            while (GlobalSearch.wantsMore(_state.value.shown.size, pages, cursor.done)) {
                _state.update { it.copy(stillSearching = true) }
                val next = source.page(serverQuery, cursor, filters)
                pages++
                cursor = next.cursor
                keepNext(query, filters, next)
                absorb(next, keepTo = query)
                _state.update { it.copy(endReached = next.cursor.done) }
            }
            _state.update { it.copy(stillSearching = false) }
            if (_state.value.groups.isNotEmpty()) {
                rememberJob = viewModelScope.launch {
                    delay(rememberMs)
                    remember(query.text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VideoSearchSource.Offline) {
            asked = ""
            _state.update { it.copy(searching = false, stillSearching = false, offline = true) }
        } catch (e: Exception) {
            val wait = Failures.floodWaitSeconds(e.message)
            // Waited out and asked again, a couple of times at most: a flood wait answered by
            // another one is said as a failure, with Retry, rather than counted down for good.
            if (wait != null && wait in 1..MAX_FLOOD_WAIT && floodRetries < MAX_FLOOD_RETRIES) {
                for (left in wait downTo 1) {
                    _state.update { it.copy(searching = false, stillSearching = false, floodWait = left) }
                    delay(1_000)
                }
                _state.update { it.copy(floodWait = 0, searching = true) }
                firstAnswer(query, fresh, floodRetries + 1)
                return
            }
            asked = ""
            _state.update {
                it.copy(searching = false, stillSearching = false, error = Failures.humanise(e))
            }
        }
    }

    /**
     * A page into the cards, screened against the whole query when it was asked by a shorter
     * spelling. A [fresh] page is the first of a new answer: it starts again from the local half,
     * dropping the cards carried over from the words typed before.
     */
    private suspend fun absorb(page: AllChatsPage, keepTo: SearchQuery = _state.value.query, fresh: Boolean = false) {
        val fallback = serverQuery != keepTo.server
        val kept = if (!fallback) page.items else withContext(work) { page.items.filter { GlobalSearch.matchesLocally(it, keepTo, onlineNames(it)) } }
        val local = localNow
        regroup(chats = page.chats.associateBy { it.id }) { s ->
            val base = if (fresh) GlobalSearch.merge(emptyList(), local, s.query, s.sort, identity) else s.groups
            GlobalSearch.merge(base, kept, s.query, s.sort, identity)
        }
    }

    /**
     * The cards worked out again by [build] off the main thread, then set, unless the state moved
     * meanwhile (the kids lock, a hidden chat, a card gone), in which case they are worked out
     * again from the new state.
     */
    private suspend fun regroup(chats: Map<Long, ChatBrief> = emptyMap(), build: (GlobalSearchState) -> List<ResultGroup>) {
        while (true) {
            val s = _state.value
            val groups = withContext(work) { build(s) }
            if (_state.compareAndSet(s, s.copy(groups = groups, chats = if (chats.isEmpty()) s.chats else s.chats + chats))) return
        }
    }

    /** The online title of [item], when the providers have already been asked about it. */
    private fun onlineNames(item: MediaItem): List<String> {
        val online = OnlineMetadata.current ?: return emptyList()
        val query = item.metaQuery ?: return emptyList()
        val found = runCatching { online.peek(query) }.getOrNull() as? MetaResult.Found ?: return emptyList()
        return listOf(found.info.title)
    }

    companion object {
        /** Long enough that each letter typed on a phone is not a round trip to Telegram: see [com.tmplayer.data.SearchSettle]. */
        const val SETTLE_MS = com.tmplayer.data.SearchSettle.MS

        /** A query that has sat with results this long was read, and is kept as a recent search. */
        const val REMEMBER_MS = 2_000L

        /** A longer wait than this is not counted down on screen: it is a failure to report. */
        const val MAX_FLOOD_WAIT = 120

        /** Flood waits a search waits out by itself before it says it failed. */
        const val MAX_FLOOD_RETRIES = 2
    }
}
