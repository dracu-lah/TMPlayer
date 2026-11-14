package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.sentry.SentryOptions
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta

class CrashRecordTest {

    @get:Rule
    val dir = TemporaryFolder()

    private fun crash(): Throwable =
        IllegalStateException("outer", IllegalArgumentException("inner"))

    @Test
    fun `a record survives the trip to the disk`() {
        val file = dir.root.resolve("crash/pending.json")
        CrashRecord.of(crash(), "1.26.0", 12600).write(file)

        val read = CrashRecord.read(file)!!
        assertEquals("1.26.0", read.versionName)
        assertEquals(12600L, read.versionCode)
        assertEquals(
            listOf("java.lang.IllegalStateException", "java.lang.IllegalArgumentException"),
            read.chain.map { it.type },
        )
        assertEquals("outer", read.chain[0].message)
        assertEquals(crash().stackTrace.first().methodName, read.chain[0].frames.first().methodName)
        assertFalse("no temporary file is left behind", dir.root.resolve("crash/pending.json.part").exists())
    }

    @Test
    fun `a record without a message reads back without one`() {
        val file = dir.root.resolve("pending.json")
        CrashRecord.of(RuntimeException(), "1.26.0", 12600).write(file)
        assertNull(CrashRecord.read(file)!!.chain.single().message)
    }

    @Test
    fun `a missing or broken file is no record`() {
        val file = dir.root.resolve("pending.json")
        assertNull(CrashRecord.read(file))
        file.writeText("{ half")
        assertNull(CrashRecord.read(file))
    }

    @Test
    fun `the event puts the cause first and the oldest frame first, as Sentry does`() {
        val record = CrashRecord.of(crash(), "1.26.0", 12600)
        val event = record.toEvent()
        val exceptions = event.exceptions!!

        assertEquals(listOf("IllegalArgumentException", "IllegalStateException"), exceptions.map { it.type })
        assertEquals("java.lang", exceptions.last().module)
        assertEquals("tmplayer@1.26.0", event.release)
        assertEquals(false, exceptions.last().mechanism?.isHandled)
        val frames = exceptions.last().stacktrace!!.frames!!
        assertEquals(record.chain[0].frames.last().methodName, frames.first().function)
        assertTrue(frames.any { it.isInApp == true })
    }

    @Test
    fun `a crash written by one build and read by the next is sent as the build that crashed`() {
        val file = dir.root.resolve("crash/pending.json")
        CrashRecord.of(crash(), "1.27.0", 12700, listOf(UUID_A)).write(file)

        // Build B reads it: the event names build A, and only A's mapping.
        val read = CrashRecord.read(file)!!
        assertTrue(CrashRecord.worthSending(read, runningVersionCode = 12710, now = read.time + 60_000))
        val event = read.toEvent()
        assertEquals("tmplayer@1.27.0", event.release)
        assertEquals("12700", event.dist)
        assertEquals("1.27.0", event.contexts.app?.appVersion)
        assertEquals("12700", event.contexts.app?.appBuild)
        val image = event.debugMeta!!.images!!.single()
        assertEquals(DebugImage.PROGUARD, image.type)
        assertEquals(UUID_A, image.uuid)

        // What the SDK does with it on the way out: the running build's UUID is not added beside it.
        val options = SentryOptions().apply { proguardUuid = UUID_B }
        val sent = DebugMeta.buildDebugMeta(event.debugMeta, options)!!
        assertEquals(listOf(UUID_A), sent.images!!.map { it.uuid })
    }

    @Test
    fun `the app context is put back to the crashed build after the SDK fills it`() {
        val record = CrashRecord.of(crash(), "1.27.0", 12700, listOf(UUID_A))
        val event = record.toEvent()
        event.contexts.app!!.appVersion = "1.27.1"
        event.contexts.app!!.appBuild = "12710"
        record.stampBuild(event)
        assertEquals("1.27.0", event.contexts.app?.appVersion)
        assertEquals("12700", event.contexts.app?.appBuild)
    }

    @Test
    fun `a crash without a UUID from another build, or older than a month, is not kept`() {
        val now = System.currentTimeMillis()
        val old = CrashRecord(now, "1.27.0", 12700, emptyList())
        assertFalse("another build with nothing to decode it", CrashRecord.worthSending(old, 12710, now))
        assertTrue("the running build needs no UUID", CrashRecord.worthSending(old, 12700, now))
        val stale = CrashRecord(now - CrashRecord.KEEP_MILLIS - 1, "1.27.0", 12700, emptyList(), listOf(UUID_A))
        assertFalse(CrashRecord.worthSending(stale, 12700, now))
        assertFalse(CrashRecord.worthSending(stale, 12710, now))
    }

    @Test
    fun `the desktop keeps another build's crash without a UUID, for a month`() {
        val now = System.currentTimeMillis()
        val old = CrashRecord(now, "1.27.2", 12702, emptyList())
        assertTrue(CrashRecord.worthSending(old, 12800, now, needsMapping = false))
        val stale = CrashRecord(now - CrashRecord.KEEP_MILLIS - 1, "1.27.2", 12702, emptyList())
        assertFalse(CrashRecord.worthSending(stale, 12800, now, needsMapping = false))
    }

    @Test
    fun `a record written by 1_27_1 or older reads with no UUID`() {
        val file = dir.root.resolve("pending.json")
        file.writeText(
            """{"time":1,"versionName":"1.27.1","versionCode":12710,"chain":[{"type":"java.lang.RuntimeException","message":null,"frames":[]}]}""",
        )
        val read = CrashRecord.read(file)!!
        assertTrue(read.proguardUuids.isEmpty())
        assertNull(read.toEvent().debugMeta)
    }

    @Test
    fun `the plugin's properties file gives its UUIDs`() {
        assertEquals(listOf(UUID_A), CrashRecord.proguardUuidsIn("io.sentry.ProguardUuids=$UUID_A\n"))
        assertEquals(listOf(UUID_A, UUID_B), CrashRecord.proguardUuidsIn("io.sentry.ProguardUuids=$UUID_A|$UUID_B"))
        assertTrue(CrashRecord.proguardUuidsIn("io.sentry.build-tool=gradle").isEmpty())
    }

    @Test
    fun `two start-up crashes in a row are a loop, and a later crash breaks the run`() {
        val file = dir.root.resolve("crash/pending.json")
        val first = CrashRecord.of(crash(), "1.27.0", 12700, sinceStartMillis = 2_000)
        assertEquals(1, first.startupRun)
        assertFalse(first.startupLoop)
        first.write(file)

        // The next run crashes as early, with the first still waiting: a loop, and it survives the disk.
        val second = CrashRecord.of(crash(), "1.27.0", 12700, sinceStartMillis = 3_000, previous = CrashRecord.read(file))
        assertTrue(second.startupLoop)
        second.write(file)
        val read = CrashRecord.read(file)!!
        assertEquals(2, read.startupRun)
        assertEquals(3_000L, read.sinceStartMillis)
        assertTrue(read.startupLoop)

        val late = CrashRecord.of(crash(), "1.27.0", 12700, sinceStartMillis = CrashRecord.STARTUP_MILLIS + 1, previous = read)
        assertEquals(0, late.startupRun)
        assertFalse(late.startupLoop)
        val unknown = CrashRecord.of(crash(), "1.27.0", 12700, previous = read)
        assertFalse("a crash with no start time is not counted", unknown.startupLoop)
    }

    @Test
    fun `the log carries the crash of the earlier run, with its build, its mapping and its frames`() {
        val first = CrashRecord.of(crash(), "1.28.5", 1280599, listOf(UUID_A), sinceStartMillis = 3_200)
        val record = CrashRecord.of(crash(), "1.28.5", 1280599, listOf(UUID_A), sinceStartMillis = 3_200, previous = first)
        val lines = record.describe()
        assertTrue(lines.first().startsWith("Last crash"))
        assertTrue(lines.any { it.contains("Build 1.28.5 (1280599)") && it.contains("3.2 s into the run") && it.contains("number 2 in a row") })
        assertTrue(lines.any { it.contains("Mapping ids: $UUID_A") })
        assertTrue(lines.any { it.trim() == "java.lang.IllegalStateException: outer" })
        assertTrue(lines.any { it.trim() == "java.lang.IllegalArgumentException: inner" })
        assertTrue(lines.any { it.startsWith("    at ") && it.contains("CrashRecordTest") })
    }

    @Test
    fun `the log's crash is cut to a few lines however deep and long the crash is`() {
        fun deep(n: Int): Throwable = if (n == 0) RuntimeException("x".repeat(5_000)) else deep(n - 1)
        val chain = (1..30).fold(deep(40) as Throwable) { cause, i -> RuntimeException("cause $i", cause) }
        val lines = CrashRecord.of(chain, "1.28.5", 1280599).describe()
        assertTrue("got ${lines.size}", lines.size <= 62)
        assertTrue(lines.all { it.length < 400 })
    }

    private companion object {
        const val UUID_A = "6f1c9e2a-0b3d-4c7e-9a51-2d8f4b6e1a01"
        const val UUID_B = "0d4e7b1c-5a2f-4e93-8c60-7b1a9d3f2e02"
    }
}
