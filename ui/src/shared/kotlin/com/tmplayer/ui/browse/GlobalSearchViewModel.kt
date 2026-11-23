package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.TopicHit
import com.tmplayer.data.TopicSearch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.Failures
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.HidingFilter
import com.tmplayer.data.KidsLock
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MessageLookup
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryCache
import com.tmplayer.data.QueryKey
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchIdentity
import com.tmplayer.data.SearchQuery
import com.tmplayer.data.SearchSort
import com.tmplayer.data.Td
import com.tmplayer.data.TitleHit
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.PosterChoices
import com.tmplayer.online.WhereToWatch
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
     * [page] over every chat of the account rather than the added chats alone: the one-off
     * "Search my other chats" (HX62). The kids lock still applies. A source with no such
     * difference (the promo fixture) answers as [page] does.
     */
    suspend fun everyChatPage(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage =
        page(query, cursor, filters)

    /**
     * What Telegram says of [item]'s message now: found, gone for good (deleted, its chat left
     * since the search) or [MessageLookup.Unreachable] (a flood wait, a server error, a post held
     * back). Null when it cannot be asked (offline, the fixture).
     */
    suspend fun lookup(item: MediaItem): MessageLookup? = null

    /**
     * Whether [item] is still there to open. False only when its message is gone for good; null
     * when that cannot be told (offline, the fixture, an answer that did not come), which opens it
     * as found. An unreachable lookup is not a gone video: its card stays and the page opens.
     */
    suspend fun stillThere(item: MediaItem): Boolean? = when (lookup(item)) {
        is MessageLookup.Found -> true
        is MessageLookup.Gone -> false
        MessageLookup.Unreachable, null -> null
    }

    /** No connection to ask Telegram over. */
    class Offline : java.io.IOException()

    companion object {
        /** How long a tap on a result waits to learn it is still there before the page opens anyway. */
        const val CHECK_MS = 2_000L

        /**
         * The signed in account, through TDLib's `searchMessages`. Only once there is a
         * connection: TDLib holds a search until it can reach Telegram, and offline that left
         * "Searching" spinning with no end and no Retry.
         */
        val Telegram = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
                val session = Td.awaitConnectedSessionOrNull() ?: throw Offline()
                val repo = ChatRepository(session.client)
                // Search reads the added chats alone (HX27). Telegram cannot be asked for "these
                // chats only", so with a choice made each chat is searched on its own; with no
                // choice made it is one search over the account, as it has always been.
                val added = com.tmplayer.data.AddedChats.awaitReady()
                return if (added == null) {
                    repo.searchAllChats(query, cursor, filters = filters)
                } else {
                    repo.searchAddedChats(query, added, cursor, filters = filters)
                }
            }

            override suspend fun everyChatPage(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
                val session = Td.awaitConnectedSessionOrNull() ?: throw Offline()
                return ChatRepository(session.client).searchAllChats(query, cursor, filters = filters, everyChat = true)
            }

            /**
             * Asked of TDLib's own database first, within [VideoSearchSource.CHECK_MS]: a message
             * the search just listed answers at once, and no wait for a connection.
             */
            override suspend fun lookup(item: MediaItem): MessageLookup =
                Td.lookupMedia(item.chatId, item.messageId, CHECK_MS)
        }
    }
}

/**
 * Where any film or show is looked up by its name (HX41): TMDB through [OnlineMetadata] in the
 * app, a fake in the tests. A metadata question only: it names titles and services, and never
 * fetches, requests or searches for a video anywhere the viewer has not added themselves.
 */
interface TitleSource {
    /** The titles [query] could mean. Never throws: off, no key and offline are answers of their own. */
    suspend fun titles(query: MetaQuery): PosterChoices

    /** Where [title] can be streamed or rented in the viewer's region. Never throws. */
    suspend fun whereToWatch(title: PosterChoice): WhereToWatch

    companion object {
        val Online = object : TitleSource {
            override suspend fun titles(query: MetaQuery): PosterChoices =
                OnlineMetadata.current?.candidates(query) ?: PosterChoices.Off

            override suspend fun whereToWatch(title: PosterChoice): WhereToWatch =
                OnlineMetadata.current?.whereToWatch(title) ?: WhereToWatch.Off
        }
    }
}

/** How the title lookup above the cards stands (HX41). */
enum class TitleLookup {
    /** Nothing to look up: no words, a quality alone, or no title source. */
    Idle,

    /** Waiting for the typing to settle, or for TMDB. */
    Looking,

    /** TMDB answered; [GlobalSearchState.titleHits] says what. */
    Found,

    /** Lookups are off or there is no key: the search reads the chats alone, and says nothing of it. */
    Off,

    /** Offline or throttled: a quiet line with Try again, the cards unaffected. */
    Unavailable,
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
    /**
     * The next page failed: the list keeps what it has and ends in a Try again row
     * ([GlobalSearchViewModel.retryMore]). Nothing asks again by itself while it is set.
     */
    val moreFailed: Boolean = false,
    /** Telegram has nothing more for this query. */
    val endReached: Boolean = false,
    /** No connection: what is listed is what this device already knew. Asked again on reconnect. */
    val offline: Boolean = false,
    /** Telegram asked to wait: the seconds left before the search asks again by itself. */
    val floodWait: Int = 0,
    /** The search failed, in words; null when it did not. */
    val error: String? = null,
    /**
     * Topics of groups whose names answer the words (U14), best first ([TopicSearch.rank]): a
     * group of its own over the videos, "Chat > Topic", the kids lock already applied.
     */
    val topics: List<TopicHit> = emptyList(),
    /** Telegram is being asked for topics, or is about to be once the typing settles. */
    val topicsSearching: Boolean = false,
    /** Every group asked for topics refused, in words; null otherwise. With Try again and Send logs. */
    val topicsError: String? = null,
    /** More groups could have topics than one search asks ([TopicSearch.CHATS_PER_SEARCH]): the heading says so. */
    val topicsPartial: Boolean = false,
    /**
     * The search reads the chats the viewer added (HX62), which is what makes "Search my other
     * chats" worth offering. False when nothing was added yet: every chat is searched, and the
     * screen says so ([GlobalSearchState.everyChatNote]).
     */
    val addedOnly: Boolean = false,
    /**
     * "All chats" is on: every chat counts, so there is nothing to add and no screen offers to
     * (the "Add more chats" button and the note that points to it stay away).
     */
    val allChatsOn: Boolean = false,
    /** The one-off "Search my other chats" is on for these words: the whole account, not the added chats. */
    val everyChat: Boolean = false,
    /** The films and shows TMDB named for the words (HX41), in its ranked order; empty with none. */
    val titles: List<PosterChoice> = emptyList(),
    val titleLookup: TitleLookup = TitleLookup.Idle,
    /** The "Where to watch" sheet: the title it is for, and its answer (null while asking). Null when closed. */
    val watching: Pair<PosterChoice, WhereToWatch?>? = null,
) {
    /**
     * The titles over the cards, each with the copies the viewer has (HX41): [GlobalSearch.titles]
     * over every card found, not only the ones the filters let through, so a filter never makes a
     * title "Not in your chats".
     */
    val titleHits: List<TitleHit> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (titles.isEmpty()) emptyList() else GlobalSearch.titles(titles, groups, query)
    }

    /**
     * "Not in your chats": TMDB named the title and nothing found carries it. Only once the
     * chats have answered, so it never shows while the search is still reading them.
     */
    val notInChats: Boolean get() = !searching && !settling && !stillSearching && GlobalSearch.notInChats(titleHits)

    /** How many videos in how many chats the list holds, for the count line; null for an empty list. */
    val tally: GlobalSearch.Tally? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (shown.isEmpty()) null else GlobalSearch.tally(shown)
    }

    /** "Search my other chats" is on offer: the search is of the added chats and has not yet gone further. */
    val canSearchOthers: Boolean get() = addedOnly && !everyChat && !query.isEmpty && !offline

    /** The search covered every chat because none was added yet: said above the results, with the way to choose. */
    val everyChatNote: Boolean get() = !addedOnly && !allChatsOn && !everyChat && !query.isEmpty && groups.isNotEmpty()

    /** "Add more chats" is worth offering: not while every chat already counts. */
    val canAddChats: Boolean get() = !allChatsOn

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
    /** Where topics are found by their names (U14); none for a fixture that does not offer it. */
    private val topicSource: TopicSource? = if (source === VideoSearchSource.Telegram) TopicSource.Telegram else null,
    /** The chats search may list: the kids lock and the added chats ([ChatGate.browsing]); a test's own. */
    private val gate: StateFlow<Set<Long>?> = ChatGate.browsing,
    /** The kids lock alone, for the one-off search of the viewer's other chats ([searchOtherChats]). */
    private val wholeGate: StateFlow<Set<Long>?> = ChatGate.allowed,
    /** The viewer's answer to the picker: whether search reads the added chats alone. */
    private val choice: StateFlow<com.tmplayer.data.AddedChoice> = com.tmplayer.data.AddedChats.choice,
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
    /** Where any film or show is looked up by name (HX41); none for a fixture that does not offer it. */
    private val titleSource: TitleSource? = if (source === VideoSearchSource.Telegram) TitleSource.Online else null,
) : ViewModel(com.tmplayer.platform.JobFailures.viewModelScope("GlobalSearch")) {

    /** Telegram's pages for one query, as the [cache] keeps them: before the kids lock and hidden chats. */
    private data class Answer(val serverQuery: String, val pages: List<AllChatsPage>, val cursor: AllChatsCursor)

    private fun keyOf(query: SearchQuery, filters: SearchFilters) =
        QueryKey.Search(query.server, "${filters.serverSide.first}:${filters.serverSide.second}${if (everyChat) ":every" else ""}")

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

    /**
     * The chat list as the screen holds it, in its order: which groups the topic search may ask
     * ([TopicSearch.candidates]). Set by the screen as the list changes; only loaded chats are
     * ever asked, so a very large account searches the topics of the chats it has loaded.
     */
    @Volatile
    var chatList: List<ChatSummary> = emptyList()

    private var topicJob: Job? = null

    /** The words the topics on screen answer (or are being found for); blank for none. */
    private var topicWords = ""

    /** Whether each chat has topics, as [TopicSource.isForum] said this run, so a chat is looked at once. */
    private val forums = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    /**
     * The viewer's preferred audio language as a two letter code, which leads the copies of a
     * video whose names carry it (EG-F5). Set by the screen from the track preferences.
     */
    @Volatile
    var audioLanguage: String? = null

    /** The cards' copy order: [identity], led by [audioLanguage] where there is one. */
    private val identityNow: SearchIdentity get() = SearchIdentity.preferring(identity, audioLanguage)

    /** "Search my other chats" is on, for the words in [everyChatWords] only (HX62: never the default). */
    @Volatile
    private var everyChat = false
    private var everyChatWords = ""

    /** The chats the cards may come from: the added chats under the kids lock, the lock alone for the one-off. */
    private fun activeGate(): Set<Long>? = if (everyChat) wholeGate.value else gate.value

    private suspend fun fetch(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage =
        if (everyChat) source.everyChatPage(query, cursor, filters) else source.page(query, cursor, filters)

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
        // The kids lock coming on, or a chat removed in the picker, while results are listed
        // takes its chats out of them at once.
        viewModelScope.launch {
            gate.collect { browsing ->
                val allowed = if (everyChat) wholeGate.value else browsing
                if (allowed != null) _state.update { s ->
                    s.copy(
                        groups = s.groups.filter { g -> g.copies.all { KidsLock.allows(allowed, it.chatId) } },
                        topics = s.topics.filter { KidsLock.allows(allowed, it.chatId) },
                    )
                }
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

    /**
     * "Search my other chats" (HX62): the same words once more over every chat of the account,
     * not only the added ones, listed with what was found already. A one-off: the next words go
     * back to the added chats. Nothing to do when nothing was added (every chat is searched
     * already) or the search has no words.
     */
    fun searchOtherChats() {
        val now = _state.value
        if (!now.canSearchOthers) return
        everyChat = true
        everyChatWords = now.query.server
        startVideos(now.query.text, settle = 0, force = true)
    }

    /** Clears every filter, from the empty state's one tap. */
    fun clearFilters() = setFilters(SearchFilters())

    /** The next page, when the list nears its end. */
    fun loadMore() {
        val now = _state.value
        if (now.query.isEmpty || now.busy || now.loadingMore || now.endReached || now.error != null || now.offline) return
        if (now.moreFailed) return
        if (job?.isActive == true) return
        if (now.groups.size >= GlobalSearch.MAX_GROUPS) return
        _state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            runCatching { fetch(serverQuery, cursor, now.filters) }
                .onSuccess { page ->
                    cursor = page.cursor
                    keepNext(now.query, now.filters, page)
                    absorb(page)
                    _state.update { it.copy(loadingMore = false, moreFailed = false, endReached = page.cursor.done) }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    // A page that failed half way down keeps what is on screen and ends in a Try
                    // again row; scrolling alone does not ask again (it would loop on a dead line).
                    _state.update { s -> s.copy(loadingMore = false, moreFailed = true) }
                }
        }
    }

    /** The Try again row after a page failed: asks for that page once more. */
    fun retryMore() {
        if (!_state.value.moreFailed) return
        _state.update { it.copy(moreFailed = false) }
        loadMore()
    }

    /** After a failure: the same query again, without the wait. */
    fun retry() {
        val text = _state.value.query.text
        if (text.isNotBlank()) start(text, settle = 0, force = true)
    }

    /** The topics' own Try again: only the topics are asked again, the videos stay. */
    fun retryTopics() {
        val text = _state.value.query.text
        if (text.isNotBlank()) findTopics(GlobalSearch.clean(text), settle = 0, force = true)
    }

    /** The connection came back: a search that could only list this device's videos asks Telegram. */
    fun reconnected() {
        if (_state.value.offline) retry()
    }

    /**
     * Whether [item] can still be opened: false when its message was deleted or its chat left
     * since the search, and then its card goes. Unreachable, no answer within
     * [VideoSearchSource.CHECK_MS], a copy on this device and a search that is offline all open
     * it: the page handles a video that turns out to be gone.
     */
    suspend fun check(item: MediaItem): Boolean {
        if (item.onDevice || _state.value.offline) return true
        val there = try {
            kotlinx.coroutines.withTimeoutOrNull(VideoSearchSource.CHECK_MS) { source.stillThere(item) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
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
        startVideos(text, settle, force)
        findTopics(GlobalSearch.clean(text), settle, force)
        findTitles(settle, force)
    }

    private var titleJob: Job? = null

    /** The TMDB question the titles on screen answer (or are being found for); null for none. */
    private var titleAsked: MetaQuery? = null

    /**
     * Any film or show the words name (HX41), into [GlobalSearchState.titles]: TMDB once the typing
     * has settled for [settle], one question per settled set of words (the question itself is at
     * most four calls through [OnlineMetadata]'s own limiter, and kept in memory for the run). A
     * quality or a space changing asks nothing new. With lookups off or no key nothing is asked
     * and the cards are the whole answer; offline or throttled leaves a quiet Try again
     * ([retryTitles]). A new letter cancels what was asked.
     */
    private fun findTitles(settle: Long, force: Boolean) {
        val source = titleSource ?: return
        val query = _state.value.query
        val asking = GlobalSearch.titleQuery(query)
        if (asking == null) {
            titleJob?.cancel()
            titleAsked = null
            _state.update { it.copy(titles = emptyList(), titleLookup = TitleLookup.Idle, watching = null) }
            return
        }
        val same = asking.title.equals(titleAsked?.title, ignoreCase = true) && asking.year == titleAsked?.year
        if (same && !force && (titleJob?.isActive == true || _state.value.titleLookup == TitleLookup.Found)) return
        titleJob?.cancel()
        titleAsked = asking
        _state.update { it.copy(titleLookup = TitleLookup.Looking) }
        titleJob = viewModelScope.launch {
            if (settle > 0) delay(settle)
            val answer = try {
                source.titles(asking)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PosterChoices.Unavailable
            }
            _state.update { s ->
                when (answer) {
                    is PosterChoices.Found -> s.copy(titles = answer.choices, titleLookup = TitleLookup.Found)
                    PosterChoices.Off, PosterChoices.NoKey -> s.copy(titles = emptyList(), titleLookup = TitleLookup.Off)
                    PosterChoices.Unavailable -> s.copy(titles = emptyList(), titleLookup = TitleLookup.Unavailable)
                }
            }
        }
    }

    /** The titles' own Try again, after TMDB could not be reached: only the titles are asked again. */
    fun retryTitles() {
        if (_state.value.titleLookup != TitleLookup.Unavailable) return
        findTitles(settle = 0, force = true)
    }

    /**
     * Opens "Where to watch" for [title] (HX41): TMDB's legal streaming and rental list for the
     * viewer's region, one call, kept for the run. A second press while it is asking does nothing.
     */
    fun openWhereToWatch(title: PosterChoice) {
        val source = titleSource ?: return
        val open = _state.value.watching
        if (open != null && open.first == title && open.second != WhereToWatch.Unavailable) return
        _state.update { it.copy(watching = title to null) }
        viewModelScope.launch {
            val answer = try {
                source.whereToWatch(title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WhereToWatch.Unavailable
            }
            // Closed, or another title opened meanwhile: the answer is not shown over it.
            _state.update { s -> if (s.watching?.first == title) s.copy(watching = title to answer) else s }
        }
    }

    fun closeWhereToWatch() = _state.update { it.copy(watching = null) }

    private fun startVideos(text: String, settle: Long, force: Boolean) {
        val query = GlobalSearch.parse(text)
        val now = _state.value
        if (!force && query == now.query) return
        // The one-off search of every chat lasts for its own words only.
        if (everyChat && (query.isEmpty || query.server != everyChatWords)) everyChat = false
        // The kids lock and the added chats (HX27): search lists videos from the added chats alone.
        val allowed = activeGate()
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
                regroup { s -> GlobalSearch.merge(GlobalSearch.order(s.groups, query, s.sort), local, query, s.sort, identityNow) }
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
            _state.value = GlobalSearchState(
                query = query, filters = now.filters, sort = now.sort,
                topics = now.topics, topicsSearching = now.topicsSearching, topicsError = now.topicsError, topicsPartial = now.topicsPartial,
                addedOnly = addedOnlyNow(),
                allChatsOn = allChatsNow(),
            )
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
            topics = now.topics,
            topicsSearching = now.topicsSearching,
            topicsError = now.topicsError,
            topicsPartial = now.topicsPartial,
            addedOnly = addedOnlyNow(),
                allChatsOn = allChatsNow(),
            everyChat = everyChat,
            // The titles stay up until the new words' own answer replaces them ([findTitles]).
            titles = now.titles,
            titleLookup = now.titleLookup,
            watching = now.watching,
        )
        val kept = cache?.peek<Answer>(keyOf(query, now.filters), PageQueries.SEARCH_STALE_MS)
        if (kept != null) {
            serverQuery = kept.data.serverQuery
            cursor = kept.data.cursor
            asked = query.server
        }
        // "Search my other chats" asks the same words again: what the added chats gave stays listed.
        val widening = everyChat && now.groups.isNotEmpty() && !now.query.isEmpty && now.query.server == query.server
        job = viewModelScope.launch {
            // The local half, off the main thread: no connection needed and no wait. What was
            // listed and still answers the new words stays up until Telegram's answer takes its
            // place.
            val local = withContext(work) { GlobalSearch.localVideos(known() + alsoKnown, query, allowed, ::onlineNames) }
            localNow = local
            regroup { s ->
                val carried = GlobalSearch.order(
                    if (widening) s.groups else s.groups.filter { g -> g.copies.any { GlobalSearch.matchesLocally(it, query, onlineNames(it)) } },
                    query,
                    s.sort,
                )
                GlobalSearch.merge(carried, local, query, s.sort, identityNow)
            }
            // The same words searched before: Telegram's pages from then, listed at once through
            // the kids lock and the added chats as they are now, and asked again only once stale.
            if (kept != null) {
                kept.data.pages.forEachIndexed { index, page ->
                    absorb(page.copy(items = page.items.filter { KidsLock.allows(allowed, it.chatId) }), keepTo = query, fresh = index == 0)
                }
                _state.update { it.copy(searching = false, settling = false, endReached = kept.data.cursor.done) }
                if (!kept.stale) return@launch
            }
            if (settle > 0) {
                delay(settle)
                // Asked only now, so only now does the screen say it is searching.
                _state.update { it.copy(settling = false, searching = kept == null) }
            }
            firstAnswer(query, fresh = kept == null && !widening)
        }
    }

    /**
     * Topics whose names answer [words] (U14), into [GlobalSearchState.topics].
     *
     * What this device already holds first and at once: the topics of the groups whose tabs were
     * opened. Then, once the typing has settled for [settle], Telegram, within [TopicSearch]'s
     * budget: the chat list's groups in order, looked up in TDLib's memory until
     * [TopicSearch.CHATS_PER_SEARCH] with topics are found (at most
     * [TopicSearch.FORUM_CHECKS_PER_SEARCH] looked at), [TopicSearch.AT_ONCE] asked at a time.
     * A new letter cancels it all; a FLOOD_WAIT is waited out by the call that got it while the
     * others go on ([ChatRepository.searchForumTopics]). Only when every group asked refused, and
     * nothing was found, does the group say it failed.
     */
    private fun findTopics(words: String, settle: Long, force: Boolean) {
        val source = topicSource ?: return
        if (!force && words == topicWords) return
        topicJob?.cancel()
        topicWords = words
        val chats = chatList
        if (!GlobalSearch.searchable(words) || chats.isEmpty()) {
            _state.update { it.copy(topics = emptyList(), topicsSearching = false, topicsError = null, topicsPartial = false) }
            return
        }
        val allowed = gate.value
        val includeArchived = _state.value.filters.includeArchived
        topicJob = viewModelScope.launch {
            val order = withContext(work) { chats.withIndex().associate { it.value.id to it.index } }
            val candidates = withContext(work) {
                TopicSearch.candidates(chats, words, { KidsLock.allows(allowed, it) }, forums::get, includeArchived)
            }
            // The groups whose topics are kept from an earlier visit are matched from them, for free.
            val kept = candidates.mapNotNull { chat -> source.known(chat.id)?.let { chat to it } }
            val local = withContext(work) {
                kept.flatMap { (chat, topics) -> TopicSearch.hits(chat.id, chat.title, topics, words, fromTelegram = false) }
            }
            val keptIds = kept.mapTo(HashSet()) { it.first.id }
            _state.update { it.copy(topics = TopicSearch.rank(local, order), topicsSearching = true, topicsError = null, topicsPartial = false) }
            if (settle > 0) delay(settle)

            // The groups with topics, from TDLib's memory, within the budget.
            val asking = mutableListOf<ChatSummary>()
            var looked = 0
            var partial = false
            for (chat in candidates) {
                if (chat.id in keptIds) continue
                if (asking.size >= TopicSearch.CHATS_PER_SEARCH || looked >= TopicSearch.FORUM_CHECKS_PER_SEARCH) {
                    partial = true
                    break
                }
                val forum = forums[chat.id] ?: run {
                    looked++
                    runCatching { source.isForum(chat.id) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                        ?.also { forums[chat.id] = it }
                }
                if (forum == true) asking += chat
            }

            val found = java.util.Collections.synchronizedList(ArrayList(local))
            val failures = java.util.concurrent.atomic.AtomicInteger()
            var failure: Throwable? = null
            val permits = Semaphore(TopicSearch.AT_ONCE)
            coroutineScope {
                asking.forEach { chat ->
                    launch {
                        permits.withPermit {
                            try {
                                val topics = source.search(chat.id, words, TopicSearch.TOPICS_PER_CHAT)
                                found += TopicSearch.hits(chat.id, chat.title, topics, words, fromTelegram = true)
                                val ranked = withContext(work) { TopicSearch.rank(found.toList(), order) }
                                _state.update { it.copy(topics = ranked) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failures.incrementAndGet()
                                failure = e
                            }
                        }
                    }
                }
            }
            val failed = failure?.takeIf { asking.isNotEmpty() && failures.get() == asking.size && found.isEmpty() }
            _state.update {
                it.copy(
                    topicsSearching = false,
                    topicsError = failed?.let { e -> Failures.humanise(e) },
                    topicsPartial = partial,
                )
            }
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
            var page = fetch(serverQuery, AllChatsCursor(), filters)
            pages++
            if (page.items.isEmpty()) {
                // Telegram matches whole words, and to it "Harbour_Notes" is one: try the query
                // spelled as release names spell it, then its longest word alone.
                val tries = GlobalSearch.variants(query.server) + listOfNotNull(com.tmplayer.data.AllChatsSearch.fallbackQuery(query.server))
                for (spelling in tries) {
                    val retry = fetch(spelling, AllChatsCursor(), filters)
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
                val next = fetch(serverQuery, cursor, filters)
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
            val base = if (fresh) GlobalSearch.merge(emptyList(), local, s.query, s.sort, identityNow) else s.groups
            GlobalSearch.merge(base, kept, s.query, s.sort, identityNow)
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

    /** Whether search reads the added chats alone: the viewer picked chats (HX27), however few. */
    private fun addedOnlyNow(): Boolean = choice.value.mode == com.tmplayer.data.AddedMode.Picked

    private fun allChatsNow(): Boolean = choice.value.mode == com.tmplayer.data.AddedMode.All

    /** The online title of [item], when the providers have already been asked about it. */
    private fun onlineNames(item: MediaItem): List<String> {
        val online = OnlineMetadata.current ?: return emptyList()
        val query = com.tmplayer.data.VideoCorrections.queryOf(item) ?: return emptyList()
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
