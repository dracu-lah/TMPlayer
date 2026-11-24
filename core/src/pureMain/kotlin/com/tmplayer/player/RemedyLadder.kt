package com.tmplayer.player

import com.tmplayer.data.DecodeJudge
import com.tmplayer.data.PlayerEngine

/**
 * Where a video of one class starts, from [com.tmplayer.data.RemedyMemory.planFor]: the remedies
 * that worked for the class ([start]), the ones that failed on two videos in this app version
 * ([skip]), the decoder names that failed ([exclude]) and whether the class stuttered before.
 */
data class RemedyPlan(
    val start: Set<Remedy> = emptySet(),
    val skip: Set<Remedy> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val stutteredBefore: Boolean = false,
)

/**
 * The path a video plays on, the element of the ladder's visited set: the engine and, in
 * ExoPlayer, what was changed from the defaults. Every field only ever moves away from the
 * default during one video, so a hop can never return to a path it left.
 */
data class RemedyPath(
    val engine: PlayerEngine = PlayerEngine.ExoPlayer,
    /** The app's software video decoder (R2). */
    val software: Boolean = false,
    /** Decoder names left out of the codec list (R1c). */
    val excluded: Set<String> = emptySet(),
    /** Async MediaCodec queueing turned on (R1a). */
    val asyncFlipped: Boolean = false,
    /** Tunneled playback turned off (R1b). */
    val tunnelingOff: Boolean = false,
    /** Re-prepared once at the same position. */
    val reloaded: Boolean = false,
    /** The app's software audio decoder (A1). */
    val audioSoftware: Boolean = false,
    /** Audio passthrough off (A2). */
    val passthroughOff: Boolean = false,
)

/** What is true at the moment of the evidence, read by the player each time it asks. */
data class LadderFacts(
    /** `Build.VERSION.SDK_INT`; 0 when not known. */
    val sdk: Int = 0,
    /** The video decoder playing now (`MediaCodecInfo.name`), when known. */
    val decoderName: String? = null,
    /** Another decoder is listed for this format once [decoderName] is left out. */
    val otherDecoder: Boolean = false,
    /** The app's FFmpeg decoder takes this video format. */
    val softwareTakes: Boolean = false,
    /** [DecodeJudge] for the picture in software. */
    val softwareVerdict: DecodeJudge.Verdict = DecodeJudge.Verdict.UNKNOWN,
    /** Tunneled playback is on now. */
    val tunneling: Boolean = false,
    /** The viewer picked the video decoder in Settings: no decoder hop by itself. */
    val decoderPicked: Boolean = false,
    /** The viewer set Tunneled playback to On or Off: it is not flipped. */
    val tunnelingPicked: Boolean = false,
    /** The app's FFmpeg audio decoder takes this audio format. */
    val audioSoftwareTakes: Boolean = false,
    /** Audio passthrough is on now. */
    val passthrough: Boolean = false,
    /** Off when the viewer set "Never switch by itself": hard evidence then only offers. */
    val autoSwitch: Boolean = true,
)

/** What [RemedyLadder] tells the player to do. */
sealed interface RemedyAction {
    /**
     * Re-prepare at the same position, tracks, speed and subtitles on [path] (in [engine] when it
     * is set: the engine hop). [replaced] is the remedy that was running at the same level and
     * failed, null for the defaults.
     */
    data class Hop(
        val remedy: Remedy,
        val path: RemedyPath,
        val evidence: RemedyEvidence,
        val replaced: Remedy? = null,
        val engine: EngineDecision? = null,
    ) : RemedyAction

    /** Show a one-tap chip with the reason; a tap goes to [RemedyLadder.accept]. One per video. */
    data class Offer(val remedy: Remedy, val evidence: RemedyEvidence) : RemedyAction

    /** Too soon after a hop: the new path is on probation. Send the evidence again at [atMs] if it holds. */
    data class Later(val atMs: Long) : RemedyAction

    /** Nothing to change (bytes not arriving, soft evidence already offered). */
    data object Stay : RemedyAction

    /** No step left: the status sheet (R4) with a lighter copy, download first, another app, Send logs. */
    data object Sheet : RemedyAction
}

/**
 * The automatic remedy ladder for one video (RL1, research section 4): reads [RemedyEvidence],
 * picks the next remedy for its class, and never loops. Pure, no Android types.
 *
 * The rules ("never loops", 4.2):
 * - At most [MAX_HOPS] automatic hops per video, one per level: decoder (R1 or R2), audio (A1 or
 *   A2), engine (R3). The engine hop is [EngineSelector.afterFailure]'s, never a policy of its own.
 * - A hop to a path already visited is refused.
 * - No hop within [PROBATION_MS] of the last one ([RemedyAction.Later]), none on [RemedyEvidence.Source].
 * - Soft evidence offers once per video; only a class that stuttered before takes a stutter step by itself.
 * - The viewer's choices win: a picked engine (through [EngineSelector]), a picked decoder, a set
 *   tunneling mode, and "Never switch by itself" (then hard evidence offers too).
 * - No picture after a hop: stop, the sheet (research C7), so a bad hop never leaves sound over black.
 * - After the sheet, only the sheet.
 *
 * Usage (RL6 wires it):
 * ```
 * val plan = memory.planFor(key, appVersion)
 * val ladder = RemedyLadder(engineInput, EngineSelector.choose(engineInput), plan, factsNow())
 * open(ladder.path, ladder.running)              // memory's working remedies already applied
 * // onPlaybackStateChanged(READY): ladder.ready(); onRenderedFirstFrame: ladder.firstFrame()
 * when (val action = ladder.onEvidence(RemedyEvidence.Frozen, factsNow(), nowMs)) {
 *     is RemedyAction.Hop -> { memory = memory.hopped(key, action, videoTag, appVersion, today); reopen(action) }
 *     is RemedyAction.Offer -> showChip(action)   // tap: ladder.accept(action, factsNow(), nowMs)
 *     is RemedyAction.Later -> askAgainAt(action.atMs)
 *     RemedyAction.Sheet -> { memory = memory.failed(key, ladder.taken.lastOrNull(), videoTag, appVersion, today); showSheet() }
 *     RemedyAction.Stay -> Unit
 * }
 * if (ladder.played(cleanPlayingMs)) memory = memory.worked(key, ladder.taken, today)
 * ```
 */
class RemedyLadder(
    private val engineInput: EngineInput,
    running: EngineDecision,
    private val plan: RemedyPlan = RemedyPlan(),
    facts: LadderFacts = LadderFacts(),
) {
    enum class Phase { Opening, FirstFrameWait, Probation, Stable, Exhausted }

    /** The engine playing now, as [EngineSelector] decided it. */
    var running: EngineDecision = running
        private set

    /** The path playing now. */
    var path: RemedyPath = RemedyPath(engine = running.engine)
        private set

    var phase: Phase = Phase.Opening
        private set

    private val takenList = mutableListOf<Remedy>()
    private val hopList = mutableListOf<RemedyAction.Hop>()
    private val visitedPaths = mutableSetOf<RemedyPath>()
    private val levelsUsed = mutableSetOf<Int>()
    private var engineSwitches = 0
    private var offered = false
    private var workedSaid = false
    private var lastHopAtMs: Long? = null

    /** The remedies in effect: the plan's start ones, then each hop's, in order. For [com.tmplayer.data.RemedyMemory.worked]. */
    val taken: List<Remedy> get() = takenList.toList()

    /** The hops of this video, for the trace and the stutter report. */
    val hops: List<RemedyAction.Hop> get() = hopList.toList()

    /** Every path this video played on. */
    val visited: Set<RemedyPath> get() = visitedPaths.toSet()

    init {
        if (running.engine == PlayerEngine.ExoPlayer && !facts.decoderPicked && plan.exclude.isNotEmpty()) {
            path = path.copy(excluded = plan.exclude.take(MAX_EXCLUDED).toSet())
            takenList += Remedy.ExcludeDecoder
        }
        for (remedy in plan.start.sortedBy { it.ordinal }) {
            if (remedy in plan.skip || remedy == Remedy.ExcludeDecoder) continue
            val step = step(remedy, facts) ?: continue
            path = step.path
            step.engine?.let { this.running = it; engineSwitches++ }
            takenList += remedy
        }
        visitedPaths += path
    }

    /** The player reached READY. */
    fun ready() {
        if (phase == Phase.Opening) phase = Phase.FirstFrameWait
    }

    /** The first frame of the path reached the screen. */
    fun firstFrame() {
        if (phase == Phase.Opening || phase == Phase.FirstFrameWait) phase = Phase.Probation
    }

    /**
     * Clean playing time on this path since its first frame ([cleanMs], the runner's count: no
     * pause, no buffering). True once, at [WORKED_MS]: then the runner tells the memory the path worked.
     */
    fun played(cleanMs: Long): Boolean {
        if (phase != Phase.Probation && phase != Phase.Stable) return false
        if (cleanMs >= STABLE_MS) phase = Phase.Stable
        if (workedSaid || cleanMs < WORKED_MS) return false
        workedSaid = true
        return true
    }

    /** What to do about [evidence], given [facts] as they are now, at [atMs] (any monotonic clock). */
    fun onEvidence(evidence: RemedyEvidence, facts: LadderFacts, atMs: Long): RemedyAction {
        if (evidence == RemedyEvidence.Source) return RemedyAction.Stay
        val viewer = evidence.fromViewer
        if (phase == Phase.Exhausted) return if (evidence.hard && !viewer) RemedyAction.Sheet else RemedyAction.Stay
        lastHopAtMs?.let { if (atMs - it < PROBATION_MS) return RemedyAction.Later(it + PROBATION_MS) }
        val onSoftware = path.software && path.engine == PlayerEngine.ExoPlayer
        val stutter = evidence == RemedyEvidence.Stutter || evidence == RemedyEvidence.UserStutter
        if (evidence == RemedyEvidence.SoftwareTooSlow || (stutter && onSoftware)) {
            return offer(Remedy.LighterCopy, evidence) ?: RemedyAction.Stay
        }
        if (evidence == RemedyEvidence.NoFirstFrame && hopList.isNotEmpty()) return exhaust()
        val auto = (facts.autoSwitch || viewer) && (evidence.hard || (evidence == RemedyEvidence.Stutter && plan.stutteredBefore))
        val pick = pick(ladderFor(evidence), facts)
        // A viewer's press with nothing left to try is "Stay", not the sheet: see [RemedyEvidence.fromViewer].
        fun dead(): RemedyAction = if (evidence.hard && !viewer) exhaust() else RemedyAction.Stay
        return when {
            pick == null -> dead()
            auto -> hop(pick, evidence, atMs)
            else -> offer(pick.remedy, evidence) ?: dead()
        }
    }

    /** The viewer tapped [offer]: the hop if it still fits the budget, else the sheet. A lighter copy is the copy chooser's. */
    fun accept(offer: RemedyAction.Offer, facts: LadderFacts, atMs: Long): RemedyAction {
        if (offer.remedy == Remedy.LighterCopy) return RemedyAction.Stay
        if (phase == Phase.Exhausted) return RemedyAction.Sheet
        val pick = pick(listOf(offer.remedy), facts) ?: return exhaust()
        return hop(pick, offer.evidence, atMs)
    }

    /**
     * The viewer chose [remedy] themselves (Turn off passthrough for this video, WP-24): the press is
     * its own consent, so no "Switch by itself" setting and no probation wait applies, but the hop
     * budget is the ladder's own ([MAX_HOPS], shared with every other hop) and a step that does not
     * apply (no passthrough on this path, the level spent) or leads to a path already visited is
     * "Stay".
     */
    fun viewerPress(remedy: Remedy, evidence: RemedyEvidence, facts: LadderFacts, atMs: Long): RemedyAction {
        if (phase == Phase.Exhausted) return RemedyAction.Stay
        val pick = pick(listOf(remedy), facts) ?: return RemedyAction.Stay
        return hop(pick, evidence, atMs)
    }

    private class Pick(val remedy: Remedy, val path: RemedyPath, val engine: EngineDecision?)

    private fun pick(order: List<Remedy>, facts: LadderFacts): Pick? {
        if (hopList.size >= MAX_HOPS) return null
        for (remedy in order) {
            if (remedy in plan.skip || remedy.level in levelsUsed) continue
            val step = step(remedy, facts) ?: continue
            if (step.path in visitedPaths) continue
            return step
        }
        return null
    }

    /** The path [remedy] leads to from here, or null when it does not apply. */
    private fun step(remedy: Remedy, facts: LadderFacts): Pick? {
        val exo = path.engine == PlayerEngine.ExoPlayer
        val hardwarePicture = exo && !path.software
        val name = facts.decoderName?.trim()?.takeIf { it.isNotEmpty() }
        val next: RemedyPath? = when (remedy) {
            Remedy.Reload -> path.copy(reloaded = true).takeIf { !path.reloaded }
            Remedy.ExcludeDecoder ->
                if (hardwarePicture && !facts.decoderPicked && facts.otherDecoder && name != null &&
                    name !in path.excluded && path.excluded.size < MAX_EXCLUDED
                ) path.copy(excluded = path.excluded + name) else null
            Remedy.FlipAsync ->
                path.copy(asyncFlipped = true).takeIf { hardwarePicture && !path.asyncFlipped && facts.sdk in 1 until ASYNC_DEFAULT_SDK }
            Remedy.FlipTunneling ->
                path.copy(tunnelingOff = true).takeIf { exo && facts.tunneling && !facts.tunnelingPicked && !path.tunnelingOff }
            Remedy.Software ->
                path.copy(software = true).takeIf {
                    hardwarePicture && !facts.decoderPicked && facts.softwareTakes &&
                        facts.softwareVerdict != DecodeJudge.Verdict.SOFTWARE_TOO_SLOW
                }
            Remedy.AudioSoftware -> path.copy(audioSoftware = true).takeIf { exo && !path.audioSoftware && facts.audioSoftwareTakes }
            Remedy.PassthroughOff -> path.copy(passthroughOff = true).takeIf { exo && facts.passthrough && !path.passthroughOff }
            Remedy.OtherEngine -> {
                val decision = EngineSelector.afterFailure(engineInput, running, EngineFailure.Decoder, engineSwitches)
                    ?: return null
                return Pick(remedy, RemedyPath(engine = decision.engine), decision)
            }
            Remedy.LighterCopy -> null
        }
        return next?.let { Pick(remedy, it, null) }
    }

    private fun hop(pick: Pick, evidence: RemedyEvidence, atMs: Long): RemedyAction.Hop {
        val audio = pick.remedy.level == Remedy.AudioSoftware.level
        val replaced = takenList.lastOrNull { (it.level == Remedy.AudioSoftware.level) == audio }
        val hop = RemedyAction.Hop(pick.remedy, pick.path, evidence, replaced, pick.engine)
        path = pick.path
        pick.engine?.let { running = it; engineSwitches++ }
        visitedPaths += path
        levelsUsed += pick.remedy.level
        takenList += pick.remedy
        hopList += hop
        lastHopAtMs = atMs
        phase = Phase.Opening
        workedSaid = false
        return hop
    }

    private fun offer(remedy: Remedy, evidence: RemedyEvidence): RemedyAction.Offer? {
        if (offered) return null
        offered = true
        return RemedyAction.Offer(remedy, evidence)
    }

    private fun exhaust(): RemedyAction {
        phase = Phase.Exhausted
        return RemedyAction.Sheet
    }

    companion object {
        /** Automatic hops per video: one decoder, audio or engine level each, two in all (4.2). */
        const val MAX_HOPS = 2

        /** No hop this soon after another: the new path's probation. */
        const val PROBATION_MS = 1_500L

        /** Clean play after the first frame that makes a path stable. */
        const val STABLE_MS = 12_000L

        /** Clean play on a path that makes it the class's working path. */
        const val WORKED_MS = 60_000L

        /** Media3 queues MediaCodec asynchronously from this API level by default: R1a flips it on below. */
        const val ASYNC_DEFAULT_SDK = 31

        /** Decoder names left out at most, per video and per class. */
        const val MAX_EXCLUDED = 3

        /** The remedies to try for [evidence], first that applies wins (research 4.4). */
        fun ladderFor(evidence: RemedyEvidence): List<Remedy> = when (evidence) {
            RemedyEvidence.DecoderError -> listOf(Remedy.ExcludeDecoder, Remedy.Software, Remedy.OtherEngine)
            RemedyEvidence.NoDecoder -> listOf(Remedy.Software, Remedy.OtherEngine)
            RemedyEvidence.NoFirstFrame ->
                listOf(Remedy.ExcludeDecoder, Remedy.FlipAsync, Remedy.FlipTunneling, Remedy.Software, Remedy.OtherEngine)
            RemedyEvidence.Frozen -> listOf(Remedy.Reload, Remedy.ExcludeDecoder, Remedy.Software, Remedy.OtherEngine)
            RemedyEvidence.WrongPicture, RemedyEvidence.UserReport ->
                listOf(Remedy.ExcludeDecoder, Remedy.Software, Remedy.OtherEngine)
            RemedyEvidence.AudioFailed -> listOf(Remedy.AudioSoftware, Remedy.PassthroughOff, Remedy.OtherEngine)
            RemedyEvidence.Stutter, RemedyEvidence.UserStutter ->
                listOf(Remedy.FlipAsync, Remedy.FlipTunneling, Remedy.Software, Remedy.OtherEngine)
            RemedyEvidence.SoftwareTooSlow, RemedyEvidence.Source -> emptyList()
        }
    }
}
