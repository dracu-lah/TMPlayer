package com.tmplayer.desktop.os

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmtcMappingTest {

    @Test
    fun guidBytesFollowTheStructLayout() {
        // Data1, Data2 and Data3 little endian; Data4 as written.
        val bytes = SmtcMapping.guidBytes("{00112233-4455-6677-8899-aabbccddeeff}")
        val expected = byteArrayOf(
            0x33, 0x22, 0x11, 0x00, 0x55, 0x44, 0x77, 0x66,
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun iunknownHasItsWellKnownBytes() {
        val bytes = SmtcMapping.guidBytes(SmtcMapping.IUNKNOWN_IID)
        assertArrayEquals(ByteArray(8) + byteArrayOf(0xc0.toByte(), 0, 0, 0, 0, 0, 0, 0x46), bytes)
    }

    @Test
    fun guidTextRoundTrips() {
        for (iid in listOf(
            SmtcMapping.INTEROP_IID, SmtcMapping.SMTC_IID, SmtcMapping.BUTTON_ARGS_IID,
            SmtcMapping.DISPLAY_UPDATER_IID, SmtcMapping.VIDEO_PROPERTIES_IID,
            SmtcMapping.BUTTON_HANDLER_IID, SmtcMapping.IAGILE_OBJECT_IID,
        )) {
            assertEquals(iid, SmtcMapping.guidText(SmtcMapping.guidBytes(iid)))
            assertEquals(iid, SmtcMapping.guidText(SmtcMapping.guidBytes("{${iid.uppercase()}}")))
        }
    }

    @Test
    fun malformedGuidsAreRefused() {
        for (bad in listOf("", "not a guid", "0011223-4455-6677-8899-aabbccddeeff", "00112233-4455-6677-8899-aabbccddeefg", "00112233445566778899aabbccddeeff")) {
            assertTrue(bad, runCatching { SmtcMapping.guidBytes(bad) }.isFailure)
        }
    }

    /**
     * The handler's IID is derived by WinRT from the TypedEventHandler IID, the runtime class's
     * default interface and the event args' interface, so this one check holds all four constants
     * to each other: a typo in any of them would change the result.
     */
    @Test
    fun buttonHandlerIidIsTheWinRtDerivation() {
        assertEquals(SmtcMapping.BUTTON_HANDLER_IID, SmtcMapping.pinterfaceIid(SmtcMapping.BUTTON_HANDLER_SIGNATURE))
    }

    @Test
    fun positionHandlerIidIsTheWinRtDerivation() {
        assertEquals(SmtcMapping.POSITION_HANDLER_IID, SmtcMapping.pinterfaceIid(SmtcMapping.POSITION_HANDLER_SIGNATURE))
    }

    private fun np(track: Long = 1, durationMs: Long = 600_000, positionMs: Long = 0, playing: Boolean = true, at: Long = 0, rate: Float = 1f) =
        NowPlaying(track, "A", durationMs, positionMs, playing, null, canGoNext = false, canGoPrevious = false, atNanos = at, rate = rate)

    @Test
    fun timeSpansAreHundredNanosecondTicks() {
        assertEquals(10_000_000L, SmtcMapping.ticks(1_000))
        assertEquals(0L, SmtcMapping.ticks(-5))
        assertEquals(1_000L, SmtcMapping.millis(10_000_000L))
    }

    @Test
    fun theTimelineIsSentForANewVideoAPauseARateAndASeekOnly() {
        val first = SmtcMapping.timeline(null, np())
        assertEquals(SmtcTimeline(600_000, 0, 1.0), first)
        // Steady playback: Windows moves the position by itself.
        assertNull(SmtcMapping.timeline(np(), np(positionMs = 5_000, at = 5_000_000_000L)))
        assertNotNull(SmtcMapping.timeline(np(), np(positionMs = 5_000, at = 5_000_000_000L, playing = false)))
        assertEquals(1.5, SmtcMapping.timeline(np(), np(rate = 1.5f))!!.rate, 0.0)
        assertEquals(300_000L, SmtcMapping.timeline(np(), np(positionMs = 300_000, at = 1_000_000_000L))!!.positionMs)
        assertNotNull(SmtcMapping.timeline(np(), np(track = 2)))
    }

    @Test
    fun aVideoOfNoKnownLengthHasNoTimelineAndAPositionPastTheEndIsHeld() {
        assertNull(SmtcMapping.timeline(null, np(durationMs = 0)))
        assertEquals(600_000L, SmtcMapping.timeline(null, np(positionMs = 900_000))!!.positionMs)
        assertEquals(1.0, SmtcMapping.timeline(null, np(rate = Float.NaN))!!.rate, 0.0)
    }

    @Test
    fun pinterfaceDerivationMatchesAKnownWinRtIid() {
        // IVector<String>, whose IID {98b9acc1-4b56-532e-ac73-03d5291cca90} is in every SDK.
        assertEquals(
            "98b9acc1-4b56-532e-ac73-03d5291cca90",
            SmtcMapping.pinterfaceIid("pinterface({913337e9-11a1-4345-a3a2-4e7f956e222d};string)"),
        )
    }

    @Test
    fun playbackStatusFollowsTheState() {
        assertEquals(SmtcMapping.STATUS_CLOSED, SmtcMapping.playbackStatus(null))
        assertEquals(SmtcMapping.STATUS_PLAYING, SmtcMapping.playbackStatus(SmtcState("a", true, false, false)))
        assertEquals(SmtcMapping.STATUS_PAUSED, SmtcMapping.playbackStatus(SmtcState("a", false, false, false)))
    }

    @Test
    fun buttonsReachTheirCallbacks() {
        val seen = mutableListOf<String>()
        val callbacks = object : MediaSessionCallbacks {
            override fun onPlay() { seen += "play" }
            override fun onPause() { seen += "pause" }
            override fun onStop() { seen += "stop" }
            override fun onNext() { seen += "next" }
            override fun onPrevious() { seen += "previous" }
        }
        for (b in listOf(
            SmtcMapping.BUTTON_PLAY, SmtcMapping.BUTTON_PAUSE, SmtcMapping.BUTTON_STOP,
            SmtcMapping.BUTTON_NEXT, SmtcMapping.BUTTON_PREVIOUS,
        )) {
            assertTrue(SmtcMapping.dispatch(b, callbacks))
        }
        assertEquals(listOf("play", "pause", "stop", "next", "previous"), seen)
        for (b in listOf(SmtcMapping.BUTTON_RECORD, SmtcMapping.BUTTON_FAST_FORWARD, SmtcMapping.BUTTON_REWIND, 8, 9, -1)) {
            assertFalse(SmtcMapping.dispatch(b, callbacks))
        }
        assertEquals(5, seen.size)
    }
}
