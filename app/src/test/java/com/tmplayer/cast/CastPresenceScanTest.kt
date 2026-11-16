package com.tmplayer.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FQ8: the phone's search behind the Cast button ([CastPresenceScan]) stops by itself, leaving the
 * screen ends it, the button follows the merged list of both kinds and a cast under way; the rule
 * itself is tested in core (CastPresenceTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastPresenceScanTest {

    private class Fake(override val available: Boolean = true) : CastSupport {
        val list = MutableStateFlow<List<CastDeviceInfo>>(emptyList())
        val state = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
        override val devices: StateFlow<List<CastDeviceInfo>> get() = list
        override val session: StateFlow<CastSessionState> get() = state
        override val remote: StateFlow<RemoteStatus> = MutableStateFlow(RemoteStatus())
        var discovering = 0
        var starts = 0
        override fun startDiscovery() {
            discovering++
            starts++
        }
        override fun stopDiscovery() { discovering-- }
        override fun connect(id: String) = Unit
        override fun endSession() = Unit
        override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seek(positionMs: Long) = Unit
        override fun stopMedia() = Unit
        override fun setVolume(level: Double) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setActiveTracks(ids: List<Long>) = Unit
        override fun setRate(rate: Double) = Unit
    }

    private val tv = CastDeviceInfo("gc:r1", "Bedroom", "Chromecast")
    private val dlnaTv = CastDeviceInfo("dlna:udn:a", "Living room TV", "Samsung", kind = CastKind.Dlna)

    private var now = 1_000_000L
    private val clock = { now }

    private fun scanOf(support: CastSupport, scope: TestScope, memory: CastPresence.Memory = CastPresence.Memory(), canSearch: Boolean = true) =
        CastPresenceScan(support, scope, canSearch = { canSearch }, presence = CastPresence(clock, memory))

    @Test
    fun `the search stops by itself after the scan window`() {
        val scope = TestScope(StandardTestDispatcher())
        val support = Fake()
        val scan = scanOf(support, scope)
        scan.start()
        scope.runCurrent()
        assertEquals(1, support.discovering)
        assertTrue(scan.searching)
        scope.advanceTimeBy(CastPresenceScan.SCAN_MS - 1)
        scope.runCurrent()
        assertEquals(1, support.discovering)
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals("no search left running at idle", 0, support.discovering)
        assertFalse(scan.searching)
        assertFalse(scan.shown.value)
        scan.stop()
        assertEquals("stop after the end is not a second stop", 0, support.discovering)
    }

    @Test
    fun `a TV found during the search shows the button and keeps it after the search`() {
        val scope = TestScope(StandardTestDispatcher())
        val support = Fake()
        val scan = scanOf(support, scope)
        scan.start()
        scope.runCurrent()
        assertFalse(scan.shown.value)
        support.list.value = listOf(dlnaTv)
        scope.runCurrent()
        assertTrue(scan.shown.value)
        scope.advanceTimeBy(CastPresenceScan.SCAN_MS + 1)
        scope.runCurrent()
        assertEquals(0, support.discovering)
        support.list.value = emptyList()
        scope.runCurrent()
        assertTrue(scan.shown.value)
        scan.stop()
    }

    @Test
    fun `leaving the screen ends the search at once, coming back searches again`() {
        val scope = TestScope(StandardTestDispatcher())
        val support = Fake()
        val scan = scanOf(support, scope)
        scan.start()
        scope.runCurrent()
        scan.stop()
        assertEquals(0, support.discovering)
        scope.advanceTimeBy(CastPresenceScan.SCAN_MS * 2)
        scope.runCurrent()
        assertEquals("the timer does not stop it twice", 0, support.discovering)
        scan.start()
        scan.start()
        scope.runCurrent()
        assertEquals("a second start while running is one search", 1, support.discovering)
        assertEquals(2, support.starts)
        scan.stop()
        assertEquals(0, support.discovering)
    }

    @Test
    fun `a cast started elsewhere brings the button, and it stays when the cast ends`() {
        val scope = TestScope(StandardTestDispatcher())
        val support = Fake()
        val scan = scanOf(support, scope)
        scan.start()
        scope.advanceTimeBy(CastPresenceScan.SCAN_MS + 1)
        scope.runCurrent()
        assertFalse(scan.shown.value)
        support.list.value = listOf(tv)
        support.state.value = CastSessionState.Connected("Bedroom", "Chromecast", null)
        scope.runCurrent()
        assertTrue(scan.shown.value)
        support.state.value = CastSessionState.Idle
        scope.runCurrent()
        assertTrue(scan.shown.value)
        scan.stop()
    }

    @Test
    fun `a cast already up when the screen opens shows the button before any search`() {
        val scope = TestScope(StandardTestDispatcher())
        val support = Fake().also { it.state.value = CastSessionState.Connected("Bedroom", "Chromecast", null) }
        assertTrue(scanOf(support, scope).shown.value)
    }

    @Test
    fun `no casting in this build, no search and no button`() {
        val scope = TestScope(StandardTestDispatcher())
        val support = Fake(available = false)
        val scan = scanOf(support, scope)
        scan.start()
        scope.runCurrent()
        assertEquals(0, support.starts)
        assertFalse(scan.shown.value)
        assertFalse(scanOf(NoCastSupport, scope).also { it.start() }.shown.value)
    }

    @Test
    fun `without the local network permission nothing is searched and the picker is left to ask`() {
        val scope = TestScope(StandardTestDispatcher())
        val support = Fake()
        val scan = scanOf(support, scope, canSearch = false)
        scan.start()
        scope.runCurrent()
        assertEquals(0, support.starts)
        assertTrue(scan.shown.value)
    }

    @Test
    fun `the button follows the merged list of both kinds`() {
        val scope = TestScope(StandardTestDispatcher())
        val google = Fake()
        val dlna = Fake()
        val merged = MergedCastSupport(google, dlna, kotlinx.coroutines.Dispatchers.Unconfined)
        val scan = scanOf(merged, scope)
        scan.start()
        scope.runCurrent()
        assertEquals(1, google.discovering)
        assertEquals(1, dlna.discovering)
        assertFalse(scan.shown.value)
        dlna.list.value = listOf(CastDeviceInfo("udn:a", "Living room TV", "Samsung", kind = CastKind.Dlna))
        scope.runCurrent()
        assertTrue(scan.shown.value)
        scope.advanceTimeBy(CastPresenceScan.SCAN_MS + 1)
        scope.runCurrent()
        assertEquals(0, google.discovering)
        assertEquals(0, dlna.discovering)
    }
}
