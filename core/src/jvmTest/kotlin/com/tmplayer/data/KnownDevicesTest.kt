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
}
