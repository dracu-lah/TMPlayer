package com.tmplayer.desktop.os

import com.tmplayer.desktop.os.NativeRefresh.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The runtime folder mediamp unpacks into is cleared when a build ships a different runtime, and
 * only then, only of what the runtime's manifest lists, and never in a way that can fail the app.
 */
class NativeRefreshTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = listOf("libmpv.so", "libmpv.so.2", "libass.so.9", "libmediampv.so")

    private fun dir(): File = tmp.newFolder()

    private fun File.put(name: String, text: String = "old"): File = File(this, name).apply { writeText(text) }

    @Test
    fun `a stale folder is replaced and the marker written`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-2", manifest))
        manifest.forEach { assertFalse("$it should be gone", File(d, it).exists()) }
        assertEquals("id-2", File(d, NativeRefresh.MARKER).readText())
        assertFalse(File(d, "tmplayer-natives.id.tmp").exists())
    }

    @Test
    fun `an older marker counts as stale too`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        d.put(NativeRefresh.MARKER, "id-1")
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-2", manifest))
        assertFalse(File(d, "libmpv.so").exists())
        assertEquals("id-2", File(d, NativeRefresh.MARKER).readText())
    }

    @Test
    fun `a matching marker leaves the folder alone, and the next start does too`() {
        val d = dir()
        assertEquals(Outcome.Fresh, NativeRefresh.refresh(d, "id-1", manifest))
        // mediamp unpacks.
        manifest.forEach { d.put(it, "fresh") }
        assertEquals(Outcome.Current, NativeRefresh.refresh(d, "id-1", manifest))
        manifest.forEach { assertEquals("fresh", File(d, it).readText()) }
    }

    @Test
    fun `a fresh install only gets the marker`() {
        val d = dir()
        assertEquals(Outcome.Fresh, NativeRefresh.refresh(d, "id-1", manifest))
        assertEquals("id-1", File(d, NativeRefresh.MARKER).readText())
    }

    @Test
    fun `a folder that is not there is not made and is not an error`() {
        val gone = File(tmp.root, "no/such/dir")
        assertEquals(Outcome.Failed, NativeRefresh.refresh(gone, "id-1", manifest))
        assertFalse(gone.exists())
    }

    @Test
    fun `files the runtime did not put there are kept`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        d.put("notes.txt", "mine")
        d.put("libother.so", "foreign")
        File(d, "sub").mkdirs()
        File(d, "sub/keep.txt").writeText("deep")
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-2", manifest))
        assertEquals("mine", File(d, "notes.txt").readText())
        assertEquals("foreign", File(d, "libother.so").readText())
        assertEquals("deep", File(d, "sub/keep.txt").readText())
    }

    @Test
    fun `a manifest name that climbs out of the folder is ignored`() {
        val d = dir()
        val outside = tmp.newFile("outside.txt").apply { writeText("precious") }
        d.put("libmpv.so")
        val evil = listOf("../outside.txt", "..", ".", "/etc/passwd", "a/b", "", "  ", "libmpv.so")
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-2", evil))
        assertEquals("precious", outside.readText())
        assertFalse(File(d, "libmpv.so").exists())
        assertEquals(listOf("libmpv.so"), NativeRefresh.safeNames(evil))
    }

    @Test
    fun `a symlink is unlinked and its target, outside the folder, survives`() {
        val d = dir()
        val outside = tmp.newFolder("outside")
        val target = File(outside, "target.so").apply { writeText("precious") }
        val inner = File(outside, "inner.txt").apply { writeText("precious too") }
        try {
            Files.createSymbolicLink(File(d, "libmpv.so").toPath(), target.toPath())
            // A listed name that is a link to a whole folder.
            Files.createSymbolicLink(File(d, "libass.so.9").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeTrue("symlinks not available: $e", false)
        }
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-2", manifest))
        assertFalse(Files.exists(File(d, "libmpv.so").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(File(d, "libass.so.9").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("precious", target.readText())
        assertEquals("precious too", inner.readText())
    }

    @Test
    fun `a listed folder is removed with what is in it, and a link inside it is not followed`() {
        val d = dir()
        val outside = tmp.newFile("outside.txt").apply { writeText("precious") }
        val sub = File(d, "libass.so.9").apply { mkdirs() }
        File(sub, "x").writeText("x")
        try {
            Files.createSymbolicLink(File(sub, "link").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeTrue("symlinks not available: $e", false)
        }
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-2", manifest))
        assertFalse(sub.exists())
        assertEquals("precious", outside.readText())
    }

    @Test
    fun `a blank id (a build without the pinned natives) never touches the folder`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        assertEquals(Outcome.NotApplicable, NativeRefresh.refresh(d, "", manifest))
        assertEquals(Outcome.NotApplicable, NativeRefresh.refresh(d, "  \n", manifest))
        manifest.forEach { assertEquals("old", File(d, it).readText()) }
        assertFalse(File(d, NativeRefresh.MARKER).exists())
        assertFalse(File(d, NativeRefresh.LOCK).exists())
    }

    @Test
    fun `a missing manifest touches nothing`() {
        val d = dir()
        d.put("libmpv.so")
        assertEquals(Outcome.Failed, NativeRefresh.refresh(d, "id-1", null))
        assertEquals("old", File(d, "libmpv.so").readText())
    }

    @Test
    fun `a read only folder does not throw and nothing is removed`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        d.put(NativeRefresh.LOCK, "")
        assumeTrue("running as a user that ignores permissions", d.setWritable(false) && !File(d, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false).also { ok -> it.delete() } })
        try {
            assertEquals(Outcome.Failed, NativeRefresh.refresh(d, "id-2", manifest))
            // Again: still no exception, no loop.
            assertEquals(Outcome.Failed, NativeRefresh.refresh(d, "id-2", manifest))
        } finally {
            d.setWritable(true)
        }
        manifest.forEach { assertEquals("old", File(d, it).readText()) }
    }

    @Test
    fun `a read only folder without a lock file does not throw either`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        assumeTrue("running as a user that ignores permissions", d.setWritable(false) && !File(d, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false).also { ok -> it.delete() } })
        try {
            assertEquals(Outcome.Failed, NativeRefresh.refresh(d, "id-2", manifest))
        } finally {
            d.setWritable(true)
        }
        manifest.forEach { assertEquals("old", File(d, it).readText()) }
    }

    @Test
    fun `garbage in the marker means different, and the marker is replaced`() {
        val bytes = listOf(
            ByteArray(0),
            ByteArray(1_000_000) { 'a'.code.toByte() },
            ByteArray(4096).also { Random(7).nextBytes(it) },
            byteArrayOf(0, 0, 0, -1, -2, 10, 13),
            "id-1 and more".toByteArray(),
        )
        for (b in bytes) {
            val d = dir()
            d.put("libmpv.so")
            File(d, NativeRefresh.MARKER).writeBytes(b)
            assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-1", manifest))
            assertEquals("id-1", File(d, NativeRefresh.MARKER).readText())
            assertFalse(File(d, "libmpv.so").exists())
        }
    }

    @Test
    fun `a marker with a trailing newline still matches`() {
        val d = dir()
        d.put(NativeRefresh.MARKER, "id-1\n")
        d.put("libmpv.so")
        assertEquals(Outcome.Current, NativeRefresh.refresh(d, "id-1", manifest))
        assertTrue(File(d, "libmpv.so").exists())
    }

    @Test
    fun `a marker that is a folder is replaced, not looped on`() {
        val d = dir()
        d.put("libmpv.so")
        File(d, NativeRefresh.MARKER).mkdirs()
        val first = NativeRefresh.refresh(d, "id-1", manifest)
        // An empty folder is replaced by the move; either way the answer is one of the two and no throw.
        assertTrue(first == Outcome.Refreshed || first == Outcome.Failed)
    }

    @Test
    fun `garbage manifests are filtered`() {
        val d = dir()
        d.put("libmpv.so")
        val huge = List(5000) { "lib$it.so" }
        val junk = listOf("\u0000", "a\u0000b", "x".repeat(10_000), "libmpv.so", "libmpv.so", NativeRefresh.MARKER, NativeRefresh.LOCK, "😀")
        assertEquals(1000, NativeRefresh.safeNames(huge).size)
        assertEquals(listOf("libmpv.so"), NativeRefresh.safeNames(junk))
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-1", junk))
    }

    @Test
    fun `every name in the real runtime manifest is kept by the filter`() {
        assumeTrue(OsInfo.isLinux && OsInfo.archTag == "x64")
        val raw = NativeRefresh::class.java.classLoader.getResourceAsStream("mpv-natives-linux-x64.txt")!!
            .use { String(it.readBytes()).lines() }.map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(raw.size > 40)
        assertEquals(raw.distinct(), NativeRefresh.safeNames(raw))
        assertTrue(raw.contains("libmpv.so") && raw.contains("libmediampv.so"))
    }

    @Test
    fun `callers at once, in this process, remove once and agree`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        val go = CountDownLatch(1)
        try {
            val results = (1..n).map {
                pool.submit<Outcome> {
                    go.await()
                    NativeRefresh.refresh(d, "id-2", manifest)
                }
            }
            go.countDown()
            val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == Outcome.Refreshed })
            assertEquals(n - 1, outcomes.count { it == Outcome.Current })
        } finally {
            pool.shutdownNow()
        }
        assertEquals("id-2", File(d, NativeRefresh.MARKER).readText())
        manifest.forEach { assertFalse(File(d, it).exists()) }
    }

    @Test
    fun `another process holding the lock makes this one wait, then skip, and never delete`() {
        val d = dir()
        manifest.forEach { d.put(it) }
        // A channel from another handle in this JVM stands in for another process: tryLock on a
        // second channel for the same file overlaps, which the code treats as "held".
        FileChannel.open(File(d, NativeRefresh.LOCK).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use {
                val started = System.nanoTime()
                assertEquals(Outcome.Busy, NativeRefresh.refresh(d, "id-2", manifest, lockWaitMs = 300))
                assertTrue("waited for the lock", System.nanoTime() - started >= 250_000_000L)
            }
        }
        manifest.forEach { assertTrue("$it kept", File(d, it).exists()) }
        assertFalse(File(d, NativeRefresh.MARKER).exists())
        // Released: the next call goes through.
        assertEquals(Outcome.Refreshed, NativeRefresh.refresh(d, "id-2", manifest))
    }
}
