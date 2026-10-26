package com.tmplayer.data

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Device
import io.sentry.protocol.User
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StutterReportTest {

    private fun facts(
        codec: String = "av1",
        bitDepth: Int = 10,
        bucket: Int = 1080,
        model: String = "MiTV-AESP0",
    ) = StutterReport.Facts(
        codec = codec, profile = "av01.0.08M.10", bitDepth = bitDepth, width = 1920, height = 1080, fps = 23.976f,
        bitrateKbps = 9000, container = "video/x-matroska", decoder = "c2.ffmpeg.av1.decoder", hardware = false,
        prediction = "SOFTWARE_TOO_SLOW", speedPercent = 36, dropped = 120, frames = 300, underruns = 43,
        manufacturer = "Xiaomi", model = model, androidVersion = "9", cores = 4, abi = "armeabi-v7a",
        appVersion = "1.28.2", sizeBucket = bucket,
    )

    private fun tempFile(): File = Files.createTempDirectory("stutter").toFile().resolve("stutter.json")

    @Test
    fun theEventHoldsExactlyTheAllowedFields() {
        val event = StutterReport.build(facts())
        assertEquals(StutterReport.ALLOWED_TAGS, event.tags!!.keys)
        assertEquals(SentryLevel.WARNING, event.level)
        assertEquals(StutterReport.MESSAGE, event.message!!.formatted)
        assertNull(event.user)
        assertNull(event.breadcrumbs)
        assertNull(event.extras)
        assertNull(event.exceptions)
        assertEquals("36", event.tags!!["speed_percent"])
        assertEquals("software", event.tags!!["decoder_type"])
        assertEquals("SOFTWARE_TOO_SLOW", event.tags!!["prediction"])
    }

    @Test
    fun groupsByKindAndModelNotByPlay() {
        val a = StutterReport.build(facts()).getFingerprints()
        assertEquals(a, StutterReport.build(facts().copy(speedPercent = 20, dropped = 5)).getFingerprints())
        assertTrue(a != StutterReport.build(facts(model = "Other")).getFingerprints())
        assertTrue(a != StutterReport.build(facts(codec = "hevc")).getFingerprints())
    }

    @Test
    fun textFieldsCannotCarryAPathOrAFileName() {
        val dirty = facts().copy(
            container = "/storage/emulated/0/Movies/Secret.Show.S01E01.mkv\nnext",
            decoder = "x".repeat(500),
            profile = "a/b\\c",
        )
        val tags = StutterReport.tagsOf(dirty)
        assertFalse(tags["container"]!!.contains('/'))
        assertEquals(64, tags["decoder"]!!.length)
        assertEquals("abc", tags["profile"])
        assertFalse(tags.values.any { it.contains('\n') || it.contains('/') })
    }

    @Test
    fun scrubbingKeepsOnlyAllowedTagsAndContexts() {
        val event = StutterReport.build(facts()).apply {
            user = User().apply { id = "installation-id" }
            serverName = "my-phone"
            breadcrumbs = mutableListOf(Breadcrumb("opened Secret.Show.S01E01.mkv"))
            extras = mutableMapOf("title" to "Secret Show")
            tags!!["title"] = "Secret Show"
            tags!!["chat_id"] = "12345"
            transaction = "PlayerActivity"
            contexts.setDevice(Device().apply { manufacturer = "Xiaomi"; model = "MiTV"; name = "Ann's TV"; id = "abc" })
        }
        StutterReport.scrub(event)
        assertEquals(StutterReport.ALLOWED_TAGS, event.tags!!.keys)
        assertNull(event.user)
        assertNull(event.serverName)
        assertNull(event.breadcrumbs)
        assertNull(event.extras)
        assertNull(event.transaction)
        assertEquals("MiTV", event.contexts.device!!.model)
        assertNull(event.contexts.device!!.name)
        assertNull(event.contexts.device!!.id)
        assertEquals(StutterReport.MESSAGE, event.message!!.formatted)
    }

    @Test
    fun isStutterOnlyForItsOwnEvents() {
        assertTrue(StutterReport.isStutter(StutterReport.build(facts())))
        assertFalse(StutterReport.isStutter(SentryEvent()))
    }

    @Test
    fun oneReportPerKindPerDay() {
        var now = 1_000_000_000_000L
        val file = tempFile()
        val limits = StutterLimits(file) { now }
        assertTrue(limits.admit("av1:10:1080"))
        assertFalse(limits.admit("av1:10:1080"))
        assertTrue(limits.admit("hevc:10:1080"))
        assertTrue(limits.admit("av1:10:2160"))
        // A new limits object on the same file still remembers (a new process).
        assertFalse(StutterLimits(file) { now }.admit("av1:10:1080"))
        now += 24L * 60 * 60 * 1000
        assertTrue(limits.admit("av1:10:1080"))
    }

    @Test
    fun rateLimitThroughTheReporter() {
        val file = tempFile()
        val sent = mutableListOf<SentryEvent>()
        val reporter = StutterReporter({ true }, StutterLimits(file) { 5L }, sent::add)
        assertTrue(reporter.report(facts()))
        assertFalse(reporter.report(facts().copy(speedPercent = 10)))
        assertTrue(reporter.report(facts(codec = "hevc")))
        assertEquals(2, sent.size)
    }

    @Test
    fun nothingIsSentCountedOrKeptWithReportingOff() {
        val file = tempFile()
        val sent = mutableListOf<SentryEvent>()
        val reporter = StutterReporter({ false }, StutterLimits(file), sent::add)
        assertFalse(reporter.report(facts()))
        assertTrue(sent.isEmpty())
        assertFalse(file.exists())
        // Turning it on later is not blocked by anything counted while it was off.
        var on = false
        val later = StutterReporter({ on }, StutterLimits(file), sent::add)
        assertFalse(later.report(facts()))
        on = true
        assertTrue(later.report(facts()))
    }
}
