package com.tmplayer.desktop.player

import com.tmplayer.i18n.L
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** libmpv that will not load ends in the status sheet, not in a dead window (HX36). */
class VideoEngineFailureTest {

    private val policy = UnsatisfiedLinkError("C:\\Users\\a\\AppData\\Local\\TMPlayer\\native\\libmpv-2.dll: An Application Control policy has blocked this file.")

    @Test
    fun aPolicyBlockOnWindowsSaysWindowsBlockedIt() {
        assertEquals(L.playerEngineBlocked, VideoEngineFailure.describe(policy, windows = true))
        assertTrue(L.playerEngineBlocked.startsWith("Windows blocked the video engine"))
    }

    @Test
    fun theLaterTriesAreTheSameFailure() {
        // After the first failure the class is dead, and every try is a NoClassDefFoundError that carries it.
        val later = NoClassDefFoundError("Could not initialize class org.openani.mediamp.mpv.MPVHandle").apply { initCause(policy) }
        assertTrue(VideoEngineFailure.isBlocked(later))
    }

    @Test
    fun otherLoadFailuresOrOtherSystemsGetTheGeneralLine() {
        val missing = UnsatisfiedLinkError("libmpv.so.2: cannot open shared object file")
        assertFalse(VideoEngineFailure.isBlocked(missing))
        assertEquals(L.playerEngineMissing, VideoEngineFailure.describe(missing, windows = true))
        assertEquals(L.playerEngineMissing, VideoEngineFailure.describe(policy, windows = false))
        assertEquals(L.playerEngineMissing, VideoEngineFailure.describe(null, windows = true))
    }

    @Test
    fun aCauseThatLoopsBackDoesNotHangTheCheck() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(VideoEngineFailure.isBlocked(a))
    }

    @Test
    fun theStandInEngineReportsTheReasonAndIgnoresEverything() = runBlocking<Unit> {
        val engine = NoEngine("blocked")
        engine.play()
        engine.seekTo(10)
        engine.close()
        assertEquals("blocked", engine.state.first().error)
        assertFalse(engine.state.first().opened)
    }
}
