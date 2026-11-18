package com.tmplayer.data

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.App
import io.sentry.protocol.Device
import io.sentry.protocol.Gpu
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryRuntime
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class CrashReportScrubberTest {

    private fun frame() = SentryStackFrame().apply {
        module = "com.tmplayer.player.PlayerActivity"
        function = "onCreate"
        lineno = 42
        filename = "PlayerActivity.kt"
        absPath = "/home/build/PlayerActivity.kt"
        contextLine = "val title = \"Secret.Show.S01E01.mkv\""
        vars = mapOf("path" to "/storage/emulated/0/Download/Secret.Show.S01E01.mkv")
    }

    /** An event with every field the Android SDK is known to fill in, and a few it might. */
    private fun fullEvent() = SentryEvent().apply {
        release = "tmplayer@1.22.1"
        environment = "release"
        platform = "java"
        dist = "122"
        user = User().apply { id = "installation-id-1234" }
        serverName = "my-phone"
        transaction = "PlayerActivity"
        tags = mutableMapOf("isSideLoaded" to "true", "installerStore" to "org.fdroid")
        extras = mutableMapOf("anything" to "else")
        breadcrumbs = mutableListOf(Breadcrumb("opened Secret.Show.S01E01.mkv"))
        setModule("com.example", "1.0")
        message = Message().apply { formatted = "Could not open \"My Holiday Video 2024.mp4\"" }

        contexts.setDevice(Device().apply {
            id = "installation-id-1234"
            name = "Ann's phone"
            manufacturer = "Xiaomi"
            brand = "POCO"
            model = "23021RAA2Y"
            family = "POCO"
            modelId = "TQ3A"
            batteryLevel = 0.5f
            isCharging = true
            isOnline = true
            orientation = Device.DeviceOrientation.PORTRAIT
            isSimulator = false
            memorySize = 8_000_000_000
            freeMemory = 1_000_000_000
            storageSize = 128_000_000_000
            freeStorage = 9_000_000_000
            screenDensity = 2.75f
            screenDpi = 440
            screenWidthPixels = 1080
            screenHeightPixels = 2400
            bootTime = Date()
            timezone = TimeZone.getTimeZone("Asia/Kolkata")
            connectionType = "wifi"
            locale = "en_IN"
            archs = arrayOf("arm64-v8a")
            processorCount = 8
        })
        contexts.setOperatingSystem(OperatingSystem().apply {
            name = "Android"
            version = "14"
            build = "UKQ1.230804.001"
            kernelVersion = "5.10.0"
            isRooted = false
            rawDescription = "raw"
        })
        contexts.setApp(App().apply {
            appIdentifier = "com.tmplayer"
            appName = "TMPlayer"
            appVersion = "1.22.1"
            appBuild = "122"
            appStartTime = Date()
            permissions = mapOf("INTERNET" to "granted")
            inForeground = true
            viewNames = listOf("PlayerActivity")
            deviceAppHash = "hash"
        })
        contexts.setRuntime(SentryRuntime().apply { name = "Android" })
        contexts.setGpu(Gpu().apply { name = "Adreno" })
        contexts.put("custom", mapOf("a" to "b"))

        exceptions = listOf(SentryException().apply {
            type = "IOException"
            value = "open failed: /storage/emulated/0/Download/Secret.Show.S01E01.mkv (ENOENT)"
            stacktrace = SentryStackTrace(listOf(frame()))
        })
        threads = listOf(SentryThread().apply {
            id = 1
            name = "main"
            stacktrace = SentryStackTrace(listOf(frame()))
        })
    }

    @Test
    fun keepsExactlyTheAllowedFields() {
        val event = CrashReportScrubber.scrub(fullEvent())

        assertEquals(setOf("device", "os", "app"), event.contexts.keys().toList().toSet())

        val device = event.contexts.device!!
        assertEquals(Device().apply { manufacturer = "Xiaomi"; model = "23021RAA2Y" }, device)

        val os = event.contexts.operatingSystem!!
        assertEquals(OperatingSystem().apply { name = "Android"; version = "14" }, os)

        val app = event.contexts.app!!
        assertEquals(App().apply { appVersion = "1.22.1"; appBuild = "122" }, app)

        assertEquals("tmplayer@1.22.1", event.release)
        assertEquals("release", event.environment)
        assertNull(event.user)
        assertNull(event.serverName)
        assertNull(event.transaction)
        assertNull(event.tags)
        assertNull(event.extras)
        assertNull(event.breadcrumbs)
        assertNull(event.getModule("com.example"))
        assertEquals("Could not open [file]", event.message!!.formatted)
    }

    /**
     * S2: the player's tags are the only ones a crash keeps, by an exact list. The SDK's own tags
     * and one a future SDK might add are gone, since the scrubber empties the tags before ours go
     * back on; a key or a value outside the list is refused rather than cleaned.
     */
    @Test
    fun keepsExactlyThePlayerTagsAndFailsClosed() {
        val event = fullEvent().apply {
            tags = tags.orEmpty().toMutableMap().apply {
                put("future_sdk_field", "something new")
                put("title", "Secret Show")
            }
        }
        CrashReportScrubber.scrub(event)
        val playback = CrashContext.Playback(
            engine = "media3",
            decoder = "c2.mtk.hevc.decoder",
            codec = "HEVC",
            width = 3840,
            height = 2160,
            stutter = "dropped",
            atMillis = 0,
        )
        CrashContext.tag(event, CrashContext.tagsOf("Mediatek MT8695", playback) + mapOf("title" to "Secret Show", "chat" to "x"))

        assertEquals(
            listOf("engine", "chipset", "decoder", "codec", "size_class", "stutter", "heap_mb", "heap_used"),
            CrashContext.ALLOWED_TAGS,
        )
        assertEquals(
            mapOf(
                "engine" to "media3",
                "chipset" to "Mediatek MT8695",
                "decoder" to "c2.mtk.hevc.decoder",
                "codec" to "hevc",
                "size_class" to "uhd",
                "stutter" to "dropped",
            ),
            event.tags,
        )
        // Everything else CP01 allows is as it was.
        assertEquals(setOf("device", "os", "app"), event.contexts.keys().toList().toSet())
        assertNull(event.extras)
        assertNull(event.breadcrumbs)
    }

    @Test
    fun keepsTheStackTraceButNotTheFileNames() {
        val event = CrashReportScrubber.scrub(fullEvent())

        val exception = event.exceptions!!.single()
        assertEquals("IOException", exception.type)
        assertEquals("open failed: [path] (ENOENT)", exception.value)

        val frames = exception.stacktrace!!.frames!! + event.threads!!.single().stacktrace!!.frames!!
        frames.forEach { frame ->
            assertEquals("com.tmplayer.player.PlayerActivity", frame.module)
            assertEquals("onCreate", frame.function)
            assertEquals(42, frame.lineno)
            assertEquals(mapOf("path" to "[path]"), frame.vars)
            assertNull(frame.absPath)
            assertNull(frame.contextLine)
        }
    }

    @Test
    fun redactsPathsAndVideoNames() {
        val r = CrashReportScrubber::redact
        assertEquals("cannot read [path]", r("cannot read /data/user/0/com.tmplayer/files/x.bin"))
        assertEquals("bad name [file] here", r("bad name Movie.2024.1080p.MKV here"))
        assertEquals("bad [file]", r("bad 'Some Long Name (2020).webm'"))
        assertEquals("at Lcom/tmplayer/Foo;", r("at Lcom/tmplayer/Foo;"))
        assertEquals("index 3 out of bounds for length 2", r("index 3 out of bounds for length 2"))
        assertFalse(r("x /storage/emulated/0/a.mp4").contains("emulated"))
    }

    @Test
    fun redactsComputerPaths() {
        val r = CrashReportScrubber::redact
        assertEquals("[path] (Access is denied)", r("""C:\Users\Jane\AppData\Local\TMPlayer\x.db (Access is denied)"""))
        assertEquals("cannot open [path]", r("""cannot open C:\Users\Jane Doe\Videos\holiday.mkv"""))
        assertEquals("on [path] now", r("on D:/TMPlayer/cache now"))
        assertEquals("share [path]", r("""share \\nas\films\a.mp4"""))
        assertEquals("[path] (No such file or directory)", r("/Users/Jane Doe/Movies/TMPlayer/a.mkv (No such file or directory)"))
        assertFalse(r("""C:\Users\Jane Doe\Videos\a.mkv""").contains("Jane"))
        assertEquals("ratio 3/4 at Lcom/tmplayer/Foo;", r("ratio 3/4 at Lcom/tmplayer/Foo;"))
    }

    @Test
    fun aComputerKeepsNoDevice() {
        val event = SentryEvent().apply {
            contexts.setDevice(Device().apply { model = "Inspiron 15"; manufacturer = "Dell" })
            contexts.setOperatingSystem(OperatingSystem().apply { name = "Windows 11"; version = "10.0" })
            contexts.setApp(App().apply { appVersion = "1.27.2"; appBuild = "1.27.2" })
            serverName = "JANES-LAPTOP"
        }
        CrashReportScrubber.scrub(event, keepDevice = false)
        assertNull(event.contexts.device)
        assertNull(event.serverName)
        assertEquals("Windows 11", event.contexts.operatingSystem!!.name)
        assertEquals("10.0", event.contexts.operatingSystem!!.version)
        assertEquals("1.27.2", event.contexts.app!!.appVersion)
    }
}
