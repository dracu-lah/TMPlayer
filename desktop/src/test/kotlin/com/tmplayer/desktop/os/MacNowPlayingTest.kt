package com.tmplayer.desktop.os

import com.tmplayer.desktop.os.MacNowPlayingMapping.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The macOS Now Playing mapping and session over a fake binding: the native part is checked on a Mac. */
class MacNowPlayingTest {

    private class Fake : MacNowPlayingBinding {
        val shown = mutableListOf<Pair<MacNowPlayingMapping.Info?, Int>>()
        val enabled = mutableMapOf<Command, Boolean>()
        var closed = false
        override fun show(info: MacNowPlayingMapping.Info?, state: Int) {
            shown += info to state
        }
        override fun enable(command: Command, on: Boolean) {
            enabled[command] = on
        }
        override fun close() {
            closed = true
        }
    }

    private var clockNanos = 0L
    private val fake = Fake()
    private val session = MacNowPlayingSession(fake, object : MediaSessionCallbacks {}, clock = { clockNanos })

    private fun np(playing: Boolean = true, duration: Long = 600_000, position: Long = 0, rate: Float = 1f) =
        NowPlaying(1, "Episode", duration, position, playing, null, canGoNext = true, canGoPrevious = false, atNanos = 0, rate = rate)

    @Test
    fun `the info carries the title, length, elapsed time and a rate of zero while paused`() {
        val playing = MacNowPlayingMapping.info(np(position = 90_000, rate = 1.5f))
        assertEquals("Episode", playing.title)
        assertEquals(600.0, playing.durationSec!!, 0.0)
        assertEquals(90.0, playing.elapsedSec, 0.0)
        assertEquals(1.5, playing.rate, 0.0)
        assertEquals(0.0, MacNowPlayingMapping.info(np(playing = false)).rate, 0.0)
        assertEquals(1.5, MacNowPlayingMapping.info(np(playing = false, rate = 1.5f)).defaultRate, 0.0)
    }

    @Test
    fun `an unknown length is left out and a position past the end is held`() {
        assertNull(MacNowPlayingMapping.info(np(duration = 0)).durationSec)
        assertEquals(600.0, MacNowPlayingMapping.info(np(position = 900_000)).elapsedSec, 0.0)
        assertEquals(1.0, MacNowPlayingMapping.info(np(rate = Float.NaN)).rate, 0.0)
    }

    @Test
    fun `the playback state follows playing, paused and nothing`() {
        assertEquals(MacNowPlayingMapping.STATE_PLAYING, MacNowPlayingMapping.playbackState(np()))
        assertEquals(MacNowPlayingMapping.STATE_PAUSED, MacNowPlayingMapping.playbackState(np(playing = false)))
        assertEquals(MacNowPlayingMapping.STATE_STOPPED, MacNowPlayingMapping.playbackState(null))
    }

    @Test
    fun `commands reach their callbacks and a scrubber position becomes milliseconds`() {
        val seen = mutableListOf<String>()
        val callbacks = object : MediaSessionCallbacks {
            override fun onPlay() { seen += "play" }
            override fun onPause() { seen += "pause" }
            override fun onPlayPause() { seen += "toggle" }
            override fun onStop() { seen += "stop" }
            override fun onNext() { seen += "next" }
            override fun onPrevious() { seen += "previous" }
            override fun onSeekTo(positionMs: Long) { seen += "seek $positionMs" }
        }
        for (c in Command.entries) MacNowPlayingMapping.dispatch(c, callbacks, 12.5)
        MacNowPlayingMapping.dispatch(Command.ChangePosition, callbacks, Double.NaN)
        MacNowPlayingMapping.dispatch(Command.ChangePosition, callbacks, -3.0)
        assertEquals(listOf("play", "pause", "toggle", "stop", "next", "previous", "seek 12500"), seen)
    }

    @Test
    fun `the session shows a video once, again for a pause, a speed or a seek, never for a steady tick`() {
        session.update("Episode", 600_000, 0, playing = true, canGoNext = true)
        clockNanos = 5_000_000_000L
        session.update("Episode", 600_000, 5_000, playing = true, canGoNext = true)
        assertEquals(1, fake.shown.size)
        session.update("Episode", 600_000, 5_000, playing = false, canGoNext = true)
        assertEquals(2, fake.shown.size)
        assertEquals(MacNowPlayingMapping.STATE_PAUSED, fake.shown.last().second)
        session.update("Episode", 600_000, 5_000, playing = false, canGoNext = true, rate = 2f)
        assertEquals(3, fake.shown.size)
        clockNanos = 6_000_000_000L
        session.update("Episode", 600_000, 300_000, playing = false, canGoNext = true, rate = 2f)
        assertEquals(4, fake.shown.size)
    }

    @Test
    fun `the commands follow what the player can do and clear turns them all off`() {
        session.update("Episode", 600_000, 0, playing = true, canGoNext = true, canGoPrevious = false)
        assertTrue(fake.enabled.getValue(Command.Play))
        assertTrue(fake.enabled.getValue(Command.Next))
        assertFalse(fake.enabled.getValue(Command.Previous))
        assertTrue(fake.enabled.getValue(Command.ChangePosition))
        session.clear()
        assertTrue(fake.enabled.values.none { it })
        assertNull(fake.shown.last().first)
        assertEquals(MacNowPlayingMapping.STATE_STOPPED, fake.shown.last().second)
    }

    @Test
    fun `release clears the entry and closes the binding, and later updates say nothing`() {
        session.update("Episode", 600_000, 0, playing = true)
        session.release()
        val count = fake.shown.size
        session.update("Other", 600_000, 0, playing = true)
        assertTrue(fake.closed)
        assertEquals(count, fake.shown.size)
    }
}
