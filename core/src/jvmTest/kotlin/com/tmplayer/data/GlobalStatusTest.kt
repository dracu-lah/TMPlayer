package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The corner's line for what the link to Telegram is doing (WP-100): Updating after 2 s and slow
 * after 60 s, a wait Telegram asked for with its seconds, a sync that needs the viewer once per
 * kind per day, and nothing ticking while nothing is shown.
 */
class GlobalStatusTest {

    private fun decide(
        link: TelegramLink = TelegramLink.Ready,
        updatingForMs: Long = 0,
        wait: Int = 0,
        sync: SyncStatus.Kind? = null,
        dismissed: Boolean = false,
    ) = GlobalStatusRules.decide(link, updatingForMs, wait, sync, dismissed)

    @Test
    fun `catching up for under two seconds shows nothing, for two shows Updating, for sixty it is slow`() {
        assertEquals(GlobalLine.Idle, decide(TelegramLink.Updating, updatingForMs = 1_500))
        assertEquals(GlobalLine.Updating, decide(TelegramLink.Updating, updatingForMs = 2_500))
        assertEquals(GlobalLine.Updating, decide(TelegramLink.Updating, updatingForMs = 59_999))
        assertEquals(GlobalLine.SlowAnswer, decide(TelegramLink.Updating, updatingForMs = 61_000))
        // Ready is gone from the corner whatever the clock says.
        assertEquals(GlobalLine.Idle, decide(TelegramLink.Ready, updatingForMs = 90_000))
    }

    @Test
    fun `a wait over ten seconds shows its seconds and counts down, a short one is waited out quietly`() {
        assertEquals(GlobalLine.RateLimited(42), decide(wait = 42))
        assertEquals(GlobalLine.RateLimited(41), decide(wait = 41))
        assertEquals(GlobalLine.Idle, decide(wait = 10))
        assertEquals(GlobalLine.Idle, decide(wait = 0))
        assertEquals(GlobalLine.Idle, decide(wait = -5))
        // Past a week is a garbled answer, not Telegram's.
        assertEquals(GlobalLine.Idle, decide(wait = Int.MAX_VALUE))
    }

    @Test
    fun `a wait beats catching up and catching up beats the sync line`() {
        assertEquals(GlobalLine.RateLimited(30), decide(TelegramLink.Updating, 90_000, wait = 30, sync = SyncStatus.Kind.TooNew))
        assertEquals(GlobalLine.SlowAnswer, decide(TelegramLink.Updating, 90_000, sync = SyncStatus.Kind.TooNew))
        assertEquals(GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew), decide(sync = SyncStatus.Kind.TooNew))
    }

    @Test
    fun `only the syncs that wait for the viewer get a line, and a dismissed one is gone`() {
        for (kind in SyncStatus.Kind.entries) {
            val line = decide(sync = kind)
            if (kind in listOf(SyncStatus.Kind.Unreadable, SyncStatus.Kind.TooNew, SyncStatus.Kind.OtherAccount)) {
                assertEquals(GlobalLine.SyncNeedsYou(kind), line, kind.name)
                assertEquals(GlobalLine.Idle, decide(sync = kind, dismissed = true), kind.name)
            } else {
                assertEquals(GlobalLine.Idle, line, kind.name)
            }
        }
        assertEquals(GlobalLine.Idle, decide(sync = null))
    }

    @Test
    fun `the sync line is once per kind per day through the hint registry, with ids the registry accepts`() {
        val id = GlobalStatusRules.syncHintId(SyncStatus.Kind.Unreadable, "20261124")
        assertEquals("sync_unreadable_20261124", id)
        assertTrue(id.length <= HintRegistry.MAX_ID)
        assertTrue(id != GlobalStatusRules.syncHintId(SyncStatus.Kind.TooNew, "20261124"))
        assertTrue(id != GlobalStatusRules.syncHintId(SyncStatus.Kind.Unreadable, "20261125"))
        // A garbled day is cut to digits, so the id stays one the registry stores.
        val odd = GlobalStatusRules.syncHintId(SyncStatus.Kind.OtherAccount, "2026-11-24 Zz!!")
        assertEquals("sync_account_20261124", odd)
        val stored = HintRegistry.raise(HintState(), id)
        assertTrue(stored.seen(id))
        assertFalse(HintRegistry.raise(HintState(), odd).seen(id))
    }

    @Test
    fun `nothing ticks while nothing is shown, a count down and a catch up do, a slow one stops`() {
        assertFalse(GlobalStatusRules.ticks(GlobalLine.Idle, TelegramLink.Ready))
        assertFalse(GlobalStatusRules.ticks(GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew), TelegramLink.Ready))
        assertTrue(GlobalStatusRules.ticks(GlobalLine.RateLimited(30), TelegramLink.Ready))
        // Updating, whether it is shown yet or not, has two thresholds still to cross.
        assertTrue(GlobalStatusRules.ticks(GlobalLine.Idle, TelegramLink.Updating))
        assertTrue(GlobalStatusRules.ticks(GlobalLine.Updating, TelegramLink.Updating))
        assertFalse(GlobalStatusRules.ticks(GlobalLine.SlowAnswer, TelegramLink.Updating))
    }
}
