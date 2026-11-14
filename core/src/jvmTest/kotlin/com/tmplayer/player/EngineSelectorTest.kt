package com.tmplayer.player

import com.tmplayer.data.DecoderHint
import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.KnownDevice
import com.tmplayer.data.PlayerEngine
import com.tmplayer.data.PlayerEngine.ExoPlayer
import com.tmplayer.data.PlayerEngine.Vlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineSelectorTest {

    private val tanix = DeviceFacts(manufacturer = "Tanix", model = "TX6", device = "tx6", hardware = "sun50iw6p1", sdk = 28)
    private val pixel = DeviceFacts(manufacturer = "Google", model = "Pixel 8", device = "shiba", hardware = "zuma", sdk = 35)

    private data class Row(
        val name: String,
        val choice: EngineChoice,
        val device: DeviceFacts,
        val history: EngineHistory = EngineHistory.None,
        val stereo3d: Boolean = false,
        val vlc: Boolean = true,
        val mime: String? = null,
        val engine: PlayerEngine,
        val reason: EngineReason,
        val decoder: DecoderHint? = null,
    )

    private val exoTwice = EngineHistory(exoPlayerFailures = 2)
    private val bothFailed = EngineHistory(exoPlayerFailures = 5, vlcFailures = 1)

    private val table = listOf(
        Row("automatic, ordinary device", EngineChoice.Automatic, pixel, engine = ExoPlayer, reason = EngineReason.Default),
        Row("automatic, known device", EngineChoice.Automatic, tanix, engine = Vlc, reason = EngineReason.KnownDevice),
        Row("automatic, known device, no VLC", EngineChoice.Automatic, tanix, vlc = false, engine = ExoPlayer, reason = EngineReason.VlcUnavailable),
        Row("user ExoPlayer on a known device", EngineChoice.ExoPlayer, tanix, engine = ExoPlayer, reason = EngineReason.UserChoice),
        Row("user ExoPlayer after failures", EngineChoice.ExoPlayer, pixel, exoTwice, engine = ExoPlayer, reason = EngineReason.UserChoice),
        Row("user VLC", EngineChoice.Vlc, pixel, engine = Vlc, reason = EngineReason.UserChoice),
        Row("user VLC, no library", EngineChoice.Vlc, pixel, vlc = false, engine = ExoPlayer, reason = EngineReason.VlcUnavailable),
        Row("3D over automatic on a known device", EngineChoice.Automatic, tanix, stereo3d = true, engine = ExoPlayer, reason = EngineReason.Stereo3d, decoder = DecoderHint.Device),
        Row("3D over the user's VLC", EngineChoice.Vlc, pixel, stereo3d = true, engine = ExoPlayer, reason = EngineReason.Stereo3d, decoder = DecoderHint.Device),
        Row("3D over learned VLC", EngineChoice.Automatic, pixel, exoTwice, stereo3d = true, engine = ExoPlayer, reason = EngineReason.Stereo3d, decoder = DecoderHint.Device),
        Row("ExoPlayer failed once", EngineChoice.Automatic, pixel, EngineHistory(exoPlayerFailures = 1), engine = ExoPlayer, reason = EngineReason.Default),
        Row("ExoPlayer failed twice", EngineChoice.Automatic, pixel, exoTwice, engine = Vlc, reason = EngineReason.DeviceHistory),
        Row("ExoPlayer failed twice, no VLC", EngineChoice.Automatic, pixel, exoTwice, vlc = false, engine = ExoPlayer, reason = EngineReason.VlcUnavailable),
        Row("both failed: ExoPlayer for good", EngineChoice.Automatic, pixel, bothFailed, engine = ExoPlayer, reason = EngineReason.Default),
        Row("known list over history", EngineChoice.Automatic, tanix, bothFailed, engine = Vlc, reason = EngineReason.KnownDevice),
    )

    @Test
    fun decisionTable() {
        for (row in table) {
            val decision = EngineSelector.choose(
                EngineInput(row.choice, row.device, row.history, VideoFacts(row.mime), row.stereo3d, row.vlc),
            )
            assertEquals(row.engine, decision.engine, row.name)
            assertEquals(row.reason, decision.reason, row.name)
            assertEquals(row.decoder, decision.decoder, row.name)
        }
    }

    @Test
    fun unknownStoredChoiceIsAutomatic() {
        for (stored in listOf(null, "", " ", "mx", "VLC2", "\u0000", "a".repeat(10_000))) {
            assertEquals(EngineChoice.Automatic, EngineChoice.from(stored), "stored \"${stored?.take(20)}\"")
        }
        assertEquals(EngineChoice.Vlc, EngineChoice.from("vlc"))
        assertEquals(EngineChoice.Vlc, EngineChoice.from(" VLC "))
        assertEquals(EngineChoice.ExoPlayer, EngineChoice.from("ExoPlayer"))
        assertEquals(EngineChoice.Automatic, EngineChoice.from("Automatic"))
    }

    @Test
    fun knownEntryCarriesItsDecoderAndId() {
        val list = listOf(KnownDevice(id = "zuma-sw", chipsets = listOf("zuma"), engine = ExoPlayer, decoder = DecoderHint.Software))
        val decision = EngineSelector.choose(EngineInput(EngineChoice.Automatic, pixel, vlcAvailable = true, known = list))
        assertEquals(EngineDecision(ExoPlayer, EngineReason.KnownDevice, DecoderHint.Software, "zuma-sw"), decision)
        // The viewer's ExoPlayer drops the list's opinion on the decoder too.
        val picked = EngineSelector.choose(EngineInput(EngineChoice.ExoPlayer, pixel, vlcAvailable = true, known = list))
        assertNull(picked.decoder)
    }

    @Test
    fun codecEntryWaitsForTheFormat() {
        val list = listOf(KnownDevice(id = "h6-hevc", chipsets = listOf("sun50iw6"), codecs = listOf("video/hevc"), engine = Vlc))
        fun pick(mime: String?) = EngineSelector.choose(EngineInput(EngineChoice.Automatic, tanix, video = VideoFacts(mime), vlcAvailable = true, known = list))
        assertEquals(Vlc, pick("video/hevc").engine)
        assertEquals(ExoPlayer, pick("video/avc").engine)
        assertEquals(ExoPlayer, pick(null).engine)
    }

    @Test
    fun oneAutomaticSwitchPerVideoExoPlayerToVlcOnly() {
        val input = EngineInput(EngineChoice.Automatic, pixel, vlcAvailable = true)
        val start = EngineSelector.choose(input)
        assertEquals(ExoPlayer, start.engine)
        val switched = assertNotNull(EngineSelector.afterFailure(input, start, EngineFailure.Decoder, switches = 0))
        assertEquals(EngineDecision(Vlc, EngineReason.SwitchedAfterFailure), switched)
        // VLC fails too: no way back by itself, the sheet offers ExoPlayer.
        assertNull(EngineSelector.afterFailure(input, switched, EngineFailure.Decoder, switches = 1))
        assertNull(EngineSelector.afterFailure(input, switched, EngineFailure.Decoder, switches = 0))
        assertEquals(ExoPlayer, EngineSelector.toOffer(input, Vlc))
        // A second ExoPlayer failure in the same video (after a manual switch back) does not switch again.
        assertNull(EngineSelector.afterFailure(input, start, EngineFailure.Decoder, switches = 1))
    }

    @Test
    fun noAutomaticSwitchWhenTheRulesSayStay() {
        val exo = EngineDecision(ExoPlayer, EngineReason.Default)
        val auto = EngineInput(EngineChoice.Automatic, pixel, vlcAvailable = true)
        assertNull(EngineSelector.afterFailure(auto, exo, EngineFailure.Source, 0), "a network cut is not the engine's fault")
        assertNull(EngineSelector.afterFailure(auto, exo, EngineFailure.Other, 0))
        assertNull(EngineSelector.afterFailure(auto.copy(choice = EngineChoice.ExoPlayer), exo, EngineFailure.Decoder, 0), "user choice wins")
        assertNull(EngineSelector.afterFailure(auto.copy(vlcAvailable = false), exo, EngineFailure.Decoder, 0))
        assertNull(EngineSelector.afterFailure(auto.copy(stereo3d = true), exo, EngineFailure.Decoder, 0))
        assertEquals(Vlc, EngineSelector.toOffer(auto, ExoPlayer))
        assertNull(EngineSelector.toOffer(auto.copy(vlcAvailable = false), ExoPlayer))
        assertNull(EngineSelector.toOffer(auto.copy(stereo3d = true), ExoPlayer))
    }

    /**
     * The no-loop rule across videos: whatever fails, the history only grows, so the engine picked
     * at the start of a video goes to VLC at most once and, once back on ExoPlayer, stays there.
     */
    @Test
    fun neverSwitchesBackAndForthAcrossVideos() {
        for (pattern in 0 until 64) {
            var history = EngineHistory.None
            val picked = ArrayList<PlayerEngine>()
            for (video in 0 until 6) {
                val input = EngineInput(EngineChoice.Automatic, pixel, history, vlcAvailable = true)
                val start = EngineSelector.choose(input)
                picked += start.engine
                val fails = (pattern shr video) and 1 == 1
                if (fails) {
                    history = history.failed(start.engine)
                    val next = EngineSelector.afterFailure(input, start, EngineFailure.Decoder, 0)
                    if (next != null) {
                        // The switched engine fails too on odd videos.
                        if (video % 2 == 1) history = history.failed(next.engine)
                        assertNull(EngineSelector.afterFailure(input, next, EngineFailure.Decoder, 1))
                    }
                }
            }
            val toVlc = picked.zipWithNext().count { (a, b) -> a == ExoPlayer && b == Vlc }
            val toExo = picked.zipWithNext().count { (a, b) -> a == Vlc && b == ExoPlayer }
            assertTrue(toVlc <= 1 && toExo <= 1, "pattern $pattern picked $picked")
            val firstVlc = picked.indexOf(Vlc)
            val backAt = if (firstVlc < 0) -1 else picked.withIndex().firstOrNull { it.index > firstVlc && it.value == ExoPlayer }?.index ?: -1
            assertTrue(backAt < 0 || Vlc !in picked.drop(backAt), "pattern $pattern went back to VLC: $picked")
        }
    }

    @Test
    fun historyStoresAndSurvivesGarbage() {
        val history = EngineHistory(3, 1)
        assertEquals(history, EngineHistory.decode(history.encode()))
        for (stored in listOf(null, "", "exo", "exo=", "exo=x;vlc=-5", "vlc=99999999999", "=;=;", "a".repeat(1_000))) {
            val read = EngineHistory.decode(stored)
            assertTrue(read.exoPlayerFailures >= 0 && read.vlcFailures >= 0)
        }
        assertEquals(EngineHistory(0, 0), EngineHistory.decode("exo=x;vlc=-5"))
        assertEquals(1_000, EngineHistory(1_000, 0).failed(ExoPlayer).exoPlayerFailures)
    }
}
