package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.Account
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Failures
import com.tmplayer.data.Fuzzy
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.SponsoredBatch
import com.tmplayer.data.SponsoredItem
import com.tmplayer.data.SponsoredMessageRepository
import com.tmplayer.data.SponsoredReportOutcome
import com.tmplayer.data.Td
import com.tmplayer.data.TdSession
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The chat list plus who is signed in; both are needed before the browser can draw. */
data class BrowseData(
    val chats: List<ChatSummary>,
    val account: Account? = null,
)

class ChatListViewModel : ViewModel() {

    private val _state = MutableStateFlow<UiState<BrowseData>>(UiState.Loading("Loading your chats…"))
    val state: StateFlow<UiState<BrowseData>> = _state.asStateFlow()

    init {
        load()
    }

    private var loadJob: Job? = null
    private var account: Account? = null
    private var chats: List<ChatSummary>? = null

    /** When the list last finished syncing, for [refreshIfStale]. */
    private var syncedAt = 0L

    /**
     * Refreshes only if the list has had time to go stale.
     *
     * Every press of Back out of a chat or out of Settings used to call [load], and [load] pulls
     * the whole chat list from the server and then re-reads three hundred chats out of TDLib's
     * database one at a time. On a stick that is a visible stutter, and it happened on a screen
     * the viewer had been looking at ninety seconds earlier. Telegram does reorder chats as
     * messages arrive, so the list genuinely does go stale, but not in the time it takes to look
     * at one chat and come back.
     */
    fun refreshIfStale() {
        if (chats == null || System.currentTimeMillis() - syncedAt > STALE_AFTER_MS) load()
    }

    fun load() {
        loadJob?.cancel()
        if (chats == null) _state.value = UiState.Loading("Loading your chats…")
        loadJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            try {
                // Either local read may be briefly unavailable on the first Ready update after
                // QR sign-in. Neither is treated as the final answer; the connected pass below
                // retries both without asking the viewer to press Refresh.
                chats = try {
                    repository.cachedChats()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                account = try {
                    Td.me(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (!session.isCurrent()) return@launch
                if (chats != null) publish()

                // The local database is the first answer. Telegram is then synchronized when its
                // socket is ready, without holding the saved library behind a connection wait.
                val connected = Td.awaitConnectedSession()
                if (connected.generation != session.generation) return@launch
                if (account == null) account = runCatching { Td.me(session) }.getOrNull()
                chats = repository.syncChats()
                syncedAt = System.currentTimeMillis()
                if (session.isCurrent()) publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (session.isCurrent() && chats == null) {
                    _state.value = UiState.Error(Failures.humanise(error))
                }
            }
        }
    }

    /** Removes one account's data immediately when authorization is lost. */
    fun reset() {
        loadJob?.cancel()
        account = null
        chats = null
        syncedAt = 0L
        _state.value = UiState.Loading("Loading your chats…")
    }

    private companion object {
        /**
         * Long enough that walking into a chat and back never re-syncs, short enough that a list
         * left open while something else was being watched catches up on the way back.
         */
        const val STALE_AFTER_MS = 2 * 60 * 1000L
    }

    private fun publish() {
        val loaded = chats ?: return
        _state.value = if (loaded.isEmpty()) {
            UiState.Empty("No chats yet. Open Telegram on your phone, then come back.")
        } else {
            UiState.Content(BrowseData(loaded, account))
        }
    }
}

data class MediaListState(
    val items: List<MediaItem> = emptyList(),
    val sponsored: SponsoredBatch? = null,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

class MediaListViewModel(
    private val chatId: Long,
    private val minSizeBytes: Long,
    private val maxSizeBytes: Long,
) : ViewModel() {

    private var cursors = MediaCursors()
    private var pageJob: Job? = null
    private var availabilityJob: Job? = null
    private var sponsoredJob: Job? = null

    /** What the viewer typed. Kept whole, because it is what results are ranked against. */
    private var query = ""

    /**
     * What Telegram is actually being asked for, which is not always [query].
     *
     * Telegram matches a search against the file name as a literal run of characters, so a query
     * of four words finds nothing unless all four appear in that order. Real file names are
     * `Show.Name.S02E04.1080p.WEB-DL.mkv`, and real queries are "show name season 2". When the
     * whole query comes back empty this falls back to the single most distinctive word in it, and
     * the pages that word returns are then ranked locally against everything the viewer typed.
     */
    private var serverQuery = ""
    private var sponsored: SponsoredBatch? = null
    private val viewedSponsored = mutableSetOf<Long>()

    private val _state = MutableStateFlow<UiState<MediaListState>>(UiState.Loading("Finding videos…"))
    val state: StateFlow<UiState<MediaListState>> = _state.asStateFlow()

    init {
        load()
    }

    /** Re-runs the listing against [text]; blank means "everything in this chat". */
    fun search(text: String) {
        if (text == query) return
        query = text
        load(preserveContent = false)
    }

    fun load() = load(preserveContent = true)

    private fun load(preserveContent: Boolean) {
        val previous = if (preserveContent) (_state.value as? UiState.Content)?.value else null
        val previousCursors = cursors
        cursors = MediaCursors()
        val searching = query.isNotBlank()
        if (previous == null) {
            _state.value = UiState.Loading(if (searching) "Searching…" else "Finding videos…")
        }
        pageJob?.cancel()
        loadSponsored()
        pageJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            serverQuery = query
            runCatching { firstPage(repository) }
                .onSuccess { rawPage ->
                    if (!session.isCurrent()) return@onSuccess
                    val page = rawPage.copy(items = keep(rawPage.items))
                    cursors = page.cursors
                    _state.value = if (
                        page.items.isEmpty() && page.endReached && sponsored == null
                    ) {
                        UiState.Empty(emptyMessage())
                    } else {
                        UiState.Content(
                            MediaListState(
                                items = page.items,
                                sponsored = sponsored,
                                endReached = page.endReached,
                            ),
                        )
                    }
                    // A first page can come back empty while older pages still hold videos: a chat
                    // whose newest forty files are all outside the size limits, most often.
                    //
                    // Continued here rather than by calling loadMore(), which is what this used to
                    // do and which never ran: loadMore() refuses to start while pageJob is active,
                    // and pageJob is this very coroutine, so the recovery was a guaranteed no-op
                    // and such a chat simply reported itself as having no videos.
                    if (page.items.isEmpty() && !page.endReached) {
                        pageMore(session, repository, emptyList(), page.endReached)
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (session.isCurrent()) {
                        if (previous != null) {
                            cursors = previousCursors
                            _state.value = UiState.Content(previous)
                        } else {
                            _state.value = UiState.Error(Failures.humanise(it))
                        }
                    }
                }
        }
    }

    /**
     * The first page, with the single-word retry behind it.
     *
     * The full query is asked for first, because when it works it is the most precise answer
     * available and costs one round trip. Only an empty first page earns the retries, and only for
     * a query of two words or more: a single word that found nothing found nothing.
     *
     * The words are tried longest first because the long ones carry the meaning. "the" and "s02"
     * match half a chat; "severance" matches the show. Whichever word first returns anything
     * becomes the paging query from then on, so scrolling keeps working with the cursors it has,
     * and every page is ranked locally against what the viewer actually typed.
     */
    private suspend fun firstPage(repository: ChatRepository): MediaPage {
        val whole = repository.mediaPage(chatId, MediaCursors(), query)
        if (whole.items.isNotEmpty() || query.isBlank()) return whole

        val words = Fuzzy.tokens(query)
            .filter { it.length >= MIN_FALLBACK_WORD }
            .sortedByDescending { it.length }
            .take(MAX_FALLBACK_WORDS)
        if (words.size < 2 && Fuzzy.tokens(query).size < 2) return whole

        for (word in words) {
            if (word == query) continue
            val page = repository.mediaPage(chatId, MediaCursors(), word)
            if (page.items.isNotEmpty()) {
                serverQuery = word
                return page
            }
        }
        return whole
    }

    /**
     * The size filter, plus the local ranking when a search is running.
     *
     * Ranking here rather than only in [firstPage] is what keeps a fallback honest: the server was
     * asked about one word, so it will happily return everything in the chat that carries it, and
     * without this the viewer would see a listing that has very little to do with their query.
     */
    private fun keep(items: List<MediaItem>): List<MediaItem> {
        val sized = items.filter(::withinSizeLimits)
        if (query.isBlank()) return sized
        return Fuzzy.rank(sized, query) { it.fileName.ifBlank { it.title } }
    }

    /** Sponsored content never blocks or replaces the ordinary media result. */
    private fun loadSponsored() {
        sponsoredJob?.cancel()
        sponsoredJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val loaded = runCatching {
                SponsoredMessageRepository(session.client).load(chatId)
            }.getOrNull()
            if (!session.isCurrent()) return@launch
            sponsored = loaded?.takeIf { it.messages.isNotEmpty() }
            when (val current = _state.value) {
                is UiState.Content -> {
                    _state.value = UiState.Content(current.value.copy(sponsored = sponsored))
                }
                is UiState.Empty -> if (sponsored != null) {
                    _state.value = UiState.Content(
                        MediaListState(sponsored = sponsored, endReached = true),
                    )
                }
                else -> Unit
            }
        }
    }

    fun markSponsoredViewed(messageId: Long) {
        if (!viewedSponsored.add(messageId)) return
        viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val result = runCatching {
                SponsoredMessageRepository(session.client).markViewed(chatId, messageId)
            }
            if (result.isFailure && session.isCurrent()) viewedSponsored.remove(messageId)
        }
    }

    fun clickSponsored(
        item: SponsoredItem,
        media: Boolean,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            runCatching {
                SponsoredMessageRepository(session.client).click(chatId, item.messageId, media)
            }.onSuccess {
                if (session.isCurrent()) onSuccess()
            }.onFailure {
                if (session.isCurrent()) onFailure(Failures.humanise(it))
            }
        }
    }

    fun reportSponsored(
        item: SponsoredItem,
        optionId: ByteArray = byteArrayOf(),
        onResult: (SponsoredReportOutcome) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            runCatching {
                SponsoredMessageRepository(session.client).report(chatId, item.messageId, optionId)
            }.onSuccess { outcome ->
                if (!session.isCurrent()) return@onSuccess
                if (
                    outcome is SponsoredReportOutcome.Reported ||
                    outcome is SponsoredReportOutcome.AdsHidden
                ) {
                    sponsored = sponsored?.copy(
                        messages = sponsored?.messages.orEmpty().filterNot {
                            it.messageId == item.messageId
                        },
                    )?.takeIf { it.messages.isNotEmpty() }
                    val current = (_state.value as? UiState.Content)?.value
                    if (current != null) {
                        _state.value = UiState.Content(current.copy(sponsored = sponsored))
                    }
                }
                onResult(outcome)
            }.onFailure {
                if (session.isCurrent()) onFailure(Failures.humanise(it))
            }
        }
    }

    /** Why this chat is showing nothing, in the terms the viewer can do something about. */
    private fun emptyMessage(): String = when {
        query.isNotBlank() -> "Nothing in this chat matches “$query”."
        // Say which knob is hiding things, rather than claiming the chat is empty when it is
        // the filter doing the work.
        isFiltering -> "No videos here between " +
            "${SizeFilter.label(minSizeBytes)} and " +
            "${SizeFilter.label(maxSizeBytes)}.\n\n" +
            "Change the video size limits in Settings to see more."
        else -> "No playable videos in this chat."
    }

    private val isFiltering: Boolean
        get() = minSizeBytes > SizeFilter.FLOOR || maxSizeBytes < SizeFilter.CEILING

    private fun withinSizeLimits(item: MediaItem) =
        SizeFilter.matches(item.sizeBytes, minSizeBytes, maxSizeBytes)

    /** Revalidates badges after playback or a cache clear without rebuilding the whole grid. */
    fun refreshLocalAvailability() {
        val current = (_state.value as? UiState.Content)?.value ?: return
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            var changed = false
            val updated = current.items.map { item ->
                val onDevice = Td.localFileAvailability(item.fileId) == LocalFileAvailability.Complete
                if (onDevice == item.onDevice) {
                    item
                } else {
                    changed = true
                    item.copy(onDevice = onDevice)
                }
            }
            // No revision counter beside this any more: MediaItem compares by value, so a list
            // whose badges moved is genuinely a different list and the flow publishes it.
            if (session.isCurrent() && changed) {
                _state.value = UiState.Content(current.copy(items = updated))
            }
        }
    }

    /**
     * Called when focus nears the end of the listing.
     *
     * Scrolling never stops at a page boundary: pages keep being pulled until this one actually
     * grew, or the chat ran out. Without that, a page whose videos are all outside the size limits
     * adds nothing, the item count does not change, and the screen the viewer is scrolling has no
     * reason left to ask for more, so the listing stops short of the end of the chat.
     */
    fun loadMore() {
        val current = _state.value as? UiState.Content ?: return
        if (current.value.loadingMore || current.value.endReached) return
        if (pageJob?.isActive == true) return

        _state.value = UiState.Content(current.value.copy(loadingMore = true))
        pageJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            pageMore(session, repository, current.value.items, endReached = false)
        }
    }

    /**
     * Pulls pages until the listing actually grew, or the chat ran out.
     *
     * Shared by the scroll-triggered [loadMore] and by the recovery inside the first load, so the
     * two cannot drift apart. It publishes the result itself rather than returning it, because
     * both callers want exactly the same published shape.
     */
    private suspend fun pageMore(
        session: TdSession,
        repository: ChatRepository,
        startingFrom: List<MediaItem>,
        endReached: Boolean,
    ) {
        var items = startingFrom
        var reachedEnd = endReached
        var failed = false
        // A chat can hold thousands of files that the size limits all reject, and the viewer
        // is watching a "Loading more…" chip the whole time. After this many empty-handed
        // pages it gives the screen back; the next scroll picks up where the cursors are.
        var attempts = 0

        while (
            items.size == startingFrom.size &&
            !reachedEnd &&
            !failed &&
            attempts++ < MAX_EMPTY_PAGES
        ) {
            runCatching { repository.mediaPage(chatId, cursors, serverQuery) }
                .onSuccess { rawPage ->
                    val page = rawPage.copy(items = keep(rawPage.items))
                    cursors = page.cursors
                    items = (items + page.items).distinctBy { it.messageId }
                    reachedEnd = page.endReached
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    failed = true
                }
        }

        if (!session.isCurrent()) return
        _state.value = if (items.isEmpty() && reachedEnd && sponsored == null) {
            UiState.Empty(emptyMessage())
        } else {
            UiState.Content(
                MediaListState(
                    items = items,
                    sponsored = sponsored,
                    loadingMore = false,
                    // A page that failed is not the end of the chat, only the end of this attempt.
                    // Leaving the listing open means scrolling on retries it, rather than a single
                    // dropped connection cutting the chat short for as long as it stays open.
                    endReached = reachedEnd,
                ),
            )
        }
    }

    private companion object {
        const val MAX_EMPTY_PAGES = 8

        /** Shorter than this and a word is "the" or "s02": it narrows nothing. */
        const val MIN_FALLBACK_WORD = 3

        /** Three retries is already three round trips; past that the wait is the worse answer. */
        const val MAX_FALLBACK_WORDS = 3
    }
}
