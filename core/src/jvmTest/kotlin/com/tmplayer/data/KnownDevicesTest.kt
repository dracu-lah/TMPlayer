package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnownDevicesTest {

    private val tanix = DeviceFacts(manufacturer = "Tanix", model = "TX6", device = "tx6", hardware = "sun50iw6p1", sdk = 28)

    @Test
    fun builtInCoversTheAllwinnerH6() {
        val entry = KnownDevices.find(KnownDevices.builtIn, tanix, null)
        assertEquals("allwinner-h6", entry?.id)
        assertEquals(PlayerEngine.Vlc, entry?.engine)
        assertNull(KnownDevices.find(KnownDevices.builtIn, DeviceFacts(hardware = "mt8695", model = "MiTV-MOOQ0"), null))
        // A short chipset value does not stand for a longer token.
        assertNull(KnownDevices.find(KnownDevices.builtIn, DeviceFacts(hardware = "sun50"), null))
    }

    @Test
    fun aBadEntryIsSkippedTheRestKept() {
        val feed = """
            {"devices": [
              null, 7, "x", [],
              {"id": "no-preference", "chipsets": ["amlogic"]},
              {"id": "too-broad", "engine": "vlc"},
              {"id": "blank-matchers", "engine": "vlc", "chipsets": ["", " ", 5, null], "models": {}},
              {"id": "bad-engine", "engine": "mx", "chipsets": ["s905x"]},
              {"id": "backwards", "engine": "vlc", "chipsets": ["s905x"], "sdkMin": 30, "sdkMax": 24},
              {"id": "good", "engine": "VLC", "chipsets": [" S905X ", "s905x"], "models": ["X96"], "sdkMin": 24, "sdkMax": "28", "codecs": ["video/hevc", 3]},
              {"id": "decoder-only", "decoder": "software", "models": ["MiBOX4"], "sdkMin": 1e30}
            ]}
        """.trimIndent()
        val list = KnownDevices.parse(feed)
        assertEquals(listOf("good", "decoder-only"), list.map { it.id })
        val good = list[0]
        assertEquals(listOf("s905x"), good.chipsets)
        assertEquals(listOf("x96"), good.models)
        assertEquals(24, good.sdkMin)
        assertNull(good.sdkMax, "a number as text is not a number")
        assertEquals(listOf("video/hevc"), good.codecs)
        assertNull(list[1].sdkMin, "an absurd API level is ignored")
        assertEquals(DecoderHint.Software, list[1].decoder)
    }

    @Test
    fun matchingNeedsEveryGroupGiven() {
        val entry = KnownDevice(id = "x", chipsets = listOf("s905x"), models = listOf("x96"), sdkMin = 24, sdkMax = 28, engine = PlayerEngine.Vlc)
        val box = DeviceFacts(model = "X96", hardware = "amlogic", board = "s905x", sdk = 25)
        assertTrue(entry.matches(box, null))
        assertTrue(!entry.matches(box.copy(model = "Other"), null))
        assertTrue(!entry.matches(box.copy(sdk = 29), null))
        assertTrue(!entry.matches(box.copy(sdk = 23), null))
        assertTrue(!KnownDevice(id = "none", engine = PlayerEngine.Vlc).matches(box, null), "no matchers covers nothing")
    }

    @Test
    fun aStaleOrEmptyFeedFallsBackToTheShippedList() {
        assertEquals(KnownDevices.builtIn, KnownDevices.effective(KnownDevices.parse(null)))
        assertEquals(KnownDevices.builtIn, KnownDevices.effective(KnownDevices.parse("not json")))
        val override = KnownDevices.parse("""[{"id": "allwinner-h6", "engine": "exoplayer", "decoder": "software", "chipsets": ["sun50iw6"]}, {"id": "new", "engine": "vlc", "models": ["tx3"]}]""")
        val merged = KnownDevices.effective(override)
        assertEquals(listOf("allwinner-h6", "new"), merged.map { it.id })
        val h6 = assertNotNull(KnownDevices.find(merged, tanix, null))
        assertEquals(PlayerEngine.ExoPlayer, h6.engine)
    }

    @Test
    fun aHugeFeedIsCapped() {
        val entries = (0 until 2_000).joinToString(",") { """{"id":"e$it","engine":"vlc","models":["m$it"]}""" }
        assertEquals(KnownDevices.MAX_ENTRIES, KnownDevices.parse("[$entries]").size)
    }

    @Test
    fun theRemedyFieldsAreReadAndBadOnesSkipped() {
        val feed = """
            [
              {"id": "async-only", "chipsets": ["rk3328"], "async": true},
              {"id": "bad-fields", "chipsets": ["rk3328"], "async": "yes", "excludeDecoders": "OMX.rk", "startRung": "R4"},
              {"id": "names", "chipsets": ["amlogic1"], "note": "frozen after a seek",
               "excludeDecoders": [" OMX.amlogic.hevc.decoder.awesome2 ", "omx.amlogic.hevc.decoder.awesome2", "", 4, "bad name!", "${"x".repeat(60)}"],
               "startRung": "sw"},
              {"id": "rung", "models": ["tx6s"], "startRung": "R3"},
              {"id": "reload", "models": ["tx6s"], "startRung": "rl"}
            ]
        """.trimIndent()
        val list = KnownDevices.parse(feed)
        assertEquals(listOf("async-only", "names", "rung"), list.map { it.id }, "an entry whose every remedy field is bad says nothing")
        assertEquals(true, list[0].async)
        val names = list[1]
        assertEquals(listOf("OMX.amlogic.hevc.decoder.awesome2"), names.excludeDecoders, "case kept, the same name twice once, bad ones dropped")
        assertEquals(com.tmplayer.player.Remedy.Software, names.startRung)
        assertEquals("frozen after a seek", names.note)
        assertEquals(com.tmplayer.player.Remedy.OtherEngine, list[2].startRung)
        // None of these prefers an engine or a decoder, so the engine choice is untouched.
        assertNull(KnownDevices.find(list, DeviceFacts(hardware = "amlogic1"), null))
    }

    @Test
    fun startNamesAndRungs() {
        assertEquals(com.tmplayer.player.Remedy.AudioSoftware, KnownDevices.startOf("A1"))
        assertEquals(com.tmplayer.player.Remedy.PassthroughOff, KnownDevices.startOf("passthroughoff"))
        assertEquals(com.tmplayer.player.Remedy.ExcludeDecoder, KnownDevices.startOf(" ex "))
        assertNull(KnownDevices.startOf("lc"), "a lighter copy is never a start")
        assertNull(KnownDevices.startOf("R1"), "R1 is several remedies")
        assertNull(KnownDevices.startOf(""))
        assertNull(KnownDevices.startOf(null))
    }

    @Test
    fun theListFillsWhatTheMemoryDoesNotKnow() {
        val list = listOf(
            KnownDevice(id = "a", chipsets = listOf("amlogic1"), async = true, startRung = com.tmplayer.player.Remedy.Software, note = "n"),
            KnownDevice(id = "b", chipsets = listOf("amlogic1"), excludeDecoders = listOf("OMX.a", "OMX.b"), async = false),
        )
        val known = KnownDevices.remediesFor(list, DeviceFacts(hardware = "amlogic1"), "video/hevc")
        assertEquals(KnownRemedies(async = true, exclude = listOf("OMX.a", "OMX.b"), start = com.tmplayer.player.Remedy.Software, note = "n"), known)
        assertEquals(KnownRemedies(), KnownDevices.remediesFor(list, DeviceFacts(hardware = "other"), null))

        val fresh = KnownDevices.plan(com.tmplayer.player.RemedyPlan(), known)
        assertEquals(setOf(com.tmplayer.player.Remedy.Software, com.tmplayer.player.Remedy.FlipAsync), fresh.start)
        assertEquals(setOf("OMX.a", "OMX.b"), fresh.exclude)

        // The class memory wins at its level, and what it skips the list cannot bring back.
        val learned = com.tmplayer.player.RemedyPlan(
            start = setOf(com.tmplayer.player.Remedy.ExcludeDecoder),
            skip = setOf(com.tmplayer.player.Remedy.FlipAsync),
            exclude = setOf("c2.x", "c2.y"),
        )
        val merged = KnownDevices.plan(learned, known)
        assertEquals(setOf(com.tmplayer.player.Remedy.ExcludeDecoder), merged.start)
        assertEquals(setOf("c2.x", "c2.y", "OMX.a"), merged.exclude, "three names at most, the memory's first")
    }
}
