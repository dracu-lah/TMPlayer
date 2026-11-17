package com.tmplayer.desktop.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The check that Chromium starts, in a child process, before TMPlayer starts it itself
 * ([CefProbe]): how the child is launched (the packaged launcher, or `java` with the class path),
 * and that its answer is kept, a yes for good and a no for a week, so it runs once.
 */
class CefProbeTest {

    private val dir: File = Files.createTempDirectory("tm-probe").toFile()

    @Test
    fun `the packaged app runs its own launcher with the probe argument`() {
        val command = CefProbe.command(dir, launcher = "/opt/tmplayer/bin/TMPlayer", classPath = "ignored", jvmOptions = emptyList())
        assertEquals(listOf("/opt/tmplayer/bin/TMPlayer", CefProbe.ARG, dir.absolutePath), command)
    }

    @Test
    fun `a JVM started by hand runs java with the class path, the opens and the app's main`() {
        val command = CefProbe.command(
            dir,
            launcher = "/usr/lib/jvm/jdk-21/bin/java",
            classPath = "a.jar:b.jar",
            jvmOptions = listOf("-Xmx2g", "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "-Dfoo=bar"),
        )
        assertEquals(
            listOf("/usr/lib/jvm/jdk-21/bin/java", "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "-cp", "a.jar:b.jar", "com.tmplayer.desktop.MainKt", CefProbe.ARG, dir.absolutePath),
            command,
        )
        assertEquals("java.exe on Windows", "C:\\jdk\\bin\\java.exe", CefProbe.command(dir, "C:\\jdk\\bin\\java.exe", "a.jar", emptyList())?.first())
        assertNull("no class path, no child", CefProbe.command(dir, "/usr/bin/java", "", emptyList()))
        assertNull("no launcher, no child", CefProbe.command(dir, null, "a.jar", emptyList()))
    }

    @Test
    fun `a yes is kept and the child is not run again`() {
        var runs = 0
        assertTrue(CefProbe.startsHere(dir) { runs++; true })
        assertTrue(File(dir, CefProbe.OK).isFile)
        assertTrue(CefProbe.startsHere(dir) { runs++; false })
        assertEquals(1, runs)
    }

    @Test
    fun `a no is believed for a week, then tried again`() {
        var runs = 0
        val now = System.currentTimeMillis()
        assertFalse(CefProbe.startsHere(dir, now) { runs++; false })
        assertTrue(File(dir, CefProbe.FAILED).isFile)
        assertFalse(CefProbe.startsHere(dir, now + 60_000) { runs++; true })
        assertEquals("not run again within the week", 1, runs)
        assertTrue(CefProbe.startsHere(dir, now + CefProbe.FAILED_FOR_MS + 60_000) { runs++; true })
        assertEquals(2, runs)
        assertFalse("a yes clears the no", File(dir, CefProbe.FAILED).exists())
    }
}
