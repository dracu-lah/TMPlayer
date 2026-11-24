package com.tmplayer.player

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.KnownDevices
import com.tmplayer.data.KnownRemedies
import com.tmplayer.data.PlayerEngine
import com.tmplayer.data.RemedyMemory
import com.tmplayer.i18n.L

/**
 * The remedy ladder wired to the player (RL6, research 4.3): it owns the one second sampler tick
 * the two watches read ([FirstFrameWatch], [FrozenOutputWatch]), turns what the player reports
 * (errors, the first frame, codec errors from `AnalyticsListener`) into [RemedyEvidence], asks
 * [RemedyLadder], and carries out the answer through [Host]: a hop re-opens the video at the same
 * place, tracks, speed and subtitles on the new path (the player's own engine switch and resume
 * state do the carrying), the engine hop moves it to the other engine, and the status sheet comes
 * only when the ladder says no step is left. What a class learned goes to [RemedyMemory].
 *
 * Bounds, so it never loops and never costs a paused player anything:
 * - At most [RemedyLadder.MAX_HOPS] automatic hops per video, one per level (decoder, sound,
 *   engine); the player's older one-time switches ask [allowsDecoderSwitch] and [allowsEngineSwitch]
 *   first and say when they acted, so they share the same budget.
 * - The tick runs only while the player plays a picture someone can see, and stops by itself once
 *   the first frame came and the path played [RemedyLadder.WORKED_MS] clean; a seek watches for a
 *   stall again for [SEEK_WATCH_MS]. A pause, background audio or a released player posts nothing.
 * - The app's FFmpeg decoder failing, VLC failing, and any failure before the tracks are known are
 *   left to the player's existing handling (the switch back to the device's decoder, the engine
 *   sheet), as before.
 *
 * Main thread only.
 */
@UnstableApi
class RemedyRunner(private val host: Host, private val clock: Clock) {

    /** The main thread's clock and queue: the player's handler, a fake one in tests. */
    interface Clock {
        fun now(): Long
        fun post(delayMs: Long, task: Runnable)
        fun cancel(task: Runnable)
    }

    /** What the player reads and does for the runner. Each call is cheap and never throws. */
    interface Host {
        val sdk: Int
        val appVersion: String

        /** Days since 1970, for [RemedyMemory]. */
        fun today(): Int

        /** A short hashed tag of the video, never its title ([RemedyMemory.hopped]). */
        fun videoTag(): String

        /** ExoPlayer's state now, or null when it is not what plays (VLC, a cast, released). */
        fun sample(): Sample?

        /** The ladder's facts now, with [excluded] decoder names already left out. */
        fun facts(excluded: Set<String>): LadderFacts

        /** Whether one of the device's own decoders takes sound of [mime]: passthrough off needs one (A2). */
        fun deviceDecodesAudio(format: androidx.media3.common.Format?): Boolean = AudioPolicy.deviceDecodes(format)

        /** The class of what plays ([com.tmplayer.data.RemedyClassKey]), null while its formats are not known. */
        fun classKey(): String?

        /** Which renderer [error] came from, null when it is not a renderer's. */
        fun failedRenderer(error: PlaybackException): FailedRenderer?

        /** The engine choice's inputs when a hop to the other engine is allowed here, else null. */
        fun engineInput(): EngineInput?

        /** The engine playing now. */
        fun engineRunning(): EngineDecision

        /** ExoPlayer built again on [policyForBuild] at the same place, tracks, speed and subtitles; false when it cannot be. */
        fun reopen(): Boolean

        /** The video moved into [decision]'s engine at the same place; false when it cannot go there. */
        fun switchEngine(decision: EngineDecision): Boolean

        /** One plain line on screen. */
        fun say(text: String)

        /** A one-tap chip; [onTap] takes the offered step. */
        fun offer(text: String, action: String, onTap: () -> Unit)

        /** The status sheet: the picture could not be put right here. */
        fun sheet(text: String)

        /** Hand [error] to the player's usual handling again (after a wait the ladder asked for). */
        fun redeliver(error: PlaybackException)

        fun saveMemory(memory: RemedyMemory)

        fun log(line: String)

        /** A ladder hop for the playback trace (RL11): the step as taken, or the same step again with its result. */
        fun traceHop(hop: TraceHop) = Unit

        /** The ladder's facts for the playback trace (RL11): its class, remedies taken, paths visited and hops. */
        fun traceFacts(facts: Map<String, String>) = Unit
    }

    /** What the viewer pressed in the player's menu ("Report stutter", "Picture looks wrong"), see [viewerReported]. */
    enum class ViewerReport(val evidence: RemedyEvidence) {
        Stutter(RemedyEvidence.UserStutter),
        WrongPicture(RemedyEvidence.UserReport),
    }

    /** What [viewerReported] did, so the player can say it in words. */
    enum class ViewerOutcome {
        /** The picture's class is not known yet, or this is not ExoPlayer's picture: only the mark was kept. */
        NoClass,

        /** The press was recorded for the class; no step was taken (one was taken already, or none applies here). */
        Recorded,

        /** The press was recorded and the next step taken: the video plays another way now, said on screen by the runner. */
        Tried,
    }

    /** One look at ExoPlayer for the watches. */
    data class Sample(
        val nowMs: Long,
        val ready: Boolean,
        val buffering: Boolean,
        /** `isPlaying`: ready, asked to play, nothing holding it. */
        val playing: Boolean,
        val positionMs: Long,
        val speed: Float,
        val rendered: Long,
        val dropped: Long,
        val skipped: Long,
        /** A soundtrack is selected and playing. */
        val audioPlaying: Boolean,
        /** A real picture track is selected (not cover art, not sound alone). */
        val pictureExpected: Boolean,
        /** The picture is off on purpose (background audio, out of sight). */
        val hidden: Boolean,
        /** The picture went to the app's FFmpeg decoder. */
        val software: Boolean,
    )

    /** The renderer an error came from: its track type ([C.TRACK_TYPE_VIDEO], [C.TRACK_TYPE_AUDIO]) and whether it is FFmpeg's. */
    data class FailedRenderer(val trackType: Int, val ffmpeg: Boolean)

    private var memory = RemedyMemory.Empty
    private var known = KnownRemedies()
    private var active = false
    private var ladder: RemedyLadder? = null
    private var key: String? = null
    private var builtPath = RemedyPath()
    private var exhausted = false
    private var decoderSwitchedByItself = false

    /** The decoder the viewer picked for this video from the player's menu (WP-25): true for software, null until then. */
    private var viewerSoftware: Boolean? = null

    /** The viewer's software pick is too heavy for software here: never taught to the class (PB2-09). */
    private var viewerSoftwareHeavy = false

    /** The viewer turned passthrough off for this video (WP-24): for this video only, never taught to the class (PB2-05). */
    private var viewerPassthrough = false
    private var engineSwitchedByItself = false
    private val firstFrameWatch = FirstFrameWatch()
    private val frozenWatch = FrozenOutputWatch()
    private var pictureSeen = false
    private var cleanMs = 0L

    /** The picture played clean on a path that changed the sound while no sound played: not one to learn. */
    private var silentWhileClean = false
    private var lastCleanMs = -1L
    private var proven = false
    /** Until then a proven path is watched again for a stall: the stretch after a seek. */
    private var seekWatchUntilMs = -1L
    private var viewerStepTaken = false
    /** The last hop written to the trace with its result still "pending". */
    private var pendingHop: TraceHop? = null
    private var ticking = false
    private var codecErrors = 0
    private var later: Runnable? = null
    /** Until then the player in hand is about to be replaced by a re-open: its last errors are not evidence. */
    private var replacingUntilMs = -1L
    private val tick = Runnable { sampleNow() }

    /** The ladder of the video playing, for the trace and tests. */
    val current: RemedyLadder? get() = ladder

    /** Whether a tick is waiting: never while paused or stopped. */
    val tickPending: Boolean get() = ticking

    /**
     * A new video: everything learned in this one is dropped. [known] is what the device list says
     * here; [active] is false for sound alone, where there is no picture to watch.
     */
    fun newVideo(known: KnownRemedies, active: Boolean) {
        stop()
        this.known = known
        this.active = active
        ladder = null
        key = null
        builtPath = RemedyPath()
        exhausted = false
        replacingUntilMs = -1L
        decoderSwitchedByItself = false
        viewerSoftware = null
        viewerSoftwareHeavy = false
        viewerPassthrough = false
        engineSwitchedByItself = false
        codecErrors = 0
        viewerStepTaken = false
        pendingHop = null
        rearm()
    }

    /** The stored memory, read as the video opens. */
    fun memoryLoaded(stored: RemedyMemory) {
        memory = stored
    }

    /** Stops the tick and any wait: the screen goes, or a new video comes. */
    fun stop() {
        stopTicking()
        later?.let(clock::cancel)
        later = null
    }

    /** The path the next ExoPlayer is built on, logged by the caller. */
    fun policyForBuild(): DecoderPolicy {
        val planned = ladder?.path ?: startPath()
        // The viewer's decoder pick (WP-25) holds through a re-open for a sound or engine step.
        val path = viewerSoftware?.let { planned.copy(software = it) } ?: planned
        builtPath = path
        replacingUntilMs = -1L
        return DecoderPolicy.of(path, known.async, host.sdk)
    }

    /** Before the class is known: what the device list says about this device alone. */
    private fun startPath(): RemedyPath {
        if (!active || facts(emptySet()).decoderPicked) return RemedyPath()
        return RemedyPath(
            excluded = known.exclude.take(RemedyLadder.MAX_EXCLUDED).toSet(),
            asyncFlipped = known.async == true && host.sdk in 1 until RemedyLadder.ASYNC_DEFAULT_SDK,
        )
    }

    /**
     * The tracks are known: the class, its plan from the memory and the device list, and the
     * ladder. When the plan starts somewhere else than the player was built on, the video opens
     * again there once, before its first frame: not a hop, and nothing said on screen.
     */
    fun tracksKnown() {
        if (!active || ladder != null) return
        val k = host.classKey() ?: return
        key = k
        val plan = KnownDevices.plan(memory.planFor(k, host.appVersion), known)
        val running = host.engineRunning()
        val made = RemedyLadder(engineInput(), running, plan, facts(emptySet()))
        ladder = made
        host.log("Remedy: class $k, start ${made.taken.joinToString(",") { it.code }.ifEmpty { "default" }}")
        host.traceFacts(SessionFacts.ladder(k, made.taken, made.visited.size, made.hops.size))
        when {
            made.running.engine != running.engine -> if (!host.switchEngine(made.running)) host.log("Remedy: the planned engine cannot play this video here")
            made.path.copy(engine = builtPath.engine) != builtPath -> {
                replacing()
                if (!host.reopen()) {
                    replacingUntilMs = -1L
                    host.log("Remedy: the planned path could not be opened, the video stays on its own")
                }
            }
            // Playing already when the tracks came: the tick starts now, not at the next play.
            host.sample()?.playing == true -> startTicking()
        }
    }

    /** STATE_READY. */
    fun ready() {
        ladder?.ready()
    }

    /** `onRenderedFirstFrame`. */
    fun firstFrame() {
        ladder?.firstFrame()
        firstFrameWatch.firstFrame()
        if (!pictureSeen) {
            pictureSeen = true
            frozenWatch.arm()
        }
        // The picture back after background audio: the watches go on where they stopped.
        if (host.sample()?.playing == true) startTicking()
    }

    /** `onIsPlayingChanged`: the tick runs only while it plays. */
    fun playingChanged(playing: Boolean) {
        if (playing) startTicking() else stopTicking()
    }

    /** A seek asked by the viewer: the watches allow for it. */
    fun seeked() {
        firstFrameWatch.seeked()
        frozenWatch.seeked()
        lastCleanMs = -1L
        // A decoder that stalls does so after a seek most often: the frozen watch looks again for a while.
        seekWatchUntilMs = clock.now() + SEEK_WATCH_MS
        if (host.sample()?.playing == true) startTicking()
    }

    /**
     * `onVideoCodecError`: Media3 releases a codec whose error is recoverable and tries it once
     * more by itself, so this is logged and counted, not a step; a failure that stays reaches
     * [onPlayerError].
     */
    fun codecError(error: Exception) {
        codecErrors++
        val recoverable = (error as? android.media.MediaCodec.CodecException)?.isRecoverable
        host.log("Remedy: video codec error $codecErrors (${error.javaClass.simpleName}, recoverable ${recoverable ?: "?"})")
    }

    /**
     * A player error. True when it was dealt with here (a hop, or a short wait for the new path's
     * probation); false leaves it to the player's usual handling, which ends in the status sheet.
     */
    fun onPlayerError(error: PlaybackException): Boolean {
        val l = ladder ?: return false
        val k = key ?: return false
        if (!active || exhausted || host.engineRunning().engine != PlayerEngine.ExoPlayer) return false
        // The player that failed is already being replaced: the new path answers for itself.
        if (clock.now() < replacingUntilMs) return true
        val failed = host.failedRenderer(error)
        val evidence = evidenceOf(error, failed?.trackType) ?: return false
        // The app's own decoder failing goes back to the device's the way it always did (DecoderSwitch).
        if (failed?.ffmpeg == true) return false
        if (evidence != RemedyEvidence.AudioFailed && decoderSwitchedByItself) return false
        var facts = facts(l.path.excluded)
        if (evidence == RemedyEvidence.AudioFailed) {
            // An output that refused plain PCM is not helped by FFmpeg's PCM.
            facts = facts.copy(audioSoftwareTakes = AudioPolicy.softwareHelps(error, facts.audioSoftwareTakes))
            // Passthrough off (A2) helps only where the output refused Dolby or DTS and the device has its
            // own decoder for it; with none the track would go silent, so the engine hop comes next instead.
            val refused = AudioPolicy.refusedFormat(error)
            facts = facts.copy(passthrough = facts.passthrough && AudioPolicy.dolbyOrDts(refused) && host.deviceDecodesAudio(refused))
            // The output refused the bitstream: the next video of this class starts its sound in PCM (RL8).
            if (AudioPolicy.teachesSoftware(error, facts.audioSoftwareTakes)) {
                memory = memory.learned(k, Remedy.AudioSoftware, host.today())
                host.saveMemory(memory)
            }
        }
        stopTicking()
        return perform(l.onEvidence(evidence, facts, clock.now()), evidence, error)
    }

    /** Whether the player's own one-time decoder switch may still act: not once this video's step at that level is spent. */
    fun allowsDecoderSwitch(video: Boolean): Boolean {
        val l = ladder ?: return true
        if (exhausted || l.hops.size >= RemedyLadder.MAX_HOPS) return false
        val rungs = if (video) setOf(Rung.R1, Rung.R2) else setOf(Rung.A1, Rung.A2)
        return l.hops.none { it.remedy.rung in rungs }
    }

    /** The player's own decoder switch acted: the decoder level of this video is spent. */
    fun decoderSwitched(video: Boolean) {
        if (video) decoderSwitchedByItself = true
    }

    /** Whether the player's own engine switch may still act by itself. */
    fun allowsEngineSwitch(): Boolean {
        val l = ladder ?: return !engineSwitchedByItself
        if (exhausted || engineSwitchedByItself || l.hops.size >= RemedyLadder.MAX_HOPS) return false
        return l.hops.none { it.remedy.rung == Rung.R3 }
    }

    /** The player's own engine switch acted. */
    fun engineSwitched() {
        engineSwitchedByItself = true
    }

    /** The host's facts, with the decoder counted as picked once the viewer chose one in the menu: choices win over the ladder. */
    private fun facts(excluded: Set<String>): LadderFacts {
        val read = host.facts(excluded)
        return if (viewerSoftware != null) read.copy(decoderPicked = true) else read
    }

    /**
     * The viewer chose the other decoder from the player's menu (WP-25, "Try the other decoder"
     * while the video plays): a choice, not an automatic hop, so it is never refused and never
     * counted in [RemedyLadder.MAX_HOPS]. It spends this video's decoder level, so neither the
     * ladder nor the player's own one-time switch moves the picture back by itself (no loop); it
     * holds through a re-open ([policyForBuild]). Back to the device's decoder unlearns software for
     * the class at once; software is learned only the way the ladder learns, after
     * [RemedyLadder.WORKED_MS] of clean play on it ([countClean]), and never when [heavy] says the
     * picture is too much for software here (PB2-09: one curious press on a 1080p 10-bit HEVC video
     * sent every later one of its class on the 1 GB stick to FFmpeg at four frames a second). A
     * probation wait for the old path is dropped and the watches start again for the new one.
     *
     * No replace grace (PB2-02): the switch prepares the same player again in place, nothing builds
     * a new one to end the grace, so an error right after the press (FFmpeg's init, memory on a
     * small stick) was swallowed and the player sat idle with no sheet. Such an error now takes the
     * usual way: the decoder level is spent, so it ends in the status sheet.
     */
    fun viewerPickedDecoder(software: Boolean, heavy: Boolean = false) {
        viewerSoftware = software
        viewerSoftwareHeavy = software && heavy
        decoderSwitchedByItself = true
        later?.let(clock::cancel)
        later = null
        replacingUntilMs = -1L
        rearm()
        val k = key ?: return
        if (!active) return
        host.log("Remedy: viewer picked the ${if (software) "software" else "device"} decoder, class $k")
        if (software) return
        memory = memory.unlearned(k, Remedy.Software, host.today())
        host.saveMemory(memory)
    }

    /**
     * What a clean minute teaches the class ([countClean]): the ladder's steps, with the viewer's
     * own choices in their place. The decoder the viewer picked stands for the ladder's at that
     * level (software only when it is not too heavy here, PB2-09), and the press that turned
     * passthrough off for this video is not taught at all (PB2-05).
     */
    private fun learnedFrom(l: RemedyLadder): List<Remedy> {
        var taken = l.taken
        if (viewerPassthrough) taken = taken.filter { it != Remedy.PassthroughOff }
        when (viewerSoftware) {
            true -> taken = taken.filter { it != Remedy.Software } + listOfNotNull(Remedy.Software.takeIf { !viewerSoftwareHeavy })
            false -> taken = taken.filter { it != Remedy.Software }
            null -> Unit
        }
        return taken
    }

    /** The decoder the viewer picked in the menu for this video, null when none (WP-25). */
    val viewerDecoderSoftware: Boolean? get() = viewerSoftware

    private fun engineInput(): EngineInput =
        host.engineInput() ?: EngineInput(choice = EngineChoice.Automatic, device = DeviceFacts(), vlcAvailable = false)

    private fun startTicking() {
        if (ticking || !wanted()) return
        ticking = true
        clock.post(TICK_MS, tick)
    }

    private fun stopTicking() {
        if (ticking) clock.cancel(tick)
        ticking = false
        lastCleanMs = -1L
    }

    /**
     * Something is left to watch: a first frame not seen, or a stall not yet caught on a path not
     * yet proven or just after a seek. A proven path posts nothing more until the next seek or hop,
     * so a long film costs no tick after its first minute (the 1 GB stick's idle CPU).
     */
    private fun wanted(): Boolean {
        if (!active || exhausted || ladder == null) return false
        if (!firstFrameWatch.done) return true
        return !frozenWatch.fired && (!proven || clock.now() < seekWatchUntilMs)
    }

    internal fun sampleNow() {
        ticking = false
        val l = ladder ?: return
        if (!wanted()) return
        val s = host.sample() ?: return
        val now = s.nowMs
        if (firstFrameWatch.sample(now, s.ready, s.audioPlaying, s.buffering, false, s.software, s.hidden, !s.pictureExpected)) {
            if (perform(l.onEvidence(RemedyEvidence.NoFirstFrame, facts(l.path.excluded), clock.now()), RemedyEvidence.NoFirstFrame, null)) return
        }
        val steady = s.playing && s.ready && !s.buffering && !s.hidden && s.pictureExpected
        if (pictureSeen && frozenWatch.sample(now, s.positionMs, steady, s.rendered, s.dropped, s.skipped, s.speed)) {
            if (perform(l.onEvidence(RemedyEvidence.Frozen, facts(l.path.excluded), clock.now()), RemedyEvidence.Frozen, null)) return
        }
        // A path that changed the sound is not learned while no sound plays (a track it left unsupported).
        if (steady && !s.audioPlaying && l.path.soundChanged()) silentWhileClean = true
        countClean(l, steady, now)
        // The picture off on purpose (background audio): nothing to watch until it is back ([firstFrame]).
        if (s.playing && !s.hidden) startTicking()
    }

    /** Clean playing time on this path since its first frame; at [RemedyLadder.WORKED_MS] the class learns it. */
    private fun countClean(l: RemedyLadder, steady: Boolean, now: Long) {
        if (!pictureSeen || !steady) {
            lastCleanMs = -1L
            return
        }
        val step = if (lastCleanMs < 0) 0L else now - lastCleanMs
        if (step in 1..FrozenOutputWatch.MAX_GAP_MS) cleanMs += step
        lastCleanMs = now
        if (cleanMs >= RemedyLadder.STABLE_MS) traceResult(TraceHop.WORKED)
        if (!l.played(cleanMs)) return
        proven = true
        val k = key ?: return
        if (silentWhileClean) {
            host.log("Remedy: class $k played clean without sound on ${l.taken.joinToString(",") { it.code }}: not learned")
            return
        }
        val taught = learnedFrom(l)
        memory = memory.worked(k, taught, host.today())
        host.saveMemory(memory)
        host.log("Remedy: class $k played ${RemedyLadder.WORKED_MS / 1000} s clean on ${taught.joinToString(",") { it.code }.ifEmpty { "default" }}")
    }

    /** Carries out [action]; true when the evidence was dealt with here. */
    private fun perform(action: RemedyAction, evidence: RemedyEvidence, error: PlaybackException?): Boolean {
        val l = ladder ?: return false
        val k = key ?: return false
        return when (action) {
            is RemedyAction.Hop -> hop(l, k, action, error)
            is RemedyAction.Offer -> {
                host.offer(offerText(action.remedy), L.playerRemedyTry) {
                    val now = ladder ?: return@offer
                    perform(now.accept(action, facts(now.path.excluded), clock.now()), action.evidence, null)
                }
                false
            }
            is RemedyAction.Later -> {
                wait(action.atMs, evidence, error)
                true
            }
            RemedyAction.Stay -> false
            RemedyAction.Sheet -> {
                exhaust(l, k, evidence, error)
                error == null
            }
        }
    }

    private fun hop(l: RemedyLadder, k: String, hop: RemedyAction.Hop, error: PlaybackException?, learn: Boolean = true): Boolean {
        // The viewer's passthrough press is for this video alone: no hop, no failure for the class (PB2-05).
        if (learn) {
            memory = memory.hopped(k, hop, host.videoTag(), host.appVersion, host.today())
            host.saveMemory(memory)
        }
        // The step before this one, if it was still on probation, did not hold: the trace says so.
        traceResult(TraceHop.FAILED)
        val traced = TraceHop(System.currentTimeMillis(), hop.evidence.name, hop.replaced?.code ?: "default", hop.remedy.code)
        pendingHop = traced
        host.traceHop(traced)
        host.traceFacts(SessionFacts.ladder(k, l.taken, l.visited.size, l.hops.size))
        host.log(
            "Remedy: ${hop.evidence.name} -> ${hop.remedy.code} (${hop.remedy.rung}), replaced ${hop.replaced?.code ?: "default"}, " +
                "hop ${l.hops.size} of ${RemedyLadder.MAX_HOPS}, class $k" + (error?.let { ", ${it.errorCodeName}" } ?: ""),
        )
        rearm()
        val engine = hop.engine
        if (engine != null) {
            replacing()
            if (!host.switchEngine(engine)) {
                replacingUntilMs = -1L
                exhaust(l, k, hop.evidence, error)
                return error == null
            }
            host.say(L.playerRemedyAnotherWay)
            return true
        }
        replacing()
        if (!host.reopen()) {
            replacingUntilMs = -1L
            exhaust(l, k, hop.evidence, error)
            return error == null
        }
        host.say(
            when (hop.remedy) {
                Remedy.Reload -> L.playerRemedyPictureRestart
                Remedy.AudioSoftware, Remedy.PassthroughOff -> L.playerRemedySoundAnotherWay
                else -> L.playerRemedyAnotherWay
            },
        )
        return true
    }

    /** The new path's probation: the same evidence asked again at [atMs], once. */
    private fun wait(atMs: Long, evidence: RemedyEvidence, error: PlaybackException?) {
        later?.let(clock::cancel)
        val task = Runnable {
            later = null
            val l = ladder ?: return@Runnable
            val dealt = !exhausted && perform(l.onEvidence(evidence, facts(l.path.excluded), clock.now()), evidence, error)
            if (!dealt && error != null) host.redeliver(error)
        }
        later = task
        clock.post((atMs - clock.now()).coerceIn(0L, RemedyLadder.PROBATION_MS), task)
    }

    /** The pending hop got its [result]: written again, the same step with its answer. Once per hop. */
    private fun traceResult(result: String) {
        val pending = pendingHop ?: return
        pendingHop = null
        host.traceHop(pending.copy(result = result))
    }

    /**
     * The viewer pressed "Report stutter" or "Picture looks wrong" (RL10). The press is hard
     * evidence for the picture's class: a stutter is counted in the class memory (its next stutter
     * steps by itself), and the ladder's next step is taken once for this video, whatever the
     * "Never switch by itself" choice says, because the press is the consent. A second press, a
     * press while the last step is on probation, one with nothing left to try and one on a picture
     * that is not ExoPlayer's change nothing more than the mark the caller keeps for Send logs, and
     * never open the status sheet: the video plays, the viewer only said it could be better.
     */
    fun viewerReported(report: ViewerReport): ViewerOutcome {
        val l = ladder ?: return ViewerOutcome.NoClass
        val k = key ?: return ViewerOutcome.NoClass
        if (!active) return ViewerOutcome.NoClass
        host.log("Remedy: viewer reported ${report.name}, class $k")
        if (report == ViewerReport.Stutter) {
            memory = memory.stuttered(k, host.today())
            host.saveMemory(memory)
        }
        if (viewerStepTaken || exhausted || host.engineRunning().engine != PlayerEngine.ExoPlayer) return ViewerOutcome.Recorded
        val action = l.onEvidence(report.evidence, facts(l.path.excluded), clock.now())
        if (action !is RemedyAction.Hop) return ViewerOutcome.Recorded
        viewerStepTaken = true
        stopTicking()
        val dealt = hop(l, k, action, null)
        return if (dealt && !exhausted) ViewerOutcome.Tried else ViewerOutcome.Recorded
    }

    /**
     * The viewer pressed "Turn off passthrough for this video" (WP-24): the sound is rebuilt as PCM,
     * so Volume boost and Night mode reach it. It is the ladder's own passthrough-off step (A2), taken
     * as a viewer's press (consent without the "Never switch by itself" choice), sharing the hop
     * count with every other step; nothing is learned for the class from it. [ViewerOutcome.Tried]
     * when the sound was rebuilt, [ViewerOutcome.Recorded] when nothing could be done (the step is
     * spent, the budget used, not ExoPlayer's sound), [ViewerOutcome.NoClass] before the ladder exists.
     */
    fun viewerPassthroughOff(): ViewerOutcome {
        val l = ladder ?: return ViewerOutcome.NoClass
        val k = key ?: return ViewerOutcome.NoClass
        host.log("Remedy: viewer asked for passthrough off, class $k")
        if (exhausted || host.engineRunning().engine != PlayerEngine.ExoPlayer) return ViewerOutcome.Recorded
        val action = l.viewerPress(Remedy.PassthroughOff, RemedyEvidence.UserReport, facts(l.path.excluded), clock.now())
        if (action !is RemedyAction.Hop) return ViewerOutcome.Recorded
        stopTicking()
        viewerPassthrough = true
        val dealt = hop(l, k, action, null, learn = false)
        return if (dealt && !exhausted) ViewerOutcome.Tried else ViewerOutcome.Recorded
    }

    private fun exhaust(l: RemedyLadder, k: String, evidence: RemedyEvidence, error: PlaybackException?) {
        exhausted = true
        stopTicking()
        val last = l.taken.lastOrNull()?.takeUnless { viewerPassthrough && it == Remedy.PassthroughOff }
        memory = memory.failed(k, last, host.videoTag(), host.appVersion, host.today())
        host.saveMemory(memory)
        host.log("Remedy: no step left for ${evidence.name} after ${l.hops.size} hops, class $k")
        // The viewer's press never opens the sheet: the video was playing, see [viewerReported].
        if (error != null || evidence.fromViewer) return
        host.sheet(if (evidence == RemedyEvidence.NoFirstFrame) L.playerRemedyNoPicture else L.playerRemedyPictureStopped)
    }

    private fun offerText(remedy: Remedy): String = when (remedy) {
        Remedy.AudioSoftware, Remedy.PassthroughOff -> L.playerRemedySoundAnotherWay
        else -> L.playerRemedyAnotherWay
    }

    private fun replacing() {
        replacingUntilMs = clock.now() + REPLACE_GRACE_MS
    }

    /** A new path (a hop, a re-open): every watch starts again, nothing carried over. */
    private fun rearm() {
        stopTicking()
        firstFrameWatch.arm()
        frozenWatch.arm()
        pictureSeen = false
        cleanMs = 0L
        silentWhileClean = false
        lastCleanMs = -1L
        proven = false
        seekWatchUntilMs = -1L
    }

    private fun RemedyPath.soundChanged(): Boolean = passthroughOff || audioSoftware

    companion object {
        const val TICK_MS = 1_000L

        /** How long after a seek a proven path is watched for a stall. */
        const val SEEK_WATCH_MS = 15_000L

        /** How long the errors of a player being replaced are ignored, at most: the re-open is posted, then builds. */
        const val REPLACE_GRACE_MS = 2_000L

        /**
         * What a player error tells the ladder, from its code and the track of the renderer that
         * failed; null for anything that is not a decoder's or the sound output's failure (the
         * source, the network, a file gone), which no decoder change helps.
         */
        fun evidenceOf(error: PlaybackException, trackType: Int?): RemedyEvidence? = when (error.errorCode) {
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> RemedyEvidence.AudioFailed
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            -> when (trackType) {
                C.TRACK_TYPE_VIDEO -> RemedyEvidence.DecoderError
                C.TRACK_TYPE_AUDIO -> RemedyEvidence.AudioFailed
                else -> null
            }
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> when (trackType) {
                C.TRACK_TYPE_VIDEO -> RemedyEvidence.NoDecoder
                C.TRACK_TYPE_AUDIO -> RemedyEvidence.AudioFailed
                else -> null
            }
            else -> null
        }

        /** The player's handler as the runner's clock. */
        fun clockOf(handler: Handler): Clock = object : Clock {
            override fun now(): Long = SystemClock.elapsedRealtime()
            override fun post(delayMs: Long, task: Runnable) {
                handler.postDelayed(task, delayMs)
            }
            override fun cancel(task: Runnable) = handler.removeCallbacks(task)
        }
    }
}
