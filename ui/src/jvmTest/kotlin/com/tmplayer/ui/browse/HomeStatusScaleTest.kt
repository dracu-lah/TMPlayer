package com.tmplayer.ui.browse

import com.tmplayer.data.ChatListLoad
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSync
import com.tmplayer.data.WorkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The status line on a large account (WP-93), against [SyntheticAccount]: 3,000 chats, 1,500
 * archived, 200 starred, Telegram's flood waits and channels posting. Home's first content comes
 * within 3 s, every refresh's Syncing ends by itself (its answers or its time box), nothing is left
 * Syncing once the answers are in, and the chat list's sync ends in a status that is not Syncing.
 * Prints what it measured on lines starting "WP93|".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStatusScaleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun report(line: String) = println("WP93| $line")

    @Test
    fun `home on a large account says syncing only while it syncs`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime }, starredCount = 200)
        val model = HomeViewModel(account.homeSource, work = dispatcher, disk = dispatcher, connected = MutableStateFlow(true), clock = { testScheduler.currentTime })
        account.startPosting { account.followed }
        val starred = account.mainList().filter { it.id in account.starred }
        model.fill(starred)
        model.requestRecent()
        var refreshing = true
        backgroundScope.launch {
            while (refreshing) {
                delay(HomeViewModel.AUTO_REFRESH_MS)
                if (refreshing) model.refreshInPlace(background = true)
            }
        }
        var firstAt = -1L
        var syncingSince = -1L
        var longest = 0L
        var runs = 0
        val end = 40 * 60_000L
        while (testScheduler.currentTime < end) {
            advanceTimeBy(500)
            val now = testScheduler.currentTime
            if (firstAt < 0 && model.loaded.value.values.any { it.isNotEmpty() }) firstAt = now
            if (now > end - 10 * 60_000L) refreshing = false
            val syncing = model.status.value is WorkStatus.Syncing
            if (syncing && syncingSince < 0) {
                syncingSince = now
                runs++
            }
            if (!syncing && syncingSince >= 0) {
                longest = maxOf(longest, now - syncingSince)
                syncingSince = -1
            }
        }
        runCurrent()
        report("home first content ${firstAt / 1000.0} s; ${runs} refreshes said Syncing, the longest ${longest / 1000.0} s; status at the end ${model.status.value}; flood waits ${account.floodHits}")
        assertTrue("first content at $firstAt ms", firstAt in 0..3_000)
        assertTrue("refreshes said Syncing: $runs", runs > 0)
        assertTrue("Syncing for $longest ms", longest <= HomeViewModel.SYNC_BOX_MS + 1_000)
        assertTrue("left ${model.status.value}", model.status.value !is WorkStatus.Syncing)
    }

    @Test
    fun `the chat list sync of a large account ends in a status`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        var synced: ChatSync? = null
        var firstAt = -1L
        backgroundScope.launch {
            val first = ChatListLoad.cached(account.listCalls, ChatRepository.CHAT_LIMIT, emptyList(), ChatRepository.ARCHIVE_FIRST).chats
            firstAt = testScheduler.currentTime
            synced = ChatListLoad.sync(account.listCalls, ChatRepository.CHAT_LIMIT, first, ChatRepository.ARCHIVE_LIMIT)
        }
        while (synced == null && testScheduler.currentTime < 10 * 60_000L) advanceTimeBy(1_000)
        val sync = synced
        assertTrue("synced", sync != null)
        val now = testScheduler.currentTime
        val asked = ChatListStatus.afterSync(sync!!, held = true, keptAt = null, asked = true, now = now)
        val quiet = ChatListStatus.afterSync(sync, held = true, keptAt = null, asked = false, now = now)
        report("chat list first answer ${firstAt / 1000.0} s, synced ${now / 1000.0} s with ${sync.chats.size} chats; asked $asked, automatic $quiet")
        assertTrue("first answer at $firstAt ms", firstAt in 0..3_000)
        assertEquals(WorkStatus.Updated(now), asked)
        assertEquals(WorkStatus.Idle, quiet)
    }
}
