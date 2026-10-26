package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.i18n.L
import com.tmplayer.platform.Background
import com.tmplayer.data.Account
import com.tmplayer.data.AuthState
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.KidsLock
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ForumTopicSummary
import com.tmplayer.data.arrangeChats
import com.tmplayer.data.Failures
import com.tmplayer.data.Fuzzy
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.ChatGrid
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.SponsoredBatch
import com.tmplayer.data.SponsoredItem
import com.tmplayer.data.SponsoredMessageRepository
import com.tmplayer.data.SponsoredReportOutcome
import com.tmplayer.data.Td
import com.tmplayer.data.batched
import com.tmplayer.data.VideoChanges
import com.tmplayer.data.VideoBatch
import com.tmplayer.data.TdSession
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.icons.TmIcons
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListMain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** The chat list plus who is signed in; both are needed before the browser can draw. */
data class BrowseData(
    val chats: List<ChatSummary>,
    val account: Account? = null,
)

/**
 * @param settings where the cold-start snapshot lives. Null in the contexts that have no
 *   Application to hand, in which case the first frame waits on TDLib.
 */
class ChatListViewModel(
    private val settings: SettingsStore? = null,
    /**
     * The page cache the list is kept in for this run ([PageQueries.cache]), so a view model made
     * again draws it in its first frame. Null (the tests) keeps nothing between view models.
     */
    private val memory: com.tmplayer.data.QueryCache? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<BrowseData>>(UiState.Loading(LOADING_CHATS))
    val state: StateFlow<UiState<BrowseData>> = _state.asStateFlow()

    /**
     * True once the store has said there is no list from an earlier launch, which makes this the
     * first sync on this device: the slow one, worth a line explaining that it is only slow once.
     */
    private var firstLoad = false

    private fun loading(): UiState.Loading =
        UiState.Loading(LOADING_CHATS, tip = FIRST_LOAD_TIP.takeIf { firstLoad })

    /**
     * The kids lock (CP57), applied where the list is handed to the screens rather than where it
     * is read: the rows in hand stay whole, so the lock going off shows every chat at once, with
     * no re-sync, and a chat arriving through an update is screened like the rest.
     */
    private fun followKidsLock() {
        viewModelScope.launch {
            ChatGate.allowed.collect { publish() }
        }
        // A chat hidden from its menu, or shown again from Settings, leaves or rejoins the list at once.
        viewModelScope.launch {
            com.tmplayer.data.HiddenChats.ids.collect { publish() }
        }
    }

    /**
     * Draws yesterday's list now, so the screen is not empty while TDLib opens its database.
     *
     * Nothing here is trusted for longer than it takes the real list to arrive: [load] is already
     * running alongside this and overwrites it, and a snapshot that loses the race is simply
     * dropped rather than painted over content that is newer than it is.
     */
    private fun paintFromSnapshot() {
        // The list this run already had (a second window, an account screen made again) is in
        // memory: drawn in this very frame, before the disk is asked. The folders are cut from it
        // on the screen, so each folder's list is there at once as well.
        memory?.data<List<ChatSummary>>(QueryKey.ChatList())?.takeIf { it.isNotEmpty() }?.let { kept ->
            viewModelScope.launch {
                ChatGate.awaitReady()
                if (chats != null) return@launch
                chats = kept
                publish()
            }
        }
        val store = settings ?: return
        viewModelScope.launch {
            // The header first: it is one small read, and a rail that says "Your account" over a
            // grey circle for the seconds TDLib takes to open reads as the app having forgotten
            // who it belongs to.
            val rememberedAccount = runCatching { store.cachedAccountSnapshot() }.getOrNull()
            if (rememberedAccount != null && account == null) {
                account = rememberedAccount
                publish()
            }
            // Null when the read itself failed, which says nothing about whether this install has
            // seen the list before, so it is not taken for a first visit.
            val read = runCatching { store.cachedChatSnapshot() }.getOrNull()
            val remembered = read.orEmpty()
            if (read != null && read.isEmpty() && chats == null) {
                firstLoad = true
                if (_state.value is UiState.Loading) _state.value = loading()
            }
            if (remembered.isEmpty() || chats != null) return@launch
            // The kids lock is read before yesterday's list is drawn, not after: painting every
            // chat for the moment the store takes to answer would show what the lock hides.
            ChatGate.awaitReady()
            if (chats != null) return@launch
            chats = remembered
            publish()
        }
    }

    /**
     * Keeps the list live while it is on screen: titles, photos, order, unread counts and mutes.
     *
     * Order is followed through TDLib's own ordering rather than by sorting on the position it
     * hands out: `getChats` reads the list it already keeps sorted, out of its local database, and
     * costs one round trip, with no `getChat` fan-out, because every row is already in hand and
     * only its place is in question.
     */
    private fun watchChatChanges() {
        viewModelScope.launch {
            while (true) {
                val session = Td.awaitAuthorizedSession()
                val client = session.client
                try {
                    coroutineScope {
                        launch {
                            client.chatTitleUpdates.collect { update ->
                                edit(update.chatId) { it.copy(title = update.title) }
                            }
                        }
                        launch {
                            client.chatPhotoUpdates.collect { update ->
                                edit(update.chatId) {
                                    it.copy(
                                        miniThumbnail = update.photo?.minithumbnail?.data,
                                        photoFileId = update.photo?.small?.id ?: 0,
                                    )
                                }
                            }
                        }
                        launch {
                            // Every message is a position update, so a busy account produces a
                            // burst. Coalesced, so one read follows the burst rather than each
                            // update. The archive is watched too: moving a chat between the two
                            // lists is reported as a position in each, and there is a tab for it.
                            client.chatPositionUpdates
                                .filter {
                                    it.position.list is ChatListMain ||
                                        it.position.list is ChatListArchive
                                }
                                .onEach { update ->
                                    // The pin travels with the position, so it is read straight
                                    // off the update: re-reading the order alone would move a
                                    // freshly pinned chat while its row still said unpinned.
                                    val archived = update.position.list is ChatListArchive
                                    if (update.position.order != 0L) {
                                        edit(update.chatId) {
                                            it.copy(
                                                isPinned = update.position.isPinned,
                                                isArchived = archived,
                                            )
                                        }
                                    }
                                }
                                .conflate()
                                .collect {
                                    delay(REORDER_SETTLE_MS)
                                    reorder(ChatRepository(client))
                                }
                        }
                        launch {
                            // Reading a chat somewhere else empties its badge here, which is what
                            // keeps the Unread tab a live list.
                            client.chatReadInboxUpdates.collect { update ->
                                edit(update.chatId) { it.copy(unreadCount = update.unreadCount) }
                            }
                        }
                        launch {
                            client.chatNotificationSettingsUpdates.collect { update ->
                                edit(update.chatId) {
                                    it.copy(
                                        isMuted = !update.notificationSettings.useDefaultMuteFor &&
                                            update.notificationSettings.muteFor > 0,
                                    )
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The client went away with a sign-out. The loop waits for the next one.
                }
                // Nothing to collect from a dead client; wait for the session to be replaced.
                Td.auth.first { it !is AuthState.Ready }
            }
        }
    }

    /**
     * Puts the rows already in hand into the order TDLib now holds them in.
     *
     * Ids TDLib knows and this screen does not are skipped rather than fetched: a chat that has
     * only just appeared arrives with the next sync, and chasing it here would put a `getChat`
     * fan-out behind an update that fires on every incoming message. Rows TDLib no longer lists
     * are dropped, which is what leaving a chat looks like from here.
     */
    private suspend fun reorder(repository: ChatRepository) {
        val current = chats ?: return
        val order = runCatching { repository.chatOrder(limit) }.getOrNull() ?: return
        if (order.isEmpty()) return
        val byId = current.associateBy { it.id }
        // Arranged, not merely reordered. TDLib's order takes no view on pinning, so applying it
        // raw would undo the pinned-first arrangement on any incoming message.
        val sorted = arrangeChats(order.mapNotNull(byId::get))
        // Only when every row survived the move. A short answer means TDLib is holding fewer
        // chats than the screen is, and dropping the difference would look like the library
        // shrinking on its own.
        if (sorted.size != current.size || sorted == current) return
        chats = sorted
        publish()
    }

    /** Applies a change to one row, if that row is on screen. Publishes only when it moved. */
    private fun edit(chatId: Long, change: (ChatSummary) -> ChatSummary) {
        val current = chats ?: return
        val at = current.indexOfFirst { it.id == chatId }
        if (at < 0) return
        val updated = change(current[at])
        if (updated == current[at]) return
        chats = current.toMutableList().also { it[at] = updated }
        publish()
    }

    private var loadJob: Job? = null

    private val _refreshing = MutableStateFlow(false)

    /** Whether a load is running, so a refresh button can keep turning until the list is in. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * The header's own channel to the drawer, because [publish] cannot carry it until the chat
     * list exists: on a cold start the name and face are read off disk in milliseconds while the
     * list is still seconds away, and a header held hostage to the list is the "Your account"
     * placeholder the snapshot was written to avoid.
     */
    private val _accountHeader = MutableStateFlow<Account?>(null)
    val accountHeader: StateFlow<Account?> = _accountHeader.asStateFlow()

    private var account: Account? = null
        set(value) {
            field = value
            _accountHeader.value = value
        }
    private var chats: List<ChatSummary>? = null

    /** When the list last finished syncing, for [refreshIfStale]. */
    private var syncedAt = 0L

    /**
     * How many chats of the main list are read: [ChatRepository.CHAT_LIMIT] at first, a further
     * [ChatRepository.CHAT_LIMIT] each time the list's end comes near while Telegram may have more
     * ([loadMoreChats]), so an account with a thousand chats can still scroll to its oldest.
     */
    private var limit = ChatRepository.CHAT_LIMIT

    private val _paging = MutableStateFlow(ChatPaging())

    /** Whether the list may go on past the chats read, and whether the next ones are on their way. */
    val paging: StateFlow<ChatPaging> = _paging.asStateFlow()

    private var moreJob: Job? = null

    /** Whether Telegram may hold more of the main list than [limit] let in: the list came back full. */
    private fun mayHoldMore(list: List<ChatSummary>): Boolean = list.count { !it.isArchived } >= limit

    /**
     * Reads the next [ChatRepository.CHAT_LIMIT] chats of the main list, when the list came back
     * full: the list's end trigger ([com.tmplayer.ui.components.LoadMoreAhead]). One at a time; a
     * failure keeps the rows there are and says so, and calling this again tries again.
     */
    fun loadMoreChats() {
        val current = chats ?: return
        val now = _paging.value
        if (moreJob?.isActive == true || loadJob?.isActive == true || !now.more) return
        _paging.value = now.copy(loading = true, failure = null)
        moreJob = viewModelScope.launch {
            try {
                val session = Td.awaitConnectedSessionOrNull()
                if (session == null) {
                    _paging.value = _paging.value.copy(loading = false, failure = Failures.OFFLINE)
                    return@launch
                }
                val wanted = limit + ChatRepository.CHAT_LIMIT
                val sync = ChatRepository(session.client).syncChats(limit = wanted, known = current)
                if (!session.isCurrent()) return@launch
                Td.noteFailure(sync.failure)
                if (sync.chats.size >= current.size) {
                    limit = wanted
                    chats = foldered(sync.chats)
                    publish()
                    if (sync.complete) rememberSnapshot(sync.chats)
                }
                _paging.value = ChatPaging(
                    more = mayHoldMore(chats.orEmpty()),
                    failure = sync.failure?.let { Failures.humanise(it) },
                )
            } catch (cancelled: CancellationException) {
                _paging.value = _paging.value.copy(loading = false)
                throw cancelled
            } catch (error: Throwable) {
                _paging.value = _paging.value.copy(loading = false, failure = Failures.humanise(error))
            }
        }
    }

    // After every property, not above them: on Dispatchers.Main.immediate (the desktop's, and
    // Android's) the snapshot coroutine runs inside this block, and a field declared below it
    // would still be null there, or be reset by its initialiser once the block returned.
    init {
        paintFromSnapshot()
        load()
        watchChatChanges()
        followKidsLock()
    }

    /**
     * Refreshes only if the list has had time to go stale.
     *
     * [load] pulls the whole chat list from the server and then re-reads three hundred chats out
     * of TDLib's database one at a time, which on a stick is a visible stutter. Telegram does
     * reorder chats as messages arrive, so the list genuinely goes stale, but not in the time it
     * takes to look at one chat and come back.
     */
    fun refreshIfStale() {
        if (chats == null || System.currentTimeMillis() - syncedAt > STALE_AFTER_MS) load()
    }

    /**
     * The Refresh button, which declines to make a flood wait worse.
     *
     * Telegram answers a request made during a flood wait by extending it, so this refuses instead
     * and returns the seconds left, so the screen can say so.
     */
    fun refreshUnlessRateLimited(): Int {
        val waiting = Td.floodWaitRemainingSeconds()
        if (waiting > 0) return waiting
        load()
        return 0
    }

    /**
     * Retry on a list that is taking long: the load already running goes on (TDLib keeps what it
     * has read, and a new load would only ask again from the top), a stopped one starts again.
     */
    fun retry() {
        if (loadJob?.isActive == true) return
        load()
    }

    /** Which chats are in which folder, as last read ([fillFolders]); null until read once. */
    private var membership: Map<Long, List<Int>>? = null
    private var foldersJob: Job? = null

    /**
     * Reads which chats are in which folder, behind the list rather than ahead of it: on a
     * database TDLib has not filled yet that reads every chat of the account, and the list waited
     * for it before showing anything (HX8). The rows keep their folders meanwhile.
     */
    private fun fillFolders(repository: ChatRepository, session: com.tmplayer.data.TdSession) {
        if (foldersJob?.isActive == true || Td.folders.value.isEmpty()) return
        foldersJob = viewModelScope.launch {
            val found = runCatching { repository.folderMembership() }.getOrNull() ?: return@launch
            if (!session.isCurrent()) return@launch
            membership = found
            val current = chats ?: return@launch
            val next = com.tmplayer.data.ChatListLoad.withFolders(current, found)
            if (next != current) {
                chats = next
                publish()
            }
        }
    }

    /**
     * [list] with the folders last read, for rows read since; before they are read, each row keeps
     * the folders the rows on screen (yesterday's snapshot) had, so the folder chips do not empty.
     */
    private fun foldered(list: List<ChatSummary>): List<ChatSummary> {
        membership?.let { return com.tmplayer.data.ChatListLoad.withFolders(list, it) }
        val before = chats?.associate { it.id to it.folderIds } ?: return list
        return list.map { chat -> before[chat.id]?.takeIf { it != chat.folderIds }?.let { chat.copy(folderIds = it) } ?: chat }
    }

    private fun rememberSnapshot(chats: List<ChatSummary>) {
        val store = settings ?: return
        // On the background scope rather than this one: it outlives the screen on purpose, since
        // the whole value of the write is to the launch after this one.
        Background.scope.launch { runCatching { store.saveChatSnapshot(chats) } }
    }

    fun load() {
        loadJob?.cancel()
        if (chats == null) _state.value = loading()
        _refreshing.value = true
        val job = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            try {
                // Either local read may be briefly unavailable on the first Ready update after
                // QR sign-in. Neither is treated as the final answer; the connected pass below
                // retries both without asking the viewer to press Refresh.
                chats = try {
                    // An empty answer here is TDLib's database still waking, not an empty
                    // account; the connected sync below is the one allowed to say "no chats".
                    // Either way the cold-start snapshot is kept until something real beats it.
                    repository.cachedChats(limit).takeIf { it.isNotEmpty() }?.let(::foldered) ?: chats
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    chats
                }
                account = try {
                    Td.me(session) ?: account
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The snapshot-seeded header, if there was one, survives a failed fetch.
                    account
                }
                if (!session.isCurrent()) return@launch
                if (chats != null) publish()

                // The local database is the first answer. Telegram is then synchronized when its
                // socket is ready, without holding the saved library behind a connection wait.
                // The wait is time-boxed, so a device that never gets a socket still gets its
                // cached list rather than sitting on "Loading your chats…".
                val connected = Td.awaitConnectedSessionOrNull()
                if (connected == null) {
                    if (session.isCurrent() && chats == null) {
                        _state.value = UiState.Error(Failures.OFFLINE)
                    }
                    return@launch
                }
                if (connected.generation != session.generation) return@launch
                // The connected pass always asks again: the answer before it may have been the
                // cold-start snapshot, whose photo id is a placeholder. Written down afterwards
                // for the next launch; content-equal writes are skipped in the store.
                account = runCatching { Td.me(session) }.getOrNull() ?: account
                account?.let { fresh ->
                    settings?.let { store -> runCatching { store.saveAccountSnapshot(fresh) } }
                }
                // Handed what the local read already produced, so the sync fetches only chats it
                // has not seen rather than the whole list a second time.
                val sync = repository.syncChats(limit = limit, known = chats.orEmpty())
                // A failed sync is not an empty library. The pages that did arrive are still
                // worth showing, and the flood wait inside the message is what stops Refresh
                // from making the next attempt worse.
                Td.noteFailure(sync.failure)
                if (sync.complete || sync.chats.isNotEmpty()) chats = foldered(sync.chats)
                if (sync.complete) syncedAt = System.currentTimeMillis()
                if (session.isCurrent()) publish()
                // A first launch keeps what it got even when the sync fell short (a flood wait on
                // a large account): without it every start waited on TDLib again (HX8).
                if (sync.complete || (firstLoad && sync.chats.isNotEmpty())) chats?.let(::rememberSnapshot)
                if (sync.complete) _paging.value = ChatPaging(more = mayHoldMore(sync.chats))
                if (session.isCurrent()) fillFolders(repository, session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (session.isCurrent() && chats == null) {
                    _state.value = UiState.Error(Failures.humanise(error))
                }
            }
        }
        loadJob = job
        // A newer load cancels this one; only the newest says when the list has arrived.
        job.invokeOnCompletion { if (loadJob === job) _refreshing.value = false }
    }

    /**
     * Pins, archives and marks read: the three actions that change Telegram rather than this app.
     *
     * Each one draws its result immediately and then lets TDLib correct it. Waiting on a round trip
     * before the row moves means a menu that closes onto an unchanged list, which reads as a press
     * that did nothing. TDLib sends the real position and read state back within a moment, and the
     * collectors above apply it, so a request that fails is undone rather than left as a false row.
     */
    fun setPinned(chat: ChatSummary, pinned: Boolean, onFailure: (String) -> Unit) {
        edit(chat.id) { it.copy(isPinned = pinned) }
        arrange()
        act(onFailure) { it.setPinned(chat, pinned) }
    }

    fun setArchived(chat: ChatSummary, archived: Boolean, onFailure: (String) -> Unit) {
        edit(chat.id) { it.copy(isArchived = archived, isPinned = false) }
        arrange()
        act(onFailure) { it.setArchived(chat.id, archived) }
    }

    fun setMuted(chat: ChatSummary, muted: Boolean, onFailure: (String) -> Unit) {
        // No arrange(): muting changes what a row says, not where it sits.
        edit(chat.id) { it.copy(isMuted = muted) }
        act(onFailure) { it.setMuted(chat.id, muted) }
    }

    fun markRead(chat: ChatSummary, onFailure: (String) -> Unit) {
        edit(chat.id) { it.copy(unreadCount = 0) }
        act(onFailure) { it.markRead(chat.id) }
    }

    /** Re-sorts the rows in hand, for a change that moves one of them rather than renaming it. */
    private fun arrange() {
        val current = chats ?: return
        val sorted = arrangeChats(current)
        if (sorted == current) return
        chats = sorted
        publish()
    }

    private fun act(
        onFailure: (String) -> Unit,
        request: suspend (ChatRepository) -> TdlResult<*>,
    ) {
        viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            // Two ways for this to fail and both have to be caught. TDLib reports a refused
            // request as a Failure value rather than by throwing, so a runCatching on its own
            // would call every rejection a success and leave the optimistic row standing.
            val outcome = runCatching { request(ChatRepository(session.client)) }
            if (!session.isCurrent()) return@launch
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) throw error
            val message = when {
                error != null -> Failures.humanise(error)
                else -> (outcome.getOrNull() as? TdlResult.Failure)?.let {
                    Failures.humanise(it.message)
                }
            } ?: return@launch

            onFailure(message)
            // Whatever was drawn on trust is now wrong, so ask TDLib what is actually true.
            load()
        }
    }

    /** Removes one account's data immediately when authorization is lost. */
    fun reset() {
        loadJob?.cancel()
        moreJob?.cancel()
        foldersJob?.cancel()
        membership = null
        account = null
        chats = null
        syncedAt = 0L
        limit = ChatRepository.CHAT_LIMIT
        _paging.value = ChatPaging()
        _state.value = loading()
        // The cold-start snapshots go with it: they exist so the next launch opens on the last
        // sync, and after a sign-out "the last sync" is somebody else's name, face and chats.
        settings?.let { store ->
            Background.scope.launch { runCatching { store.clearColdStartSnapshots() } }
        }
        // And Home's copy of its rows, for the same reason.
        Background.scope.launch { runCatching { com.tmplayer.data.HomeCache.clear() } }
    }

    private companion object {
        /**
         * Long enough that walking into a chat and back never re-syncs, short enough that a list
         * left open while something else was being watched catches up on the way back.
         */
        const val STALE_AFTER_MS = 2 * 60 * 1000L

        /**
         * How long a burst of position updates is left to finish before the order is re-read.
         * Telegram sends one per message, and a chat that is being typed in sends a run of them.
         */
        const val REORDER_SETTLE_MS = 400L

        val LOADING_CHATS: String get() = L.browseLoadingChats
    }

    private fun publish() {
        val everything = chats ?: return
        // Kept whole, before the kids lock and the hidden chats, which are applied below on each read.
        memory?.put(QueryKey.ChatList(), everything)
        val allowed = ChatGate.allowed.value
        val loaded = com.tmplayer.data.HiddenChats.filter(KidsLock.chats(everything, allowed)) { it.id }
        _state.value = when {
            loaded.isNotEmpty() -> UiState.Content(BrowseData(loaded, account))
            // Said plainly, so a child's empty screen is not mistaken for a broken account.
            allowed != null && everything.isNotEmpty() -> UiState.Empty(L.lockKidsEmpty, icon = TmIcons.Lock)
            // A first sign in on a large account can meet this while Telegram is still sending
            // the list, so the way on is to ask again, not to go and do something in Telegram.
            else -> UiState.Empty(L.browseNoChats, StateAction.Refresh, icon = TmIcons.ChatsMany)
        }
    }
}

/**
 * Where the chat list's paging stands ([ChatListViewModel.paging]).
 *
 * @param more true when the main list came back full, so Telegram may hold older chats.
 * @param loading true while the next chats are read: the list's foot shows placeholder rows.
 * @param failure why the last read failed; the foot offers Try again.
 */
data class ChatPaging(val more: Boolean = false, val loading: Boolean = false, val failure: String? = null)

/**
 * Shown under the first chat list on a device, which is the slow one: every launch after it opens
 * on the list saved from the last.
 */
/**
 * The walk back through a chat stops after a few empty pages rather than holding the screen for
 * thousands of files the size limits all reject. Saying so is the point: the chat has not been
 * read to the end, so "no videos in this chat" would be a guess, and Keep looking carries on from
 * where the walk stopped.
 */
val STILL_MORE_TO_SEARCH: String get() = L.browseStillMoreToSearch

val FIRST_LOAD_TIP: String get() = L.browseFirstLoadTip

/**
 * "No videos here between 100 MB and 2 GB.", with the open ends said in English rather than as
 * the slider's "No minimum" and "No limit".
 */
fun noVideosWithin(minBytes: Long, maxBytes: Long): String {
    val hasMin = minBytes > SizeFilter.FLOOR
    val hasMax = maxBytes < SizeFilter.CEILING
    return when {
        hasMin && hasMax -> L.browseNoVideosBetween(L.messages.formatter.sizeLimit(minBytes), L.messages.formatter.sizeLimit(maxBytes))
        hasMin -> L.browseNoVideosFrom(L.messages.formatter.sizeLimit(minBytes))
        hasMax -> L.browseNoVideosUpTo(L.messages.formatter.sizeLimit(maxBytes))
        else -> L.browseNoPlayableVideos
    }
}

/**
 * The topic chips of a group with Telegram's topics turned on: the topics worth offering, and the
 * one being shown, 0 for all of them. Null on [MediaListViewModel.topics] for every other chat,
 * which is how the screens know to draw no row at all.
 */
@androidx.compose.runtime.Immutable
data class TopicBar(val topics: List<ForumTopicSummary>, val selected: Int = 0) {
    /** The name of [id], for a tile in the all-topics listing, or null when it is not offered. */
    fun nameOf(id: Int): String? = if (id == 0) null else topics.firstOrNull { it.id == id }?.name
}

data class MediaListState(
    val items: List<MediaItem> = emptyList(),
    val sponsored: SponsoredBatch? = null,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /**
     * The chat's compressed files met so far ([com.tmplayer.data.Archives]), newest first: drawn
     * at the listing's foot as files that cannot be played, each with its reason. A chat with
     * these and no videos is a listing of them alone, not "No playable videos".
     */
    val archives: List<com.tmplayer.data.ArchiveFile> = emptyList(),
    /** The last page asked for failed: the foot says so with Try again ([MediaListViewModel.retryMore]). */
    val moreFailed: Boolean = false,
)

/**
 * @param downloadedIds the ids ([MediaItem.id]) of the videos whose file is in the Downloads
 *   folder, asked as each page lands so their tiles say Downloaded rather than Cached. Null where
 *   the platform has no such index yet, and then TDLib's answer is the only one.
 */
class MediaListViewModel(
    private val chatId: Long,
    private val minSizeBytes: Long,
    private val maxSizeBytes: Long,
    /**
     * Told about a search once it has found something and the viewer has stopped typing, so it can
     * be offered again as a recent search. See [com.tmplayer.data.RecentSearches].
     */
    private val onSearched: (suspend (String) -> Unit)? = null,
    private val downloadedIds: (suspend () -> Set<String>)? = null,
    /**
     * Reads and writes the topic last picked in this chat, so a group opens on the section the
     * viewer was in rather than back on all of them. Null where nothing is remembered.
     */
    private val topicMemory: TopicMemory? = null,
) : ViewModel() {

    /** Where [MediaListViewModel] keeps the topic picked in each group. */
    interface TopicMemory {
        suspend fun chosen(chatId: Long): Int
        suspend fun remember(chatId: Long, topicId: Int)
    }

    /** The forum topic being listed, or 0 for the whole chat. */
    private var topicId = 0

    private val _topics = MutableStateFlow<TopicBar?>(null)

    /** Null until the chat turns out to have topics, and for good when it has none. */
    val topics: StateFlow<TopicBar?> = _topics.asStateFlow()

    private var cursors = MediaCursors()
    private var pageJob: Job? = null
    private var availabilityJob: Job? = null
    private var sponsoredJob: Job? = null
    private var recordJob: Job? = null

    /** A search typed and waiting for the typing to rest: see [type]. */
    private var typeJob: Job? = null

    /** Set by [load] when it asked Telegram rather than drawing a kept listing. */
    private var askedTelegram = false

    private val _searching = MutableStateFlow(false)

    /**
     * A search of this chat is on its way while the listing from before stays up, for a small
     * spinner in the search field. Not set while the typing settles ([type]), nor for a listing
     * drawn from what was kept, so it never spins without a question asked.
     */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** What the viewer typed. Kept whole, because it is what results are ranked against. */
    private var query = ""

    /** Videos [keep] has turned away since the listing last started over. */
    private var hiddenBySize: Int
        get() = _sizeHidden.value
        set(value) {
            _sizeHidden.value = value
        }

    private val _sizeHidden = MutableStateFlow(0)

    /**
     * How many videos the size limits have kept out of the pages read so far, the count behind
     * [hiddenNotice] and the empty state. Always 0 during a search, which ignores the limits.
     */
    val sizeHidden: StateFlow<Int> = _sizeHidden.asStateFlow()

    /** Set once [hiddenNotice] has had something to say for this opening of the chat. */
    private var toldHidden = false

    private val _hiddenNotice = MutableStateFlow(0)

    /**
     * How many videos the size limits hid, for a one-off message that says so and points to
     * Settings, where the limits are changed. Raised once per opening of the chat, when a listing
     * without a search first settles with videos hidden, and never again as more pages add to the
     * count. Kept here rather than in the screen, so turning the phone does not say it twice; the
     * screen calls [hiddenNoticeShown] once it has. 0 means nothing to say.
     */
    val hiddenNotice: StateFlow<Int> = _hiddenNotice.asStateFlow()

    /** The screen has shown [hiddenNotice]. */
    fun hiddenNoticeShown() {
        _hiddenNotice.value = 0
    }

    /**
     * Raises [hiddenNotice] the first time a listing of the whole chat settles with videos left out
     * by the size limits. An empty listing already says so in its own words, so it is not told
     * twice; a search ignores the limits.
     */
    private fun watchHidden() {
        viewModelScope.launch {
            _state.collect { state ->
                if (toldHidden || query.isNotBlank() || state !is UiState.Content) return@collect
                val hidden = hiddenBySize
                if (hidden <= 0) return@collect
                toldHidden = true
                _hiddenNotice.value = hidden
            }
        }
    }

    private val _selfDestructing = MutableStateFlow(0)

    /**
     * How many self-destructing videos the pages read so far have left out
     * ([com.tmplayer.data.ContentProtection]): they can only be opened in Telegram, so the listing
     * says so in a quiet line rather than leave them missing with no word.
     */
    val selfDestructing: StateFlow<Int> = _selfDestructing.asStateFlow()

    /** The archives the pages read so far have met, since the listing last started over. */
    private var archives = emptyList<com.tmplayer.data.ArchiveFile>()

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

    private val _state = MutableStateFlow<UiState<MediaListState>>(UiState.Loading(L.browseFindingVideos))
    val state: StateFlow<UiState<MediaListState>> = _state.asStateFlow()

    /** The chat held open in TDLib for as long as this listing lives; see [Td.watchChat]. */
    private var watching: AutoCloseable? = null

    /** The answers kept from the last visit ([PageQueries]): this chat's pages, topics and extras. */
    private val cache = PageQueries.cache

    /** What this listing is kept under: the chat, the topic and the search. */
    private fun gridKey() = QueryKey.ChatMedia(chatId, topicId, query)

    init {
        val memory = topicMemory
        // Last visit's topics first, so the row of chips is drawn with the first frame rather than
        // arriving under the viewer's eyes.
        val known = PageQueries.lastTopic(chatId)
        if (memory == null || known != null) {
            // A topic picked earlier in this run is already in memory: the kept listing is drawn
            // at once, in this very frame.
            topicId = known ?: 0
            load(preserveContent = true, force = false)
        } else {
            // The remembered topic is read before the first page is asked for, so a group the viewer
            // left on "Movies" never flashes its whole listing first. It is one local read.
            viewModelScope.launch {
                topicId = runCatching { memory.chosen(chatId) }.getOrDefault(0)
                PageQueries.noteTopic(chatId, topicId)
                load(preserveContent = true, force = false)
            }
        }
        loadTopics()
        followChanges()
        watchHidden()
        // Nothing but the videos is listed over a chat's grid any more (user, 2026-10-22: TMPlayer
        // plays videos): its audio, its round video messages and its pinned post (CP81) are not
        // fetched, so no request is spent on them and every platform's grid starts with the videos.
    }

    /**
     * Where the grid stood when this listing was last left, for the screen to start there: the
     * first item shown and how far past it. 0 and 0 for a listing not seen before.
     */
    val restoredScroll: Pair<Int, Int>
        get() = cache.data<ChatGrid>(gridKey())?.let { it.scrollIndex to it.scrollOffset } ?: (0 to 0)

    /** Remembers where the grid stands, as it is left, for [restoredScroll] on the next visit. */
    fun rememberScroll(index: Int, offset: Int) {
        cache.update<ChatGrid>(gridKey()) { grid ->
            if (grid == null || (grid.scrollIndex == index && grid.scrollOffset == offset)) grid
            else grid.copy(scrollIndex = index, scrollOffset = offset)
        }
    }

    /**
     * Finds out whether this chat has topics and which of them hold videos.
     *
     * Runs beside the first page rather than ahead of it: counting each topic's videos is a pair of
     * server calls per topic, and the listing should not wait on a row of chips. A remembered topic
     * that has since been deleted, or a group that has turned topics off, falls back to all.
     */
    private fun loadTopics() {
        // The chips of the last visit, drawn now; asked again only once they have gone stale.
        val kept = cache.peek<List<ForumTopicSummary>>(QueryKey.Topics(chatId), PageQueries.CHAT_STALE_MS)
        if (kept != null) {
            _topics.value = if (kept.data.isEmpty()) null else TopicBar(kept.data, topicId)
            if (!kept.stale) return
        }
        viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            // The tabs first, straight from the topic list, so the row is up at once; then again
            // once each topic's videos are counted, without the ones that turned out to have none.
            // Not over chips kept from the last visit, which are counted already.
            val listed = runCatching { repository.forumTopics(chatId, countVideos = false) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return@launch
            if (!session.isCurrent()) return@launch
            if (listed.isEmpty()) {
                cache.put(QueryKey.Topics(chatId), emptyList<ForumTopicSummary>())
                _topics.value = null
                return@launch
            }
            if (kept == null) _topics.value = TopicBar(listed, topicId)
            val found = runCatching { repository.forumTopics(chatId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return@launch
            if (!session.isCurrent()) return@launch
            cache.put(QueryKey.Topics(chatId), found.filter { it.hasVideos })
            val offered = found.filter { it.hasVideos || it.id == topicId }
            if (topicId != 0 && offered.none { it.id == topicId }) {
                _topics.value = if (offered.isEmpty()) null else TopicBar(offered, 0)
                selectTopic(0)
                return@launch
            }
            _topics.value = if (offered.isEmpty()) null else TopicBar(offered, _topics.value?.selected ?: topicId)
        }
    }

    /** A topic chip: lists [id] alone, or the whole chat for 0, and remembers the choice. */
    fun selectTopic(id: Int) {
        _topics.value?.let { bar -> if (bar.selected != id) _topics.value = bar.copy(selected = id) }
        if (id == topicId) return
        topicId = id
        PageQueries.noteTopic(chatId, id)
        topicMemory?.let { memory -> viewModelScope.launch { runCatching { memory.remember(chatId, id) } } }
        recordJob?.cancel()
        load(preserveContent = false)
    }

    override fun onCleared() {
        watching?.close()
        watching = null
    }

    /**
     * Keeps the listing in step with the chat while it is open: a video posted in Telegram shows at
     * the top, an edited one is read again, a deleted one goes. Applied to what is on screen rather
     * than by loading again, so the pages already scrolled through, the scroll position and the
     * remote's focus all stay where they are.
     */
    private fun followChanges() {
        viewModelScope.launch {
            Td.awaitAuthorizedSession()
            val handle = Td.watchChat(chatId)
            if (watching == null) watching = handle else handle.close()
        }
        viewModelScope.launch {
            Td.videoChanges
                .filter { it.chatId == chatId }
                .batched(LIVE_WINDOW_MS)
                .collect { batches -> batches.forEach { applyChanges(it) } }
        }
    }

    private suspend fun applyChanges(batch: VideoBatch) {
        val session = runCatching { Td.awaitAuthorizedSession() }.getOrNull() ?: return
        val repository = ChatRepository(session.client)
        // A search lists what matched, ranked: a new post is not part of it, a deleted one still goes.
        val searching = query.isNotBlank()
        // A topic's listing takes only the posts made in that topic.
        val added = if (searching) emptyList() else marked(keepLive(batch.added.filter { topicId == 0 || it.topicId == topicId }))
        val listed = (_state.value as? UiState.Content)?.value?.items.orEmpty().mapTo(HashSet()) { it.messageId }
        val edits = batch.edited.filter { !searching || it in listed }.associateWith { id ->
            runCatching { repository.mediaItem(chatId, id) }.getOrNull()?.let { marked(keepLive(listOf(it))).firstOrNull() }
        }
        if (!session.isCurrent()) return
        when (val current = _state.value) {
            is UiState.Content -> {
                val items = VideoChanges.merge(current.value.items, batch, added, edits)
                if (items !== current.value.items) {
                    _state.value = UiState.Content(current.value.copy(items = items))
                }
            }
            // A chat that had nothing to show has its first video.
            is UiState.Empty -> if (added.isNotEmpty()) {
                _state.value = UiState.Content(
                    MediaListState(items = added, sponsored = sponsored, endReached = true),
                )
            }
            else -> Unit
        }
    }

    /** The size limits for a live post, as [keep] applies them to a page, without the search ranking. */
    private fun keepLive(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return items
        val split = SizeFilter.split(items, minSizeBytes, maxSizeBytes) { it.sizeBytes }
        hiddenBySize += split.hidden
        return split.kept
    }

    /**
     * The search field changed: [search] once the typing has rested for [SearchSettle.MS], the
     * last text only. Cleared, it lists the chat again at once. One letter is not asked for while
     * typing (it matches half the chat); the search key still asks for it.
     */
    fun type(text: String) {
        typeJob?.cancel()
        val clean = text.trim()
        if (clean.isEmpty()) {
            search("")
            return
        }
        if (clean == query || !com.tmplayer.data.GlobalSearch.searchable(clean)) return
        typeJob = viewModelScope.launch {
            delay(com.tmplayer.data.SearchSettle.MS)
            search(clean)
        }
    }

    /**
     * Re-runs the listing against [text] at once (the search key, a recent search, voice); blank
     * means "everything in this chat". The same words as now are not asked again. What is listed
     * stays up until the answer replaces it, so the grid never flashes to a skeleton between
     * searches.
     */
    fun search(text: String) {
        typeJob?.cancel()
        val clean = text.trim()
        if (clean == query) return
        query = clean
        recordJob?.cancel()
        _searching.value = false
        askedTelegram = false
        // A failure keeps the old listing only when it is the same listing: a search's own
        // failure says so, rather than leaving the last words' results under the new ones.
        load(preserveContent = true, keepOnFailure = false)
        val job = pageJob
        if (askedTelegram && job != null && job.isActive && _state.value is UiState.Content) {
            _searching.value = true
            job.invokeOnCompletion { if (pageJob === job) _searching.value = false }
        }
    }

    /** The button under an empty listing. */
    fun act(action: StateAction) = when (action) {
        StateAction.KeepLooking -> keepLooking()
        StateAction.Refresh -> load()
        // Leaves the chat for the search across all of them: the screen does that, with [query].
        StateAction.SearchAllChats -> SearchRequest.ask(query)
    }

    /** What the viewer searched this chat for, for a screen offering the same words everywhere. */
    val searchText: String get() = query

    /**
     * Carries on walking back through the chat from where the last walk gave up, rather than
     * starting again at the newest message and reading the same empty pages a second time.
     */
    private fun keepLooking() {
        if (_state.value !is UiState.Empty || pageJob?.isActive == true) return
        _state.value = UiState.Loading(if (query.isNotBlank()) L.browseSearching else L.browseFindingVideos)
        pageJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            pageMore(session, ChatRepository(session.client), emptyList(), endReached = false)
        }
    }

    /**
     * Remembers [searched] as a recent search, unless the viewer carries on typing first: on a
     * phone every letter is a search of its own, and only the one they stopped at is worth a chip.
     */
    private fun recordSearch(searched: String) {
        val record = onSearched ?: return
        if (searched.isBlank()) return
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            delay(RECORD_AFTER_MS)
            if (searched == query) runCatching { record(searched) }
        }
    }

    /** Refresh and Try again: Telegram asked at once, past what is kept, the listing staying up meanwhile. */
    fun load() = load(preserveContent = true, force = true)

    /**
     * Lists the chat from the first page. Unless [force]d, what the [cache] kept of this listing
     * is drawn at once, through the size limits as they are now, and asked again behind it once
     * it is older than [PageQueries.GRID_STALE_MS] (stale while revalidate): no skeleton, and the
     * pages scrolled through last time are all there.
     */
    private fun load(preserveContent: Boolean, force: Boolean = false, keepOnFailure: Boolean = true) {
        _topics.value?.let { bar -> if (bar.selected != topicId) _topics.value = bar.copy(selected = topicId) }
        val key = gridKey()
        val kept = if (force) null else cache.peek<ChatGrid>(key, PageQueries.GRID_STALE_MS)
        if (kept != null) {
            pageJob?.cancel()
            loadSponsored()
            showKept(key, kept.data, revalidate = kept.stale)
            return
        }
        askedTelegram = true
        val previous = if (preserveContent) (_state.value as? UiState.Content)?.value else null
        val previousCursors = cursors
        cursors = MediaCursors()
        hiddenBySize = 0
        _selfDestructing.value = 0
        archives = emptyList()
        val searching = query.isNotBlank()
        if (previous == null) {
            _state.value = UiState.Loading(if (searching) L.browseSearching else L.browseFindingVideos)
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
                    _selfDestructing.value += rawPage.hiddenSelfDestructing
                    val seen = rawPage.copy(items = marked(rawPage.items))
                    // Kept before the size limits, which are applied again on every read.
                    cache.put(key, ChatGrid.of(seen, serverQuery))
                    val page = seen.copy(items = keep(seen.items))
                    cursors = page.cursors
                    archives = com.tmplayer.data.Archives.merge(archives, page.archives)
                    // A first page can come back empty while older pages still hold videos: a chat
                    // whose newest forty files are all outside the size limits, most often.
                    //
                    // Continued by calling pageMore directly, never loadMore(): loadMore() refuses
                    // to start while pageJob is active, and pageJob is this very coroutine, so it
                    // would be a guaranteed no-op.
                    //
                    // Nothing is published before the walk, so the skeleton stays up on a first
                    // load and the old listing stays up on a refresh. An empty Content here would
                    // be a blank body for the whole walk, with nothing to point the remote at, and
                    // that reads as a chat with no videos rather than one still being searched.
                    if (page.items.isEmpty() && !page.endReached) {
                        pageMore(session, repository, emptyList(), page.endReached, key)
                        return@onSuccess
                    }
                    if (page.items.isNotEmpty()) recordSearch(query)
                    _state.value = if (page.items.isEmpty() && sponsored == null && archives.isEmpty()) {
                        nothingFound()
                    } else {
                        UiState.Content(
                            MediaListState(
                                items = page.items,
                                sponsored = sponsored,
                                endReached = page.endReached,
                                archives = archives,
                            ),
                        )
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (session.isCurrent()) {
                        if (previous != null && keepOnFailure) {
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
     * Draws [grid], the listing kept under [key], through the size limits and the search ranking
     * as they are now, and when it has gone stale asks its first page again behind it, laying the
     * answer over the pages kept ([ChatGrid.refreshed]). A kept listing the size limits empty
     * entirely walks on from its cursors, as a first page would.
     */
    private fun showKept(key: QueryKey.ChatMedia, grid: ChatGrid, revalidate: Boolean) {
        val walking = show(grid)
        if (walking) {
            pageJob = viewModelScope.launch {
                val session = Td.awaitAuthorizedSession()
                pageMore(session, ChatRepository(session.client), emptyList(), endReached = false, key = key)
            }
            return
        }
        if (!revalidate) {
            refreshLocalAvailability()
            return
        }
        pageJob = viewModelScope.launch {
            val fresh = runCatching {
                cache.fetch(key) {
                    if (key.query.isEmpty()) {
                        PageQueries.refreshGrid(key)
                    } else {
                        val session = Td.awaitAuthorizedSession()
                        serverQuery = query
                        val page = firstPage(ChatRepository(session.client)).let { it.copy(items = marked(it.items)) }
                        if (!session.isCurrent()) throw CancellationException("signed out")
                        ChatGrid.of(page, serverQuery)
                    }
                }
            }.onFailure { if (it is CancellationException && key == gridKey()) throw it }
                // A refresh that failed keeps the kept listing up: it is still the best answer.
                .getOrNull() ?: return@launch
            if (key != gridKey()) return@launch
            val seen = fresh.copy(items = marked(fresh.items))
            if (seen.items != fresh.items) cache.update<ChatGrid>(key) { if (it === fresh) seen else it }
            if (show(seen)) {
                val session = Td.awaitAuthorizedSession()
                pageMore(session, ChatRepository(session.client), emptyList(), endReached = false, key = key)
                return@launch
            }
            refreshLocalAvailability()
        }
    }

    /**
     * Publishes [grid] as the listing. True when the size limits left nothing of it while the
     * chat goes on, so the caller walks on (the skeleton stays up meanwhile, as on a first load).
     */
    private fun show(grid: ChatGrid): Boolean {
        cursors = grid.cursors
        serverQuery = grid.serverQuery.ifEmpty { query }
        archives = grid.archives
        _selfDestructing.value = grid.selfDestructing
        hiddenBySize = 0
        val items = keep(grid.items)
        if (items.isEmpty() && !grid.endReached) {
            _state.value = UiState.Loading(if (query.isNotBlank()) L.browseSearching else L.browseFindingVideos)
            return true
        }
        _state.value = if (items.isEmpty() && sponsored == null && archives.isEmpty()) {
            nothingFound(grid.endReached)
        } else {
            UiState.Content(
                MediaListState(items = items, sponsored = sponsored, endReached = grid.endReached, archives = archives),
            )
        }
        return false
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
        val whole = repository.mediaPage(chatId, MediaCursors(), query, topicId = topicId)
        if (whole.items.isNotEmpty() || query.isBlank()) return whole

        val words = Fuzzy.tokens(query)
            .filter { it.length >= MIN_FALLBACK_WORD }
            .sortedByDescending { it.length }
            .take(MAX_FALLBACK_WORDS)
        if (words.size < 2 && Fuzzy.tokens(query).size < 2) return whole

        for (word in words) {
            if (word == query) continue
            val page = repository.mediaPage(chatId, MediaCursors(), word, topicId = topicId)
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
        // A search names the video the viewer is after, so the size limits stand aside for it: an
        // episode a little smaller than its neighbours is exactly the one a viewer goes looking for.
        if (query.isNotBlank()) return Fuzzy.rank(items, query) { it.searchText }
        val split = SizeFilter.split(items, minSizeBytes, maxSizeBytes) { it.sizeBytes }
        hiddenBySize += split.hidden
        return split.kept
    }

    /** Downloaded videos told apart from cached ones, when the platform keeps an index. */
    private suspend fun marked(items: List<MediaItem>): List<MediaItem> {
        val lookup = downloadedIds ?: return items
        val downloaded = runCatching { lookup() }.getOrDefault(emptySet())
        if (downloaded.isEmpty()) return items
        return items.map { if (it.id in downloaded) it.withLocality(MediaItem.Locality.Downloaded) else it }
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

    /**
     * Why this chat is showing nothing, in the terms the viewer can do something about.
     *
     * [reachedEnd] false means the walk back through the chat gave up before its start, so the
     * honest answer is "nothing yet" with a way to keep going. When the size limits are what
     * emptied it, it says so and points to Settings, where they are changed.
     */
    /** [emptyState]: with nothing listed over the videos, an empty chat always says why. */
    private fun nothingFound(reachedEnd: Boolean = true): UiState<MediaListState> = emptyState(reachedEnd)

    private fun emptyState(reachedEnd: Boolean = true): UiState.Empty {
        // Say which knob is hiding things, rather than claiming the chat is empty when it is the
        // filter doing the work, and where that knob is: the size limits live in Settings only.
        val hidden = if (query.isBlank() && hiddenBySize > 0) {
            "${L.browseHiddenBySize(hiddenBySize)} ${L.browseSizeLimitsInSettings}"
        } else {
            null
        }
        // Inside a topic, the empty answer names it: "nothing here" in a group of ten sections
        // reads as the whole group being empty.
        val topic = _topics.value?.nameOf(topicId)
        return when {
            // The next step is the same words across every chat, where the video may well be.
            query.isNotBlank() && topic != null -> UiState.Empty(L.gridTopicNoMatch(topic, query), StateAction.SearchAllChats, icon = TmIcons.SearchRemove)
            query.isNotBlank() -> UiState.Empty(L.browseNoMatch(query), StateAction.SearchAllChats, icon = TmIcons.SearchRemove)
            !reachedEnd && hidden != null -> UiState.Empty("$STILL_MORE_TO_SEARCH\n\n$hidden", StateAction.KeepLooking, icon = TmIcons.FilterHorizontal)
            !reachedEnd -> UiState.Empty(STILL_MORE_TO_SEARCH, StateAction.KeepLooking)
            hidden != null -> UiState.Empty("${noVideosWithin(minSizeBytes, maxSizeBytes)}\n\n$hidden", icon = TmIcons.FilterHorizontal)
            topic != null -> UiState.Empty(L.gridTopicEmpty(topic))
            else -> UiState.Empty(L.browseNoPlayableVideos)
        }
    }

    /** Revalidates badges after playback or a cache clear without rebuilding the whole grid. */
    fun refreshLocalAvailability() {
        val current = (_state.value as? UiState.Content)?.value ?: return
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            var changed = false
            val downloaded = downloadedIds?.let { runCatching { it() }.getOrNull() }.orEmpty()
            val updated = current.items.map { item ->
                val locality = when {
                    item.id in downloaded -> MediaItem.Locality.Downloaded
                    Td.localFileAvailability(item.fileId) == LocalFileAvailability.Complete ->
                        MediaItem.Locality.Cached
                    else -> MediaItem.Locality.Remote
                }
                if (locality == item.locality) {
                    item
                } else {
                    changed = true
                    item.withLocality(locality)
                }
            }
            // No revision counter needed: MediaItem compares by value, so a list whose badges
            // moved is genuinely a different list and the flow publishes it.
            if (session.isCurrent() && changed) {
                _state.value = UiState.Content(current.copy(items = updated))
            }
        }
    }

    /**
     * Called when focus nears the end of the listing.
     *
     * Scrolling never stops at a page boundary: pages keep being pulled until the listing actually
     * grew, or the chat ran out. Otherwise a page whose videos are all outside the size limits
     * leaves the item count unchanged, and the screen has no reason left to ask for more.
     */
    fun loadMore() {
        val current = _state.value as? UiState.Content ?: return
        if (current.value.loadingMore || current.value.endReached) return
        if (pageJob?.isActive == true) return

        _state.value = UiState.Content(current.value.copy(loadingMore = true, moreFailed = false))
        pageJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            pageMore(session, repository, current.value.items, endReached = false)
        }
    }

    /**
     * Try again on a page that failed, or that has been loading for too long: the stuck request
     * is dropped and the same page asked for afresh, from the cursors it would have used.
     */
    fun retryMore() {
        val current = _state.value as? UiState.Content ?: return
        if (current.value.endReached) return
        pageJob?.cancel()
        _state.value = UiState.Content(current.value.copy(loadingMore = false, moreFailed = false))
        loadMore()
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
        /** The listing the pages are kept under, appended to as they arrive. */
        key: QueryKey.ChatMedia = gridKey(),
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
            runCatching { repository.mediaPage(chatId, cursors, serverQuery, topicId = topicId) }
                .onSuccess { rawPage ->
                    _selfDestructing.value += rawPage.hiddenSelfDestructing
                    val seen = rawPage.copy(items = marked(rawPage.items))
                    // Appended to the kept listing only while it is still there: a page added to
                    // nothing would be kept as a listing missing every page before it.
                    if (session.isCurrent()) cache.update<ChatGrid>(key) { it?.plus(seen) }
                    val page = seen.copy(items = keep(seen.items))
                    cursors = page.cursors
                    archives = com.tmplayer.data.Archives.merge(archives, page.archives)
                    items = (items + page.items).distinctBy { it.messageId }
                    reachedEnd = page.endReached
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    failed = true
                }
        }

        if (!session.isCurrent()) return
        if (startingFrom.isEmpty() && items.isNotEmpty()) recordSearch(query)
        // An empty listing has to say something, whether the chat ran out or the walk gave up
        // after MAX_EMPTY_PAGES. A Content with no items is a blank screen, and a blank screen
        // asks the viewer to guess which of the two happened.
        _state.value = if (items.isEmpty() && sponsored == null && archives.isEmpty()) {
            nothingFound(reachedEnd)
        } else {
            UiState.Content(
                MediaListState(
                    items = items,
                    sponsored = sponsored,
                    archives = archives,
                    loadingMore = false,
                    // Nothing new and the page failed: said, with Try again, not dropped.
                    moreFailed = failed && items.size == startingFrom.size,
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

        /** How long a search has to stand before it counts as one the viewer meant. */
        const val RECORD_AFTER_MS = 1_500L

        /** Live posts are gathered this long, so a burst of episodes lands as one redraw. */
        const val LIVE_WINDOW_MS = 2_000L

        /** Shorter than this and a word is "the" or "s02": it narrows nothing. */
        const val MIN_FALLBACK_WORD = 3

        /** Three retries is already three round trips; past that the wait is the worse answer. */
        const val MAX_FALLBACK_WORDS = 3
    }
}

/** The topic picked in each group, kept in the app's settings. */
fun com.tmplayer.data.SettingsStore.topicMemory(): MediaListViewModel.TopicMemory {
    val store = this
    return object : MediaListViewModel.TopicMemory {
        override suspend fun chosen(chatId: Long): Int = store.chosenTopic(chatId)
        override suspend fun remember(chatId: Long, topicId: Int) = store.rememberTopic(chatId, topicId)
    }
}
