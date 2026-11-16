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
                audioPlaying = playing && ready,
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
        )

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
}
