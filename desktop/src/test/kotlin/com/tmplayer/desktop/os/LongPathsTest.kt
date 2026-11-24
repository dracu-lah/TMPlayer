package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** WP-52: Windows long paths, as text, so every OS runs the same checks. */
class LongPathsTest {

    private val deep = "C:\\" + (1..30).joinToString("\\") { "folder$it" } // well past 240

    @Test
    fun `the prefix comes off what a picker or a setting hands back`() {
        assertEquals("C:\\Videos\\a", LongPaths.clean("\\\\?\\C:\\Videos\\a"))
        assertEquals("\\\\host\\share\\a", LongPaths.clean("\\\\?\\UNC\\host\\share\\a"))
        assertEquals("\\\\host\\share\\a", LongPaths.clean("\\\\?\\unc\\host\\share\\a"))
        assertEquals("C:\\plain", LongPaths.clean("C:\\plain"))
        assertEquals("/home/a", LongPaths.clean("/home/a"))
        assertEquals("", LongPaths.clean(""))
    }

    @Test
    fun `a short path and every other OS are left as they were`() {
        assertEquals("C:\\short", LongPaths.extend("C:\\short", windows = true))
        assertEquals(deep, LongPaths.extend(deep, windows = false))
        val file = File("/home/a")
        assertSame(file, LongPaths.io(file, windows = true))
    }

    @Test
    fun `a long drive path gets the prefix with backslashes and no dot segments`() {
        val messy = deep.replace('\\', '/') + "/./sub/../x.mkv"
        val out = LongPaths.extend(messy, windows = true)
        assertTrue(out.startsWith("\\\\?\\C:\\folder1\\"))
        assertTrue(out.endsWith("\\folder30\\x.mkv"))
        assertFalse(out.contains('/'))
        assertFalse(out.contains("\\.\\"))
        assertFalse(out.contains("\\..\\"))
    }

    @Test
    fun `a long network path gets the UNC form`() {
        val unc = "\\\\server\\share\\" + (1..30).joinToString("\\") { "folder$it" }
        assertTrue(LongPaths.extend(unc, windows = true).startsWith("\\\\?\\UNC\\server\\share\\folder1"))
    }

    @Test
    fun `a relative path and an already prefixed one are not touched`() {
        val relative = (1..60).joinToString("\\") { "folder$it" }
        assertEquals(relative, LongPaths.extend(relative, windows = true))
        val prefixed = "\\\\?\\" + deep
        assertEquals(prefixed, LongPaths.extend(prefixed, windows = true))
    }

    @Test
    fun `a folder with little room left for a name is prefixed earlier`() {
        val dir = "C:\\" + "d".repeat(150)
        assertEquals(dir, LongPaths.extend(dir, windows = true))
        assertTrue(LongPaths.extend(dir, windows = true, reserve = LongPaths.NAME_RESERVE).startsWith("\\\\?\\"))
        assertTrue(LongPaths.needsPrefix(File(dir), "n".repeat(100)))
        assertFalse(LongPaths.needsPrefix(File("C:\\Videos"), "clip.mkv"))
    }

    @Test
    fun `a picker's path is cleaned and a missing one stays missing`() {
        assertNull(LongPaths.fromPicker(null))
        assertEquals("C:\\a", LongPaths.fromPicker(File("\\\\?\\C:\\a"))?.path)
        val plain = File("/plain")
        assertSame(plain, LongPaths.fromPicker(plain))
        assertNull(LongPaths.forIo(null))
    }

    @Test
    fun `the viewer never sees the prefix`() {
        assertEquals("C:\\a\\b", LongPaths.shown(File("\\\\?\\C:\\a\\b")))
    }

    @Test
    fun `odd input never throws`() {
        listOf("", "\\\\?\\", "\\\\?\\UNC\\", "\\\\", ":", "C:", "C:\\", "\u0000", "..\\..").forEach {
            LongPaths.clean(it)
            LongPaths.extend(it, windows = true)
            LongPaths.extend(it.repeat(100), windows = true)
        }
    }
}
