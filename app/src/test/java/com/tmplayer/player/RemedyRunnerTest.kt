package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.KnownRemedies
import com.tmplayer.data.PlayerEngine
import com.tmplayer.data.RemedyMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The remedy runner against a fake player and a fake decoder (RL6, research 7.6 and 7.7): a
 * decoder whose init throws, one that never outputs a frame, one that freezes, and a codec error
 * Media3 recovers from by itself. Each gets at most one automatic decoder hop, the engine hop only
 * where the other engine can run, the sheet when nothing is left, and no tick while paused.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemedyRunnerTest {

    /** The main thread's queue with a hand-turned clock. */
    private class FakeClock : RemedyRunner.Clock {
        var nowMs = 1_000L
        val tasks = mutableListOf<Pair<Long, Runnable>>()
        override fun now() = nowMs
        override fun post(delayMs: Long, task: Runnable) {
            tasks += (nowMs + delayMs) to task
        }
        override fun cancel(task: Runnable) {
            tasks.removeAll { it.second === task }
        }

        /** Runs every task due up to [ms] from now, in time order. */
        fun advance(ms: Long) {
            val end = nowMs + ms
            while (true) {
                val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                nowMs = maxOf(nowMs, next.first)
                next.second.run()
            }
            nowMs = end
        }
    }

    /**
     * A player with a fake decoder: [frames] says whether the decoder outputs, [ready] and
     * [playing] what Media3 would report. Records every step the runner asks for.
     */
    private inner class FakePlayer : RemedyRunner.Host {
        var ready = true
        var playing = true
        var frames = true
        var hidden = false
        var reopenWorks = true
        var positionMs = 0L
        var rendered = 0L
        var otherDecoder = true
        var softwareTakes = true
        var audioSoftwareTakes = true
        var passthrough = true
        /** The device has its own decoder for the refused sound. */
        var deviceDecodes = true
        /** Sound reaches the output (false: the track was left unsupported and plays silent). */
        var soundPlays = true
        var autoSwitch = true
        var engineInput: EngineInput? = null
        var engine = EngineDecision(PlayerEngine.ExoPlayer, EngineReason.Default)
        var key: String? = "amlogic1/28/hevc:10:1080:30/2/mkv/eac3"
        val reopened = mutableListOf<DecoderPolicy>()
        val engines = mutableListOf<EngineDecision>()
        val said = mutableListOf<String>()
        val sheets = mutableListOf<String>()
        val redelivered = mutableListOf<PlaybackException>()
        var saved: RemedyMemory? = null
        var lastSample = 0L

        override val sdk = 28
        override val appVersion = "1.40.0"
        override fun today() = 20_400
        override fun videoTag() = "v1"

        override fun sample(): RemedyRunner.Sample? {
            if (engine.engine != PlayerEngine.ExoPlayer) return null
            val step = if (lastSample == 0L) 0L else clock.nowMs - lastSample
            lastSample = clock.nowMs
            if (playing && ready) {
                positionMs += step
                if (frames) rendered += step / 40
            }
            return RemedyRunner.Sample(
                nowMs = clock.nowMs,
                ready = ready,
                buffering = false,
                playing = playing && ready,
                positionMs = positionMs,
                speed = 1f,
                rendered = rendered,
                dropped = 0,
                skipped = 0,
                audioPlaying = playing && ready && soundPlays,
                pictureExpected = true,
                hidden = hidden,
                software = false,
            )
        }

        override fun facts(excluded: Set<String>) = LadderFacts(
            sdk = sdk,
            decoderName = "OMX.amlogic.hevc.decoder.awesome2".takeIf { it !in excluded } ?: "c2.android.hevc.decoder",
            otherDecoder = otherDecoder,
            softwareTakes = softwareTakes,
            audioSoftwareTakes = audioSoftwareTakes,
            passthrough = passthrough,
            autoSwitch = autoSwitch,
        )

        override fun deviceDecodesAudio(format: Format?) = deviceDecodes
        override fun classKey() = key
        override fun failedRenderer(error: PlaybackException): RemedyRunner.FailedRenderer? {
            val format = (error as? ExoPlaybackException)?.rendererFormat ?: return null
            val type = if (MimeTypes.isVideo(format.sampleMimeType)) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
            return RemedyRunner.FailedRenderer(type, ffmpeg = error.rendererName?.startsWith("Ffmpeg") == true)
        }
        override fun engineInput() = engineInput
        override fun engineRunning() = engine
        override fun reopen(): Boolean {
            if (!reopenWorks) return false
            reopened += runner.policyForBuild()
            lastSample = 0L
            return true
        }
        override fun switchEngine(decision: EngineDecision): Boolean {
            engines += decision
            engine = decision
            return true
        }
        override fun say(text: String) {
            said += text
        }
        override fun offer(text: String, action: String, onTap: () -> Unit) = Unit
        override fun sheet(text: String) {
            sheets += text
        }
        override fun redeliver(error: PlaybackException) {
            redelivered += error
        }
        override fun saveMemory(memory: RemedyMemory) {
            saved = memory
        }
        override fun log(line: String) = Unit
        val traced = mutableListOf<TraceHop>()
        val tracedFacts = mutableListOf<Map<String, String>>()
        override fun traceHop(hop: TraceHop) {
            traced += hop
        }
        override fun traceFacts(facts: Map<String, String>) {
            tracedFacts += facts
        }
    }

    private val clock = FakeClock()
    private val player = FakePlayer()
    private val runner = RemedyRunner(player, clock)

    private val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(1920).setHeight(1080).build()

    private fun decoderError(code: Int, ffmpeg: Boolean = false, format: Format = hevc): ExoPlaybackException =
        ExoPlaybackException.createForRenderer(
            IllegalStateException("fake codec"),
            if (ffmpeg) "FfmpegVideoRenderer" else "MediaCodecVideoRenderer",
            0,
            format,
            C.FORMAT_HANDLED,
            false,
            code,
        )

    /** A video opened: built, tracks known, ready and playing. */
    private fun open(memory: RemedyMemory = RemedyMemory.Empty, known: KnownRemedies = KnownRemedies()) {
        runner.newVideo(known, active = true)
        runner.memoryLoaded(memory)
        runner.policyForBuild()
        runner.tracksKnown()
        play()
    }

    /** The (re)built player reaches READY and plays. */
    private fun play() {
        player.ready = true
        player.playing = true
        runner.ready()
        if (player.frames) runner.firstFrame()
        runner.playingChanged(true)
    }

    @Test
    fun aDecoderWhoseInitFailsIsLeftOutOnceThenTheSheet() {
        open()
        assertTrue(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertEquals(1, player.reopened.size)
        assertEquals(setOf("OMX.amlogic.hevc.decoder.awesome2"), player.reopened.single().excluded)
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedyAnotherWay), player.said)
        // The next decoder fails at once: the probation waits, then nothing at the decoder level is left.
        play()
        assertTrue(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        clock.advance(RemedyLadder.PROBATION_MS)
        assertEquals("one decoder hop at most", 1, player.reopened.size)
        assertEquals("handed back to the player's own sheet", 1, player.redelivered.size)
        assertFalse("after the ladder gave up the error is the player's", runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertFalse(runner.allowsDecoderSwitch(video = true))
        assertFalse(runner.allowsEngineSwitch())
        val learned = player.saved!!.records.values.single()
        assertEquals(listOf("OMX.amlogic.hevc.decoder.awesome2"), learned.excluded)
    }

    @Test
    fun noFirstFrameWithSoundTakesOneHopThenTheSheet() {
        player.frames = false
        open()
        clock.advance(FirstFrameWatch.HARDWARE_MS + 1_500)
        assertEquals(1, player.reopened.size)
        assertEquals(setOf("OMX.amlogic.hevc.decoder.awesome2"), player.reopened.single().excluded)
        // The new path shows no picture either: no second hop over black, the sheet.
        play()
        clock.advance(FirstFrameWatch.HARDWARE_MS + 1_500)
        assertEquals(1, player.reopened.size)
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedyNoPicture), player.sheets)
        clock.advance(60_000)
        assertTrue("no tick after the sheet", clock.tasks.isEmpty())
    }

    @Test
    fun aFrozenPictureReloadsOnceThenTheSheet() {
        open()
        clock.advance(5_000)
        assertTrue("a moving picture is left alone", player.reopened.isEmpty())
        player.frames = false
        clock.advance(FrozenOutputWatch.FROZEN_MS + 2_000)
        assertEquals(1, player.reopened.size)
        assertEquals(DecoderPolicy.Default, player.reopened.single())
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedyPictureRestart), player.said)
        // The reload shows a picture, which freezes again: the decoder level is spent, no engine here, the sheet.
        player.frames = true
        play()
        clock.advance(2_000)
        player.frames = false
        clock.advance(FrozenOutputWatch.FROZEN_MS + 2_000)
        assertEquals(1, player.reopened.size)
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedyPictureStopped), player.sheets)
    }

    @Test
    fun aRecoverableCodecErrorIsMedia3sThenOneHopForTheFatalOne() {
        open()
        runner.codecError(IllegalStateException("recoverable"))
        runner.codecError(IllegalStateException("recoverable again"))
        clock.advance(3_000)
        assertTrue("Media3 retries a recoverable codec error by itself", player.reopened.isEmpty())
        assertTrue(runner.current!!.hops.isEmpty())
        assertTrue(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertEquals(1, player.reopened.size)
    }

    @Test
    fun theEngineHopOnlyWhereTheOtherEngineRunsAndOnce() {
        player.otherDecoder = false
        player.softwareTakes = false
        player.engineInput = EngineInput(choice = EngineChoice.Automatic, device = DeviceFacts(), vlcAvailable = true)
        open()
        assertTrue(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertEquals(listOf(PlayerEngine.Vlc), player.engines.map { it.engine })
        assertTrue(player.reopened.isEmpty())
        // VLC failing is the player's own engine sheet, never a second automatic hop.
        assertFalse(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertEquals(1, player.engines.size)
    }

    @Test
    fun theSourceAndTheAppsOwnDecoderAreLeftToThePlayer() {
        open()
        assertFalse(runner.onPlayerError(ExoPlaybackException.createForSource(java.io.IOException("cut"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertFalse(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODING_FAILED, ffmpeg = true)))
        assertTrue(player.reopened.isEmpty())
        assertTrue("the player's own switch may still act", runner.allowsDecoderSwitch(video = true))
        runner.decoderSwitched(video = true)
        assertFalse("then the decoder level is spent", runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODING_FAILED)))
    }

    @Test
    fun theViewersStutterPressIsCountedAndTakesTheNextStepOnce() {
        open()
        assertEquals(RemedyRunner.ViewerOutcome.Tried, runner.viewerReported(RemedyRunner.ViewerReport.Stutter))
        assertEquals(1, player.reopened.size)
        assertEquals("the stutter steps: async queueing first on API 28", DecoderPolicy.Async.On, player.reopened.single().async)
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedyAnotherWay), player.said)
        val record = player.saved!!.records.values.single()
        assertEquals("hard evidence for the class", 1, record.stutters)
        assertEquals(1, record.hops)
        // A second press in the same video is the mark and the count, no second step, no sheet.
        play()
        clock.advance(3_000)
        assertEquals(RemedyRunner.ViewerOutcome.Recorded, runner.viewerReported(RemedyRunner.ViewerReport.Stutter))
        assertEquals(RemedyRunner.ViewerOutcome.Recorded, runner.viewerReported(RemedyRunner.ViewerReport.WrongPicture))
        assertEquals(1, player.reopened.size)
        assertTrue(player.sheets.isEmpty())
        assertEquals(2, player.saved!!.records.values.single().stutters)
    }

    @Test
    fun theViewersDecoderPickInTheMenuWinsOverTheLadderAndNeverLoops() {
        open()
        play()
        // WP-25: Try the other decoder from the More menu while the video plays.
        runner.viewerPickedDecoder(software = true)
        assertEquals(true, runner.viewerDecoderSoftware)
        assertEquals("a choice, not a hop: nothing re-opened by the runner", 0, player.reopened.size)
        assertTrue("nothing learned before software played clean (PB2-09)", player.saved?.records?.values?.none { Remedy.Software in it.working } ?: true)
        // A clean minute on software: now the class starts there next time.
        play()
        clock.advance(RemedyLadder.WORKED_MS + 5_000)
        val learned = player.saved!!.records.values.single()
        assertTrue("the class starts on software next time", Remedy.Software in learned.working)
        assertEquals("not counted as a hop", 0, learned.hops)
        // The player's own one-time switch may not move it back by itself.
        play()
        clock.advance(2_100)
        assertFalse(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODING_FAILED)))
        // A sound step's re-open keeps the viewer's decoder.
        assertTrue(runner.policyForBuild().softwareVideo)
        // A stutter press now steps somewhere other than the decoder the viewer picked.
        val outcome = runner.viewerReported(RemedyRunner.ViewerReport.Stutter)
        assertTrue(player.reopened.all { it.softwareVideo })
        assertTrue(outcome == RemedyRunner.ViewerOutcome.Tried || outcome == RemedyRunner.ViewerOutcome.Recorded)
        assertTrue("never past the budget", (runner.current?.hops?.size ?: 0) <= RemedyLadder.MAX_HOPS)
        // Back to the device's decoder: no longer the class's start, and the next video starts clean.
        runner.viewerPickedDecoder(software = false)
        assertFalse(Remedy.Software in player.saved!!.records.values.single().working)
        assertFalse(runner.policyForBuild().softwareVideo)
        open()
        assertEquals(null, runner.viewerDecoderSoftware)
    }

    /** PB2-09: a software pick too heavy for this device is never taught to the class, however clean it plays. */
    @Test
    fun aHeavySoftwarePickIsNeverLearnedForTheClass() {
        open()
        runner.viewerPickedDecoder(software = true, heavy = true)
        play()
        clock.advance(RemedyLadder.WORKED_MS + 5_000)
        val records = player.saved?.records?.values.orEmpty()
        assertTrue("software stays this video's only: $records", records.none { Remedy.Software in it.working })
        assertTrue("this video still plays on software", runner.policyForBuild().softwareVideo)
    }

    /** PB2-05: the passthrough press is for this video only: a clean minute after it teaches the class nothing about it. */
    @Test
    fun theViewersPassthroughOffPressIsNotLearnedForTheClass() {
        open()
        assertEquals(RemedyRunner.ViewerOutcome.Tried, runner.viewerPassthroughOff())
        assertTrue(player.reopened.last().passthroughOff)
        play()
        clock.advance(RemedyLadder.WORKED_MS + 5_000)
        val records = player.saved?.records?.values.orEmpty()
        assertTrue("PCM for this video only: $records", records.none { Remedy.PassthroughOff in it.working })
        assertTrue("the press is no hop for the class", records.all { it.hops == 0 })
        // The next video of the class starts with passthrough on again.
        player.reopened.clear()
        runner.newVideo(KnownRemedies(), active = true)
        runner.memoryLoaded(player.saved ?: RemedyMemory.Empty)
        assertFalse(runner.policyForBuild().passthroughOff)
        runner.tracksKnown()
        assertTrue(player.reopened.none { it.passthroughOff })
    }

    /** PB2-02: the menu's switch re-prepares the same player, so an error right after it is the player's own, never swallowed. */
    @Test
    fun anErrorRightAfterTheViewersDecoderPickReachesTheSheet() {
        open()
        runner.viewerPickedDecoder(software = true)
        clock.advance(100)
        assertFalse(
            "no grace: the player was not replaced, so the error goes to the status sheet",
            runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, ffmpeg = true)),
        )
        assertFalse(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertTrue("nothing re-opened by the runner", player.reopened.isEmpty())
    }

    @Test
    fun pictureLooksWrongLeavesTheDecoderNameOutEvenWithNeverSwitchOn() {
        player.autoSwitch = false
        open()
        assertEquals(RemedyRunner.ViewerOutcome.Tried, runner.viewerReported(RemedyRunner.ViewerReport.WrongPicture))
        assertEquals(setOf("OMX.amlogic.hevc.decoder.awesome2"), player.reopened.single().excluded)
        assertEquals("a wrong picture is not a stutter", 0, player.saved!!.records.values.single().stutters)
    }

    @Test
    fun aPressWithNothingToTryOrNoClassOrAnotherEngineChangesNothingAndNeverOpensTheSheet() {
        player.otherDecoder = false
        player.softwareTakes = false
        open()
        assertEquals(RemedyRunner.ViewerOutcome.Recorded, runner.viewerReported(RemedyRunner.ViewerReport.WrongPicture))
        assertTrue(player.reopened.isEmpty())
        assertTrue(player.sheets.isEmpty())
        assertTrue("a press after nothing applied still works later", runner.current!!.hops.isEmpty())

        val other = FakePlayer().also { it.key = null }
        val noClass = RemedyRunner(other, clock)
        noClass.newVideo(KnownRemedies(), active = true)
        noClass.policyForBuild()
        noClass.tracksKnown()
        assertEquals(RemedyRunner.ViewerOutcome.NoClass, noClass.viewerReported(RemedyRunner.ViewerReport.Stutter))
        assertNull(other.saved)

        val vlc = FakePlayer().also { it.engine = EngineDecision(PlayerEngine.Vlc, EngineReason.Default) }
        val onVlc = RemedyRunner(vlc, clock)
        onVlc.newVideo(KnownRemedies(), active = true)
        onVlc.policyForBuild()
        onVlc.tracksKnown()
        assertEquals(RemedyRunner.ViewerOutcome.Recorded, onVlc.viewerReported(RemedyRunner.ViewerReport.Stutter))
        assertTrue(vlc.reopened.isEmpty())
        assertTrue(vlc.engines.isEmpty())
    }

    @Test
    fun aViewerHopIsInTheTraceAndLearnsItsResult() {
        open()
        assertTrue("the class and its start are traced when the tracks are known", player.tracedFacts.first()["class"] == player.key)
        runner.viewerReported(RemedyRunner.ViewerReport.Stutter)
        assertEquals(listOf(TraceHop.PENDING), player.traced.map { it.result })
        assertEquals("UserStutter", player.traced.single().evidence)
        assertEquals("as", player.traced.single().to)
        assertEquals("rung", "as", player.tracedFacts.last()["rung"])
        play()
        clock.advance(RemedyLadder.STABLE_MS + 3_000)
        assertEquals(listOf(TraceHop.PENDING, TraceHop.WORKED), player.traced.map { it.result })
        assertTrue(player.traced[0].sameStep(player.traced[1]))
    }

    @Test
    fun beforeTheTracksAreKnownNothingIsTaken() {
        player.key = null
        open()
        assertNull(runner.current)
        assertFalse(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertTrue(runner.allowsDecoderSwitch(video = true))
        assertTrue("no ladder, no tick", clock.tasks.isEmpty())
    }

    @Test
    fun noTickWhilePausedOrStopped() {
        open()
        assertEquals("one tick waits while it plays", 1, clock.tasks.size)
        player.playing = false
        runner.playingChanged(false)
        assertTrue(clock.tasks.isEmpty())
        clock.advance(120_000)
        assertTrue("a paused player posts nothing", clock.tasks.isEmpty())
        player.playing = true
        runner.playingChanged(true)
        assertEquals(1, clock.tasks.size)
        runner.stop()
        assertTrue(clock.tasks.isEmpty())
        // A tick that finds the player paused does not post another.
        runner.playingChanged(true)
        player.playing = false
        clock.advance(RemedyRunner.TICK_MS)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test
    fun aPathThatWorkedIsWhereTheNextVideoOfTheClassStarts() {
        open()
        assertTrue(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        play()
        clock.advance(RemedyLadder.WORKED_MS + 5_000)
        val memory = player.saved!!
        assertTrue(memory.records.values.single().working.contains(Remedy.ExcludeDecoder))

        // The next video of the same class: built without the bad decoder before its first frame,
        // with no hop spent and nothing said.
        player.reopened.clear()
        player.said.clear()
        runner.newVideo(KnownRemedies(), active = true)
        runner.memoryLoaded(memory)
        val first = runner.policyForBuild()
        assertTrue(first.excluded.isEmpty())
        runner.tracksKnown()
        assertEquals(setOf("OMX.amlogic.hevc.decoder.awesome2"), player.reopened.single().excluded)
        assertTrue(player.said.isEmpty())
        assertTrue(runner.current!!.hops.isEmpty())
        // Opened again on that path, the tracks are known again: no second re-open.
        runner.tracksKnown()
        assertEquals(1, player.reopened.size)
    }

    @Test
    fun theDeviceListStartsThePathBeforeTheClassIsKnown() {
        runner.newVideo(KnownRemedies(async = true, exclude = listOf("OMX.rk.video_decoder.hevc")), active = true)
        val policy = runner.policyForBuild()
        assertEquals(setOf("OMX.rk.video_decoder.hevc"), policy.excluded)
        assertEquals(DecoderPolicy.Async.On, policy.async)
        runner.tracksKnown()
        assertTrue("the ladder starts where the build already is", player.reopened.isEmpty())
    }

    @Test
    fun aRefusedBitstreamStartsTheNextVideoOfTheClassInPcm() {
        open()
        // The fake sink refuses the Dolby bitstream: the sound moves to FFmpeg's PCM, the picture stays.
        assertTrue(runner.onPlayerError(refusedOutput(EAC3)))
        val hop = player.reopened.single()
        assertTrue(hop.softwareAudio)
        assertFalse(hop.softwareVideo)
        assertTrue(hop.excluded.isEmpty())
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedySoundAnotherWay), player.said)
        val memory = player.saved!!

        // The next video of the class, before any minute of clean play: its sound starts in PCM.
        player.reopened.clear()
        runner.newVideo(KnownRemedies(), active = true)
        runner.memoryLoaded(memory)
        assertFalse(runner.policyForBuild().softwareAudio)
        runner.tracksKnown()
        assertTrue(player.reopened.single().softwareAudio)
        assertEquals(
            DefaultRenderersFactoryModes.PREFER,
            AudioPolicy.rendererMode(DefaultRenderersFactoryModes.ON, softwareFirst = player.reopened.single().softwareAudio),
        )
        assertTrue("planned, not a hop", runner.current!!.hops.isEmpty())
    }

    @Test
    fun aRefusedPcmOutputLearnsNothing() {
        open()
        runner.onPlayerError(refusedOutput(PCM))
        assertTrue("FFmpeg's PCM would be refused the same", player.reopened.none { it.softwareAudio })
        val learned = player.saved?.records?.values?.singleOrNull()
        assertTrue(learned == null || Remedy.AudioSoftware !in learned.working)
    }

    @Test
    fun aRefusedBitstreamFfmpegCannotTakeTurnsPassthroughOff() {
        open()
        player.audioSoftwareTakes = false
        // A2: the output refused the Dolby bitstream and FFmpeg has no decoder for it, so the
        // output is built PCM only and the device's own decoder takes the track.
        assertTrue(runner.onPlayerError(refusedOutput(EAC3)))
        val hop = player.reopened.single()
        assertTrue(hop.passthroughOff)
        assertFalse(hop.softwareAudio)
        assertFalse(hop.softwareVideo)
        assertTrue(hop.shortClass().endsWith("+pt_off"))
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedySoundAnotherWay), player.said)
    }

    @Test
    fun withNoDecoderOnTheDeviceTheSoundGoesToTheOtherEngineNotSilence() {
        player.audioSoftwareTakes = false
        player.deviceDecodes = false
        player.engineInput = EngineInput(choice = EngineChoice.Automatic, device = DeviceFacts(), vlcAvailable = true)
        open()
        // TrueHD refused by the output, no FFmpeg decoder, none on the device: PCM only would be silence.
        assertTrue(runner.onPlayerError(refusedOutput(EAC3)))
        assertTrue(player.reopened.none { it.passthroughOff })
        assertEquals(listOf(PlayerEngine.Vlc), player.engines.map { it.engine })
    }

    @Test
    fun aSoundPathThatPlaysSilentIsNeverLearnedAsWorking() {
        player.audioSoftwareTakes = false
        open()
        assertTrue(runner.onPlayerError(refusedOutput(EAC3)))
        assertTrue(player.reopened.single().passthroughOff)
        // The rebuilt player shows the picture, but the track was left unsupported: no sound.
        player.soundPlays = false
        play()
        clock.advance(RemedyLadder.WORKED_MS + 5_000)
        val saved = player.saved
        assertTrue("the hop was written to the class memory", saved != null && saved.records.isNotEmpty())
        val learned = saved!!.records.values.flatMap { it.working }
        assertFalse("silent play must not teach the class: $learned", Remedy.PassthroughOff in learned)
    }

    @Test
    fun aSoundPathWithSoundIsLearned() {
        player.audioSoftwareTakes = false
        open()
        assertTrue(runner.onPlayerError(refusedOutput(EAC3)))
        play()
        clock.advance(RemedyLadder.WORKED_MS + 5_000)
        assertTrue(player.saved!!.records.values.single().working.contains(Remedy.PassthroughOff))
    }

    @Test
    fun passthroughOffIsNoStepForARefusedPcmOutput() {
        open()
        player.audioSoftwareTakes = false
        runner.onPlayerError(refusedOutput(PCM))
        assertTrue("PCM was refused: turning passthrough off changes nothing", player.reopened.none { it.passthroughOff })
    }

    @Test
    fun passthroughOffIsNoStepWhenThePlayerIsPcmOnlyAlready() {
        open()
        player.audioSoftwareTakes = false
        player.passthrough = false
        runner.onPlayerError(refusedOutput(EAC3))
        assertTrue("built PCM only already (Settings off)", player.reopened.none { it.passthroughOff })
    }

    private object DefaultRenderersFactoryModes {
        const val ON = androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        const val PREFER = androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    }

    @Test
    fun aProvenPathTicksNoMoreUntilASeek() {
        open()
        clock.advance(RemedyLadder.WORKED_MS + 3_000)
        assertTrue("proven after a clean minute", player.saved!!.records.isNotEmpty())
        assertTrue("no tick for the rest of the film", clock.tasks.isEmpty())
        clock.advance(10 * 60_000)
        assertTrue(clock.tasks.isEmpty())
        // A seek watches for a stall again for a while, then it rests by itself.
        runner.seeked()
        assertEquals(1, clock.tasks.size)
        clock.advance(RemedyRunner.SEEK_WATCH_MS + 2_000)
        assertTrue("the seek's watch ends by itself", clock.tasks.isEmpty())
        // A stall right after a seek still gets its reload.
        runner.seeked()
        player.frames = false
        clock.advance(FrozenOutputWatch.FROZEN_MS + 2_000)
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedyPictureRestart), player.said)
    }

    @Test
    fun backgroundAudioPostsNothingUntilThePictureIsBack() {
        open()
        clock.advance(3_000)
        player.hidden = true
        clock.advance(2_000)
        assertTrue("the picture off on purpose: no tick", clock.tasks.isEmpty())
        clock.advance(5 * 60_000)
        assertTrue(clock.tasks.isEmpty())
        assertTrue(player.reopened.isEmpty())
        player.hidden = false
        runner.firstFrame()
        assertEquals(1, clock.tasks.size)
    }

    @Test
    fun aReopenThatCannotHappenEndsInTheSheetNotALostHop() {
        player.reopenWorks = false
        open()
        player.frames = false
        clock.advance(FrozenOutputWatch.FROZEN_MS + 2_000)
        assertTrue(player.reopened.isEmpty())
        assertTrue("nothing said about a hop that did not happen", player.said.isEmpty())
        assertEquals(listOf(com.tmplayer.i18n.L.playerRemedyPictureStopped), player.sheets)
        assertTrue(clock.tasks.isEmpty())
        // An error then is the player's own to show.
        assertFalse(runner.onPlayerError(decoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
    }

    @Test
    fun soundAloneIsNotWatched() {
        runner.newVideo(KnownRemedies(), active = false)
        runner.policyForBuild()
        runner.tracksKnown()
        runner.playingChanged(true)
        assertNull(runner.current)
        assertTrue(clock.tasks.isEmpty())
    }

    /**
     * WP-24: "Turn off passthrough for this video" is the remedy ladder's own passthrough-off step
     * taken as a viewer's press: the sound is rebuilt as PCM once, the hop count is the ladder's own
     * (a stutter press before it counts too), and a second press changes nothing.
     */
    @Test
    fun theViewersPassthroughOffPressIsTheLaddersStepAndSharesItsHopCount() {
        player.autoSwitch = false
        open()
        assertEquals(RemedyRunner.ViewerOutcome.Tried, runner.viewerReported(RemedyRunner.ViewerReport.Stutter))
        assertEquals(1, runner.current!!.hops.size)
        play()
        assertEquals(RemedyRunner.ViewerOutcome.Tried, runner.viewerPassthroughOff())
        assertEquals("the same count as every step", 2, runner.current!!.hops.size)
        assertTrue("the sound output now takes PCM only", player.reopened.last().passthroughOff)
        assertEquals(com.tmplayer.i18n.L.playerRemedySoundAnotherWay, player.said.last())
        assertTrue(player.sheets.isEmpty())
        play()
        assertEquals("a second press has nothing left to take", RemedyRunner.ViewerOutcome.Recorded, runner.viewerPassthroughOff())
        assertEquals(2, runner.current!!.hops.size)
    }

    @Test
    fun theViewersPassthroughOffPressChangesNothingWithoutPassthroughOrBeforeTheLadderExists() {
        player.passthrough = false
        open()
        assertEquals(RemedyRunner.ViewerOutcome.Recorded, runner.viewerPassthroughOff())
        assertTrue(player.reopened.isEmpty())
        val fresh = RemedyRunner(FakePlayer(), clock)
        assertEquals(RemedyRunner.ViewerOutcome.NoClass, fresh.viewerPassthroughOff())
    }
}
