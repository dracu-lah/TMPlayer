package com.tmplayer.ui.browse

import com.tmplayer.data.HomeLayout
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Home's rows turned off and Recently added read from chosen chats ([HomeLayout]), against
 * [SyntheticAccount]'s 3,000 chats: a row that is off costs no call, and Select all on every chat
 * stays within Home's budgets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLayoutScaleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.at() = testScheduler.currentTime

    private fun TestScope.model(account: SyntheticAccount) = HomeViewModel(
        account.homeSource, work = dispatcher, disk = dispatcher, connected = MutableStateFlow(true), clock = { testScheduler.currentTime },
    )

    @Test
    fun `every row off asks Telegram for nothing, a refresh included`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val model = model(account)
        // As rememberHomeRows drives it with every row off: no search, no chats.
        model.recentWanted = false
        model.requestRecent()
        model.fill(emptyList())
        advanceTimeBy(5_000)
        model.refreshInPlace()
        model.refreshInPlace(background = true)
        advanceTimeBy(60_000)
        assertEquals("calls ${account.calls}", 0, (account.calls["searchMessages"] ?: 0) + (account.calls["searchChatMessages"] ?: 0))
        assertTrue(model.recent.value == null)
        assertTrue(model.loaded.value.isEmpty())
    }

    @Test
    fun `starred rows off with Recently added off reads no starred chat after a refresh`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val model = model(account)
        val list = account.mainList()
        // First with the starred rows on, then off: what was read before is not asked again.
        model.fill(HomeRows.favouriteChats(list, account.starred))
        advanceTimeBy(30_000)
        val before = account.calls["searchChatMessages"] ?: 0
        assertTrue(before > 0)
        model.recentWanted = false
        model.fill(emptyList(), whole = false)
        model.refreshInPlace()
        advanceTimeBy(60_000)
        assertEquals(before, account.calls["searchChatMessages"] ?: 0)
        assertEquals(0, account.calls["searchMessages"] ?: 0)
    }

    @Test
    fun `turned back on, Recently added is asked for again`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val model = model(account)
        model.recentWanted = false
        model.requestRecent()
        advanceTimeBy(5_000)
        assertTrue(model.recent.value == null)
        model.recentWanted = true
        model.requestRecent()
        advanceTimeBy(30_000)
        assertTrue(model.recent.value != null)
    }

    @Test
    fun `Select all on every chat fills Recently added within 3 s and stays within budget`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val model = model(account)
        val list = account.mainList()
        // Every chat added in the picker: Recently added reads the added chats, bounded.
        val layout = HomeLayout(starredRows = false)
        val chosen = HomeLayout.chosenChats(list, list.mapTo(HashSet()) { it.id })
        assertEquals(HomeLayout.CHOSEN_MAX, chosen.size)
        var firstAt = -1L
        backgroundScope.launch {
            model.requestRecent()
            model.fill(chosen)
            combine(model.loaded, model.recent) { loaded, recent -> loaded to recent }.collect { (loaded, recent) ->
                val rows = HomeRows.build(
                    emptyList(), chosen, loaded, recent, starred = account.starred, autoFill = true, recentMinStarred = 1,
                    layout = layout, recentFrom = chosen.map { it.id },
                )
                if (firstAt < 0 && rows.any { it is HomeRow.Recent && it.loaded && it.entries.isNotEmpty() }) firstAt = at()
                assertTrue(rows.none { it is HomeRow.Chat })
            }
        }
        while (at() < 600_000L) advanceTimeBy(1_000)
        println("HX8| chosen chats (select all): first Recently added at ${firstAt / 1000.0} s, calls ${account.calls}")
        assertTrue("first at $firstAt ms", firstAt in 0..3_000)
        // Only the chats Home reads at once, not fifty, let alone three thousand.
        assertTrue("rows held ${model.loaded.value.size}", model.loaded.value.size <= HomeViewModel.EAGER_CHATS)
    }

    @Test
    fun `forty starred chats in the opposite order, the first row is in within 3 s and nothing more is read for it`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val model = model(account)
        val list = account.mainList()
        val starred = HomeRows.favouriteChats(list, account.starred)
        assertEquals(40, starred.size)
        // The viewer's order: Home's list turned upside down, so the chat at its foot is first.
        val layout = HomeLayout(order = starred.reversed().map { com.tmplayer.data.HomeOrder.chatKey(it.id) })
        var lastByDefault: String? = null
        var firstAt = -1L
        var firstKey: String? = null
        var requested = false
        backgroundScope.launch {
            model.requestRecent()
            model.fill(starred)
            combine(model.loaded, model.recent) { loaded, recent -> loaded to recent }.collect { (loaded, recent) ->
                val rows = HomeRows.build(emptyList(), starred, loaded, recent, starred = account.starred, autoFill = true, recentMinStarred = 1, layout = layout)
                // A chat with no video has no row, so "the last" is the last row Home's own order draws.
                lastByDefault = HomeRows.build(emptyList(), starred, loaded, recent, starred = account.starred, autoFill = true, recentMinStarred = 1)
                    .lastOrNull { it is HomeRow.Chat }?.key
                val first = rows.firstOrNull { it is HomeRow.Chat } as HomeRow.Chat?
                firstKey = first?.key
                // As Home does for a row on screen that has not loaded: ask for it.
                if (first != null && !first.loaded && !requested) {
                    requested = true
                    model.request(first.chatId)
                }
                if (firstAt < 0 && first != null && first.loaded && first.entries.isNotEmpty()) firstAt = at()
            }
        }
        while (at() < 120_000L) advanceTimeBy(1_000)
        println("T4| 40 starred chats in the opposite order: first row at ${firstAt / 1000.0} s, calls ${account.calls}")
        assertEquals(lastByDefault, firstKey)
        assertTrue("first at $firstAt ms", firstAt in 0..3_000)
        // The order moves rows; it asks Telegram for nothing more than Home's own order did.
        assertTrue("rows held ${model.loaded.value.size}", model.loaded.value.size <= HomeViewModel.EAGER_CHATS + 1)
    }
}
