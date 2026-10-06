package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fire OS cannot be emulated, so the detection is tested here with the manufacturer, model and
 * system features a real device reports, faked.
 */
class FireOsTest {

    private val leanback = setOf("android.software.leanback")

    @Test
    fun `the fire tv feature is enough on its own`() {
        assertEquals(FireOs.Kind.Tv, FireOs.detect("Amazon", "AFTKA", leanback + FireOs.FIRE_TV_FEATURE))
        // A Fire TV edition television made by somebody else still declares it.
        assertEquals(FireOs.Kind.Tv, FireOs.detect("Toshiba", "Smart TV", setOf(FireOs.FIRE_TV_FEATURE)))
    }

    @Test
    fun `an amazon AFT model without the feature is still a fire tv`() {
        assertEquals(FireOs.Kind.Tv, FireOs.detect("Amazon", "AFTMM", emptySet()))
        assertEquals(FireOs.Kind.Tv, FireOs.detect(" amazon ", "aftss", emptySet()))
    }

    @Test
    fun `other amazon devices are tablets`() {
        assertEquals(FireOs.Kind.Tablet, FireOs.detect("Amazon", "KFTRWI", emptySet()))
    }

    @Test
    fun `stock android and google tv are not fire os`() {
        assertEquals(FireOs.Kind.None, FireOs.detect("Google", "Chromecast", leanback))
        assertEquals(FireOs.Kind.None, FireOs.detect("Xiaomi", "POCO F5", emptySet()))
        // An AFT-looking model from somebody else is not Amazon's.
        assertEquals(FireOs.Kind.None, FireOs.detect("unknown", "AFT clone", leanback))
    }

    @Test
    fun `fire tv quirks`() {
        val q = RemoteQuirks.of(FireOs.Kind.Tv, tv = true)
        assertTrue(q.menuOpensPlayerMenu)
        assertTrue(q.holdSeekAccelerates)
        assertFalse(q.watchNextSupported)
        // Alexa owns the mic: the keyboard is the way in, recogniser or not.
        assertEquals(VoiceRoute.Keyboard, q.voiceRoute(recognizerInstalled = true))
        assertEquals(VoiceRoute.Keyboard, q.voiceRoute(recognizerInstalled = false))
    }

    @Test
    fun `a fire tablet keeps the phone handling and falls back to the keyboard`() {
        val q = RemoteQuirks.of(FireOs.Kind.Tablet, tv = false)
        assertFalse(q.menuOpensPlayerMenu)
        assertFalse(q.watchNextSupported)
        assertEquals(VoiceRoute.Keyboard, q.voiceRoute(recognizerInstalled = true))
    }

    @Test
    fun `android tv and phones keep their behaviour`() {
        val tv = RemoteQuirks.of(FireOs.Kind.None, tv = true)
        assertFalse(tv.menuOpensPlayerMenu)
        assertFalse(tv.holdSeekAccelerates)
        assertTrue(tv.watchNextSupported)
        assertEquals(VoiceRoute.Recognizer, tv.voiceRoute(recognizerInstalled = true))
        assertEquals(VoiceRoute.None, tv.voiceRoute(recognizerInstalled = false))
        assertFalse(RemoteQuirks.of(FireOs.Kind.None, tv = false).watchNextSupported)
    }

    @Test
    fun `a held seek key scans in growing steps`() {
        val base = 10_000L
        assertEquals(base, RemoteQuirks.holdStepMs(0, base))
        assertEquals(0L, RemoteQuirks.holdStepMs(1, base))
        assertEquals(0L, RemoteQuirks.holdStepMs(3, base))
        assertEquals(base, RemoteQuirks.holdStepMs(4, base))
        assertEquals(base * 3, RemoteQuirks.holdStepMs(40, base))
        assertEquals(base * 6, RemoteQuirks.holdStepMs(100, base))
        assertEquals(0L, RemoteQuirks.holdStepMs(101, base))
    }
}
