package com.tmplayer.ui.components

import com.tmplayer.data.WorkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one asked Refresh (WP-40): guarded against a second press, holds off in a flood wait, ends in a status. */
class AskedRefreshTest {

    private var now = 1_000_000L
    private var wait = 0
    private val asked = AskedRefresh(floodWait = { wait }, clock = { now })

    @Test
    fun `a press starts the refresh and shows Syncing`() {
        var started = 0
        assertTrue(asked.ask { started++ })
        assertEquals(1, started)
        assertTrue(asked.running)
        assertTrue(asked.status is WorkStatus.Syncing)
    }

    @Test
    fun `a press while one runs is ignored, from the pull, the menu or a key alike`() {
        var started = 0
        asked.ask { started++ }
        assertFalse(asked.ask { started++ })
        assertFalse(asked.ask { started++ })
        assertEquals(1, started)
    }

    @Test
    fun `a finished refresh says Up to date and may be asked again`() {
        var started = 0
        asked.ask { started++ }
        asked.finish(done = true)
        assertFalse(asked.running)
        assertEquals(WorkStatus.Updated(now), asked.status)
        assertTrue(asked.ask { started++ })
        assertEquals(2, started)
    }

    @Test
    fun `a refresh that runs past its time box says it could not update and keeps the page`() {
        asked.ask { }
        asked.finish(done = false)
        val failed = asked.status as WorkStatus.Failed
        assertEquals(now, failed.keptAt)
        assertFalse(asked.running)
    }

    @Test
    fun `a press while offline says so at once instead of updating for a minute`() {
        var online = false
        val offlineAsk = AskedRefresh(floodWait = { 0 }, clock = { now }, connected = { online })
        var started = 0
        assertFalse(offlineAsk.ask { started++ })
        assertEquals(0, started)
        assertFalse(offlineAsk.running)
        val failed = offlineAsk.status as WorkStatus.Failed
        assertTrue("a reason, not an empty message", failed.message.isNotBlank())
        assertEquals(null, failed.keptAt)
        assertTrue(offlineAsk.offlineNote())
        offlineAsk.clearOfflineNote()
        assertEquals(WorkStatus.Idle, offlineAsk.status)
        online = true
        assertTrue(offlineAsk.ask { started++ })
        assertEquals(1, started)
    }

    @Test
    fun `the time box failure names a reason`() {
        asked.ask { }
        asked.finish(done = false)
        assertTrue((asked.status as WorkStatus.Failed).message.isNotBlank())
    }

    @Test
    fun `in a flood wait the refresh holds off and the status counts the seconds down`() {
        wait = 42
        var started = 0
        assertFalse(asked.ask { started++ })
        assertEquals(0, started)
        assertFalse(asked.running)
        assertEquals(WorkStatus.RateLimited(now + 42_000L), asked.status)
        assertEquals(42_000L, asked.waitLeftMs())
        now += 42_000L
        wait = 0
        asked.clearWait()
        assertEquals(WorkStatus.Idle, asked.status)
        assertTrue(asked.ask { started++ })
    }
}
