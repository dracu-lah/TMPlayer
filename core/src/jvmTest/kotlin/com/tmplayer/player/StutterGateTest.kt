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
        fps: Float = 0f,
        profile: String = "hvc1.1.6.L120.B0",
    ) = StutterReport.Facts(
        codec = "hevc", profile = profile, bitDepth = 8, width = 1920, height = 1080, fps = fps,
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
        assertTrue(reporter.report(facts(speed = 80, dropped = 50, frames = 295, underruns = 1, fps = 25f)))
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
    fun `the report says where the bytes came from and how much was buffered, so a slow line is told from a slow decoder`() {
        val tags = StutterReport.tagsOf(facts(speed = 82, symptom = PlaybackWatch.SLOW).copy(source = "remote", bufferedAheadMs = 1_250))
        assertEquals("remote", tags["source"])
        assertEquals("1250", tags["buffered_ahead_ms"])
        assertEquals("unknown", StutterReport.tagsOf(facts()).getValue("source"))
        assertEquals("-1", StutterReport.tagsOf(facts()).getValue("buffered_ahead_ms"))
        assertEquals("remotesecret name", StutterReport.tagsOf(facts().copy(source = "remote/secret name!"))["source"], "only plain words ride in the source")
        assertEquals(StutterReport.ALLOWED_TAGS, tags.keys)
    }

    @Test
    fun `the report carries the trace's verdict as one tag, and only a cause's name can be in it`() {
        for (cause in StutterCause.entries) {
            val tags = StutterReport.tagsOf(facts().copy(verdict = StutterReport.verdictName(cause)))
            assertEquals(cause.name.lowercase(), tags["verdict"])
            assertEquals(StutterReport.ALLOWED_TAGS, tags.keys)
        }
        assertEquals("unknown", StutterReport.tagsOf(facts()).getValue("verdict"), "no verdict is unknown")
        for (dirty in listOf("", "decoder bound", "/sdcard/Secret.mkv", "NETWORK_BOUND ".repeat(50), "\u0000")) {
            val v = StutterReport.tagsOf(facts().copy(verdict = dirty)).getValue("verdict")
            assertTrue(v in StutterReport.VERDICTS, "'$dirty' became '$v'")
        }
        assertEquals("network_bound", StutterReport.tagsOf(facts().copy(verdict = "NETWORK_BOUND")).getValue("verdict"))
    }

    @Test
    fun `the verdict tag survives the gate's scrub and nothing else is added`() {
        val event = StutterReport.build(facts().copy(verdict = "decoder_bound"))
        event.tags = (event.tags.orEmpty() + ("title" to "Secret Show")).toMutableMap()
        val scrubbed = StutterReport.scrub(event)
        assertEquals("decoder_bound", scrubbed.tags!!["verdict"])
        assertEquals(StutterReport.ALLOWED_TAGS, scrubbed.tags!!.keys)
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

    // ---- why this decoder (Sentry triage, 2026-11-18) -----------------------------------------

    @Test
    fun `a stutter with no known frame rate or no profile is not sent and does not use up the day`() {
        val file = Files.createTempDirectory("gate").toFile().resolve("stutter.json")
        val sent = mutableListOf<SentryEvent>()
        val reporter = StutterReporter({ true }, StutterLimits(file) { 5L }, sent::add)
        val real = facts(speed = 60, frames = 300, symptom = PlaybackWatch.SLOW)
        // The triage's false positives: fps 0.0. Then a profile nobody can group by.
        for (fps in listOf(0f, Float.NaN, -3f, 1e9f)) assertFalse(reporter.report(real.copy(fps = fps)), "fps $fps")
        assertFalse(reporter.report(real.copy(fps = 25f, profile = "")))
        assertFalse(reporter.report(real.copy(fps = 25f, profile = "/// ")), "a profile that cleans to nothing is none")
        assertTrue(sent.isEmpty())
        assertTrue(reporter.report(real.copy(fps = 25f)))
        assertEquals(1, sent.size)
    }

    @Test
    fun `the why this decoder tags ride as allowlisted classes`() {
        val tags = StutterReport.tagsOf(
            facts(speed = 70, symptom = PlaybackWatch.SLOW).copy(
                hardware = false,
                policy = "async_default+pic_hw+snd_hw+tun_auto+out_0",
                preferSoftware = false,
                softwareBy = "switch",
                processBits = 32,
                hwDecoders = StutterReport.hwDecodersOf(listOf("OMX.MS.HEVC.Decoder" to (2 to 4096))),
            ),
        )
        assertEquals(StutterReport.ALLOWED_TAGS, tags.keys)
        assertEquals("async_default+pic_hw+snd_hw+tun_auto+out_0", tags["policy"])
        assertEquals("off", tags["prefer_software"])
        assertEquals("switch", tags["software_by"])
        assertEquals("32", tags["process_bits"])
        assertEquals("OMX.MS.HEVC.Decoder:2.4096", tags["hw_decoders"])
        // Nothing known: every new tag says so instead of being left out.
        val none = StutterReport.tagsOf(facts())
        assertEquals(StutterReport.ALLOWED_TAGS, none.keys)
        for (key in listOf("policy", "prefer_software", "software_by", "process_bits", "hw_decoders")) {
            assertEquals("unknown", none[key], key)
        }
    }

    @Test
    fun `a hardware stutter past the chip's rated frame rate is still sent and says so`() {
        // HX71: 4K 10-bit HEVC at 60 frames now goes to a phone chip rated for fewer frames there.
        // The gate must not hide it, and the report must tell it from a hardware mystery.
        val file = Files.createTempDirectory("gate").toFile().resolve("stutter.json")
        val sent = mutableListOf<SentryEvent>()
        val reporter = StutterReporter({ true }, StutterLimits(file) { 5L }, sent::add)
        val past = facts(speed = 100, dropped = 120, frames = 678, fps = 60f, profile = "hvc1.2.4.L153.90", model = "V2270A")
            .copy(bitDepth = 10, width = 3840, height = 1604, decoder = "c2.mtk.hevc.decoder", hwFit = "rate")
        assertTrue(reporter.report(past))
        assertEquals("rate", sent.single().tags?.get("hw_fit"))
        assertEquals("hardware", sent.single().tags?.get("decoder_type"))
        // Nothing dropped, nothing slow: no report, however the chip fit it.
        assertFalse(reporter.report(past.copy(dropped = 0, model = "RMX3710")))
        assertEquals(1, sent.size)
        // Only the fixed words ride; anything else, and nothing known, is unknown.
        for (word in listOf("rated", "level", "rate", "none")) {
            assertEquals(word, StutterReport.tagsOf(facts().copy(hwFit = word.uppercase()))["hw_fit"])
        }
        for (dirty in listOf("", "fast", "/sdcard/Secret.mkv", "rate ", "\u0000")) {
            assertEquals("unknown", StutterReport.tagsOf(facts().copy(hwFit = dirty))["hw_fit"], dirty)
        }
        assertEquals("unknown", StutterReport.tagsOf(facts())["hw_fit"])
        assertEquals(StutterReport.ALLOWED_TAGS, StutterReport.scrub(sent.single()).tags!!.keys)
    }

    @Test
    fun `a policy, a cause or a bit count outside its words is unknown`() {
        for (dirty in listOf("Decoder policy: fallback on", "/sdcard/Secret.mkv", "x".repeat(65), "a b", "\u0000", "ASYNC_ON")) {
            assertEquals("unknown", StutterReport.tagsOf(facts().copy(policy = dirty))["policy"], dirty)
        }
        for (dirty in listOf("Secret Show", "", "SWITCH", "ladder")) {
            assertEquals("unknown", StutterReport.tagsOf(facts().copy(softwareBy = dirty))["software_by"], dirty)
        }
        for (bits in listOf(-1, 0, 16, 128, Int.MAX_VALUE)) {
            assertEquals("unknown", StutterReport.tagsOf(facts().copy(processBits = bits))["process_bits"], "$bits")
        }
        assertEquals("on", StutterReport.tagsOf(facts().copy(preferSoftware = true))["prefer_software"])
    }

    @Test
    fun `what put the picture in software, latest cause first`() {
        fun by(software: Boolean = true, switched: Boolean = false, ladder: Boolean = false, plan: Boolean = false, setting: Boolean = false) =
            StutterReport.softwareByOf(software, switched, ladder, plan, setting)
        assertEquals("none", by(software = false, switched = true, ladder = true, plan = true, setting = true))
        assertEquals("switch", by(switched = true, ladder = true, setting = true))
        assertEquals("ladder_r2", by(ladder = true, plan = true, setting = true))
        assertEquals("plan_r2", by(plan = true, setting = true))
        assertEquals("setting", by(setting = true))
        // HARDWARE predicted, nothing asked for software: the device's decoder did not take it.
        assertEquals("selector", by())
        for (value in listOf(by(), by(false), by(switched = true), by(ladder = true), by(plan = true), by(setting = true))) {
            assertTrue(value in StutterReport.SOFTWARE_BY)
        }
    }

    @Test
    fun `the hardware decoder list keeps whole entries in one tag and nothing but names and numbers`() {
        assertEquals("none", StutterReport.hwDecodersOf(emptyList()))
        assertEquals("none", StutterReport.hwDecodersOf(listOf("///" to null)))
        val two = StutterReport.hwDecodersOf(listOf("OMX.realtek.avc.decoder" to (8 to 2048), "OMX.MTK.VIDEO.DECODER.AVC" to (8 to 8192)))
        assertEquals("OMX.realtek.avc.decoder:8.2048+OMX.MTK.VIDEO.DECODER.AVC:8.8192", two)
        assertTrue(two.length <= 64)
        // A third that does not fit is left out whole, never cut in half.
        val three = StutterReport.hwDecodersOf(
            listOf("OMX.realtek.avc.decoder" to (8 to 2048), "OMX.MTK.VIDEO.DECODER.AVC" to (8 to 8192), "c2.mtk.avc.decoder" to (8 to 1)),
        )
        assertEquals(two, three)
        // A name with a path in it, or absurd numbers, cannot carry more than letters and digits.
        val dirty = StutterReport.hwDecodersOf(listOf("/data/Secret Show+x:y" to (-5 to Int.MAX_VALUE), "y".repeat(500) to null))
        assertFalse(dirty.contains('/'))
        assertFalse(dirty.contains(' '))
        assertTrue(dirty.length <= 64)
        assertEquals(dirty, StutterReport.tagsOf(facts().copy(hwDecoders = dirty))["hw_decoders"])
        assertEquals("OMX.MS.HEVC.Decoder", StutterReport.hwDecodersOf(listOf("OMX.MS.HEVC.Decoder" to null)))
    }

    @Test
    fun `the new tags survive the gate's scrub`() {
        val event = StutterReport.build(facts().copy(policy = "async_on+pic_sw+snd_hw+tun_off+out_1", softwareBy = "ladder_r2", processBits = 64))
        val scrubbed = StutterReport.scrub(event)
        assertEquals(StutterReport.ALLOWED_TAGS, scrubbed.tags!!.keys)
        assertEquals("async_on+pic_sw+snd_hw+tun_off+out_1", scrubbed.tags!!["policy"])
        assertEquals("ladder_r2", scrubbed.tags!!["software_by"])
        assertEquals("64", scrubbed.tags!!["process_bits"])
    }
}
