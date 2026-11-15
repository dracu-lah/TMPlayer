package com.tmplayer.player

import com.tmplayer.data.CrashLoop
import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Native crash isolation for the VLC engine (PR6): the mark, the verdict, and what Automatic does after. */
class EngineCrashesTest {

    private val now = 1_731_650_000_000L
    private val mark = EngineMark(PlayerEngine.Vlc, now - 60_000L, 4_200L)
    private val tanix = DeviceFacts(manufacturer = "Tanix", model = "TX6", device = "tx6", hardware = "sun50iw6p1", sdk = 28)

    @Test
    fun `a mark left by a native crash or a hang is a VLC crash, an outside end is not`() {
        val table = listOf(
            LastExit.NativeCrash to true,
            LastExit.NotResponding to true,
            // Android before 11 cannot say: only a process that never stopped cleanly leaves a mark.
            LastExit.Unknown to true,
            LastExit.JavaCrash to false,
            LastExit.Ended to false,
        )
        for ((exit, crash) in table) assertEquals(crash, EngineCrashes.isVlcCrash(mark, exit, now), exit.name)
    }

    @Test
    fun `no mark, another engine's mark or an old one says nothing`() {
        assertFalse(EngineCrashes.isVlcCrash(null, LastExit.NativeCrash, now))
        assertFalse(EngineCrashes.isVlcCrash(mark.copy(engine = PlayerEngine.ExoPlayer), LastExit.NativeCrash, now))
        assertFalse(EngineCrashes.isVlcCrash(mark.copy(startedAtMs = now - EngineCrashes.STALE_MS - 1), LastExit.NativeCrash, now))
        // A clock that went back: the mark is from the future, so it is not trusted.
        assertFalse(EngineCrashes.isVlcCrash(mark.copy(startedAtMs = now + 60_000L), LastExit.NativeCrash, now))
    }

    @Test
    fun `the mark is written and read back, and garbage reads as none`() {
        assertEquals(mark, EngineMark.decode(mark.encode()))
        for (bad in listOf(null, "", "vlc", "vlc;x;1", "vlc;1;-1", "mpv;1;1", "vlc;0;1", "vlc;1;1;1", "x".repeat(500))) {
            assertNull(EngineMark.decode(bad), "$bad")
        }
    }

    @Test
    fun `only an early crash counts towards the start-up loop`() {
        assertTrue(EngineCrashes.earlyForLoop(mark, CrashLoop.EARLY_MS))
        assertFalse(EngineCrashes.earlyForLoop(mark.copy(sinceStartMs = CrashLoop.EARLY_MS + 1), CrashLoop.EARLY_MS))
    }

    @Test
    fun `one crash and Automatic leaves VLC alone, the viewer's own VLC stays`() {
        val known = EngineInput(EngineChoice.Automatic, tanix, vlcAvailable = true)
        assertEquals(PlayerEngine.Vlc, EngineSelector.choose(known).engine)
        val crashed = known.copy(vlcCrashes = 1)
        val decision = EngineSelector.choose(crashed)
        assertEquals(PlayerEngine.ExoPlayer, decision.engine)
        assertEquals(EngineReason.VlcCrashed, decision.reason)
        // History would have picked VLC too.
        val history = EngineInput(EngineChoice.Automatic, DeviceFacts(model = "Pixel 8"), EngineHistory(exoPlayerFailures = 2), vlcAvailable = true, vlcCrashes = 1)
        assertEquals(EngineReason.VlcCrashed, EngineSelector.choose(history).reason)
        // No switch by itself into VLC after an ExoPlayer failure either.
        val running = EngineDecision(PlayerEngine.ExoPlayer, EngineReason.Default)
        assertNull(EngineSelector.afterFailure(history, running, EngineFailure.Decoder, switches = 0))
        // The viewer's VLC is theirs.
        val mine = known.copy(choice = EngineChoice.Vlc, vlcCrashes = 5)
        assertEquals(PlayerEngine.Vlc, EngineSelector.choose(mine).engine)
        // Without the library the reason is still that there is none.
        assertEquals(EngineReason.VlcUnavailable, EngineSelector.choose(crashed.copy(vlcAvailable = false)).reason)
    }

    @Test
    fun `the viewer who picked VLC is offered ExoPlayer from the second crash, once per crash`() {
        var crashes = VlcCrashes.None.crashed()
        assertFalse(EngineCrashes.offerExoPlayer(EngineChoice.Vlc, crashes))
        crashes = crashes.crashed()
        assertTrue(EngineCrashes.offerExoPlayer(EngineChoice.Vlc, crashes))
        assertFalse(EngineCrashes.offerExoPlayer(EngineChoice.Automatic, crashes))
        assertFalse(EngineCrashes.offerExoPlayer(EngineChoice.ExoPlayer, crashes))
        crashes = crashes.offered()
        assertFalse(EngineCrashes.offerExoPlayer(EngineChoice.Vlc, crashes))
        crashes = crashes.crashed()
        assertTrue(EngineCrashes.offerExoPlayer(EngineChoice.Vlc, crashes))
    }

    @Test
    fun `the crash count is stored and survives garbage`() {
        val crashes = VlcCrashes(3, 2)
        assertEquals(crashes, VlcCrashes.decode(crashes.encode()))
        assertEquals(VlcCrashes.None, VlcCrashes.decode(null))
        assertEquals(VlcCrashes(0, 0), VlcCrashes.decode("x;y"))
        // An offer count above the crash count is cut to it.
        assertEquals(VlcCrashes(1, 1), VlcCrashes.decode("1;9"))
        assertEquals(VlcCrashes.None, VlcCrashes.decode("9".repeat(100)))
        assertEquals(1_000, VlcCrashes(1_000).crashed().count)
    }
}
