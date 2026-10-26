package com.tmplayer.ui.browse

import com.tmplayer.data.ChatListLoad
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeCache
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Home, and the chat list it was drawn with, against [SyntheticAccount]: 3,000 chats, 1,500
 * archived, four folders, 400 chats with 100,000 videos (three with 20,000), 40 starred, sixty
 * channels posting, Telegram's flood waits, TDLib's own waiting and a dropped connection (HX8).
 * Prints what it measured on lines starting "HX8|", and holds Home to its budgets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeScaleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        Dispatchers.resetMain()
        HomeCache.file = null
    }

    private fun report(line: String) = println("HX8| $line")

    private fun TestScope.at() = testScheduler.currentTime

    /** The chat list read as [ChatListViewModel.load] reads it: TDLib's local list first, then a sync, then the folders behind. */
    @Test
    fun `the chat list on a cold database answers before the account is read`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        var firstAt = -1L
        var syncedAt = -1L
        var foldersAt = -1L
        backgroundScope.launch {
            val first = ChatListLoad.cached(account.listCalls, 300, emptyList(), 100)
            firstAt = at()
            report("chat list first answer: ${first.size} chats at ${firstAt / 1000.0} s, calls ${account.calls}")
            ChatListLoad.sync(account.listCalls, 300, first, 100)
            syncedAt = at()
            report("chat list synced at ${syncedAt / 1000.0} s, calls ${account.calls}")
            ChatListLoad.membership(account.listCalls, 300)
            foldersAt = at()
        }
        while (foldersAt < 0 && at() < 6 * 3_600_000L) advanceTimeBy(1_000)
        report("chat list folders read (behind the list) at ${foldersAt / 1000.0} s; calls ${account.calls}; flood waits ${account.floodHits}, ${account.floodWaitedMs / 1000} s waited")
        assertTrue("first answer at $firstAt ms", firstAt in 0..5_000)
        assertTrue("synced at $syncedAt ms", syncedAt in 0..10_000)
    }

    /** Desktop Home as [rememberHomeRows] and the desktop's HomePage drive it. */
    private class Drive(
        val account: SyntheticAccount,
        val model: HomeViewModel,
        /** The chat list once it has come; empty while it has not. */
        val list: MutableStateFlow<List<ChatSummary>>,
    ) {
        var picked: List<ChatSummary> = emptyList()
        var firstAt = -1L
        var doneAt = -1L
        var builds = 0
        var buildNs = 0L
    }

    private fun TestScope.drive(account: SyntheticAccount, model: HomeViewModel, list: MutableStateFlow<List<ChatSummary>>): Drive {
        val d = Drive(account, model, list)
        backgroundScope.launch {
            model.requestRecent()
            combine(list, model.recent, model.ownChats) { chats, recent, own -> Triple(chats, recent, own) }.collect { (chats, recent, own) ->
                if (chats.isEmpty()) model.learnChats(account.starred + recent.orEmpty().map { it.chatId }.distinct().take(24))
                val listed = chats.ifEmpty { own }
                val picked = HomeRows.homeChats(listed, account.starred, recent)
                if (picked != d.picked) {
                    d.picked = picked
                    model.fill(picked, whole = chats.isNotEmpty() && recent != null)
                }
            }
        }
        backgroundScope.launch {
            while (true) {
                delay(HomeViewModel.AUTO_REFRESH_MS)
                model.refreshInPlace(background = true)
            }
        }
        backgroundScope.launch {
            combine(model.loaded, model.recent, model.stalled) { loaded, recent, stalled -> Triple(loaded, recent, stalled) }.collect { (loaded, recent, _) ->
                val started = System.nanoTime()
                val rows = HomeRows.build(emptyList(), d.picked, loaded, recent, starred = account.starred, autoFill = true, recentMinStarred = 1)
                d.buildNs += System.nanoTime() - started
                d.builds++
                if (d.firstAt < 0 && rows.any { (it is HomeRow.Chat && it.loaded) || (it is HomeRow.Recent && it.loaded) }) d.firstAt = at()
                // Done: Recently added and the rows Home asks for up front are in.
                if (d.doneAt < 0 && recent != null && d.picked.isNotEmpty() && d.picked.take(HomeViewModel.EAGER_CHATS).all { it.id in loaded }) d.doneAt = at()
            }
        }
        return d
    }

    @Test
    fun `home fills on a large account within its budget`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val connected = MutableStateFlow(true)
        val model = HomeViewModel(account.homeSource, work = dispatcher, disk = dispatcher, connected = connected, clock = { testScheduler.currentTime })
        account.startPosting { account.followed }
        val d = drive(account, model, MutableStateFlow(account.mainList()))
        // The line drops ten minutes in, for a minute.
        backgroundScope.launch {
            delay(600_000)
            account.offline.value = true
            connected.value = false
            delay(60_000)
            account.offline.value = false
            connected.value = true
        }
        var callsAtDone = emptyMap<String, Int>()
        var floodsAtDone = 0
        var waitedAtDone = 0L
        var stalls = 0
        var wasStalled = false
        while (at() < 3_600_000L) {
            advanceTimeBy(1_000)
            if (model.stalled.value && !wasStalled) stalls++
            wasStalled = model.stalled.value
            if (d.doneAt >= 0 && callsAtDone.isEmpty()) {
                callsAtDone = account.calls.toMap()
                floodsAtDone = account.floodHits
                waitedAtDone = account.floodWaitedMs
            }
        }
        runCurrent()
        val snapshot = HomeCache.Snapshot(model.loaded.value, model.recent.value, model.partial.value, model.art.value, 0, model.ownChats.value)
        val cacheKiB = HomeCache.encode(snapshot).length / 1024
        report("account: ${account.mainChats} chats, ${account.archivedChats} archived, ${account.totalVideos} videos, ${account.starred.size} starred; Home picks ${d.picked.size} chats")
        report("home first row ${d.firstAt / 1000.0} s, done ${d.doneAt / 1000.0} s; calls to done $callsAtDone; flood waits to done $floodsAtDone, ${waitedAtDone / 1000} s")
        report("home after an hour: calls ${account.calls} (${account.total()} in all); flood waits ${account.floodHits}, ${account.floodWaitedMs / 1000} s; 429s ${account.tooManyRequests}; most calls in flight ${account.maxInFlight}; stalls shown $stalls")
        report("home rows held: ${model.loaded.value.size} chats, ${model.loaded.value.values.sumOf { it.size }} videos; cache $cacheKiB KiB; builds ${d.builds}, ${d.buildNs / 1_000_000} ms")
        assertTrue("first row at ${d.firstAt} ms", d.firstAt in 0..3_000)
        assertTrue("done at ${d.doneAt} ms", d.doneAt in 0..30_000)
        val searches = (account.calls["searchChatMessages"] ?: 0) + (account.calls["searchMessages"] ?: 0)
        assertTrue("$searches searches in an hour", searches < 1_500)
        assertTrue("${account.tooManyRequests} 429s in an hour", account.tooManyRequests < 20)
        assertTrue("${account.maxInFlight} calls in flight", account.maxInFlight <= HomeViewModel.PARALLEL * 3 + 2)
        assertTrue("cache $cacheKiB KiB", cacheKiB < 256)
    }

    /** No chat list at all (TDLib still reading a cold database): Home draws from its own chats. */
    @Test
    fun `home does not wait for the chat list`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val model = HomeViewModel(account.homeSource, work = dispatcher, disk = dispatcher, connected = MutableStateFlow(true), clock = { testScheduler.currentTime })
        val d = drive(account, model, MutableStateFlow(emptyList()))
        while (at() < 120_000L) advanceTimeBy(1_000)
        report("home without the chat list: first row ${d.firstAt / 1000.0} s, Home's own chats ${model.ownChats.value.size}, picks ${d.picked.size}, rows held ${model.loaded.value.size}")
        assertTrue("first row at ${d.firstAt} ms", d.firstAt in 0..3_000)
        assertTrue("starred rows", account.starred.take(5).all { it in model.loaded.value })
    }

    /** A second start draws the rows written to the disk at once, before Telegram answers anything. */
    @Test
    fun `a second start draws home from the disk at once`() = runTest(dispatcher, timeout = 5.minutes) {
        HomeCache.file = kotlin.io.path.createTempFile("home", ".json").toFile().also { it.deleteOnExit() }
        HomeCache.clear()
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val first = HomeViewModel(account.homeSource, work = dispatcher, disk = dispatcher, cached = true, connected = MutableStateFlow(true), clock = { testScheduler.currentTime })
        drive(account, first, MutableStateFlow(account.mainList()))
        while (at() < 60_000L) advanceTimeBy(1_000)
        HomeCache.save(HomeCache.Snapshot(first.loaded.value, first.recent.value, first.partial.value, first.art.value, 0, first.ownChats.value))
        val again = HomeViewModel(account.homeSource, work = dispatcher, disk = dispatcher, cached = true, connected = MutableStateFlow(true), clock = { testScheduler.currentTime })
        val rows = HomeRows.build(emptyList(), HomeRows.homeChats(again.ownChats.value, account.starred, again.recent.value), again.loaded.value, again.recent.value, starred = account.starred, autoFill = true, recentMinStarred = 1)
        report("home from the disk: ${rows.size} rows, ${again.ownChats.value.size} chats known, before any call")
        assertTrue(rows.count { it is HomeRow.Chat && it.loaded } >= 5)
    }

    /** Stuck at the start (no connection): a few seconds on Home says so and shows what it has, then fills once the line is back. */
    @Test
    fun `a stuck home says so within seconds and fills once the line is back`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        account.offline.value = true
        val connected = MutableStateFlow(false)
        val model = HomeViewModel(account.homeSource, work = dispatcher, disk = dispatcher, connected = connected, clock = { testScheduler.currentTime })
        val d = drive(account, model, MutableStateFlow(account.mainList()))
        var stalledAt = -1L
        while (at() < 30_000L) {
            advanceTimeBy(500)
            if (stalledAt < 0 && model.stalled.value) stalledAt = at()
            if (at() == 20_000L) {
                account.offline.value = false
                connected.value = true
            }
        }
        report("home with no line for 20 s: stalled shown at ${stalledAt / 1000.0} s, first row ${d.firstAt / 1000.0} s, stalled now ${model.stalled.value}")
        assertTrue("stalled at $stalledAt ms", stalledAt in 3_000..6_000)
        assertTrue("first row at ${d.firstAt} ms", d.firstAt in 20_000..25_000)
        assertTrue(!model.stalled.value)
    }
}
