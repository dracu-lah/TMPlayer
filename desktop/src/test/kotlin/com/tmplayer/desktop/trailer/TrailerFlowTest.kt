package com.tmplayer.desktop.trailer

import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerFailure
import com.tmplayer.online.TrailerPhase
import com.tmplayer.online.TrailerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The desktop's in-app trailer, step by step ([TrailerFlow], private/TRAILERS-PLAN.md T4): the
 * press with each engine status, the consent dialog's three ways out, the download's progress,
 * cancel and failure, Chromium not starting, and the page's messages and the ready timeout through
 * [TrailerRules], every one of which falls back to the external open exactly once.
 */
class TrailerFlowTest {

    private val trailer = MetaTrailer("YE7VzlLtp-4")
    private val dir = File("browser/jcef")
    private val native = CefNative("linux-amd64", 100, 300, "00")
    private val fits = EngineOffer.Fits(native, File("browser"), freeBytes = 10_000_000_000)
    private val noRoom = EngineOffer.NoRoom(native, File("browser"), freeBytes = 10)
    private val none = TrailerScreen.None

    private fun playing(phase: TrailerPhase = TrailerPhase.Loading): TrailerScreen.Playing {
        var screen: TrailerScreen = TrailerFlow.tap(none, trailer, EngineStatus.Installed(dir)).screen
        if (phase != TrailerPhase.Loading) screen = TrailerFlow.message(screen, "ready").screen
        if (phase == TrailerPhase.Playing) screen = TrailerFlow.message(screen, "state:1").screen
        return screen as TrailerScreen.Playing
    }

    @Test
    fun `an installed player opens the sheet and starts Chromium`() {
        val step = TrailerFlow.tap(none, trailer, EngineStatus.Installed(dir))
        assertEquals(TrailerScreen.Playing(trailer, dir), step.screen)
        assertEquals(TrailerEffect.Start(dir), step.effect)
    }

    @Test
    fun `no player yet asks first and downloads nothing`() {
        val step = TrailerFlow.tap(none, trailer, EngineStatus.Missing(fits))
        assertEquals(TrailerScreen.Asking(trailer, fits), step.screen)
        assertEquals(TrailerEffect.None, step.effect)
    }

    @Test
    fun `an unsupported computer and a player that would not start go straight to YouTube`() {
        assertEquals(TrailerEffect.Fallback(trailer, TrailerFallback.Unsupported), TrailerFlow.tap(none, trailer, EngineStatus.Unsupported).effect)
        assertEquals(TrailerEffect.Fallback(trailer, TrailerFallback.StartFailed), TrailerFlow.tap(none, trailer, EngineStatus.StartFailed).effect)
        assertEquals(none, TrailerFlow.tap(none, trailer, EngineStatus.Unsupported).screen)
    }

    @Test
    fun `a second press while something is up is ignored`() {
        val asking = TrailerFlow.tap(none, trailer, EngineStatus.Missing(fits)).screen
        val again = TrailerFlow.tap(asking, MetaTrailer("aaaaaaaaaaa"), EngineStatus.Installed(dir))
        assertEquals(asking, again.screen)
        assertEquals(TrailerEffect.None, again.effect)
    }

    @Test
    fun `Download installs where it fits, and only there`() {
        val asking = TrailerFlow.tap(none, trailer, EngineStatus.Missing(fits)).screen
        val step = TrailerFlow.accept(asking)
        assertEquals(TrailerScreen.Installing(trailer), step.screen)
        assertEquals(TrailerEffect.Install(fits), step.effect)

        val full = TrailerFlow.tap(none, trailer, EngineStatus.Missing(noRoom)).screen
        assertEquals("no Download where it does not fit", TrailerStep(full), TrailerFlow.accept(full))
    }

    @Test
    fun `Watch on YouTube falls back without a line, a dismiss does nothing at all`() {
        val asking = TrailerFlow.tap(none, trailer, EngineStatus.Missing(fits)).screen
        assertEquals(TrailerStep(none, TrailerEffect.Fallback(trailer, TrailerFallback.Declined)), TrailerFlow.decline(asking))
        assertNull(DesktopTrailers.message(TrailerFallback.Declined))
        assertEquals(TrailerStep(none), TrailerFlow.dismiss(asking))
    }

    @Test
    fun `progress shows while installing and is dropped after cancel`() {
        val installing = TrailerFlow.accept(TrailerFlow.tap(none, trailer, EngineStatus.Missing(fits)).screen).screen
        val shown = TrailerFlow.progress(installing, EngineProgress.Downloading(40, 100)).screen
        assertEquals(EngineProgress.Downloading(40, 100), (shown as TrailerScreen.Installing).progress)

        val cancelled = TrailerFlow.cancel(shown)
        assertEquals(TrailerStep(none, TrailerEffect.Fallback(trailer, TrailerFallback.Cancelled)), cancelled)
        assertEquals("a late progress changes nothing", TrailerStep(none), TrailerFlow.progress(none, EngineProgress.Unpacking(1, 2)))
        assertEquals("a late success changes nothing", TrailerStep(none), TrailerFlow.installed(none, dir))
        assertEquals("a late failure says nothing", TrailerStep(none), TrailerFlow.installFailed(none))
    }

    @Test
    fun `an install that ends plays, one that fails opens YouTube`() {
        val installing = TrailerFlow.accept(TrailerFlow.tap(none, trailer, EngineStatus.Missing(fits)).screen).screen
        assertEquals(TrailerStep(TrailerScreen.Playing(trailer, dir), TrailerEffect.Start(dir)), TrailerFlow.installed(installing, dir))
        assertEquals(TrailerStep(none, TrailerEffect.Fallback(trailer, TrailerFallback.DownloadFailed)), TrailerFlow.installFailed(installing))
    }

    @Test
    fun `Chromium not starting falls back`() {
        assertEquals(TrailerStep(none, TrailerEffect.Fallback(trailer, TrailerFallback.StartFailed)), TrailerFlow.startFailed(playing()))
    }

    @Test
    fun `the page's messages move the state as TrailerRules says`() {
        val ready = TrailerFlow.message(playing(), "ready").screen as TrailerScreen.Playing
        assertEquals(TrailerPhase.Ready, ready.state.phase)
        val on = TrailerFlow.message(ready, "state:1").screen as TrailerScreen.Playing
        assertEquals(TrailerPhase.Playing, on.state.phase)
        val paused = TrailerFlow.message(on, "state:2").screen as TrailerScreen.Playing
        assertEquals(TrailerPhase.Paused, paused.state.phase)
        assertEquals("garbage changes nothing", TrailerStep(paused), TrailerFlow.message(paused, "state:99<script>"))
        assertEquals(TrailerStep(paused), TrailerFlow.message(paused, null))
    }

    @Test
    fun `every YouTube error falls back, the ones TrailerRules lists and any other`() {
        for (code in TrailerRules.FALLBACK_ERRORS + listOf(152, 999, -1)) {
            val step = TrailerFlow.message(playing(TrailerPhase.Playing), "error:$code")
            assertEquals("error $code", TrailerStep(none, TrailerEffect.Fallback(trailer, TrailerFallback.Page)), step)
        }
        assertEquals(TrailerStep(none, TrailerEffect.Fallback(trailer, TrailerFallback.Page)), TrailerFlow.loadFailed(playing()))
    }

    @Test
    fun `no ready within the timeout falls back, a ready page is left alone`() {
        assertTrue(TrailerFlow.waiting(playing()))
        assertEquals(TrailerStep(none, TrailerEffect.Fallback(trailer, TrailerFallback.Page)), TrailerFlow.timedOut(playing()))
        val ready = playing(TrailerPhase.Ready)
        assertEquals(TrailerStep(ready), TrailerFlow.timedOut(ready))
        assertEquals(TrailerFailure.TimedOut, TrailerRules.timedOut(playing().state).failure)
    }

    @Test
    fun `keys run the bridge's scripts once the player is ready`() {
        assertNull("nothing before ready", TrailerFlow.script(playing(), TrailerKey.Toggle))
        val ready = playing(TrailerPhase.Playing)
        assertEquals(TrailerEmbed.TOGGLE, TrailerFlow.script(ready, TrailerKey.Toggle))
        assertEquals(TrailerEmbed.seekBy(-10), TrailerFlow.script(ready, TrailerKey.Back))
        assertEquals(TrailerEmbed.seekBy(10), TrailerFlow.script(ready, TrailerKey.Forward))
        assertNull(TrailerFlow.script(ready, TrailerKey.Close))
        assertEquals(TrailerStep(none), TrailerFlow.close(ready))
        assertEquals("Close on nothing is nothing", TrailerStep(none), TrailerFlow.close(none))
    }

    @Test
    fun `each fallback but the viewer's own choice says why`() {
        for (reason in TrailerFallback.entries - TrailerFallback.Declined) {
            assertTrue(reason.name, !DesktopTrailers.message(reason).isNullOrBlank())
        }
    }
}
