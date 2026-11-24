package com.tmplayer.desktop.os

import org.freedesktop.dbus.DBusPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MprisMappingTest {

    private fun np(
        track: Long = 1,
        title: String = "Episode 3",
        durationMs: Long = 1_440_000,
        positionMs: Long = 60_000,
        playing: Boolean = true,
        art: String? = null,
        at: Long = 0,
        rate: Float = 1f,
    ) = NowPlaying(track, title, durationMs, positionMs, playing, art, canGoNext = true, canGoPrevious = false, atNanos = at, rate = rate)

    @Test
    fun `Rate is the playback speed, with a range that always holds it`() {
        assertEquals(1.0, MprisMapping.rate(null), 0.0)
        assertEquals(1.5, MprisMapping.rate(np(rate = 1.5f)), 0.0)
        assertEquals(1.0, MprisMapping.rate(np(rate = Float.NaN)), 0.0)
        assertEquals(1.0, MprisMapping.rate(np(rate = 0f)), 0.0)
        val fast = MprisMapping.playerProperties(np(rate = 8f))
        assertEquals(8.0, fast["Rate"])
        assertEquals(8.0, fast["MaximumRate"])
        assertEquals(0.25, MprisMapping.playerProperties(np())["MinimumRate"])
    }

    @Test
    fun `a new speed is a changed property and the position carries on at the new pace`() {
        assertEquals(setOf("Rate"), MprisMapping.changed(np(), np(rate = 2f)).keys)
        // Two seconds of the clock at 2x is four seconds of the video.
        assertEquals(64_000L, np(rate = 2f, at = 0).positionAt(2_000_000_000L))
        assertFalse(np(rate = 2f).seekedTo(np(rate = 2f, positionMs = 64_000, at = 2_000_000_000L)))
    }

    @Test
    fun `metadata carries track id, title and length in microseconds`() {
        val m = MprisMapping.metadata(np(art = "file:///tmp/thumb.jpg"))
        assertEquals(MprisMapping.trackPath(1), m["mpris:trackid"])
        assertEquals("Episode 3", m["xesam:title"])
        assertEquals(1_440_000_000L, m["mpris:length"])
        assertEquals("file:///tmp/thumb.jpg", m["mpris:artUrl"])
    }

    @Test
    fun `unknown length and no art are left out, and nothing playing is NoTrack`() {
        val m = MprisMapping.metadata(np(durationMs = 0))
        assertFalse("mpris:length" in m)
        assertFalse("mpris:artUrl" in m)
        assertEquals(mapOf("mpris:trackid" to DBusPath(MprisMapping.NO_TRACK)), MprisMapping.metadata(null))
    }

    @Test
    fun `playback status follows playing, paused and nothing`() {
        assertEquals("Playing", MprisMapping.playbackStatus(np(playing = true)))
        assertEquals("Paused", MprisMapping.playbackStatus(np(playing = false)))
        assertEquals("Stopped", MprisMapping.playbackStatus(null))
    }

    @Test
    fun `variants get the signatures the spec names`() {
        val v = MprisMapping.variants(MprisMapping.playerProperties(np()))
        assertEquals("s", v.getValue("PlaybackStatus").sig)
        assertEquals("d", v.getValue("Rate").sig)
        assertEquals("a{sv}", v.getValue("Metadata").sig)
        assertEquals("b", v.getValue("CanSeek").sig)
        val meta = v.getValue("Metadata").value as Map<*, *>
        assertEquals("o", (meta["mpris:trackid"] as org.freedesktop.dbus.types.Variant<*>).sig)
        assertEquals("x", (meta["mpris:length"] as org.freedesktop.dbus.types.Variant<*>).sig)
        assertEquals("as", MprisMapping.variant(emptyList<String>()).sig)
    }

    @Test
    fun `only what changed is signalled, and position never is`() {
        val before = np(positionMs = 60_000, at = 0)
        val after = before.copy(positionMs = 61_000, atNanos = 1_000_000_000)
        assertEquals(emptyMap<String, Any>(), MprisMapping.changed(before, after))

        val paused = after.copy(playing = false)
        assertEquals(mapOf("PlaybackStatus" to "Paused"), MprisMapping.changed(after, paused))

        val next = np(track = 2, title = "Episode 4")
        assertEquals(setOf("Metadata"), MprisMapping.changed(before, next).keys)
        assertTrue("CanPlay" in MprisMapping.changed(null, before))
    }

    @Test
    fun `position runs on while playing and stops at the end`() {
        val p = np(positionMs = 60_000, at = 0)
        assertEquals(62_500_000L, MprisMapping.positionUs(p, 2_500_000_000))
        assertEquals(60_000_000L, MprisMapping.positionUs(p.copy(playing = false), 2_500_000_000))
        assertEquals(1_440_000L, p.positionAt(10_000_000_000_000))
    }

    @Test
    fun `a jump steady playback cannot explain is a seek`() {
        val p = np(positionMs = 60_000, at = 0)
        assertFalse(p.seekedTo(p.copy(positionMs = 61_000, atNanos = 1_000_000_000)))
        assertTrue(p.seekedTo(p.copy(positionMs = 70_000, atNanos = 1_000_000_000)))
        assertTrue(p.seekedTo(p.copy(positionMs = 50_000, atNanos = 1_000_000_000)))
        // A new video starting at zero is a track change, not a seek.
        assertFalse(p.seekedTo(np(track = 2, positionMs = 0, at = 1_000_000_000)))
    }
}
