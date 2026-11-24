package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChats
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.data.ChatCount
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatPicking
import com.tmplayer.data.ChatSuggestions
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatVideoCount
import com.tmplayer.data.Failures
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Add chats" as the screens draw it (HX27). The screen holds no rule of its own: what is listed,
 * in which band, what Select all means and what the foot adds up are [ChatPicking]'s, and what is
 * recommended is [ChatSuggestions]'.
 *
 * @param chats every chat of the account, unnarrowed ([ChatListViewModel.everything]).
 * @param counts the counter's calls, or null where there is nothing to count against (a preview,
 *   the promo build): the rows then say nothing about videos rather than spin.
 * @param write where Done writes the choice: the store's own [SettingsStore.setAddedChats], or a
 *   test's stand in; null leaves Done doing nothing, as with no store.
 * @param writeChoice where Done writes the choice while "All chats" is on (HX86): the mode and the
 *   kept ticks together ([SettingsStore.setAddedChoice]). Without it [write] gets the kept ticks.
 */
class AddChatsViewModel(
    private val settings: SettingsStore? = null,
    private val counts: ChatCountCalls? = null,
    private val write: (suspend (List<AddedChat>) -> Unit)? = settings?.let { store -> store::setAddedChats },
    private val writeChoice: (suspend (AddedChoice) -> Unit)? = settings?.let { store -> store::setAddedChoice },
    /** Whether [countRest] counts the whole account in the background; a test of the row counts turns it off. */
    private val countsRest: Boolean = true,
) : ViewModel(com.tmplayer.platform.JobFailures.viewModelScope("AddChats")) {

    /**
     * @param ticked the chats that will be added when Done is pressed. While [allChats] is on they
     *   are the kept ticks: not counted, not editable, and back in force when it is switched off.
     * @param allChats the "All chats" switch (HX86): every chat counts, archived, muted, bots and
     *   people included, and the per-chat ticks are disabled.
     * @param counted each chat's videos as they arrive; a chat absent from here is still counting.
     * @param added the chats the viewer had added when the picker opened (the stored choice when
     *   it is a pick), fixed for the opening: the Added band, and what Recommended leaves out.
     * @param recommended the Recommended band: taken within seconds from the head start, ranked
     *   again as the rest of the account is counted until the viewer touches the list, then only
     *   added to at its end, so no row moves under a finger.
     * @param recommending true from the first open until the Recommended band is taken (at most
     *   [RECOMMEND_BY_MS]), while the screen holds the band's place with a skeleton so no row
     *   moves under a finger or a click when it lands. Not held when the viewer already has chats
     *   added: opening the picker to add one more needs no wait.
     * @param saving true while the choice is being written, so Done cannot run twice.
     * @param saved true once it is written and the screen may close.
     * @param justAdded how many chats this save added that were not chosen when the picker opened,
     *   for the "3 chats added" notice as the screen closes; 0 when "All chats" was written.
     * @param error what went wrong when the last write failed, shown with Try again, or null.
     */
    data class State(
        val query: String = "",
        val scope: ChatPicking.Scope = ChatPicking.Scope.All,
        val folder: Int? = null,
        val ticked: Set<Long> = emptySet(),
        val allChats: Boolean = false,
        val counted: Map<Long, ChatCount> = emptyMap(),
        val added: Set<Long> = emptySet(),
        val recommended: List<Long> = emptyList(),
        val belowBar: Boolean = false,
        val recommending: Boolean = false,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val justAdded: Int = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What was ticked when the picker opened, so Back knows whether anything changed. */
    private var opened: Set<Long>? = null

    /** Whether "All chats" was on when the picker opened, for the same question. */
    private var openedAll = false

    /** True once the viewer has touched the switch, so a stored answer that lands late never overrules it. */
    private var touchedAll = false

    /**
     * A viewer who has never answered starts with Saved Messages ticked (HX86), once the list has
     * brought it. True from the start with no store to read (a preview, a test), else once the
     * stored answer has been read and was none.
     */
    private var fresh = settings == null
    private var seeded = false
    private var savedId: Long? = null

    /** The chats the viewer had added, with their names, so one that is gone keeps its row. */
    private var addedNames: List<AddedChat> = emptyList()

    private var headStart: Job? = null
    private var background: Job? = null

    /** The account as the screen gave it, so each batch of counts can rank the band again. */
    private var scanned: List<ChatSummary> = emptyList()

    /**
     * True once the viewer has done anything to the list (ticked, starred, searched, filtered):
     * from then on nothing in the band moves under their finger, and a chat that qualifies later is
     * added at its end ([ChatSuggestions.refine]).
     */
    private var settled = false

    /** True while the remote is jumping: the background count waits so the list is not rebuilt mid jump. */
    @Volatile private var held = false
    private val counting = HashMap<Long, Job>()

    /** Background answers waiting for the next batch: one state update per [BATCH_MS], not one per chat. */
    private val pending = HashMap<Long, ChatCount>()
    /** State updates the count has made, for the scale test's cap on how often the screen is recomposed. */
    var stateUpdates = 0
        private set

    /**
     * Reads the stored choice and ticks what it holds. A viewer who has never answered opens with
     * nothing ticked and the Recommended band to choose from; one who has answered opens on their
     * own chats (the Added band), so opening the picker and pressing Done changes nothing.
     *
     * A tick made before the read lands is kept: the stored ids are added to it, never replace it.
     */
    fun open() {
        val store = settings ?: return
        viewModelScope.launch {
            val choice = store.addedChats.first()
            addedNames = choice.chats
            if (choice.mode != AddedMode.Unanswered) {
                // Picked, or All with the ticks kept beside the switch (HX86).
                val ids = choice.chats.mapTo(LinkedHashSet()) { it.id }
                opened = ids
                openedAll = choice.mode == AddedMode.All
                _state.update {
                    it.copy(
                        ticked = it.ticked + ids,
                        // A switch the viewer already touched is theirs, whatever was stored.
                        allChats = if (touchedAll) it.allChats else openedAll,
                        added = ids,
                        // Adding one more chat to a choice already made needs no wait for the band.
                        recommending = it.recommending && ids.isEmpty(),
                    )
                }
            } else {
                // Never answered: Saved Messages is ticked once the list has it (HX86), so Done
                // works at once, and Back from the untouched picker leaves the account as it was.
                opened = emptySet()
                fresh = true
                seedFresh()
            }
        }
    }

    /** Tells the model which chat is Saved Messages, as the list brings it, for the fresh start. */
    fun knowSaved(id: Long?) {
        savedId = id
        seedFresh()
    }

    /**
     * Ticks Saved Messages for a viewer who has never answered (HX86), once, and only on an empty
     * choice: a tick made first is theirs. The untouched picker then counts as unchanged, so Back
     * does not ask about a tick nobody made.
     */
    private fun seedFresh() {
        val id = savedId ?: return
        if (!fresh || seeded) return
        seeded = true
        // Not under the switch either: with every chat counting there is nothing to start on.
        if (_state.value.ticked.isNotEmpty() || _state.value.allChats) return
        _state.update { it.copy(ticked = setOf(id)) }
        if (opened?.isEmpty() == true) opened = setOf(id)
    }

    /**
     * The "All chats" switch (HX86). On: every chat counts and the ticks are disabled but kept.
     * Off: the kept ticks come back ([ChatPicking.afterAllOff]), never an empty set, and a chat the
     * viewer has since left is dropped quietly. Pending until Done, like a tick: flipped on and off
     * again, the last state is what is written, once.
     */
    fun setAllChats(on: Boolean, chats: List<ChatSummary>, listComplete: Boolean) {
        if (locked) return
        touchedAll = true
        settled = true
        _state.update { now ->
            when {
                now.allChats == on -> now
                on -> now.copy(allChats = true)
                else -> now.copy(allChats = false, ticked = ChatPicking.afterAllOff(now.ticked, chats, listComplete))
            }
        }
    }

    /**
     * Counts the head of the list once, so the Recommended band can appear within the first
     * seconds of a cold open rather than after the whole account is read.
     */
    fun start(chats: List<ChatSummary>) {
        knowSaved(ChatPicking.savedId(chats))
        if (headStart != null || chats.isEmpty() || counts == null) return
        scanned = chats
        if (_state.value.recommended.isEmpty() && _state.value.added.isEmpty()) _state.update { it.copy(recommending = true) }
        headStart = viewModelScope.launch {
            val jobs = ChatPicking.headStart(chats).mapNotNull { id ->
                count(id)
                counting[id]
            }
            // The band has to be there in the first seconds, so it is made from whatever has
            // answered by then and not from the whole head start: one chat Telegram makes us wait
            // twenty seconds for held back the other fifty nine, which ChatPickerScaleTest
            // measured at 20.9 s before this.
            withTimeoutOrNull(RECOMMEND_BY_MS) { jobs.joinAll() }
            recommend(chats)
            // The list is shown from here on whatever came of it: offline, a band that arrives
            // later is better than a screen of placeholders for as long as the line is down.
            _state.update { it.copy(recommending = false) }
            // Nothing answered in time at all (offline, a cold database): rather than leave the
            // band empty for the session, it waits for the head start and takes it then.
            if (_state.value.recommended.isEmpty()) {
                jobs.joinAll()
                recommend(chats)
            }
        }
    }

    /**
     * Counts the rest of the account in the background once the head start is done, channels first
     * ([ChatPicking.scanOrder]), so the Recommended band can find a video channel however far down
     * the list it sits and the television's short list can fold the chats with no videos (HX76).
     * At most [atOnce] at a time, each one the counter's own budget (FLOOD_WAIT waited out by the
     * call that got it, the others go on). It stops with [stop], and a chat counted by its row is
     * skipped. A chat that could not be counted is asked once more at the end, at most [RETRY_MAX]
     * of them. The answers are applied in batches every [batchMs] and not while the remote is
     * jumping, since each update re-folds every row (4,500 chats took 1.1 to 1.4 cores of the Mi
     * TV); each batch ranks the band again.
     * @param atOnce how many chats are asked at a time; the television passes [BACKGROUND_ON_TV].
     * @param batchMs the gap between batches; the television passes [BATCH_ON_TV_MS], since a batch
     * still re-folds 4,500 rows and 500 ms kept the Mi TV's main thread at half a core.
     */
    fun countRest(chats: List<ChatSummary>, atOnce: Int = BACKGROUND_AT_ONCE, batchMs: Long = BATCH_MS) {
        if (counts == null || !countsRest || background != null || chats.isEmpty()) return
        scanned = chats
        background = viewModelScope.launch {
            headStart?.join()
            coroutineScope {
                val flusher = launch {
                    while (true) {
                        delay(batchMs)
                        if (!held) flush()
                    }
                }
                scan(ChatPicking.scanOrder(chats).map { it.id }, atOnce)
                flush()
                val failed = chats.asSequence().map { it.id }
                    .filter { _state.value.counted[it] == ChatCount.Unknown }
                    .take(RETRY_MAX).toList()
                if (failed.isNotEmpty()) {
                    _state.update { it.copy(counted = it.counted - failed.toSet()) }
                    scan(failed, atOnce)
                }
                flusher.cancel()
            }
            flush()
        }
    }

    /** Counts [ids] in order, [atOnce] at a time, skipping any already counted. */
    private suspend fun scan(ids: List<Long>, atOnce: Int) = coroutineScope {
        val queue = Channel<Long>(Channel.UNLIMITED)
        for (id in ids) queue.trySend(id)
        queue.close()
        List(atOnce.coerceAtLeast(1)) {
            launch {
                for (id in queue) {
                    // Not while "All chats" is on: nothing is recommended then, so nothing is asked of Telegram.
                    while (held || _state.value.allChats) delay(if (_state.value.allChats) ALL_POLL_MS else HOLD_POLL_MS)
                    if (_state.value.counted.containsKey(id) || pending.containsKey(id)) continue
                    count(id, batched = true)
                    counting[id]?.join()
                }
            }
        }.joinAll()
    }

    /** Applies the waiting background answers in one state update, and ranks the band again from them. */
    private fun flush() {
        if (pending.isEmpty()) return
        val batch = HashMap(pending)
        pending.clear()
        stateUpdates++
        _state.update { it.copy(counted = it.counted + batch) }
        if (scanned.isNotEmpty()) recommend(scanned)
    }

    /** Pauses and resumes the background count: the screen holds it while the remote is jumping. */
    fun hold(on: Boolean) { held = on }

    /**
     * Counts one chat, for a row that came on screen. Asked twice for the same chat, it counts
     * once; scrolled away and back, it reads the answer it already has.
     */
    fun count(chatId: Long, batched: Boolean = false) {
        val calls = counts ?: return
        if (_state.value.counted.containsKey(chatId) || counting.containsKey(chatId) || pending.containsKey(chatId)) return
        counting[chatId] = viewModelScope.launch {
            val answer = try {
                ChatVideoCount.of(calls, chatId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A counter that throws must not take the picker with it: the row says it could
                // not count, and the viewer can ask again.
                ChatCount.Unknown
            }
            if (batched) {
                pending[chatId] = answer
            } else {
                stateUpdates++
                _state.update { it.copy(counted = it.counted + (chatId to answer)) }
            }
        }.also { job -> job.invokeOnCompletion { counting.remove(chatId) } }
    }

    /**
     * Ranks the band from the counts in hand. The first take (within [RECOMMEND_BY_MS]) fills it,
     * later batches rank it again until the viewer touches the list, and after that only add to its
     * end: a list that rearranges itself under a finger ticks the wrong chat
     * ([ChatSuggestions.refine]).
     */
    private fun recommend(chats: List<ChatSummary>) {
        val now = _state.value
        // What is added already is not recommended again: it has its own band.
        val counted = ChatPicking.counted(chats, now.counted).filter { it.id !in now.added }
        val current = ChatSuggestions.Suggestion(now.recommended, now.belowBar)
        val next = ChatSuggestions.refine(current, counted, settled, pinned = now.ticked)
        if (next == current) return
        // Shown only: nothing is ticked for the viewer (user, 2026-11-04).
        _state.update { it.copy(recommended = next.ids, belowBar = next.belowBar) }
    }

    fun type(text: String) {
        settled = true
        _state.update { it.copy(query = text.take(MAX_QUERY)) }
    }

    fun scope(scope: ChatPicking.Scope) {
        settled = true
        _state.update { it.copy(scope = scope, folder = null) }
    }

    /**
     * Narrows the list to Telegram folder [id] (HX29), in place of a scope; null goes back to
     * every chat. Kept for the opening only: nothing about it is stored, so a folder deleted
     * since simply is not there next time.
     */
    fun folder(id: Int?) {
        // The folder chips are greyed while "All chats" is on (HX86), whatever asks.
        if (id != null && _state.value.allChats) return
        settled = true
        _state.update { it.copy(folder = id, scope = ChatPicking.Scope.All) }
    }

    /** Whether the choice is being written or is written: the selection is then fixed, so what is saved is what the foot showed. */
    private val locked: Boolean get() = _state.value.saving || _state.value.saved

    fun toggle(chatId: Long) {
        settled = true
        // Disabled while "All chats" is on: the kept ticks are not edited behind the switch.
        if (!locked && !_state.value.allChats) _state.update { it.copy(ticked = ChatPicking.toggle(it.ticked, chatId)) }
    }

    /**
     * Stars or unstars a chat from the picker itself (HX30), the same store the Favourites tab
     * reads and the same toggle the chat menu elsewhere calls: no second notion of starred lives
     * here. A chat can be starred whether or not it ends up ticked, and the other way round.
     */
    fun toggleStar(chatId: Long) {
        settled = true
        val store = settings ?: return
        viewModelScope.launch { store.toggleFavorite(chatId) }
    }

    /** Select all, or Clear, over the rows on screen. [visible] is [ChatPicking.visible]. */
    fun selectAll(visible: Set<Long>) {
        settled = true
        if (!locked && !_state.value.allChats) _state.update { it.copy(ticked = ChatPicking.selectAll(it.ticked, visible)) }
    }

    fun clear(visible: Set<Long>) {
        settled = true
        if (!locked && !_state.value.allChats) _state.update { it.copy(ticked = ChatPicking.clear(it.ticked, visible)) }
    }

    /** How many chats Select all would add, for the word before a very large one. */
    fun adding(visible: Set<Long>): Int = visible.count { it !in _state.value.ticked }

    /** Whether leaving now would lose a change, which is the only time Back asks. */
    val changed: Boolean get() = opened?.let { it != _state.value.ticked || openedAll != _state.value.allChats } ?: false

    /**
     * Writes the choice. The names are kept with the ids, so a chat later left or deleted still
     * has a row to remove. Pressed twice, it runs once.
     *
     * A write that fails (a full disk, a store that cannot be opened) ends the saving and says
     * what happened ([State.error]), so the screen offers Try again rather than sitting on a
     * press that never finishes, which in the mandatory chat choice would trap the viewer.
     * Nothing is tried again on its own.
     */
    fun done(chats: List<ChatSummary>) {
        if (write == null && writeChoice == null) return
        if (locked) return
        // Taken now, with the press: a tick that lands while the write runs is not part of it, and
        // the screen cannot make one (the selection is fixed until the write ends in an error).
        val ticked = _state.value.ticked
        val all = _state.value.allChats
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val byId = chats.associateBy { it.id }
            val known = addedNames.associate { it.id to it.title }
            val chosen = ticked.map { id -> AddedChat(id, byId[id]?.title ?: known[id].orEmpty()) }
            try {
                // "All chats" on keeps the ticks beside the switch; off writes a plain pick (HX86).
                when {
                    all && writeChoice != null -> writeChoice.invoke(AddedChoice(AddedMode.All, chosen))
                    write != null -> write.invoke(chosen)
                    else -> writeChoice?.invoke(AddedChoice(AddedMode.Picked, chosen))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(saving = false, error = Failures.humanise(failure)) }
                return@launch
            }
            val added = if (all) 0 else ticked.count { it !in (opened ?: emptySet()) }
            opened = ticked
            openedAll = all
            _state.update { it.copy(saving = false, saved = true, justAdded = added) }
        }
    }

    /** Stops every count. Called when the screen leaves, so nothing runs on behind it. */
    fun stop() {
        headStart?.cancel()
        headStart = null
        background?.cancel()
        background = null
        flush()
        // A head start cut short never takes the band, so nothing is left waiting on it.
        _state.update { it.copy(recommending = false) }
        counting.values.toList().forEach { it.cancel() }
        counting.clear()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        private const val MAX_QUERY = 100

        /** Chats counted at once by the background pass, the same three as the counter's own gate. */
        const val BACKGROUND_AT_ONCE = 3

        /** One at a time on the television: its four weak cores cannot spare more (HX76). */
        const val BACKGROUND_ON_TV = 1
        /** The gap between batches of background counts: each one ranks the band again, so it is not every few hundred ms. */
        private const val BATCH_MS = 2_000L

        /** Chats that could not be counted and are asked once more at the end of the pass. */
        private const val RETRY_MAX = 100
        const val BATCH_ON_TV_MS = 5_000L
        private const val HOLD_POLL_MS = 150L
        private const val ALL_POLL_MS = 1_000L

        /**
         * How long the Recommended band waits for the head start before it is taken from whatever
         * answered: the three seconds AGENTS.md gives any first content, which is also what a
         * viewer will sit through before deciding the screen is broken.
         */
        private const val RECOMMEND_BY_MS = 3_000L

        /** The live counter, or null while there is no signed in session to count over. */
        suspend fun calls(): ChatCountCalls? = Td.awaitConnectedSessionOrNull()?.let { ChatVideoCount.calls(it.client) }
    }
}
