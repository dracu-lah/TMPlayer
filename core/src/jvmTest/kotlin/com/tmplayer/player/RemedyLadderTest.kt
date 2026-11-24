package com.tmplayer.player

import com.tmplayer.data.DecodeJudge.Verdict
import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.PlayerEngine
import com.tmplayer.player.RemedyEvidence.AudioFailed
import com.tmplayer.player.RemedyEvidence.DecoderError
import com.tmplayer.player.RemedyEvidence.Frozen
import com.tmplayer.player.RemedyEvidence.NoDecoder
import com.tmplayer.player.RemedyEvidence.NoFirstFrame
import com.tmplayer.player.RemedyEvidence.SoftwareTooSlow
import com.tmplayer.player.RemedyEvidence.Source
import com.tmplayer.player.RemedyEvidence.Stutter
import com.tmplayer.player.RemedyEvidence.UserReport
import com.tmplayer.player.RemedyEvidence.UserStutter
import com.tmplayer.player.RemedyEvidence.WrongPicture
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemedyLadderTest {

    private fun input(choice: EngineChoice = EngineChoice.Automatic, vlc: Boolean = true) =
        EngineInput(choice, DeviceFacts(hardware = "testchip", sdk = 28), vlcAvailable = vlc, known = emptyList())

    private val exo = EngineDecision(PlayerEngine.ExoPlayer, EngineReason.Default)

    /** A device where everything applies: another decoder listed, software fits, passthrough on. */
    private val rich = LadderFacts(
        sdk = 28,
        decoderName = "OMX.vendor.hevc",
        otherDecoder = true,
        softwareTakes = true,
        softwareVerdict = Verdict.SOFTWARE_FITS,
        audioSoftwareTakes = true,
        passthrough = true,
    )

    private fun describe(action: RemedyAction): String = when (action) {
        is RemedyAction.Hop -> "hop:${action.remedy}"
        is RemedyAction.Offer -> "offer:${action.remedy}"
        is RemedyAction.Later -> "later"
        RemedyAction.Stay -> "stay"
        RemedyAction.Sheet -> "sheet"
    }

    private data class Row(
        val name: String,
        val evidence: List<RemedyEvidence>,
        val expected: List<String>,
        val facts: LadderFacts,
        val plan: RemedyPlan = RemedyPlan(),
        val vlc: Boolean = true,
        val choice: EngineChoice = EngineChoice.Automatic,
    )

    private fun run(row: Row): List<String> {
        val ladder = RemedyLadder(input(row.choice, row.vlc), exo, row.plan, row.facts)
        // Two seconds apart: past each hop's probation.
        return row.evidence.mapIndexed { i, e -> describe(ladder.onEvidence(e, row.facts, i * 2_000L)) }
    }

    /** Every row of research 4.4, with the "then" columns reached by what applies or by the class memory's skip. */
    private val table = listOf(
        // Thrown init or decoding error: R1c, then R2, then R3, R4.
        Row("init error: other name, then engine, then sheet", listOf(DecoderError, DecoderError, DecoderError),
            listOf("hop:ExcludeDecoder", "hop:OtherEngine", "sheet"), rich),
        Row("init error, no other name: software", listOf(DecoderError), listOf("hop:Software"), rich.copy(otherDecoder = false)),
        Row("init error, name failed on two videos: software", listOf(DecoderError), listOf("hop:Software"), rich,
            plan = RemedyPlan(skip = setOf(Remedy.ExcludeDecoder))),
        Row("init error, nothing else here: sheet", listOf(DecoderError), listOf("sheet"),
            rich.copy(otherDecoder = false, softwareTakes = false), vlc = false),
        // No decoder for the format: R2, R3, R4.
        Row("no decoder: software", listOf(NoDecoder), listOf("hop:Software"), rich),
        Row("no decoder, software too slow: engine", listOf(NoDecoder), listOf("hop:OtherEngine"),
            rich.copy(softwareVerdict = Verdict.SOFTWARE_TOO_SLOW)),
        Row("no decoder, too slow, no VLC: sheet", listOf(NoDecoder), listOf("sheet"),
            rich.copy(softwareVerdict = Verdict.SOFTWARE_TOO_SLOW), vlc = false),
        // No first frame: R1c, R1a or R1b by SDK, R2, R3, R4; no picture after a hop stops.
        Row("no first frame: other name", listOf(NoFirstFrame), listOf("hop:ExcludeDecoder"), rich),
        Row("no first frame, API 28, one decoder: async", listOf(NoFirstFrame), listOf("hop:FlipAsync"), rich.copy(otherDecoder = false)),
        Row("no first frame, API 33, tunneled: tunneling off", listOf(NoFirstFrame), listOf("hop:FlipTunneling"),
            rich.copy(sdk = 33, otherDecoder = false, tunneling = true)),
        Row("no first frame, API 33, not tunneled: software", listOf(NoFirstFrame), listOf("hop:Software"),
            rich.copy(sdk = 33, otherDecoder = false)),
        Row("no first frame again after a hop: sheet", listOf(NoFirstFrame, NoFirstFrame, Frozen),
            listOf("hop:ExcludeDecoder", "sheet", "sheet"), rich),
        // Frozen output: reload, R1c, R2, R3, R4.
        Row("frozen: reload, then engine, then sheet", listOf(Frozen, Frozen, Frozen),
            listOf("hop:Reload", "hop:OtherEngine", "sheet"), rich),
        Row("frozen, reload failed on two videos: other name", listOf(Frozen), listOf("hop:ExcludeDecoder"), rich,
            plan = RemedyPlan(skip = setOf(Remedy.Reload))),
        Row("frozen, reload and name skipped: software", listOf(Frozen), listOf("hop:Software"), rich,
            plan = RemedyPlan(skip = setOf(Remedy.Reload, Remedy.ExcludeDecoder))),
        // Green or garbage frames, sentinel or the viewer: R1c, R2 if it fits, else R3, R4.
        Row("green frames: other name", listOf(WrongPicture), listOf("hop:ExcludeDecoder"), rich),
        Row("green frames, name skipped, fits: software", listOf(WrongPicture), listOf("hop:Software"), rich,
            plan = RemedyPlan(skip = setOf(Remedy.ExcludeDecoder))),
        Row("green frames, name skipped, too slow: engine", listOf(WrongPicture), listOf("hop:OtherEngine"),
            rich.copy(softwareVerdict = Verdict.SOFTWARE_TOO_SLOW), plan = RemedyPlan(skip = setOf(Remedy.ExcludeDecoder))),
        Row("viewer says the picture is wrong: other name, then engine", listOf(UserReport, UserReport),
            listOf("hop:ExcludeDecoder", "hop:OtherEngine"), rich),
        // The viewer's press is consent and never ends in the sheet (RL10).
        Row("viewer reports stutter on API 28: async at once, then the other engine", listOf(UserStutter, UserStutter),
            listOf("hop:FlipAsync", "hop:OtherEngine"), rich),
        Row("viewer reports stutter with Never switch by itself: it still acts", listOf(UserStutter), listOf("hop:FlipAsync"),
            rich.copy(autoSwitch = false)),
        Row("viewer says picture is wrong with Never switch by itself: it still acts", listOf(UserReport), listOf("hop:ExcludeDecoder"),
            rich.copy(autoSwitch = false)),
        Row("viewer reports stutter on software: lighter copy, once", listOf(UserStutter, UserStutter), listOf("offer:LighterCopy", "stay"), rich,
            plan = RemedyPlan(start = setOf(Remedy.Software))),
        Row("viewer reports and nothing applies: stay, not the sheet", listOf(UserStutter, UserReport),
            listOf("stay", "stay"), rich.copy(sdk = 33, otherDecoder = false, softwareTakes = false), vlc = false),
        Row("viewer reports after the sheet: stay", listOf(DecoderError, DecoderError, DecoderError, UserReport, UserStutter),
            listOf("hop:ExcludeDecoder", "hop:OtherEngine", "sheet", "stay", "stay"), rich),
        // Stutter on hardware below API 31: offer R1a (auto on a second video of the class), R1b, R2 or R3.
        Row("stutter, API 28: offer async once", listOf(Stutter, Stutter), listOf("offer:FlipAsync", "stay"), rich),
        Row("stutter, API 28, class stuttered before: async by itself", listOf(Stutter), listOf("hop:FlipAsync"), rich,
            plan = RemedyPlan(stutteredBefore = true)),
        Row("stutter, API 33, tunneled: offer tunneling off", listOf(Stutter), listOf("offer:FlipTunneling"),
            rich.copy(sdk = 33, tunneling = true)),
        Row("stutter, async skipped, no tunneling: offer software", listOf(Stutter), listOf("offer:Software"), rich,
            plan = RemedyPlan(skip = setOf(Remedy.FlipAsync))),
        // Software too slow: a lighter copy, never a hop.
        Row("software too slow: lighter copy once", listOf(SoftwareTooSlow, SoftwareTooSlow), listOf("offer:LighterCopy", "stay"), rich),
        Row("stutter while on software: lighter copy", listOf(Stutter), listOf("offer:LighterCopy"), rich,
            plan = RemedyPlan(start = setOf(Remedy.Software))),
        // NETWORK_BOUND: no hop.
        Row("bytes not arriving: stay", listOf(Source, Source, Source), listOf("stay", "stay", "stay"), rich),
        // Audio track init failed: A1, A2, A3.
        Row("audio failed: software audio", listOf(AudioFailed), listOf("hop:AudioSoftware"), rich),
        Row("audio failed, no software audio: passthrough off", listOf(AudioFailed), listOf("hop:PassthroughOff"),
            rich.copy(audioSoftwareTakes = false)),
        Row("audio failed, neither: engine", listOf(AudioFailed), listOf("hop:OtherEngine"),
            rich.copy(audioSoftwareTakes = false, passthrough = false)),
        Row("audio and picture each get their step", listOf(AudioFailed, Frozen, Frozen),
            listOf("hop:AudioSoftware", "hop:Reload", "sheet"), rich),
        // Previous run crashed natively in the class: start at R2, then R3, R4 (start rows below).
        Row("after a crash, started on software: engine next", listOf(DecoderError, DecoderError), listOf("hop:OtherEngine", "sheet"), rich,
            plan = RemedyPlan(start = setOf(Remedy.Software))),
        // The viewer's choices win.
        Row("viewer picked ExoPlayer: no engine hop", listOf(Frozen, Frozen), listOf("hop:Reload", "sheet"), rich,
            choice = EngineChoice.ExoPlayer),
        Row("viewer picked the decoder: no decoder hop", listOf(DecoderError), listOf("hop:OtherEngine"), rich.copy(decoderPicked = true)),
        Row("never switch by itself: offer, then sheet", listOf(DecoderError, DecoderError), listOf("offer:ExcludeDecoder", "sheet"),
            rich.copy(autoSwitch = false)),
    )

    @Test
    fun decisionTable() {
        val wrong = table.mapNotNull { row ->
            val got = run(row)
            if (got == row.expected) null else "${row.name}: expected ${row.expected}, got $got"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun startsOnWhatTheClassLearned() {
        val software = RemedyLadder(input(), exo, RemedyPlan(start = setOf(Remedy.Software), exclude = setOf("OMX.bad")), rich)
        assertTrue(software.path.software)
        assertEquals(setOf("OMX.bad"), software.path.excluded)
        assertEquals(listOf(Remedy.ExcludeDecoder, Remedy.Software), software.taken)

        val engine = RemedyLadder(input(), exo, RemedyPlan(start = setOf(Remedy.OtherEngine)), rich)
        assertEquals(PlayerEngine.Vlc, engine.running.engine)
        assertEquals(PlayerEngine.Vlc, engine.path.engine)
        // The engine switch is spent: a failure in VLC goes to the sheet, never back.
        assertEquals(RemedyAction.Sheet, engine.onEvidence(DecoderError, rich, 0))

        val skipped = RemedyLadder(input(), exo, RemedyPlan(start = setOf(Remedy.Software), skip = setOf(Remedy.Software)), rich)
        assertFalse(skipped.path.software)

        val picked = RemedyLadder(input(), exo, RemedyPlan(exclude = setOf("OMX.bad")), rich.copy(decoderPicked = true))
        assertTrue(picked.path.excluded.isEmpty())
    }

    @Test
    fun theEngineHopIsEngineSelectors() {
        val engineInput = input()
        val ladder = RemedyLadder(engineInput, exo, RemedyPlan(), rich.copy(otherDecoder = false, softwareTakes = false))
        val hop = assertIs<RemedyAction.Hop>(ladder.onEvidence(DecoderError, rich.copy(otherDecoder = false, softwareTakes = false), 0))
        assertEquals(EngineSelector.afterFailure(engineInput, exo, EngineFailure.Decoder, 0), hop.engine)
        assertEquals(EngineReason.SwitchedAfterFailure, ladder.running.reason)
    }

    @Test
    fun aHopHasItsProbation() {
        val ladder = RemedyLadder(input(), exo, RemedyPlan(), rich)
        assertIs<RemedyAction.Hop>(ladder.onEvidence(Frozen, rich, 10_000))
        assertEquals(RemedyAction.Later(11_500), ladder.onEvidence(Frozen, rich, 10_400))
        assertIs<RemedyAction.Hop>(ladder.onEvidence(Frozen, rich, 11_500))
    }

    @Test
    fun anAcceptedOfferHopsOnce() {
        val ladder = RemedyLadder(input(), exo, RemedyPlan(), rich)
        val offer = assertIs<RemedyAction.Offer>(ladder.onEvidence(Stutter, rich, 0))
        val hop = assertIs<RemedyAction.Hop>(ladder.accept(offer, rich, 100))
        assertEquals(Remedy.FlipAsync, hop.remedy)
        assertTrue(ladder.path.asyncFlipped)
        assertEquals(RemedyAction.Stay, ladder.accept(RemedyAction.Offer(Remedy.LighterCopy, SoftwareTooSlow), rich, 5_000))
    }

    @Test
    fun aPathWorksAfterAMinuteOfCleanPlay() {
        val ladder = RemedyLadder(input(), exo, RemedyPlan(), rich)
        assertFalse(ladder.played(70_000), "no first frame yet")
        ladder.ready()
        assertEquals(RemedyLadder.Phase.FirstFrameWait, ladder.phase)
        ladder.firstFrame()
        assertFalse(ladder.played(12_000))
        assertEquals(RemedyLadder.Phase.Stable, ladder.phase)
        assertTrue(ladder.played(60_000))
        assertFalse(ladder.played(61_000), "said once")
        // A hop starts the path over.
        ladder.onEvidence(Frozen, rich, 0)
        assertEquals(RemedyLadder.Phase.Opening, ladder.phase)
        ladder.firstFrame()
        assertTrue(ladder.played(60_000))
    }

    /** Research 7.1: over random evidence sequences the ladder never loops and keeps every rule. */
    @Test
    fun propertyNeverLoops() {
        val random = Random(20261116)
        val evidence = RemedyEvidence.entries
        repeat(3_000) { round ->
            val choice = EngineChoice.entries[random.nextInt(EngineChoice.entries.size)]
            val vlc = random.nextBoolean()
            val engineInput = input(choice, vlc)
            val plan = RemedyPlan(
                start = Remedy.entries.filter { random.nextInt(6) == 0 }.toSet(),
                skip = Remedy.entries.filter { random.nextInt(5) == 0 }.toSet(),
                exclude = if (random.nextBoolean()) setOf("OMX.a") else emptySet(),
                stutteredBefore = random.nextBoolean(),
            )
            fun facts() = LadderFacts(
                sdk = listOf(0, 23, 28, 30, 31, 35)[random.nextInt(6)],
                decoderName = listOf(null, "", "OMX.a", "OMX.b", "c2.c")[random.nextInt(5)],
                otherDecoder = random.nextBoolean(),
                softwareTakes = random.nextBoolean(),
                softwareVerdict = Verdict.entries[random.nextInt(Verdict.entries.size)],
                tunneling = random.nextBoolean(),
                decoderPicked = random.nextInt(4) == 0,
                tunnelingPicked = random.nextInt(4) == 0,
                audioSoftwareTakes = random.nextBoolean(),
                passthrough = random.nextBoolean(),
                autoSwitch = random.nextInt(5) != 0,
            )
            val ladder = RemedyLadder(engineInput, exo, plan, facts())
            val seen = mutableSetOf(ladder.path)
            var now = 0L
            var hops = 0
            var engineHops = 0
            var sheet = false
            repeat(random.nextInt(1, 30)) {
                now += random.nextLong(-100, 4_000)
                val e = evidence[random.nextInt(evidence.size)]
                val f = facts()
                val action = ladder.onEvidence(e, f, now)
                val where = "round $round, $e -> ${describe(action)}"
                if (sheet) assertTrue(action == RemedyAction.Sheet || action == RemedyAction.Stay, "after the sheet only the sheet: $where")
                if (e == Source) assertEquals(RemedyAction.Stay, action, where)
                if (action is RemedyAction.Hop) {
                    hops++
                    assertTrue(e.hard || (e == Stutter && plan.stutteredBefore), "soft evidence hopped: $where")
                    assertTrue(f.autoSwitch || e.fromViewer, "hopped with Never switch by itself: $where")
                    assertTrue(seen.add(action.path), "revisited a path: $where")
                    assertTrue(action.remedy !in plan.skip, "took a skipped remedy: $where")
                    if (action.remedy == Remedy.OtherEngine) {
                        engineHops++
                        assertEquals(EngineChoice.Automatic, choice, "overrode the viewer's engine: $where")
                        assertTrue(vlc, where)
                    }
                    if (action.remedy == Remedy.Software || action.remedy == Remedy.ExcludeDecoder) {
                        assertFalse(f.decoderPicked, "overrode the viewer's decoder: $where")
                    }
                    if (action.remedy == Remedy.FlipTunneling) assertFalse(f.tunnelingPicked, where)
                    assertTrue(action.remedy != Remedy.LighterCopy, where)
                }
                if (action == RemedyAction.Sheet) sheet = true
            }
            assertTrue(hops <= RemedyLadder.MAX_HOPS, "round $round: $hops hops")
            assertTrue(engineHops <= EngineSelector.SWITCHES_PER_VIDEO, "round $round: $engineHops engine hops")
            assertEquals(hops, ladder.hops.size)
            assertEquals(seen, ladder.visited)
        }
    }

    /** WP-24: the viewer's "Turn off passthrough for this video" is the ladder's step, with no probation wait and the shared budget. */
    @Test
    fun aViewerPressTakesTheNamedStepWithoutWaitingAndSharesTheHopBudget() {
        val ladder = RemedyLadder(input(), exo, RemedyPlan(), rich)
        // A step taken a moment ago would make onEvidence wait out its probation: the press does not.
        assertIs<RemedyAction.Hop>(ladder.onEvidence(UserReport, rich, 0L))
        val press = ladder.viewerPress(Remedy.PassthroughOff, UserReport, rich, 100L)
        assertIs<RemedyAction.Hop>(press)
        assertEquals(Remedy.PassthroughOff, press.remedy)
        assertTrue(press.path.passthroughOff)
        assertEquals(2, ladder.hops.size)
        // Spent: a second press, and a sound with no passthrough, change nothing.
        assertEquals(RemedyAction.Stay, ladder.viewerPress(Remedy.PassthroughOff, UserReport, rich, 200L))
        val without = RemedyLadder(input(), exo, RemedyPlan(), rich.copy(passthrough = false))
        assertEquals(RemedyAction.Stay, without.viewerPress(Remedy.PassthroughOff, UserReport, rich.copy(passthrough = false), 0L))
        assertEquals(0, without.hops.size)
    }
}
