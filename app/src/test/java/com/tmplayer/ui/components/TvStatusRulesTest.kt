package com.tmplayer.ui.components

import com.tmplayer.data.GlobalLine
import com.tmplayer.data.SyncStatus
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The television's one status ([TvStatusRules]): the short words of every kind of the global status and of
 * a page's work, the long sentence kept as the description, and the priority needs you, sync stopped, offline,
 * the connection, a wait, updating.
 */
class TvStatusRulesTest {

    private val m = L.messages
    private val now = 1_800_000_000_000L
    private val none = ShownWork.None

    private fun pick(input: TvStatusInput, work: ShownWork = none) = TvStatusRules.pick(m, input, work, now)

    @Test
    fun `every kind says a short phrase and keeps the long sentence as its description`() {
        val row = L.syncplaceSwitch
        val cases = listOf(
            TvStatusInput(line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)) to ("Sync stopped" to L.stateSyncStoppedTv(name = row)),
            TvStatusInput(line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew)) to ("Sync needs you" to L.stateSyncNeedsYouTv(name = row)),
            TvStatusInput(line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Unreadable)) to ("Sync needs you" to L.stateSyncNeedsYouTv(name = row)),
            TvStatusInput(line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.OtherAccount)) to ("Sync needs you" to L.stateSyncNeedsYouTv(name = row)),
            TvStatusInput(line = GlobalLine.RateLimited(42)) to ("Retry in 42s" to L.stateRateLimited(seconds = 42)),
            TvStatusInput(line = GlobalLine.Updating) to ("Updating" to L.stateUpdating),
            TvStatusInput(line = GlobalLine.SlowAnswer) to ("Slow answer" to L.commonSlowAnswer),
            TvStatusInput(connection = ConnectionNotice.Reconnecting) to ("Reconnecting" to L.stateReconnecting),
            TvStatusInput(connection = ConnectionNotice.Connecting) to ("Connecting" to L.stateConnectingShort),
        )
        for ((input, expected) in cases) {
            val say = pick(input)!!
            assertEquals(expected.first, say.words)
            assertEquals(expected.second, say.description)
            assertFalse("no ellipsis in '${say.words}'", say.words.contains('…'))
            assertTrue("'${say.words}' is short", say.words.length <= 16)
        }
    }

    @Test
    fun `a wait is said in seconds, minutes or hours, the shortest true wording`() {
        assertEquals("Retry in 1s", TvStatusRules.waitWords(m, 0))
        assertEquals("Retry in 119s", TvStatusRules.waitWords(m, 119))
        assertEquals("Retry in 2m", TvStatusRules.waitWords(m, 120))
        assertEquals("Retry in 3m", TvStatusRules.waitWords(m, 121))
        assertEquals("Retry in 99m", TvStatusRules.waitWords(m, 5_939))
        assertEquals("Retry in 2h", TvStatusRules.waitWords(m, 5_940))
        assertEquals("Retry in 2h", TvStatusRules.waitWords(m, 7_200))
        assertEquals("Retry in 99h", TvStatusRules.waitWords(m, 99 * 3600))
        assertEquals("Retry later", TvStatusRules.waitWords(m, 99 * 3600 + 1))
        assertEquals("Retry later", TvStatusRules.waitWords(m, Int.MAX_VALUE))
    }

    @Test
    fun `a page's work says its short words and a page's wait counts down`() {
        assertEquals("Updating", pick(TvStatusInput(), ShownWork(WorkStatus.Syncing()))!!.words)
        assertEquals("Loading", pick(TvStatusInput(), ShownWork(WorkStatus.Loading()))!!.words)
        assertEquals("Up to date", pick(TvStatusInput(), ShownWork(WorkStatus.Updated(now)))!!.words)
        assertFalse(pick(TvStatusInput(), ShownWork(WorkStatus.Updated(now)))!!.spinner)
        assertEquals("Update failed", pick(TvStatusInput(), ShownWork(WorkStatus.Failed("Telegram did not answer in time.")))!!.words)
        assertEquals("Retry in 30s", pick(TvStatusInput(), ShownWork(WorkStatus.RateLimited(now + 30_000)))!!.words)
        assertEquals("Slow answer", pick(TvStatusInput(), ShownWork(WorkStatus.Syncing(), slow = true))!!.words)
        assertNull("nothing to say, nothing drawn", pick(TvStatusInput()))
    }

    @Test
    fun `priority is needs you, sync stopped, offline, the connection, a wait, then updating`() {
        val everything = TvStatusInput(
            line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew),
            work = PageWork(WorkStatus.Syncing(), true),
            connection = ConnectionNotice.Offline,
        )
        val work = ShownWork(WorkStatus.Syncing())
        assertEquals(TvStatusSay.Kind.NeedsYou, pick(everything, work)!!.kind)
        assertEquals(TvStatusSay.Kind.SyncStopped, pick(everything.copy(line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)), work)!!.kind)
        // Offline: the offline card says it, so nothing is drawn and nothing lower shows.
        val offline = pick(everything.copy(line = GlobalLine.Updating), work)!!
        assertEquals(TvStatusSay.Kind.Offline, offline.kind)
        assertFalse(offline.drawn)
        assertEquals(TvStatusSay.Kind.Connection, pick(everything.copy(line = GlobalLine.RateLimited(30), connection = ConnectionNotice.Reconnecting), work)!!.kind)
        assertEquals(TvStatusSay.Kind.RateLimited, pick(everything.copy(line = GlobalLine.RateLimited(30), connection = ConnectionNotice.Hidden), work)!!.kind)
        assertEquals("Retry in 30s", pick(everything.copy(line = GlobalLine.RateLimited(30), connection = ConnectionNotice.Hidden), work)!!.words)
        assertEquals(TvStatusSay.Kind.Updating, pick(everything.copy(line = GlobalLine.Updating, connection = ConnectionNotice.Hidden), work)!!.kind)
    }

    @Test
    fun `a standing sync status is left out once it no longer stands, every transient status keeps its place`() {
        val stopped = TvStatusInput(line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed), standing = false)
        assertNull("folded: nothing in the bar", pick(stopped))
        assertNull(pick(stopped.copy(line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew))))
        assertEquals("standing: the words", "Sync stopped", pick(stopped.copy(standing = true))!!.words)
        // Under the sync, a lower status shows: the sync no longer holds it back.
        val work = ShownWork(WorkStatus.Syncing())
        assertEquals("Updating", pick(stopped, work)!!.words)
        assertEquals("Reconnecting", pick(stopped.copy(connection = ConnectionNotice.Reconnecting), work)!!.words)
        assertEquals(TvStatusSay.Kind.Offline, pick(stopped.copy(connection = ConnectionNotice.Offline), work)!!.kind)
        assertEquals("Retry in 30s", pick(TvStatusInput(line = GlobalLine.RateLimited(30), standing = false))!!.words)
        assertEquals("Updating", pick(TvStatusInput(line = GlobalLine.Updating, standing = false))!!.words)
    }

    @Test
    fun `the account sheet's sentence names the sync switch and only a standing sync has one`() {
        val row = L.syncplaceSwitch
        assertEquals(L.stateSyncStoppedTv(name = row), TvStatusRules.standingSentence(m, GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)))
        assertEquals("Sync stopped. Turn it on again in Settings: Sync across my devices.", TvStatusRules.standingSentence(m, GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)))
        assertEquals(L.stateSyncNeedsYouTv(name = row), TvStatusRules.standingSentence(m, GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew)))
        assertNull(TvStatusRules.standingSentence(m, GlobalLine.Idle))
        assertNull(TvStatusRules.standingSentence(m, GlobalLine.Updating))
        assertEquals("about 8 seconds", 8_000L, TvStatusRules.STANDING_MS)
    }

    @Test
    fun `a page's failure is told only when the viewer asked for it and only for a few seconds, a partial answer never`() {
        val failed = ShownWork(WorkStatus.Failed("x"))
        assertTrue(TvStatusRules.workShown(failed, asked = true, failureOver = false, now = now).visible)
        assertFalse(TvStatusRules.workShown(failed, asked = false, failureOver = false, now = now).visible)
        assertFalse(TvStatusRules.workShown(failed, asked = true, failureOver = true, now = now).visible)
        assertFalse(TvStatusRules.workShown(ShownWork(WorkStatus.Partial(1, 1)), asked = true, failureOver = false, now = now).visible)
        assertFalse("words not yet due", TvStatusRules.workShown(ShownWork(WorkStatus.Syncing(), labelled = false), true, false, now).visible)
        assertFalse("a wait that is over leaves", TvStatusRules.workShown(ShownWork(WorkStatus.RateLimited(now - 1)), false, false, now).visible)
    }
}
