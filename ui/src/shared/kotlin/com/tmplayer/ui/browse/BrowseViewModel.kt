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
import com.tmplayer.data.ChatSync
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.WorkStatusWords
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.icons.TmIcons
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListMain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/**
 * The chat list plus who is signed in; both are needed before the browser can draw.
 *
 * @param chats the chats the viewer added (or every chat under "All chats"), after the kids lock:
 *   what Chats lists.
 * @param withStarred [chats] and the starred chats the viewer did not add, in the same order, after
 *   the kids lock (HX86): a star is the viewer's own act, so Favourites and Home's starred rows
 *   show it whether or not the chat is ticked. Chats never lists the extras.
 */
data class BrowseData(
    val chats: List<ChatSummary>,
    val account: Account? = null,
    val withStarred: List<ChatSummary> = chats,
) {
    /** The chats [section] draws from: Favourites also takes the starred chats the viewer did not add. */
    fun chatsFor(section: BrowseSection): List<ChatSummary> =
        if (section == BrowseSection.of(BrowseTab.Favorites)) withStarred else chats
}

/** Whether a folder's members are known: an empty folder may only say "nothing here" when [Loaded]. */
enum class FolderRead { Loading, NotLoaded, Loaded }

/**
 * How reading the folders' members went (HX29): [pending] while they are on their way, [failed]
 * the folders that could not be read (HX32), which are not empty folders.
 */
data class FolderStatus(val pending: Boolean = false, val failed: Set<Int> = emptySet()) {
    /** How reading [folderId]'s members went. */
    fun read(folderId: Int): FolderRead = when {
        folderId in failed -> FolderRead.NotLoaded
        pending -> FolderRead.Loading
        else -> FolderRead.Loaded
    }
}

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
) : ViewModel(com.tmplayer.platform.JobFailures.viewModelScope("ChatList")) {

    private val _state = MutableStateFlow<UiState<BrowseData>>(UiState.Loading(LOADING_CHATS))
    val state: StateFlow<UiState<BrowseData>> = _state.asStateFlow()

    // Above the init block, which paints the remembered list through publish() before the
    // properties declared under it exist (the desktop's immediate main dispatcher crashed on that once).
    private val _folderStatus = MutableStateFlow(FolderStatus())

    /** When the folders' members are read (HX29); declared here for the same reason. */
    private val folderReads = FolderReads()

    /** How reading the folders' members is going, for the picker's folder filters (HX29). */
    val folderStatus: StateFlow<FolderStatus> = _folderStatus.asStateFlow()

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
        // The kids lock and the added chats, met in the middle: a chat added or removed in the
        // picker leaves or rejoins the list at once, with no re-sync (HX27).
        viewModelScope.launch {
            ChatGate.browsing.collect { publish() }
        }
        // The stars, because a starred chat the viewer did not add still belongs on Favourites
        // (HX86): a star added or taken off changes who is in [BrowseData.withStarred].
        val store = settings ?: return
        viewModelScope.launch {
            runCatching {
                store.favorites.collect { stars ->
                    if (stars == starred) return@collect
                    starred = stars
                    publish()
                }
            }
        }
    }

    /** The starred chats, as the store holds them: [publish] lets the ones the viewer did not add through to Favourites. */
    private var starred: Set<Long> = emptySet()

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
                ChatGate.awaitBrowsing()
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
            // The gate is read before yesterday's list is drawn, not after: painting every chat
            // for the moment the store takes to answer would show what the lock hides, and flash
            // the chats the viewer never added (HX27).
            ChatGate.awaitBrowsing()
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
                                        val known = edit(update.chatId) {
                                            it.copy(
                                                isPinned = update.position.isPinned,
                                                isArchived = archived,
                                            )
                                        }
                                        // A chat this screen has never shown (HX44): queued for
                                        // fetchNewChats, bounded so a burst of them (a first sign
                                        // in still filling TDLib's database) costs at most
                                        // NEW_CHAT_FETCH_CAP round trips rather than a fan-out.
                                        // Anything this settle window could not take is caught by
                                        // the next one, or by the next full sync.
                                        if (!known && chats != null) {
                                            if (pendingNewChats.size < NEW_CHAT_FETCH_CAP) pendingNewChats += update.chatId
                                        } else {
                                            pendingNewChats -= update.chatId
                                        }
                                    } else {
                                        // Out of the list again before its first fetch: nothing to
                                        // chase, so it never flickers in.
                                        pendingNewChats -= update.chatId
                                    }
                                }
                                .conflate()
                                .collect {
                                    delay(REORDER_SETTLE_MS)
                                    val repository = ChatRepository(client)
                                    fetchNewChats(repository)
                                    reorder(repository)
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
     * By the time this runs, [fetchNewChats] has already caught up on the ids this screen did not
     * know (HX44); what is left unknown here is whatever that could not fit in its cap, and it is
     * skipped rather than fetched again, so one settle window never doubles the round trips. Rows
     * TDLib no longer lists are dropped, which is what leaving a chat looks like from here.
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

    /**
     * A chat whose position update named an id this screen had never shown, waiting for
     * [fetchNewChats] to read it with one `getChat` each (HX44). Bounded at insertion
     * ([NEW_CHAT_FETCH_CAP]), and cleared of an id that left the list again before it was fetched.
     */
    private val pendingNewChats = LinkedHashSet<Long>()

    /**
     * Reads the chats [pendingNewChats] is holding, one `getChat` each, so a newly joined channel
     * or a brand new private chat shows up on its own rather than waiting for the Refresh button
     * this task took away (HX44). This is the one gap `watchChatChanges` used to leave on purpose
     * (a position update fires on every incoming message, and chasing every unknown id behind
     * that would be a `getChat` fan-out); it is safe now because the ids are bounded per settle
     * window by [NEW_CHAT_FETCH_CAP] rather than unbounded.
     */
    private suspend fun fetchNewChats(repository: ChatRepository) {
        if (pendingNewChats.isEmpty()) return
        val ids = pendingNewChats.toList()
        pendingNewChats.clear()
        val current = chats ?: return
        val known = current.mapTo(HashSet()) { it.id }
        val fresh = ids.filterNot { it in known }
        if (fresh.isEmpty()) return
        val fetched = coroutineScope {
            fresh.map { id -> async { runCatching { repository.summary(id) }.getOrNull() } }.awaitAll()
        }.filterNotNull()
        if (fetched.isEmpty()) return
        // Read again rather than reused: the settle delay and the fetch itself both give a sign
        // out, or another collector, time to change what `chats` holds.
        val stillCurrent = chats ?: return
        val stillKnown = stillCurrent.mapTo(HashSet()) { it.id }
        val added = fetched.filterNot { it.id in stillKnown }
        if (added.isEmpty()) return
        chats = foldered(stillCurrent + added)
        publish()
    }

    /** Applies a change to one row, if that row is on screen. Publishes only when it moved. Returns whether the row was found. */
    private fun edit(chatId: Long, change: (ChatSummary) -> ChatSummary): Boolean {
        val current = chats ?: return false
        val at = current.indexOfFirst { it.id == chatId }
        if (at < 0) return false
        val updated = change(current[at])
        if (updated == current[at]) return true
        chats = current.toMutableList().also { it[at] = updated }
        publish()
        return true
    }

    private var loadJob: Job? = null

    private val _refreshing = MutableStateFlow(false)

    /** Whether a load is running, so a refresh button can keep turning until the list is in. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _listStatus = MutableStateFlow<WorkStatus>(WorkStatus.Idle)

    /**
     * What the chat list's sync is doing while chats are on screen, for the status line (WP-93,
     * LS-01): Syncing while [load] runs with a list held ([ChatListStatus.SYNC_BOX_MS] at most),
     * then Updated when the viewer asked ([refreshUnlessRateLimited]) or Idle; Failed with the time
     * of the list kept when the sync failed, until the next success; RateLimited for a flood wait;
     * Partial when the archive or some chats did not load; Offline when no line came in time (the
     * offline banner says it). Idle while nothing is held: the page's own skeleton and error speak.
     */
    val listStatus: StateFlow<WorkStatus> = _listStatus.asStateFlow()

    /** The refresh running now was pressed for: only that one ends in "Up to date". */
    private var asked = false

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

    private val _everything = MutableStateFlow<List<ChatSummary>>(emptyList())

    /**
     * Every chat read, before the kids lock, the added chats and the hidden chats narrow it: what
     * the picker lists (HX27). It is the one screen that must see a chat the viewer has not added,
     * since otherwise there would be no way to add it; every other screen reads [state].
     *
     * TODO(HX44 step 3): this is why [load] still pages the main list and the 4000-deep archive
     * every sync even under [com.tmplayer.data.AddedMode.Picked], instead of asking TDLib for only
     * the added ids: the picker ([AddChatsViewModel]) reads this flow rather than doing its own
     * full-account fetch, so narrowing [load] to the added chats would starve it of everything it
     * has not already been shown. Giving the picker its own [ChatRepository]-backed sync (its own
     * loading state, used only while it is open) would let [load] sync just [AddedChats.ids] under
     * Picked, and leave the deep archive walk to the picker alone, which is the one screen that
     * needs it. Left as a TODO rather than built now: it is a bigger change than this task's
     * (HX44's) one-sync-call swap, and getting the picker's own fetch wrong risks regressing the
     * already-shipped, scale-tested HX27/HX30 picker.
     */
    val everything: StateFlow<List<ChatSummary>> = _everything.asStateFlow()

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
        // A list short for another reason than its length (the archive, a chat, a failed first
        // read): Try again reads it all again, as Refresh does, which waits out a flood wait.
        if (now.partial && now.failure != null) {
            if (loadJob?.isActive == true || refreshUnlessRateLimited() > 0) return
            _paging.value = now.copy(loading = true, failure = null)
            return
        }
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
                val partial = sync.failure == null && sync.partial
                _paging.value = ChatPaging(
                    more = mayHoldMore(chats.orEmpty()),
                    failure = sync.failure?.let { Failures.humanise(it) } ?: L.chatsSomeNotLoaded.takeIf { partial },
                    partial = partial,
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
        if (waiting > 0) {
            // Said on the line too, with its countdown, while the toast says it once (WP-93).
            if (chats != null) WorkStatus.rateLimited(waiting, System.currentTimeMillis())?.let { _listStatus.value = it }
            return waiting
        }
        load(pressed = true)
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
    private var membership: com.tmplayer.data.FolderMembership? = null
    private var foldersJob: Job? = null

    /** True when the folders could not be read at all (no connection, a failed read): the picker offers Try again. */
    private var foldersGaveUp = false

    /**
     * True once the picker has asked for the folders (HX29). Their members are read for the picker
     * alone: on a database TDLib has not filled yet that asks Telegram for every chat of the
     * account, so nothing reads them ahead of it, and a list read later keeps them current only
     * once they have been asked for.
     */
    private fun folderIds(): List<Int> = Td.folders.value.map { it.id }

    /**
     * The picker is open: reads the folders' members unless they are already read, and again when
     * a folder has appeared since (an unread folder is loading, never an empty one).
     */
    fun wantFolders() {
        folderReads.want()
        if (folderReads.readOnWant(folderIds())) retryFolders() else publish()
    }

    /** The picker left: loads stop reading the folders, and a read in flight is cancelled. */
    fun unwantFolders() {
        folderReads.unwant()
        foldersJob?.cancel()
    }

    /** Reads the folders' members again, from the picker's Try again (HX32). */
    fun retryFolders() {
        if (foldersJob?.isActive == true) return
        foldersGaveUp = false
        publish()
        foldersJob = viewModelScope.launch {
            val session = Td.awaitConnectedSessionOrNull()
            if (session == null) {
                foldersGaveUp = true
                publish()
                return@launch
            }
            readFolders(ChatRepository(session.client), session)
        }
    }

    /**
     * Reads which chats are in which folder, behind the list rather than ahead of it: on a
     * database TDLib has not filled yet that reads every chat of the account, and the list waited
     * for it before showing anything (HX8). The rows keep their folders meanwhile.
     */
    private fun fillFolders(repository: ChatRepository, session: com.tmplayer.data.TdSession) {
        if (foldersJob?.isActive == true || Td.folders.value.isEmpty()) return
        foldersJob = viewModelScope.launch { readFolders(repository, session) }
    }

    private suspend fun readFolders(repository: ChatRepository, session: com.tmplayer.data.TdSession) {
        val asked = folderIds()
        val found = try {
            repository.folderMembership()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (!session.isCurrent()) return
        if (found == null) {
            // Not an empty answer: the folders' pages say they could not be read.
            foldersGaveUp = true
            publish()
            return
        }
        foldersGaveUp = false
        membership = found
        folderReads.markRead(asked)
        val current = chats
        if (current != null) {
            val next = com.tmplayer.data.ChatListLoad.withFolders(current, found)
            if (next != current) chats = next
        }
        publish()
        // A folder appeared while this read ran: it is read too, as long as the picker is open.
        if (folderReads.readOnWant(folderIds())) readFolders(repository, session)
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
        // As the profile whose chats these are (HX88): a snapshot never lands in the next profile.
        Background.scope.launch(com.tmplayer.data.AccountOwner(com.tmplayer.data.Accounts.stamp)) { runCatching { store.saveChatSnapshot(chats) } }
    }

    private var syncBox: Job? = null

    /** [listStatus] says Syncing, once a list is held, for [ChatListStatus.SYNC_BOX_MS] at most. */
    private fun syncingNow() {
        if (chats == null || _listStatus.value is WorkStatus.Syncing) return
        _listStatus.value = WorkStatus.Syncing()
        syncBox?.cancel()
        syncBox = viewModelScope.launch {
            kotlinx.coroutines.delay(ChatListStatus.SYNC_BOX_MS)
            if (_listStatus.value is WorkStatus.Syncing) _listStatus.value = WorkStatus.Idle
        }
    }

    /** [listStatus] after the sync ended, the time box with it. */
    private fun syncEnded(status: WorkStatus) {
        syncBox?.cancel()
        syncBox = null
        _listStatus.value = status
        asked = false
    }

    /** When the list on screen was last whole from Telegram, for "Showing what was here at"; null when not this run. */
    private fun keptAt(): Long? = syncedAt.takeIf { it > 0 }

    /** Reads the list again; [pressed] when the viewer asked for it, so its end says "Up to date". */
    fun load(pressed: Boolean = false) {
        loadJob?.cancel()
        if (chats == null) _state.value = loading()
        _refreshing.value = true
        asked = pressed
        syncingNow()
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
                    // The archive's first page only when nothing is held yet: with the archived
                    // rows already in hand its ids are read to the ceiling, as cheap ids, and the
                    // list does not shrink to a page of the archive between two syncs (HX32).
                    val held = chats.orEmpty()
                    repository.cachedChats(
                        limit,
                        known = held.filter { it.isArchived },
                        archiveLimit = if (held.isEmpty()) ChatRepository.ARCHIVE_FIRST else ChatRepository.ARCHIVE_LIMIT,
                    ).chats.takeIf { it.isNotEmpty() }?.let(::foldered) ?: chats
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
                // The list from the disk is up: what follows is Telegram catching up behind it.
                syncingNow()

                // The local database is the first answer. Telegram is then synchronized when its
                // socket is ready, without holding the saved library behind a connection wait.
                // The wait is time-boxed, so a device that never gets a socket still gets its
                // cached list rather than sitting on "Loading your chats…".
                val connected = Td.awaitConnectedSessionOrNull()
                if (connected == null) {
                    if (session.isCurrent() && chats == null) {
                        _state.value = UiState.Error(Failures.OFFLINE)
                    }
                    // No line in time: the offline banner says so, and the list stays.
                    syncEnded(if (chats != null) WorkStatus.Offline(keptAt()) else WorkStatus.Idle)
                    giveUpOnFolders()
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
                // Never silent while chats are on screen (LS-01): a failure says what is kept.
                if (session.isCurrent()) {
                    syncEnded(ChatListStatus.afterSync(sync, held = chats != null, keptAt = keptAt(), asked = asked, now = System.currentTimeMillis()))
                }
                // The retired hidden chats are folded into the added chats (AddedChats.foldHiddenChats).
                // Under "every chat" the fold keeps every chat but the hidden ones, so it is handed
                // the list only once it is whole; a pick folds without it.
                val whole = sync.complete && !sync.partial && sync.failure == null && !mayHoldMore(sync.chats)
                com.tmplayer.data.AddedChats.foldHiddenChats(sync.chats.map { com.tmplayer.data.AddedChat(it.id, it.title) }, whole)
                // A first launch keeps what it got even when the sync fell short (a flood wait on
                // a large account): without it every start waited on TDLib again (HX8).
                if (sync.complete || (firstLoad && sync.chats.isNotEmpty())) chats?.let(::rememberSnapshot)
                // A list short of the account says so, with Try again (HX32): the main list or the
                // archive failing, or chats TDLib would not describe, are not left unsaid.
                _paging.value = ChatPaging(
                    more = if (sync.failure == null) mayHoldMore(sync.chats) else _paging.value.more,
                    failure = L.chatsSomeNotLoaded.takeIf { sync.partial },
                    partial = sync.partial,
                )
                if (session.isCurrent() && folderReads.readAfterLoad(folderIds())) fillFolders(repository, session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (session.isCurrent() && chats == null) {
                    _state.value = UiState.Error(Failures.humanise(error))
                }
                // With chats on screen the failure is said (LS-01): on the status line, and in the
                // list's foot with Try again, which reads the whole list again.
                val now = System.currentTimeMillis()
                val status = if (session.isCurrent()) ChatListStatus.afterError(error, held = chats != null, keptAt = keptAt(), now = now) else WorkStatus.Idle
                syncEnded(status)
                val foot = ChatListStatus.footWords(status, error, now)
                _paging.value = if (foot != null) _paging.value.copy(loading = false, failure = foot, partial = true) else _paging.value.copy(loading = false)
                giveUpOnFolders()
            }
        }
        loadJob = job
        // A newer load cancels this one; only the newest says when the list has arrived.
        job.invokeOnCompletion { if (loadJob === job) _refreshing.value = false }
    }

    /** A load that ended before the folders were read: their pages say so instead of waiting for ever. */
    private fun giveUpOnFolders() {
        if (membership != null || foldersJob?.isActive == true) return
        foldersGaveUp = true
        publish()
    }

    /** Removes one account's data immediately when authorization is lost. */
    fun reset() {
        loadJob?.cancel()
        moreJob?.cancel()
        foldersJob?.cancel()
        membership = null
        foldersGaveUp = false
        folderReads.reset()
        _folderStatus.value = FolderStatus()
        pendingNewChats.clear()
        account = null
        chats = null
        syncedAt = 0L
        limit = ChatRepository.CHAT_LIMIT
        syncEnded(WorkStatus.Idle)
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

        /**
         * The most new chats [fetchNewChats] reads in one settle window (HX44): a first sign in
         * still filling TDLib's database fires a position update per chat, and without a cap that
         * would be a `getChat` for every one of them behind a single burst. Whatever does not fit
         * is caught by a later burst (another message in it) or by the next full sync.
         */
        const val NEW_CHAT_FETCH_CAP = 20

        val LOADING_CHATS: String get() = L.browseLoadingChats
    }

    /** Whether the folders' members are still on their way: a folder may not call itself empty yet. */
    private fun foldersPending(): Boolean = !foldersGaveUp && folderReads.unread(folderIds())

    /** The folders that could not be read: the failed ones of the last read, or all of them when it never happened. */
    private fun foldersFailed(): Set<Int> = membership?.failed
        ?: if (foldersGaveUp) Td.folders.value.mapTo(HashSet()) { it.id } else emptySet()

    private fun publish() {
        _folderStatus.value = FolderStatus(foldersPending(), foldersFailed())
        val everything = chats ?: return
        // Kept whole, before the kids lock and the added chats, which are applied below on each
        // read. The picker reads this list, so it must stay whole: a chat
        // cannot be added if the only list in memory is the one already narrowed to what was.
        memory?.put(QueryKey.ChatList(), everything)
        _everything.value = everything
        val allowed = ChatGate.browsing.value
        val loaded = KidsLock.chats(everything, allowed)
        val withStarred = mergeStarred(everything, loaded, starred, ChatGate.allowed.value)
        _state.value = when {
            loaded.isNotEmpty() || withStarred.isNotEmpty() -> UiState.Content(BrowseData(loaded, account, withStarred))
            // Nothing added yet (HX27), which is a choice to make rather than a fault: a first run
            // that skipped the picker, or every added chat since left. Asked before the kids lock,
            // since a locked app with nothing added should still say what to do about it.
            com.tmplayer.data.AddedChats.ids.value != null && everything.isNotEmpty() ->
                UiState.Empty(L.browseNoChatsAdded, StateAction.AddChats, icon = TmIcons.ChatsMany)
            // Said plainly, so a child's empty screen is not mistaken for a broken account.
            allowed != null && everything.isNotEmpty() -> UiState.Empty(L.lockKidsEmpty, icon = TmIcons.Lock)
            // A first sign in on a large account can meet this while Telegram is still sending
            // the list, so the way on is to ask again, not to go and do something in Telegram.
            else -> UiState.Empty(L.browseNoChats, StateAction.Refresh, icon = TmIcons.ChatsMany)
        }
    }
}

/**
 * [loaded] and the starred chats of [everything] the viewer did not add, in [everything]'s order, the
 * kids lock ([kids]) still applied (HX86). [loaded] itself when no star reaches past it, so the
 * common case allocates nothing and compares equal.
 */
fun mergeStarred(everything: List<ChatSummary>, loaded: List<ChatSummary>, stars: Set<Long>, kids: Set<Long>?): List<ChatSummary> {
    if (stars.isEmpty()) return loaded
    val have = loaded.mapTo(HashSet()) { it.id }
    if (stars.none { it !in have }) return loaded
    val merged = everything.filter { (it.id in have || it.id in stars) && KidsLock.allows(kids, it.id) }
    return if (merged.size == loaded.size) loaded else merged
}

/**
 * Where the chat list's paging stands ([ChatListViewModel.paging]).
 *
 * @param more true when the main list came back full, so Telegram may hold older chats.
 * @param loading true while the next chats are read: the list's foot shows placeholder rows.
 * @param failure why the last read failed, or that some chats did not load; the foot offers Try again.
 * @param partial [failure] is "some chats could not load" and Try again reloads the whole list.
 */
data class ChatPaging(
    val more: Boolean = false,
    val loading: Boolean = false,
    val failure: String? = null,
    /** The list is short of the account for a reason other than its length (HX32): Try again reads it all again. */
    val partial: Boolean = false,
)

/**
 * How a chat list sync ends on the status line ([ChatListViewModel.listStatus], WP-93), as plain
 * functions so the tests need no TDLib. Nothing is said while no list is held ([held] false): the
 * page's skeleton and its error page are the first load's.
 */
object ChatListStatus {

    /** The longest the list says Syncing; a deep archive walk past it goes on quietly. */
    const val SYNC_BOX_MS = 60_000L

    /**
     * The main list failing is Failed with the list kept from [keptAt] (or Telegram's wait, past
     * [WorkStatus.QUIET_WAIT_SECONDS]); the archive or some chats missing is Partial; a whole list
     * is Updated when the viewer [asked] for it, and Idle when it came by itself.
     */
    fun afterSync(sync: ChatSync, held: Boolean, keptAt: Long?, asked: Boolean, now: Long): WorkStatus = when {
        !held -> WorkStatus.Idle
        sync.failure != null -> WorkStatus.ofFailure(sync.failure, now, keptAt)
        sync.partial -> WorkStatus.Partial(
            okCount = sync.chats.size,
            failedCount = sync.dropped.coerceAtLeast(0),
            what = L.chatsSomeNotLoaded,
        )
        asked -> WorkStatus.Updated(now)
        else -> WorkStatus.Idle
    }

    /** A load that threw: said with the list kept, or nothing while no list is held. */
    fun afterError(error: Throwable, held: Boolean, keptAt: Long?, now: Long): WorkStatus =
        if (held) WorkStatus.ofFailure(error, now, keptAt) else WorkStatus.Idle

    /**
     * What the list's foot says for [status] beside its Try again, or null when the foot has
     * nothing to add (offline: the banner says it; nothing failed).
     */
    fun footWords(status: WorkStatus, error: Throwable?, now: Long): String? = when (status) {
        is WorkStatus.Failed -> WorkStatusWords.line(status, now)
        is WorkStatus.RateLimited -> Failures.humanise(error)
        else -> null
    }
}

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

/** Where a chat grid's pages come from: Telegram's through the signed in session, or a test's own. */
fun interface GridPages {
    suspend fun page(cursors: MediaCursors, query: String, topicId: Int): MediaPage
}

/** The walk back through a chat that the size limits keep empty, and how it counts under the skeleton (WP-94, LS-10). */
object GridWalk {
    /** The skeleton's label becomes "Looked through N posts so far" from here. */
    const val LABEL_AFTER_MS = 3_000L

    /** And is changed at most this often, so a big chat does not redraw the skeleton page by page. */
    const val LABEL_EVERY_MS = 2_000L

    /** What one page read of the chat: every video, archive and self-destructing one, before the size limits. */
    fun readIn(page: MediaPage): Int = page.items.size + page.archives.size + page.hiddenSelfDestructing
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
    /**
     * A search's word at the foot of what it found: only the newest part of the chat was read, or
     * only what is on this device. Null for a listing, and for a search Telegram answered.
     */
    val searchNote: String? = null,
    /** The search read the chat back only so far: Keep looking ([MediaListViewModel.searchDeeper]) goes on. */
    val canSearchDeeper: Boolean = false,
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
    /**
     * What a search of this chat asks, for the chat and the topic shown: TDLib's
     * `searchChatMessages` and `getChatHistory` unless a test passes its own.
     */
    private val searchSource: ((chatId: Long, topicId: Int) -> com.tmplayer.data.ChatSearchSource)? = null,
    /** The videos the app has seen anywhere (Home, Continue watching), for a search's first answer. */
    private val knownVideos: () -> List<MediaItem> = { com.tmplayer.online.KnownMedia.all() },
    /** Where the pages of a listing come from: Telegram's through the signed in session unless a test passes its own. */
    private val pages: GridPages? = null,
) : ViewModel(com.tmplayer.platform.JobFailures.viewModelScope("MediaList")) {

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
     * How many videos the size floor ("Hide small clips") has kept out of the pages read so far:
     * the hidden set this listing filtered itself, counted in one pass as each page is read, so
     * it is exact once the chat has been read to its start ([MediaListState.endReached]) and a
     * lower bound before. Always 0 during a search, which ignores the floor.
     */
    val sizeHidden: StateFlow<Int> = _sizeHidden.asStateFlow()

    /** Notes what this chat's grid holds, for "Choose the right poster" to offer to the others of a title. */
    private fun watchSiblings() {
        viewModelScope.launch {
            _state.collect { state ->
                // The videos this chat's grid holds, for "Choose the right poster" to offer the same
                // pick to the others of a title. A search's narrowed list is not the chat.
                if (state is UiState.Content && query.isBlank()) com.tmplayer.data.SiblingVideos.note(chatId, state.value.items)
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

    // Declared above the init blocks, which start the first load.
    private val _status = MutableStateFlow<WorkStatus>(WorkStatus.Idle)

    /**
     * What the listing is doing behind a grid that is on screen (WP-94): Syncing while a kept
     * listing is revalidated or a Refresh runs, Failed with the time the grid was fresh when that
     * failed with the previous grid kept, Updated after a Refresh the viewer asked for. Idle
     * otherwise, and always for a first load, which the skeleton and its label say.
     */
    val status: StateFlow<WorkStatus> = _status.asStateFlow()

    private val _statusAsked = MutableStateFlow(false)

    /** [status] is the answer to a press (Refresh, Try again), so its words show at once and "Up to date" follows. */
    val statusAsked: StateFlow<Boolean> = _statusAsked.asStateFlow()

    /**
     * Which listing is current. Every start of a listing (a topic, a refresh, a search) counts up,
     * and every answer is dropped when its number is no longer the current one, so a slow answer
     * from the topic or the listing left behind never lands over the one now shown.
     */
    private var runGen = 0

    /** Messages read by the walk back through a chat since the listing last started over ([watchScan]). */
    private var scanned = 0
    private var scanJob: Job? = null
    private var syncBox: Job? = null

    init {
        // Each settled listing is read once for covers that are an uploader's advert
        // ([com.tmplayer.data.PromoCovers]), off the main thread, so the tiles can draw the generated
        // cover over them. A state flow keeps only the newest, so a listing replaced meanwhile is the one read next.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _state.collect { state ->
                val items = (state as? UiState.Content)?.value?.items ?: return@collect
                runCatching { com.tmplayer.ui.components.PromoCoverBook.observe(items) }
            }
        }
    }

    /** What one read of this chat's pages stands on: the session it began under and how it asks. */
    private class PageRun(val isCurrent: () -> Boolean, val page: suspend (MediaCursors, String, Int) -> MediaPage)

    private suspend fun openRun(): PageRun {
        pages?.let { source -> return PageRun({ true }) { cursors, text, topic -> source.page(cursors, text, topic) } }
        val session = Td.awaitAuthorizedSession()
        val repository = ChatRepository(session.client)
        return PageRun(session::isCurrent) { cursors, text, topic -> repository.mediaPage(chatId, cursors, text, topicId = topic) }
    }

    /** [status] says Syncing, for [ChatListStatus.SYNC_BOX_MS] at most, so nothing spins for ever. */
    private fun working(asked: Boolean) {
        syncBox?.cancel()
        _statusAsked.value = asked
        _status.value = WorkStatus.Syncing()
        val gen = runGen
        syncBox = viewModelScope.launch {
            delay(ChatListStatus.SYNC_BOX_MS)
            if (gen == runGen && _status.value is WorkStatus.Syncing) _status.value = WorkStatus.Idle
        }
    }

    /** [status] after the work ended, the time box with it. */
    private fun worked(status: WorkStatus) {
        syncBox?.cancel()
        syncBox = null
        _status.value = status
    }

    /** The work ended well: "Up to date" after a press, nothing after the quiet kind. */
    private fun finished() = worked(if (_statusAsked.value) WorkStatus.Updated(System.currentTimeMillis()) else WorkStatus.Idle)

    /** When the listing under [key] was last fresh from Telegram, for "Showing what was here at". */
    private fun keptAt(key: QueryKey.ChatMedia): Long? = cache.entry(key)?.updatedAt?.takeIf { it > 0 }

    /**
     * While the walk back through a chat holds the skeleton, its label says how far it has read
     * ("Looked through 1,200 posts so far"): from [GridWalk.LABEL_AFTER_MS] on, changed at most
     * every [GridWalk.LABEL_EVERY_MS], and it stops by itself once the skeleton is gone.
     */
    private fun watchScan(gen: Int) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            delay(GridWalk.LABEL_AFTER_MS)
            var said = -1
            while (gen == runGen) {
                val now = _state.value as? UiState.Loading ?: break
                if (scanned > 0 && scanned != said) {
                    said = scanned
                    _state.value = now.copy(label = L.gridLookedThrough(count = scanned))
                }
                delay(GridWalk.LABEL_EVERY_MS)
            }
        }
    }

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
        watchSiblings()
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
        // A chat that is left stops reading at once, not at the end of the page it was waiting for.
        runGen++
        listOf(pageJob, availabilityJob, sponsoredJob, recordJob, typeJob, scanJob, syncBox).forEach { it?.cancel() }
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
        // Opens the picker, which each platform hosts itself (HX27).
        StateAction.AddChats -> AddChatsRequest.ask()
        // The filters are the screen's, kept in its settings: it clears them itself.
        StateAction.ClearFilters -> Unit
        // Show them: the size floor is the screen's too (it lists again with no floor for this visit).
        StateAction.ShowSmall -> Unit
    }

    /** What the viewer searched this chat for, for a screen offering the same words everywhere. */
    val searchText: String get() = query

    /**
     * Carries on walking back through the chat from where the last walk gave up, rather than
     * starting again at the newest message and reading the same empty pages a second time.
     */
    private fun keepLooking() {
        if (searchRun != null) {
            searchDeeper()
            return
        }
        if (_state.value !is UiState.Empty || pageJob?.isActive == true) return
        _state.value = UiState.Loading(if (query.isNotBlank()) L.browseSearching else L.browseFindingVideos)
        val gen = runGen
        watchScan(gen)
        pageJob = viewModelScope.launch {
            pageMore(openRun(), emptyList(), endReached = false, gen = gen)
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
        // Counted first: whatever the listing left behind is still reading, and its answer is the
        // old topic's or the old listing's, never this one's.
        val gen = ++runGen
        scanJob?.cancel()
        scanned = 0
        _topics.value?.let { bar -> if (bar.selected != topicId) _topics.value = bar.copy(selected = topicId) }
        // A search has its own way through the chat ([ChatSearchRun]); the listing's kept pages are
        // what it answers from first.
        if (query.isNotBlank()) {
            worked(WorkStatus.Idle)
            startSearch()
            return
        }
        searchRun = null
        searchGen++
        val key = gridKey()
        val kept = if (force) null else cache.peek<ChatGrid>(key, PageQueries.GRID_STALE_MS)
        if (kept != null) {
            pageJob?.cancel()
            loadSponsored()
            showKept(key, kept.data, revalidate = kept.stale, gen = gen, keptAt = kept.updatedAt)
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
            // The subject changed (another topic, or the chat's first look): the skeleton, with
            // its words from a second on and a count while the walk goes on.
            _state.value = UiState.Loading(if (searching) L.browseSearching else L.browseFindingVideos)
            worked(WorkStatus.Idle)
            watchScan(gen)
        } else {
            // A Refresh or Try again over the grid on screen: it stays, and the foot says Updating.
            working(asked = force)
        }
        pageJob?.cancel()
        loadSponsored()
        pageJob = viewModelScope.launch {
            val session = openRun()
            serverQuery = query
            runCatching { firstPage(session) }
                .onSuccess { rawPage ->
                    if (!session.isCurrent() || gen != runGen) return@onSuccess
                    scanned += GridWalk.readIn(rawPage)
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
                        pageMore(session, emptyList(), page.endReached, key, gen)
                        if (gen == runGen) finished()
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
                    finished()
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (session.isCurrent() && gen == runGen) {
                        if (previous != null && keepOnFailure) {
                            cursors = previousCursors
                            _state.value = UiState.Content(previous)
                            // The old grid is back, and the foot says it is the old one, since when, with Try again.
                            worked(WorkStatus.ofFailure(it, System.currentTimeMillis(), keptAt(key)))
                        } else {
                            _state.value = UiState.Error(Failures.humanise(it))
                            worked(WorkStatus.Idle)
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
    private fun showKept(key: QueryKey.ChatMedia, grid: ChatGrid, revalidate: Boolean, gen: Int, keptAt: Long?) {
        val walking = show(grid)
        if (walking) {
            // Every kept page is outside the size limits: the walk goes on under the skeleton, counting.
            worked(WorkStatus.Idle)
            watchScan(gen)
            pageJob = viewModelScope.launch {
                pageMore(openRun(), emptyList(), endReached = false, key = key, gen = gen)
            }
            return
        }
        if (!revalidate) {
            worked(WorkStatus.Idle)
            refreshLocalAvailability()
            return
        }
        // The kept grid is on screen while it is read again: quiet for 3 s, then "Updating…".
        working(asked = false)
        pageJob = viewModelScope.launch {
            val result = runCatching {
                cache.fetch(key) {
                    val source = pages
                    if (source != null) {
                        val page = source.page(MediaCursors(), key.query, key.topicId)
                        val old = cache.data<ChatGrid>(key)
                        if (old == null) ChatGrid.of(page, key.query) else ChatGrid.refreshed(old, page)
                    } else if (key.query.isEmpty()) {
                        PageQueries.refreshGrid(key)
                    } else {
                        val session = openRun()
                        serverQuery = query
                        val page = firstPage(session).let { it.copy(items = marked(it.items)) }
                        if (!session.isCurrent()) throw CancellationException("signed out")
                        ChatGrid.of(page, serverQuery)
                    }
                }
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException && key == gridKey()) throw failure
                // A refresh that failed keeps the kept listing up, still the best answer, and says so.
                if (failure !is CancellationException && gen == runGen) {
                    worked(WorkStatus.ofFailure(failure, System.currentTimeMillis(), keptAt))
                }
                return@launch
            }
            val fresh = result.getOrThrow()
            if (key != gridKey() || gen != runGen) return@launch
            val seen = fresh.copy(items = marked(fresh.items))
            if (seen.items != fresh.items) cache.update<ChatGrid>(key) { if (it === fresh) seen else it }
            if (show(seen)) {
                watchScan(gen)
                pageMore(openRun(), emptyList(), endReached = false, key = key, gen = gen)
                if (gen == runGen) finished()
                return@launch
            }
            finished()
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
    private suspend fun firstPage(session: PageRun): MediaPage =
        session.page(MediaCursors(), query, topicId)

    /** The search under way, for the words in [query]; null while the chat is listed. */
    private var searchRun: com.tmplayer.data.ChatSearchRun? = null

    /**
     * Which search is current. Every answer carries the number it was asked under and is dropped
     * when the words have changed since, so a slow answer never lands over a newer one.
     */
    private var searchGen = 0

    private fun searchSourceFor(): com.tmplayer.data.ChatSearchSource =
        searchSource?.invoke(chatId, topicId) ?: TdChatSearch(chatId, topicId)

    /**
     * What the app already holds of this chat, for a search's first answer: every page of the
     * listing loaded so far (kept in [cache]), the listing on screen, and the videos seen anywhere
     * else. Bounded by those: the kept pages and [com.tmplayer.online.KnownMedia]'s 1,500.
     */
    private fun loadedForSearch(): List<MediaItem> {
        val listed = cache.data<ChatGrid>(QueryKey.ChatMedia(chatId, topicId, ""))?.items.orEmpty()
        val shown = (_state.value as? UiState.Content)?.value?.items.orEmpty()
        val known = runCatching { knownVideos() }.getOrDefault(emptyList())
        return listed + shown + known
    }

    /**
     * Searches the chat for [query]: what is loaded answers at once, then Telegram's answer is
     * added under it (never reordering what is listed), or, when Telegram finds nothing or cannot
     * be asked, the newest part of the chat is read back and matched here.
     */
    private fun startSearch() {
        val gen = ++searchGen
        pageJob?.cancel()
        holdsJob?.cancel()
        hiddenBySize = 0
        _selfDestructing.value = 0
        archives = emptyList()
        cursors = MediaCursors()
        loadSponsored()
        val run = com.tmplayer.data.ChatSearchRun(searchSourceFor(), query, loadedForSearch(), chatId, topicId)
        searchRun = run
        val local = run.local()
        when {
            local.items.isNotEmpty() -> show(local, still = true)
            // The listing from before stays up under the spinner rather than flashing a skeleton.
            _state.value !is UiState.Content -> _state.value = UiState.Loading(L.browseSearching)
        }
        _searching.value = true
        pageJob = viewModelScope.launch {
            try {
                val answer = run.first()
                if (gen != searchGen) return@launch
                show(answer.copy(items = marked(answer.items)), still = false)
                if (answer.items.isNotEmpty()) recordSearch(query)
            } finally {
                if (gen == searchGen) _searching.value = false
            }
        }
    }

    /** The next part of a search: Telegram's next page ([deeper] false) or another round of the history. */
    private fun continueSearch(deeper: Boolean) {
        val run = searchRun ?: return
        if (pageJob?.isActive == true) return
        val gen = searchGen
        val current = (_state.value as? UiState.Content)?.value
        if (current != null) _state.value = UiState.Content(current.copy(loadingMore = true, moreFailed = false))
        else _state.value = UiState.Loading(L.browseSearching)
        pageJob = viewModelScope.launch {
            val answer = if (deeper) run.deeper() else run.more()
            if (gen != searchGen) return@launch
            show(answer.copy(items = marked(answer.items)), still = false)
        }
    }

    /** Keep looking under a search that read only the newest part of the chat. */
    fun searchDeeper() = continueSearch(deeper = true)

    /** A search's answer as the listing, its empty state or its failure. [still] while Telegram has not answered. */
    private fun show(answer: com.tmplayer.data.ChatSearchAnswer, still: Boolean) {
        val note = when {
            still -> null
            answer.how == com.tmplayer.data.ChatSearchHow.Offline -> L.browseSearchOffline
            answer.how == com.tmplayer.data.ChatSearchHow.Scanned && answer.canGoDeeper -> L.browseSearchScanned(answer.scanned)
            else -> null
        }
        val failed = answer.failed
        _state.value = when {
            answer.items.isEmpty() && failed != null -> UiState.Error(Failures.humanise(failed))
            answer.items.isEmpty() && !still -> {
                val topic = _topics.value?.nameOf(topicId)
                val said = if (topic != null) L.gridTopicNoMatch(topic, query) else L.browseNoMatch(query)
                if (note != null && answer.canGoDeeper) UiState.Empty("$said\n\n$note", StateAction.KeepLooking, icon = TmIcons.SearchRemove)
                else if (note != null) UiState.Empty("$said\n\n$note", StateAction.SearchAllChats, icon = TmIcons.SearchRemove)
                else UiState.Empty(said, StateAction.SearchAllChats, icon = TmIcons.SearchRemove)
            }
            answer.items.isEmpty() -> UiState.Loading(L.browseSearching)
            else -> UiState.Content(
                MediaListState(
                    items = answer.items,
                    sponsored = sponsored,
                    // Not the end while Telegram is still to answer: the foot stays open.
                    endReached = !still && answer.endReached,
                    moreFailed = failed != null,
                    searchNote = note,
                    canSearchDeeper = !still && answer.canGoDeeper,
                ),
            )
        }
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
    private fun nothingFound(reachedEnd: Boolean = true): UiState<MediaListState> {
        val empty = emptyState(reachedEnd)
        // "No playable videos" with nothing hiding any: say what the chat does hold (U18).
        if (query.isBlank() && reachedEnd && hiddenBySize == 0) sayWhatItHolds(empty)
        return empty
    }

    private var holdsJob: Job? = null

    /**
     * Adds what the chat holds besides videos under [empty], once the three counts are in and only
     * if that very page is still the one on screen. Nothing is added when it holds none of them
     * or the counts did not come back: the plain message stands.
     */
    private fun sayWhatItHolds(empty: UiState.Empty) {
        holdsJob?.cancel()
        val chat = chatId
        val topic = topicId
        holdsJob = viewModelScope.launch {
            val session = runCatching { Td.awaitAuthorizedSession() }.getOrNull() ?: return@launch
            val held = runCatching { ChatRepository(session.client).holds(chat, topic) }.getOrNull() ?: return@launch
            val said = held.under(empty.message)
            if (said != empty.message && _state.value == empty) _state.value = empty.copy(message = said)
        }
    }

    private fun emptyState(reachedEnd: Boolean = true): UiState.Empty {
        // Say what is hiding things, rather than claiming the chat is empty when it is the size
        // floor doing the work, and give the way out: Show them (StateAction.ShowSmall).
        val hiding = query.isBlank() && hiddenBySize > 0
        val smallClips = minSizeBytes == SizeFilter.CLIP_MIN
        val hidden = when {
            !hiding -> null
            smallClips && reachedEnd -> L.gridSmallHiddenAll(hiddenBySize)
            smallClips -> L.gridSmallHiddenSomeAll
            else -> L.browseHiddenBySize(hiddenBySize)
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
            hidden != null && smallClips -> UiState.Empty(hidden, StateAction.ShowSmall, icon = TmIcons.FilterHorizontal)
            hidden != null -> UiState.Empty("${noVideosWithin(minSizeBytes, maxSizeBytes)}\n\n$hidden", StateAction.ShowSmall, icon = TmIcons.FilterHorizontal)
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
        // A search pages through Telegram's answer; the history it read goes on only by Keep looking.
        if (searchRun != null) {
            if (!current.value.moreFailed) continueSearch(deeper = false)
            return
        }

        _state.value = UiState.Content(current.value.copy(loadingMore = true, moreFailed = false))
        val gen = runGen
        pageJob = viewModelScope.launch {
            pageMore(openRun(), current.value.items, endReached = false, gen = gen)
        }
    }

    /**
     * Try again on a page that failed, or that has been loading for too long: the stuck request
     * is dropped and the same page asked for afresh, from the cursors it would have used.
     */
    fun retryMore() {
        val current = _state.value as? UiState.Content ?: return
        val run = searchRun
        if (run != null) {
            pageJob?.cancel()
            continueSearch(deeper = run.readingHistory)
            return
        }
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
        session: PageRun,
        startingFrom: List<MediaItem>,
        endReached: Boolean,
        /** The listing the pages are kept under, appended to as they arrive. */
        key: QueryKey.ChatMedia = gridKey(),
        /** The listing this walk belongs to ([runGen]): once another has started, nothing it reads lands. */
        gen: Int = runGen,
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
            gen == runGen &&
            attempts++ < MAX_EMPTY_PAGES
        ) {
            runCatching { session.page(cursors, serverQuery, topicId) }
                .onSuccess { rawPage ->
                    // The chat or topic left behind: its page is nobody's now (and the loop ends).
                    if (gen != runGen) return@onSuccess
                    scanned += GridWalk.readIn(rawPage)
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

        if (!session.isCurrent() || gen != runGen) return
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

    }
}

/**
 * A chat search through TDLib: `searchChatMessages` (through [ChatRepository.mediaPage]) and
 * `getChatHistory`, on the signed in session. Online means a connection within
 * [ONLINE_CHECK_MS]: TDLib holds a request until it can reach Telegram, and offline that would
 * leave the search spinning with no end.
 */
private class TdChatSearch(private val chatId: Long, private val topicId: Int) : com.tmplayer.data.ChatSearchSource {
    private suspend fun repository() = ChatRepository(Td.awaitAuthorizedSession().client)

    override suspend fun search(query: String, cursors: MediaCursors, limit: Int): MediaPage =
        repository().mediaPage(chatId, cursors, query, pageSize = limit, topicId = topicId)

    override suspend fun history(fromMessageId: Long, limit: Int, onlyLocal: Boolean): com.tmplayer.data.ChatHistoryPage =
        repository().historyPage(chatId, fromMessageId, limit, onlyLocal)

    override suspend fun online(): Boolean = Td.awaitConnectedSessionOrNull(ONLINE_CHECK_MS) != null

    private companion object {
        const val ONLINE_CHECK_MS = 3_000L
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
