package com.tmplayer.player

import com.tmplayer.data.StutterLimits
import com.tmplayer.data.StutterReport
import com.tmplayer.data.StutterReporter
import io.sentry.SentryEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HX37 and HX11: "Video could not keep up" arrived with 0 dropped frames, 0 underruns and a speed
 * of 76 to 100 per cent. These are the windows that made those, and the gate that keeps them out.
 */
class StutterGateTest {

    /** Plays [seconds] of wall time at a steady [speed] of the position against the clock. */
    private class Play(val watch: PlaybackWatch = PlaybackWatch()) {
        var wall = 0L
        var position = 0L
        var rendered = 0
        var dropped = 0
        var underruns = 0
        var answer: PlaybackWatch.Answer? = null

        fun tick(
            seconds: Int,
            pace: Double = 1.0,
            asked: Float = 1f,
            dropsPerSecond: Int = 0,
            underrunEvery: Int = 0,
            fps: Int = 24,
        ) {
            repeat(seconds) {
                wall += 1000
                position += (1000 * pace).toLong()
                rendered += fps
                dropped += dropsPerSecond
                if (underrunEvery > 0 && (wall / 1000) % underrunEvery == 0L) underruns++
                answer = watch.sample(wall, position, true, rendered, dropped, underruns, asked) ?: answer
            }
        }
    }

    @Test
    fun `a clean play at normal speed is kept up`() {
        val play = Play()
        play.tick(30)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
        assertEquals("", play.watch.figures().symptom)
    }

    @Test
    fun `a viewer's slow playback speed is not a slow decoder`() {
        // 0.75x: the position moves at 75 per cent of the clock by design, which read as "76 per cent speed".
        val play = Play()
        play.tick(30, pace = 0.75, asked = 0.75f)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
        assertEquals(100, play.watch.figures().speedPercent)
        // A real shortfall at 0.75x is still caught.
        val behind = Play()
        behind.tick(30, pace = 0.5, asked = 0.75f)
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, behind.answer)
        assertEquals(PlaybackWatch.SLOW, behind.watch.figures().symptom)
    }

    @Test
    fun `fast forward on a hold is not a fast decoder either`() {
        val play = Play()
        play.tick(30, pace = 2.0, asked = 2f)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
    }

    @Test
    fun `changing the speed mid watch discards that stretch and settles again`() {
        val play = Play()
        play.tick(6)
        // The viewer picks 0.5x: the stretch across the change would read as half speed at 1x.
        play.tick(1, pace = 0.5, asked = 0.5f)
        play.tick(30, pace = 0.5, asked = 0.5f)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
    }

    @Test
    fun `frames dropped while the decoder warms up are not counted`() {
        val play = Play()
        // Fifty frames dropped in the first second (one Media3 batch), the way a start does.
        play.tick(1, dropsPerSecond = 50)
        play.tick(30)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
        assertEquals(0, play.watch.figures().dropped)
    }

    @Test
    fun `a seek puts the new start's drops and the late batch of the old one out of the count`() {
        val play = Play()
        play.tick(5)
        play.watch.reset()
        // A batch of drops from the catch-up after the seek, arriving in the next second.
        play.dropped += 50
        play.tick(1)
        play.tick(30)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
    }

    @Test
    fun `a buffering stall between two samples is not slow decoding`() {
        val play = Play()
        play.tick(5)
        // Three seconds of waiting for the download inside one sample interval, then the player
        // says so and plays on: only the player's call tells the watch.
        play.wall += 3000
        play.watch.interrupted()
        play.tick(1)
        play.tick(30)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
        assertEquals(100, play.watch.figures().speedPercent)
    }

    @Test
    fun `a pause and resume inside one interval is not slow decoding`() {
        val play = Play()
        play.tick(5)
        play.wall += 700
        play.watch.interrupted()
        play.tick(1)
        play.tick(30)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
    }

    @Test
    fun `late frames alone are not a stutter`() {
        val play = Play()
        play.tick(3)
        play.watch.frameOffsets(totalOffsetUs = -90_000L * 300, frames = 300)
        play.tick(30)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
    }

    @Test
    fun `late frames with frames dropped are`() {
        val play = Play()
        play.tick(3)
        play.watch.frameOffsets(totalOffsetUs = -90_000L * 300, frames = 300)
        // A tenth of one share: 1 of 24 a second is about 4 per cent, past the corroboration share.
        play.tick(30, dropsPerSecond = 1)
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, play.answer)
        assertEquals(PlaybackWatch.LATE, play.watch.figures().symptom)
    }

    @Test
    fun `real stutters still answer, with the symptom named`() {
        val dropping = Play()
        dropping.tick(30, dropsPerSecond = 6)
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, dropping.answer)
        assertEquals(PlaybackWatch.DROPPED, dropping.watch.figures().symptom)
        val dry = Play()
        dry.tick(30, underrunEvery = 3)
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, dry.answer)
        assertEquals(PlaybackWatch.UNDERRUNS, dry.watch.figures().symptom)
        val slow = Play()
        slow.tick(30, pace = 0.36)
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, slow.answer)
        assertEquals(PlaybackWatch.SLOW, slow.watch.figures().symptom)
    }

    @Test
    fun `symptomOf reads garbage as no evidence`() {
        assertNull(PlaybackWatch.symptomOf(1.0, 0, 0, 0, late = false))
        assertNull(PlaybackWatch.symptomOf(Double.NaN, 0, 300, 0, late = false))
        assertNull(PlaybackWatch.symptomOf(Double.POSITIVE_INFINITY, -5, -100, -3, late = false))
        assertNull(PlaybackWatch.symptomOf(-4.0, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, late = true))
        // Absurdly large: a share of a huge total is still computed, not overflowed.
        assertNull(PlaybackWatch.symptomOf(1.0, 5, Int.MAX_VALUE, 0, late = false))
        assertEquals(PlaybackWatch.DROPPED, PlaybackWatch.symptomOf(1.0, Int.MAX_VALUE, Int.MAX_VALUE, 0, late = false))
        // A few drops are not a share to judge by, even when all frames in the window were drops.
        assertNull(PlaybackWatch.symptomOf(1.0, 5, 5, 0, late = false))
        assertEquals(PlaybackWatch.SLOW, PlaybackWatch.symptomOf(0.0, 0, 300, 0, late = false))
        assertEquals(PlaybackWatch.SLOW, PlaybackWatch.symptomOf(0.76, 0, 300, 0, late = false))
        assertNull(PlaybackWatch.symptomOf(0.9, 0, 300, 2, late = false))
    }

    @Test
    fun `garbage playback speed is read as normal speed`() {
        val play = Play()
        play.tick(30, asked = Float.NaN)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, play.answer)
        val negative = Play()
        negative.tick(30, asked = -2f)
        assertEquals(PlaybackWatch.Answer.KEPT_UP, negative.answer)
    }

    // ---- the report's own gate ----------------------------------------------------------------

    private fun facts(
        speed: Int = 100,
        dropped: Int = 0,
        frames: Int = 288,
        underruns: Int = 0,
        symptom: String = "",
        model: String = "AI PONT",
    ) = StutterReport.Facts(
        codec = "hevc", profile = "hvc1.1.6.L120.B0", bitDepth = 8, width = 1920, height = 1080, fps = 0f,
        bitrateKbps = 0, container = "video/x-matroska", decoder = "OMX.MS.HEVC.Decoder", hardware = true,
        prediction = "HARDWARE", speedPercent = speed, dropped = dropped, frames = frames, underruns = underruns,
        manufacturer = "Changhong", model = model, androidVersion = "11", cores = 4, abi = "armeabi-v7a",
        appVersion = "1.28.3", sizeBucket = 1080, symptom = symptom,
    )

    @Test
    fun `a report with no real symptom is never sent and does not use up the day`() {
        val file = Files.createTempDirectory("gate").toFile().resolve("stutter.json")
        val sent = mutableListOf<SentryEvent>()
        val reporter = StutterReporter({ true }, StutterLimits(file) { 5L }, sent::add)
        // The shapes of the HX37 events: 76 to 100 per cent speed is only a stutter below 90, and
        // 0 dropped, 0 underruns at 100 per cent is nothing at all.
        assertFalse(reporter.report(facts(speed = 100)))
        assertFalse(reporter.report(facts(speed = 100, symptom = PlaybackWatch.LATE)))
        assertFalse(reporter.report(facts(speed = 95, dropped = 3, underruns = 2)))
        assertTrue(sent.isEmpty())
        // The same kind, now with a real symptom, still goes: the false alarms took nothing from the limit.
        assertTrue(reporter.report(facts(speed = 80, dropped = 50, frames = 295, underruns = 1)))
        assertEquals(1, sent.size)
        assertEquals("80", sent.single().tags!!["speed_percent"])
    }

    /**
     * HX50 (Fire TV Stick, speed 99%, 50 of 312 frames dropped, 0 underruns): checked whether the
     * drop-fraction branch ([PlaybackWatch.symptomOf]'s `DROPPED`) needs the same "measured over
     * more than an instant" treatment HX37 gave seeks, pauses and buffering, or whether it already
     * has it by summing over the whole [PlaybackWatch.WINDOW_MS].
     *
     * It does not, fully: the window is cumulative from the first counted sample, so one bad
     * second amid an otherwise flawless watch (not a seek or a pause, which [interrupted] already
     * drops, but a GC pause or a brief decoder hiccup while still playing) can alone push the share
     * over [MAX_DROPPED_SHARE][PlaybackWatch]'s 10 per cent for the rest of the window, same as a
     * genuinely struggling device would. HX50's own figures (16 per cent over the window) read the
     * same whether they came from one bad second or were spread across all twelve, so the report
     * cannot by itself tell a Fire Stick that is really behind from a Fire Stick that had one hiccup
     * and was otherwise fine; a narrower fix (a floor under one-off spikes) would need its own
     * tuning and its own false-negative risk, so it is left here as a documented gap, not changed.
     */
    @Test
    fun `one bad second amid an otherwise clean window still trips the drop-fraction gate`() {
        val play = Play()
        play.tick(3)
        // One second with half its frames dropped, no seek and no pause around it.
        play.tick(1, dropsPerSecond = 50)
        play.tick(30)
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, play.answer)
        assertEquals(PlaybackWatch.DROPPED, play.watch.figures().symptom)
    }

    @Test
    fun `the report carries the symptom and where the frame rate came from`() {
        val tags = StutterReport.tagsOf(facts(speed = 50, symptom = PlaybackWatch.SLOW).copy(fpsSource = "unknown"))
        assertEquals("slow", tags["symptom"])
        assertEquals("unknown", tags["fps_source"])
        assertEquals("0.0", tags["fps"])
        assertEquals(StutterReport.ALLOWED_TAGS, tags.keys)
    }

    @Test
    fun `an absurd or missing frame rate never reaches the tags as is`() {
        for (fps in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 1e9f, 0f)) {
            val tags = StutterReport.tagsOf(facts().copy(fps = fps))
            assertEquals("0.0", tags["fps"], "fps $fps")
        }
        assertEquals("23.9", StutterReport.tagsOf(facts().copy(fps = 23.976f))["fps"])
    }
}
