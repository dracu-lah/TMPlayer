package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChats
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
 */
class AddChatsViewModel(
    private val settings: SettingsStore? = null,
    private val counts: ChatCountCalls? = null,
    private val write: (suspend (List<AddedChat>) -> Unit)? = settings?.let { store -> store::setAddedChats },
    /** Whether [countRest] counts the whole account in the background; a test of the row counts turns it off. */
    private val countsRest: Boolean = true,
) : ViewModel(com.tmplayer.platform.JobFailures.viewModelScope("AddChats")) {

    /**
     * @param ticked the chats that will be added when Done is pressed.
     * @param counted each chat's videos as they arrive; a chat absent from here is still counting.
     * @param recommended the Recommended band, taken once and then left alone, so no row moves
     *   under a finger as the counts come in.
     * @param recommending true from the first open until the Recommended band is taken (at most
     *   [RECOMMEND_BY_MS]), while the screen holds the band's place with a skeleton so no row
     *   moves under a finger or a click when it lands.
     * @param saving true while the choice is being written, so Done cannot run twice.
     * @param saved true once it is written and the screen may close.
     * @param error what went wrong when the last write failed, shown with Try again, or null.
     */
    data class State(
        val query: String = "",
        val scope: ChatPicking.Scope = ChatPicking.Scope.All,
        val ticked: Set<Long> = emptySet(),
        val counted: Map<Long, ChatCount> = emptyMap(),
        val recommended: List<Long> = emptyList(),
        val belowBar: Boolean = false,
        val recommending: Boolean = false,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What was ticked when the picker opened, so Back knows whether anything changed. */
    private var opened: Set<Long>? = null

    /** The chats the viewer had added, with their names, so one that is gone keeps its row. */
    private var addedNames: List<AddedChat> = emptyList()

    private var headStart: Job? = null
    private var background: Job? = null

    /** True while the remote is jumping: the background count waits so the list is not rebuilt mid jump. */
    @Volatile private var held = false
    private val counting = HashMap<Long, Job>()

    /** Background answers waiting for the next batch: one state update per [BATCH_MS], not one per chat. */
    private val pending = HashMap<Long, ChatCount>()
    /** State updates the count has made, for the scale test's cap on how often the screen is recomposed. */
    var stateUpdates = 0
        private set

    /**
     * Reads the stored choice and ticks what it holds. A viewer who has never answered opens on
     * the recommendation alone, which is what the Recommended band ticks as it arrives; one who
     * has answered opens on their own chats, so opening the picker and pressing Done changes
     * nothing.
     */
    fun open() {
        val store = settings ?: return
        viewModelScope.launch {
            val choice = store.addedChats.first()
            addedNames = choice.chats
            if (choice.mode == AddedMode.Picked) {
                val ids = choice.chats.mapTo(LinkedHashSet()) { it.id }
                opened = ids
                _state.update { it.copy(ticked = ids) }
            } else {
                // Nothing picked yet: nothing is ticked until the counts recommend something, and
                // Back from an untouched picker leaves the account as it was.
                opened = emptySet()
            }
        }
    }

    /**
     * Counts the head of the list once, so the Recommended band can appear within the first
     * seconds of a cold open rather than after the whole account is read.
     */
    fun start(chats: List<ChatSummary>) {
        if (headStart != null || chats.isEmpty() || counts == null) return
        if (_state.value.recommended.isEmpty()) _state.update { it.copy(recommending = true) }
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
     * Counts the rest of the account in the background once the head start is done (HX76), so the
     * television's short list can fold the chats with no videos. At most [BACKGROUND_AT_ONCE] at a
     * time, in list order, each one the counter's own budget (FLOOD_WAIT waited out by the call that
     * got it, the others go on). It stops with [stop], and a chat counted by its row is skipped.
     * The answers are applied in batches every [BATCH_MS] and not while the remote is jumping, since
     * each update re-folds every row (4,500 chats took 1.1 to 1.4 cores of the Mi TV).
     * @param atOnce how many chats are asked at a time; the television passes [BACKGROUND_ON_TV].
     * @param batchMs the gap between batches; the television passes [BATCH_ON_TV_MS], since a batch
     * still re-folds 4,500 rows and 500 ms kept the Mi TV's main thread at half a core.
     */
    fun countRest(chats: List<ChatSummary>, atOnce: Int = BACKGROUND_AT_ONCE, batchMs: Long = BATCH_MS) {
        if (counts == null || !countsRest || background != null || chats.isEmpty()) return
        background = viewModelScope.launch {
            headStart?.join()
            val queue = Channel<Long>(Channel.UNLIMITED)
            for (chat in chats) queue.trySend(chat.id)
            queue.close()
            coroutineScope {
                val flusher = launch {
                    while (true) {
                        delay(batchMs)
                        if (!held) flush()
                    }
                }
                val workers = List(atOnce.coerceAtLeast(1)) {
                    launch {
                        for (id in queue) {
                            while (held) delay(HOLD_POLL_MS)
                            if (_state.value.counted.containsKey(id) || pending.containsKey(id)) continue
                            count(id, batched = true)
                            counting[id]?.join()
                        }
                    }
                }
                workers.joinAll()
                flusher.cancel()
            }
            flush()
        }
    }

    /** Applies the waiting background answers in one state update. */
    private fun flush() {
        if (pending.isEmpty()) return
        val batch = HashMap(pending)
        pending.clear()
        stateUpdates++
        _state.update { it.copy(counted = it.counted + batch) }
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

    /** Asks again for a chat that could not be counted, from its row. */
    fun countAgain(chatId: Long) {
        _state.update { it.copy(counted = it.counted - chatId) }
        count(chatId)
    }

    /**
     * Takes the recommendation, once. Later counts fill the rows' numbers but never re-sort the
     * band: a list that rearranges itself under a finger ticks the wrong chat.
     */
    private fun recommend(chats: List<ChatSummary>) {
        if (_state.value.recommended.isNotEmpty()) return
        val answer = ChatSuggestions.recommend(ChatPicking.counted(chats, _state.value.counted))
        if (answer.ids.isEmpty()) return
        _state.update { now ->
            // Ticked for a viewer who has not chosen before; one who has keeps their own choice,
            // with the recommendation only shown.
            val tick = if (opened.isNullOrEmpty() && now.ticked.isEmpty()) answer.ticked else emptySet()
            now.copy(
                recommended = answer.ids,
                belowBar = answer.belowBar,
                ticked = if (tick.isEmpty()) now.ticked else now.ticked + tick,
            )
        }
    }

    fun type(text: String) = _state.update { it.copy(query = text.take(MAX_QUERY)) }

    fun scope(scope: ChatPicking.Scope) = _state.update { it.copy(scope = scope) }

    fun toggle(chatId: Long) = _state.update { it.copy(ticked = ChatPicking.toggle(it.ticked, chatId)) }

    /**
     * Stars or unstars a chat from the picker itself (HX30), the same store the Favourites tab
     * reads and the same toggle the chat menu elsewhere calls: no second notion of starred lives
     * here. A chat can be starred whether or not it ends up ticked, and the other way round.
     */
    fun toggleStar(chatId: Long) {
        val store = settings ?: return
        viewModelScope.launch { store.toggleFavorite(chatId) }
    }

    /** Select all, or Clear, over the rows on screen. [visible] is [ChatPicking.visible]. */
    fun selectAll(visible: Set<Long>) = _state.update { it.copy(ticked = ChatPicking.selectAll(it.ticked, visible)) }

    fun clear(visible: Set<Long>) = _state.update { it.copy(ticked = ChatPicking.clear(it.ticked, visible)) }

    /** How many chats Select all would add, for the word before a very large one. */
    fun adding(visible: Set<Long>): Int = visible.count { it !in _state.value.ticked }

    /** Whether leaving now would lose a change, which is the only time Back asks. */
    val changed: Boolean get() = opened?.let { it != _state.value.ticked } ?: false

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
        val save = write ?: return
        if (_state.value.saving || _state.value.saved) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val ticked = _state.value.ticked
            val byId = chats.associateBy { it.id }
            val known = addedNames.associate { it.id to it.title }
            val chosen = ticked.map { id -> AddedChat(id, byId[id]?.title ?: known[id].orEmpty()) }
            try {
                save(chosen)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(saving = false, error = Failures.humanise(failure)) }
                return@launch
            }
            opened = ticked
            _state.update { it.copy(saving = false, saved = true) }
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
        private const val BATCH_MS = 500L
        const val BATCH_ON_TV_MS = 5_000L
        private const val HOLD_POLL_MS = 150L

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
