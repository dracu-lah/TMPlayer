package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChats
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.ChatCount
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatPicking
import com.tmplayer.data.ChatSnapshot
import com.tmplayer.data.ChatSuggestions
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatVideoCount
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * "Add chats" against [SyntheticAccount]'s 3,000 chats (HX27): the picker opens from the chat
 * list's snapshot, counts the head of the list, recommends from what answered, and counts the
 * rows scrolled to, all through TDLib's flood waits, a chat that fails and a chat that never
 * answers ([SyntheticAccount.CountShape]). Prints what it measured on lines starting "HX27|".
 *
 * The counter, the gate and the added chats are process wide, so every test starts and ends with
 * them cleared, and ends its picker before it ends itself: a count left holding one of the three
 * permits would stop the next test's counts for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPickerScaleTest {

    private val dispatcher = StandardTestDispatcher()
    private val dir: File = Files.createTempDirectory("picker-scale").toFile()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        reset()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        reset()
        dir.deleteRecursively()
    }

    private fun reset() {
        ChatVideoCount.forget()
        ChatGate.set(null)
        AddedChats.set(AddedChoice())
    }

    private fun report(line: String) = println("HX27| $line")

    private fun TestScope.at() = testScheduler.currentTime

    /** Runs [block] on a picker over [account], and ends the picker whatever [block] does, letting its cancelled counts hand their permits back. */
    private suspend fun TestScope.picker(account: SyntheticAccount, block: suspend (AddChatsViewModel) -> Unit) {
        val model = AddChatsViewModel(settings = null, counts = account.chatCounts)
        try {
            block(model)
        } finally {
            model.stop()
            runCurrent()
        }
    }

    /** Virtual milliseconds until [done], at most [limitMs]: how long a screen waits for something. */
    private suspend fun TestScope.until(limitMs: Long, done: () -> Boolean): Long {
        val from = at()
        while (!done() && at() - from < limitMs) advanceTimeBy(50)
        return at() - from
    }

    /** The head start finished: every chat it asked for is in the state, as a figure or as "could not count". */
    private fun AddChatsViewModel.headStartDone() = state.value.counted.size >= ChatPicking.COUNT_AHEAD

    /** The chats asked for in [ids] answered, whatever the answer was. */
    private fun AddChatsViewModel.answered(ids: Collection<Long>) = state.value.counted.keys.containsAll(ids)

    private fun knownFigure(model: AddChatsViewModel, id: Long): Int? = (model.state.value.counted[id] as? ChatCount.Known)?.videos

    /** The snapshot is written and read through the store's own thread, so that wait is real time, as in the chat list tests. */
    private suspend fun <T> inRealTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(3_000) { block() } }

    /**
     * The first screen: what the chat list kept on disk is the picker's rows, and the head of it
     * is counted at once. The disk read is the only real time here (its ceiling is the 3 s the rule
     * allows), the counts are on the virtual clock.
     */
    @Test
    fun `the picker's first rows come from the chat list's snapshot and the first counts within 3 s`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val store = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        store.saveChatSnapshot(account.mainList())
        val opened = System.nanoTime()
        val list = ChatListViewModel(store)
        val shown = inRealTime { list.everything.first { it.isNotEmpty() } }
        val readMs = (System.nanoTime() - opened) / 1_000_000
        val rows = ChatPicking.rows(shown, added = emptySet())
        picker(account) { model ->
            model.start(shown)
            val firstCount = until(10_000) { model.state.value.counted.isNotEmpty() }
            val headStart = until(60_000) { model.headStartDone() }
            report("picker from the snapshot: ${shown.size} chats, ${rows.size} rows after $readMs ms of real time; first count ${firstCount / 1000.0} s, head start answered at ${headStart / 1000.0} s (virtual); calls ${account.calls}")
            assertEquals(minOf(account.mainChats, ChatSnapshot.MAX_ENTRIES), shown.size)
            assertEquals(shown.size, rows.size)
            assertTrue("first count at $firstCount ms", firstCount in 0..3_000)
        }
    }

    /**
     * The recommendation is the picker's best first content, so on an account that counts cleanly
     * it must come with the first seconds, not after the whole list.
     */
    @Test
    fun `the recommendation arrives within 3 s on an account that counts cleanly`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime }, mixedCounts = false)
        picker(account) { model ->
            model.start(account.mainList())
            val took = until(60_000) { model.state.value.recommended.isNotEmpty() }
            report("recommendation on a clean account: ${model.state.value.recommended.size} chats, ${model.state.value.ticked.size} ticked at ${took / 1000.0} s; calls ${account.calls}")
            assertTrue("recommended at $took ms", took in 0..3_000)
        }
    }

    /**
     * Three at a time is what keeps a television stick answering while the picker counts: the
     * most the counter ever had in flight, over the head start and a scroll through every video
     * chat, is the permits it holds and no more.
     */
    @Test
    fun `counts never run more than three at a time`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            val chats = account.mainList()
            model.start(chats)
            until(10 * 60_000) { model.headStartDone() }
            (61L..account.videoChats).forEach(model::count)
            val took = until(10 * 60_000) { model.answered((1L..account.videoChats).toList()) }
            // What the shapes cost with nothing lost: a database call each, and the server for the chats TDLib could not answer (a flood asks twice).
            val wanted = (1L..account.videoChats).sumOf { id ->
                when (account.countShape(id)) {
                    SyntheticAccount.CountShape.Flood -> 3
                    SyntheticAccount.CountShape.Fails, SyntheticAccount.CountShape.ServerOnly -> 2
                    else -> 1
                }
            }
            report("counts in flight: most ${account.maxCountsInFlight} (limit ${ChatVideoCount.AT_ONCE}), server side most ${account.maxInFlight}; ${model.state.value.counted.size} chats counted in ${took / 1000.0} s; requests ${account.countRequests} (a lossless run makes $wanted), flood waits ${account.floodHits}; calls ${account.calls}")
            assertTrue("answered", model.answered((1L..account.videoChats).toList()))
            assertTrue("${account.maxCountsInFlight} counts in flight", account.maxCountsInFlight <= ChatVideoCount.AT_ONCE)
            assertTrue("never in parallel: ${account.maxCountsInFlight}", account.maxCountsInFlight >= 2)
        }
    }

    /**
     * The idle CPU rule (AGENTS.md): a picker nobody touches asks for nothing more. The head start
     * is exactly [ChatPicking.COUNT_AHEAD] chats, each asked of TDLib's database once and of the
     * server only if the database would not say, and after it the account's calls stay where they
     * are. A row the viewer scrolled to is counted, and the moment the picker closes nothing is
     * asked, which is what holds a 1 GB stick off its 150 per cent.
     */
    @Test
    fun `the head start counts sixty chats and then asks for nothing, and a closed picker asks for nothing`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            val chats = account.mainList()
            model.start(chats)
            until(10 * 60_000) { model.headStartDone() }
            // Let the one recommendation and every cancelled-nothing job settle, then watch.
            advanceTimeBy(60_000)
            val head = ChatPicking.headStart(chats)
            assertEquals(ChatPicking.COUNT_AHEAD, head.size)
            assertEquals(head.toSet(), model.state.value.counted.keys)
            val local = account.calls["getChatMessageCountLocal"] ?: 0
            val server = account.calls["getChatMessageCount"] ?: 0
            // Fails and ServerOnly once, Flood twice (the second asks again after its wait), Local never.
            val expectedServer = head.sumOf { id ->
                when (account.countShape(id)) {
                    SyntheticAccount.CountShape.Flood -> 2
                    SyntheticAccount.CountShape.Fails, SyntheticAccount.CountShape.ServerOnly -> 1
                    else -> 0
                }
            }
            val afterHead = account.total()
            val requestsAfterHead = account.countRequests
            advanceTimeBy(60_000)
            report("head start: ${head.size} chats, $local database calls, $server server calls (expected $expectedServer); a quiet minute adds ${account.total() - afterHead} calls")
            assertEquals(ChatPicking.COUNT_AHEAD, local)
            assertEquals(expectedServer, server)
            assertEquals("a minute of nothing", afterHead, account.total())
            assertEquals(requestsAfterHead, account.countRequests)

            // A scroll asks for the rows it reaches, and closing stops it where it is.
            (61L..300L).forEach(model::count)
            advanceTimeBy(2_000)
            val asking = account.countRequests
            assertTrue("the scroll asked", asking > requestsAfterHead)
            model.stop()
            runCurrent()
            val requestsAtStop = account.countRequests
            val countedAtStop = model.state.value.counted.size
            // What TDLib was already sent finishes on its own (a flood wait is waited out inside it);
            // after that, and for the minute that matters, the account is not asked anything.
            advanceTimeBy(3 * 60_000)
            val settled = account.total()
            advanceTimeBy(60_000)
            report("stopped after ${requestsAtStop - requestsAfterHead} scroll requests, $countedAtStop chats counted; calls after a minute closed: ${account.total() - settled} (requests from the counter after stop: ${account.countRequests - requestsAtStop})")
            assertEquals("the counter asked after stop", requestsAtStop, account.countRequests)
            assertEquals("calls grew over a quiet minute", settled, account.total())
            assertTrue("the scroll was not finished when it stopped", model.state.value.counted.size < 240 + ChatPicking.COUNT_AHEAD)
        }
    }

    /**
     * A FLOOD_WAIT_20 is waited out by the call that got it, holding one of the three permits,
     * while the other two carry on: the head start takes one wait, not one per chat or per flood.
     * A fast scroll to the bottom of every video chat is held to Home's budget of 429s.
     */
    @Test
    fun `a flood wait is waited out by the call that got it while the other counts carry on`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            model.start(account.mainList())
            val started = at()
            val head = until(10 * 60_000) { model.headStartDone() }
            val floodedAt = account.floodedAt.getValue(5L)
            val during = account.countAnsweredAt.count { it > floodedAt && it < floodedAt + 20_000 }
            val figure = knownFigure(model, 5L)
            report("flood in the head start: chat 5 flooded at ${(floodedAt - started) / 1000.0} s, head start whole at ${head / 1000.0} s, $during other calls answered while it waited, chat 5 counted $figure (truth ${account.videoCount(5L)})")
            assertEquals(account.videoCount(5L), figure)
            assertTrue("the wait was not waited: $head ms", head >= 20_000)
            // One wait plus the work behind it, nowhere near twenty seconds a chat.
            assertTrue("head start took $head ms", head < 20_000 + 250 + 3_000)
            assertTrue("only $during answers during the wait", during >= 40)

            // The picker scrolled to the bottom of the video chats, the other two floods in it.
            (61L..account.videoChats).forEach(model::count)
            val scroll = until(10 * 60_000) { model.answered((1L..account.videoChats).toList()) }
            report("scroll to chat ${account.videoChats}: ${scroll / 1000.0} s, flood waits ${account.floodHits} (${account.floodWaitedMs / 1000} s waited), 429s ${account.tooManyRequests}, floods answered ${account.floodedAt.keys}; calls ${account.calls}")
            assertTrue("answered", model.answered((1L..account.videoChats).toList()))
            assertTrue("${account.tooManyRequests} 429s", account.tooManyRequests < 20)
            for (id in account.floodCountIds) assertEquals("chat $id", account.videoCount(id), knownFigure(model, id))
            // Two more floods, each one wait, behind a scroll that is itself a few seconds of work.
            assertTrue("scroll took $scroll ms", scroll < 3 * (20_000 + 250) + 60_000)
        }
    }

    /**
     * The television's short list folds the chats counted at no videos, so every chat is counted
     * once in the background (HX76): never more than three at a time, a flood wait held by the call
     * that got it while the others go on, and nothing asked once the picker closes.
     */
    @Test
    fun `the background count stays at three in flight, rides out a flood wait and stops on leave`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            val chats = account.mainList()
            model.start(chats)
            model.countRest(chats)
            until(10 * 60_000) { model.headStartDone() }
            // Well past the head start, and still counting: a long list is not a short wait.
            val pastHead = until(10 * 60_000) { model.state.value.counted.size >= ChatPicking.COUNT_AHEAD + 400 }
            val counted = model.state.value.counted.size
            report("background count: $counted chats after ${pastHead / 1000.0} s, most in flight ${account.maxCountsInFlight}, flood waits ${account.floodHits}")
            assertTrue("counted past the head start: $counted", counted >= ChatPicking.COUNT_AHEAD + 400)
            assertTrue("${account.maxCountsInFlight} counts in flight", account.maxCountsInFlight <= ChatVideoCount.AT_ONCE)
            // Answers land in batches (HX76): one state update per half second at most past the head
            // start (each update re-folds every row), never one per chat.
            val updatesCap = ChatPicking.COUNT_AHEAD + pastHead / 500 + 5
            assertTrue("${model.stateUpdates} state updates for $counted chats (cap $updatesCap)", model.stateUpdates <= updatesCap)
            // Held while the remote jumps: nothing new is asked until it lets go.
            model.hold(true)
            advanceTimeBy(1_000)
            val held = account.countRequests
            advanceTimeBy(5_000)
            assertEquals("a held count asked for more", held, account.countRequests)
            model.hold(false)
            advanceTimeBy(5_000)
            assertTrue("a released count did not go on", account.countRequests > held)
            // Closing stops it where it is.
            model.stop()
            runCurrent()
            advanceTimeBy(3 * 60_000)
            val settled = account.countRequests
            advanceTimeBy(60_000)
            assertEquals("the counter asked after stop", settled, account.countRequests)
        }
    }

    /**
     * "All chats" on (HX86): nothing is recommended, so the background scan of a 3,000 chat account
     * asks Telegram for nothing while it is on, carries on from where it stopped when it is off, and
     * the switch itself costs no call.
     */
    @Test
    fun `the background count asks for nothing while all chats is on and goes on when it is off`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            val chats = account.mainList()
            model.start(chats)
            model.countRest(chats)
            until(10 * 60_000) { model.headStartDone() }
            until(10 * 60_000) { model.state.value.counted.size >= ChatPicking.COUNT_AHEAD + 100 }
            val before = account.countRequests
            model.setAllChats(true, chats, listComplete = true)
            // What was already in flight lands; after that, nothing is asked for a long while.
            advanceTimeBy(30_000)
            val settled = account.countRequests
            advanceTimeBy(5 * 60_000)
            assertEquals("the scan asked while every chat counts", settled, account.countRequests)
            assertTrue("the switch is a state change, not a call: ${settled - before} asked after it", settled - before <= ChatVideoCount.AT_ONCE)
            model.setAllChats(false, chats, listComplete = true)
            advanceTimeBy(30_000)
            assertTrue("the scan did not go on once it was off", account.countRequests > settled)
            report("all chats on: $before asked before, $settled when it settled, ${account.countRequests} after off")
        }
    }

    /** On the television the background count asks for one chat at a time (HX76). */
    @Test
    fun `the television counts one chat at a time in the background`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            val chats = account.mainList()
            model.start(chats)
            until(10 * 60_000) { model.headStartDone() }
            val before = account.maxCountsInFlight
            model.countRest(chats, AddChatsViewModel.BACKGROUND_ON_TV, AddChatsViewModel.BATCH_ON_TV_MS)
            val target = ChatPicking.COUNT_AHEAD + 100
            val elapsed = until(10 * 60_000) { model.state.value.counted.size >= target }
            // One state update per five seconds on the television: each re-folds every row, and half
            // a second kept the Mi TV's main thread at half a core with 4,500 chats (HX76).
            val updatesCap = ChatPicking.COUNT_AHEAD + elapsed / AddChatsViewModel.BATCH_ON_TV_MS + 5
            assertTrue("${model.stateUpdates} state updates in ${elapsed / 1000} s (cap $updatesCap)", model.stateUpdates <= updatesCap)
            assertTrue("counted ${model.state.value.counted.size}", model.state.value.counted.size >= target)
            assertTrue("most in flight ${account.maxCountsInFlight} before $before", account.maxCountsInFlight <= maxOf(before, 1))
            model.stop()
        }
    }

    /**
     * A call that is never answered holds one permit for its three timeouts and then says it could
     * not count; the other counts are not held behind it, and the row is offered again, never
     * cached as empty.
     */
    @Test
    fun `a count that never answers times out alone and is not cached`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            val hung = account.hungCountId
            val started = at()
            model.count(hung)
            // Not chat 5: its own flood wait is twenty seconds, which is not what is measured here.
            val rest = (6L..100L).toList()
            rest.forEach(model::count)
            val others = until(60_000) { model.answered(rest) }
            val stillWaiting = !model.state.value.counted.containsKey(hung)
            until(120_000) { model.state.value.counted.containsKey(hung) }
            val total = at() - started
            report("a count that never answers: ${rest.size} others answered at ${others / 1000.0} s while it waited, it gave up at ${total / 1000.0} s as ${model.state.value.counted[hung]}")
            assertTrue("the others waited behind it: $others ms", others < ChatVideoCount.TIMEOUT_MS)
            assertTrue("it was answered before its timeout", stillWaiting)
            assertEquals(ChatCount.Unknown, model.state.value.counted[hung])
            // One timeout, not three: a call TDLib may still be holding is given up on rather
            // than asked again behind itself. Read off a clock that is looked at every 50 ms.
            assertTrue("gave up at $total ms", total in ChatVideoCount.TIMEOUT_MS..ChatVideoCount.TIMEOUT_MS + 100)
            assertEquals(null, ChatVideoCount.cached(hung, System.currentTimeMillis()))
        }
    }

    /** The ground truth for what the picker recommends from the chats [known] to be counted: 20 videos to qualify, fullest first, the chat listed first among equals. */
    private fun expected(chats: List<ChatSummary>, account: SyntheticAccount, known: (Long) -> Boolean): List<Long> =
        chats.withIndex()
            .filter { (_, chat) -> chat.kind != ChatKind.Saved && known(chat.id) && account.videoCount(chat.id) >= ChatSuggestions.MIN_VIDEOS }
            .sortedWith(compareByDescending<IndexedValue<ChatSummary>> { account.videoCount(it.value.id) }.thenBy { it.index })
            .map { it.value.id }

    /**
     * The recommendation over every chat's real count, which is what the picker would offer if it
     * had counted the whole account: the video chats, fullest first, the first thirty ticked.
     */
    @Test
    fun `recommending over every real count picks thirty of the chats with twenty videos, fullest first`() {
        val account = SyntheticAccount(CoroutineScope(Job()), { 0L })
        val chats = account.mainList()
        val counts = chats.associate { it.id to (ChatCount.Known(account.videoCount(it.id)) as ChatCount) }
        val suggestion = ChatSuggestions.recommend(ChatPicking.counted(chats, counts))
        val want = expected(chats, account) { true }
        report("recommend over all ${chats.size} real counts: ${suggestion.ids.size} chats, first ${suggestion.ids.take(5)}")
        assertEquals(want.take(ChatSuggestions.MAX_BAND), suggestion.ids)
        assertEquals(ChatSuggestions.MAX_BAND, suggestion.ids.size)
        // The three 20,000-video chats lead, and the rest follow in the list's order.
        assertEquals(listOf(1L, 2L, 3L), suggestion.ids.take(3))
        assertTrue(suggestion.ids.all { account.videoCount(it) >= ChatSuggestions.MIN_VIDEOS })
        assertTrue(!suggestion.belowBar)
    }

    /** What the picker really recommends is made from the head start's answers alone, and from the ones that answered. */
    @Test
    fun `the picker recommends thirty of the head start's chats with twenty videos, fullest first, and ticks only Saved Messages`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            val chats = account.mainList()
            model.start(chats)
            val took = until(10 * 60_000) { model.state.value.recommended.isNotEmpty() }
            val head = ChatPicking.headStart(chats).toSet()
            val answered = { id: Long -> id in head && knownFigure(model, id) != null }
            val want = expected(chats, account, answered).take(ChatSuggestions.MAX_BAND)
            val state = model.state.value
            val unknown = state.counted.filterValues { it == ChatCount.Unknown }.keys
            report("recommended ${state.recommended.size} of ${head.size} counted (${unknown.size} could not be counted: ${unknown.sorted()}) at ${took / 1000.0} s; ticked ${state.ticked.size}; first ${state.recommended.take(5)}")
            assertEquals(want, state.recommended)
            assertEquals("only Saved Messages is ticked for a fresh start, nothing from the band", setOf(account.savedId), state.ticked)
            assertEquals("the band is capped", ChatSuggestions.MAX_BAND, want.size)
            // A chat that could not be counted is left out, not written off as empty.
            assertTrue(unknown.none { it in state.recommended })
            for (id in state.recommended) assertTrue("chat $id", account.videoCount(id) >= ChatSuggestions.MIN_VIDEOS)
            assertEquals(listOf(1L, 2L, 3L), state.recommended.take(3))
            // Counts the head start asked for are the figures the account holds, whichever side answered.
            for (id in head) knownFigure(model, id)?.let { assertEquals("chat $id", account.videoCount(id), it) }
        }
    }

    /**
     * A list that rearranges itself under a finger ticks the wrong chat, so once the viewer has
     * touched the list the band's rows stay where they are, and what the later counts find is added
     * at the end.
     */
    @Test
    fun `after the viewer ticks, a late 20000 video channel takes the slot of an outranked row and moves nothing`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime }, bigChats = mapOf(1_500L to 20_000))
        picker(account) { model ->
            val chats = account.mainList()
            model.start(chats)
            until(10 * 60_000) { model.state.value.recommended.isNotEmpty() }
            val taken = model.state.value.recommended
            assertTrue(1_500L !in taken)
            model.toggle(taken.last())
            model.countRest(chats)
            until(15 * 60_000) { 1_500L in model.state.value.recommended }
            val after = model.state.value.recommended
            report("band ${taken.size} before the viewer's tick, ${after.size} after the scan; chat 1500 at ${after.indexOf(1_500L)}")
            // Only the slot of an outranked chat changed hands: no row moved, the ticked one stayed.
            val changed = taken.indices.filter { taken[it] != after[it] }
            assertEquals("same length: the band was full", taken.size, after.size)
            assertTrue("rows changed: $changed", changed.size == 1 && after[changed.single()] == 1_500L)
            assertTrue("the ticked row stayed", taken.last() in after)
            assertTrue(1_500L in after)
        }
    }

    /**
     * T5: a channel of 20,000 videos far down a 3,000 chat account (past the head start, past the
     * first thousand) is recommended, first, while nothing has been touched. Before the scan fed
     * the band it was taken once from the 60 newest chats and never looked at it again.
     */
    @Test
    fun `a channel with 20000 videos far down the account is recommended, and the scan stays in budget`() = runTest(dispatcher, timeout = 10.minutes) {
        val big = 1_500L
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime }, bigChats = mapOf(big to 20_000))
        picker(account) { model ->
            val chats = account.mainList()
            assertEquals(ChatKind.Channel, chats.first { it.id == big }.kind)
            model.start(chats)
            model.countRest(chats)
            val first = until(10 * 60_000) { model.state.value.recommended.isNotEmpty() }
            assertTrue("first band at $first ms", first <= 3_100)
            assertTrue("not in the head start", big !in ChatPicking.headStart(chats))
            val found = until(15 * 60_000) { big in model.state.value.recommended }
            val band = model.state.value.recommended
            report("20,000 video channel at chat $big: first band at ${first / 1000.0} s, found at ${found / 1000.0} s, band ${band.size}, at ${band.indexOf(big)}; most in flight ${account.maxCountsInFlight}; requests ${account.countRequests}; calls ${account.calls}")
            assertTrue("recommended", big in band)
            assertTrue("a channel with 20,000 videos leads the band (with the other two): ${band.take(5)}", big in band.take(4))
            assertTrue("found at $found ms", found < 10 * 60_000)
            assertTrue("${account.maxCountsInFlight} counts in flight", account.maxCountsInFlight <= ChatVideoCount.AT_ONCE)
            // Every chat of the account was asked about once or twice (the server again for a flood or a failure), not more.
            val wholePass = until(20 * 60_000) { model.state.value.counted.size >= chats.size }
            assertTrue("whole account counted in ${wholePass / 1000.0} s", model.state.value.counted.size >= chats.size)
            assertTrue("requests ${account.countRequests} for ${chats.size} chats", account.countRequests <= chats.size * 3)
            // Closed: nothing more is asked.
            model.stop()
            runCurrent()
            advanceTimeBy(3 * 60_000)
            val settled = account.countRequests
            advanceTimeBy(60_000)
            assertEquals("the counter asked after stop", settled, account.countRequests)
        }
    }

    /**
     * The counter keeps one answer per chat and nothing for a chat that failed; the picker's own
     * map holds the chats asked for and no more. Asking again for a chat that is counted costs no
     * call and adds no entry.
     */
    @Test
    fun `memory stays bounded to the chats that were counted`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        picker(account) { model ->
            model.start(account.mainList())
            until(10 * 60_000) { model.headStartDone() }
            (61L..account.videoChats).forEach(model::count)
            until(10 * 60_000) { model.answered((1L..account.videoChats).toList()) }
            val asked = account.videoChats
            val counted = model.state.value.counted
            val unknown = counted.filterValues { it == ChatCount.Unknown }.keys
            val now = System.currentTimeMillis()
            val cached = (1L..account.mainChats + 1_500L).filter { ChatVideoCount.cached(it, now) != null }
            report("memory: $asked chats asked, ${counted.size} in the picker's map, ${cached.size} in the counter's cache, ${unknown.size} could not be counted")
            assertEquals(asked, counted.size)
            assertEquals(counted.filterValues { it is ChatCount.Known }.keys, cached.toSet())
            assertTrue("a failure was cached", unknown.none { ChatVideoCount.cached(it, now) != null })
            assertTrue("a chat nobody asked for was cached", cached.all { it <= asked })

            // The same chats asked again: no call, no new entry. Measured from a quiet account:
            // the last chat to be answered can still have a call of its own in flight, and the
            // question here is about asking again, not about the tail of the first pass.
            until(30_000) { false }
            val calls = account.total()
            val requests = account.countRequests
            repeat(5) { (1L..account.videoChats).forEach(model::count) }
            until(10_000) { false }
            assertEquals(asked, model.state.value.counted.size)
            assertEquals("a chat already counted was asked again", requests, account.countRequests)
            assertEquals(calls, account.total())

        }
    }

    /**
     * Folders as a filter of the picker (HX29), on the account with folders and an archive. The
     * folders' members are read once, behind the picker, never by opening it; each folder then
     * lists in one pass over the account, and taking one folder ticks that folder's chats only.
     */
    @Test
    fun `the folder filter reads the members once, lists a folder in one pass and ticks that folder only`() = runTest(dispatcher, timeout = 10.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime }, warm = true)
        val chats = account.mainList() + account.archivedList()
        picker(account) { model ->
            model.start(chats)
            val before = account.total()
            // Opening the picker and listing every chat reads no folder: the members come only on request.
            val everyChat = ChatPicking.rows(chats, emptySet(), listComplete = true)
            assertEquals(chats.size, everyChat.size)
            assertEquals("listing the chats asked Telegram for something", before, account.total())

            var membership: com.tmplayer.data.FolderMembership? = null
            backgroundScope.launch { membership = com.tmplayer.data.ChatListLoad.membership(account.listCalls, com.tmplayer.data.ChatRepository.FOLDER_LIMIT) }
            val took = until(6 * 3_600_000L) { membership != null }
            val foldered = com.tmplayer.data.ChatListLoad.withFolders(chats, membership!!)
            val folders = account.folders.keys
            val started = System.nanoTime()
            val perFolder = folders.associateWith { id -> ChatPicking.rows(foldered, emptySet(), folder = id) }
            val ms = (System.nanoTime() - started) / 1_000_000
            report("folders: members read at ${took / 1000.0} s (virtual), ${folders.size} folders listed in $ms ms of real time over ${chats.size} chats; calls ${account.calls}")
            // Each folder holds exactly what Telegram's own list for it holds, archived chats included.
            for ((id, rows) in perFolder) {
                val expected = chats.filter { account.folders.getValue(id)(it.id) }.map { it.id }.toSet()
                assertEquals("folder $id", expected, rows.map { it.id }.toSet())
            }
            assertTrue("the archived chat of folder 4 is listed", perFolder.getValue(4).any { it.chat?.isArchived == true })

            // One folder taken whole ticks its chats and no other chat.
            val channels = ChatPicking.visible(perFolder.getValue(1))
            model.folder(1)
            model.selectAll(channels)
            assertEquals(channels, model.state.value.ticked)
            assertEquals(1, model.state.value.folder)
            // Choosing a scope leaves the folder, and the ticks stay.
            model.scope(ChatPicking.Scope.Groups)
            assertEquals(null, model.state.value.folder)
            assertEquals(channels, model.state.value.ticked)
        }
    }
}
