package com.tmplayer.data

import io.sentry.SentryEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S2: the player's tags on a crash, each checked, and how long what was playing is remembered. */
class CrashContextTest {

    @After
    fun reset() = CrashContext.stopped()

    private fun playback(
        engine: String = "media3",
        decoder: String = "OMX.amlogic.avc.decoder.awesome",
        codec: String = "avc",
        width: Int = 1920,
        height: Int = 1080,
        stutter: String = "",
    ) = CrashContext.Playback(engine, decoder, codec, width, height, stutter, atMillis = 0)

    @Test
    fun `heap classes, never the exact figures, and nothing for a bad reading`() {
        val mb = 1024L * 1024
        assertEquals(mapOf("heap_mb" to "128", "heap_used" to "90+"), CrashContext.heapTags(192 * mb, 180 * mb))
        assertEquals(mapOf("heap_mb" to "64", "heap_used" to "0-49"), CrashContext.heapTags(64 * mb, 10 * mb))
        assertEquals(mapOf("heap_mb" to "1024+", "heap_used" to "75-89"), CrashContext.heapTags(2048 * mb, 1700 * mb))
        assertEquals(emptyMap<String, String>(), CrashContext.heapTags(0, 5))
        assertEquals(emptyMap<String, String>(), CrashContext.heapTags(100, -1))
        // Only the two classes pass, from any caller, with or without anything playing.
        val tags = CrashContext.tagsOf("", null, mapOf("heap_mb" to "256", "heap_used" to "99 percent", "title" to "Secret"))
        assertEquals(mapOf("heap_mb" to "256"), tags)
        val event = CrashContext.tag(SentryEvent(), mapOf("heap_mb" to "7"))
        assertNull(event.tags)
    }

    @Test
    fun `nothing playing leaves only the chipset, and no chipset no tags at all`() {
        assertEquals(mapOf("chipset" to "amlogic"), CrashContext.tagsOf("amlogic", null))
        val event = CrashContext.tag(SentryEvent(), CrashContext.tagsOf("", null))
        assertNull(event.tags)
    }

    @Test
    fun `a value that is not what its tag holds is left out`() {
        val bad = CrashContext.tagsOf(
            "/proc/cpuinfo",
            playback(
                engine = "SuperPlayer",
                decoder = "Secret.Show.S01E01.mkv",
                codec = "h264 from /storage/emulated/0",
                width = 0,
                stutter = "very bad",
            ),
        )
        assertEquals(emptyMap<String, String>(), bad)
        // A decoder name with a space or a path is refused, not cleaned into something else.
        assertEquals(null, CrashContext.tagsOf("", playback(decoder = "My Holiday Video"))["decoder"])
        assertEquals(null, CrashContext.tagsOf("", playback(decoder = "/data/user/0/x"))["decoder"])
    }

    @Test
    fun `tag refuses keys and values outside the list even when handed them directly`() {
        val event = CrashContext.tag(
            SentryEvent(),
            mapOf(
                "engine" to "media3",
                "decoder" to "a.mp4",
                "size_class" to "huge",
                "stutter" to "slow",
                "title" to "anything",
            ),
        )
        assertEquals(mapOf("engine" to "media3", "stutter" to "slow"), event.tags)
    }

    @Test
    fun `size class by the shorter side, so a portrait video counts as its landscape twin`() {
        assertEquals("sd", CrashContext.sizeClass(854, 480))
        assertEquals("hd", CrashContext.sizeClass(1280, 720))
        assertEquals("fhd", CrashContext.sizeClass(1080, 1920))
        assertEquals("qhd", CrashContext.sizeClass(2560, 1440))
        assertEquals("uhd", CrashContext.sizeClass(3840, 2160))
        assertNull(CrashContext.sizeClass(0, 1080))
        assertNull(CrashContext.sizeClass(-1, -1))
    }

    @Test
    fun `a stutter is remembered for a while, a playing video until it stops`() {
        val facts = StutterReport.Facts(
            codec = "hevc", profile = "", bitDepth = 10, width = 3840, height = 2160, fps = 24f,
            bitrateKbps = 20_000, container = "video/x-matroska", decoder = "c2.mtk.hevc.decoder", hardware = true,
            prediction = "HARDWARE", speedPercent = 70, dropped = 0, frames = 300, underruns = 0,
            manufacturer = "Xiaomi", model = "MiTV", androidVersion = "9", cores = 4, abi = "armeabi-v7a",
            appVersion = "1.30.0", sizeBucket = 4, symptom = "slow",
        )
        CrashContext.stuttered(facts, "media3", now = 1_000)
        val kept = CrashContext.playbackNow(now = 1_000 + CrashContext.FRESH_MS)
        assertEquals("slow", kept?.stutter)
        assertEquals(
            mapOf(
                "engine" to "media3", "chipset" to "mt8695", "decoder" to "c2.mtk.hevc.decoder",
                "codec" to "hevc", "size_class" to "uhd", "stutter" to "slow",
            ),
            CrashContext.tagsOf("mt8695", kept),
        )
        assertNull("faded", CrashContext.playbackNow(now = 1_001 + CrashContext.FRESH_MS))
        // A clock that went back is not trusted either.
        assertNull(CrashContext.playbackNow(now = 0))

        CrashContext.playing("media3", "c2.android.av1.decoder", "av1", 1280, 720, now = 0)
        assertTrue(CrashContext.playbackNow(now = 100 * CrashContext.FRESH_MS)?.ongoing == true)
        CrashContext.stuttered(facts, "media3", now = 5)
        assertTrue("still the player's session", CrashContext.playbackNow(now = 100 * CrashContext.FRESH_MS)?.ongoing == true)
        CrashContext.stopped()
        assertNull(CrashContext.playbackNow(now = 0))
    }
}
