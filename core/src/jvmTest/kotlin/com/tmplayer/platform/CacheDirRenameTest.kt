package com.tmplayer.platform

import com.tmplayer.data.DiskInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CacheDirRenameTest {

    private val dir: File = Files.createTempDirectory("tm-rename").toFile()
    private val legacy = File(dir, CacheDirRename.LEGACY_NAME)
    private val current = File(dir, CacheDirRename.NAME)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a fresh install just uses the new name`() {
        assertEquals(current, CacheDirRename.adopt(legacy, current))
        assertFalse(legacy.exists())
    }

    @Test
    fun `an upgrade renames once and old paths still reach the same bytes`() {
        val video = File(legacy, "videos/5.mkv").apply { parentFile.mkdirs(); writeText("bytes") }

        assertEquals(current, CacheDirRename.adopt(legacy, current))

        assertTrue(Files.isSymbolicLink(legacy.toPath()))
        assertEquals("bytes", File(current, "videos/5.mkv").readText())
        // The path TDLib's database still holds.
        assertEquals("bytes", video.readText())
        assertEquals(File(current, "videos/5.mkv").canonicalPath, video.canonicalPath)
    }

    @Test
    fun `the second launch finds the link and does nothing`() {
        File(legacy, "videos").mkdirs()
        CacheDirRename.adopt(legacy, current)
        assertEquals(current, CacheDirRename.adopt(legacy, current))
        assertTrue(current.isDirectory)
    }

    @Test
    fun `an empty new directory in the way is replaced`() {
        File(legacy, "videos").mkdirs()
        current.mkdirs()
        assertEquals(current, CacheDirRename.adopt(legacy, current))
        assertTrue(File(current, "videos").isDirectory)
    }

    @Test
    fun `both names holding files keeps the old one and merges nothing`() {
        File(legacy, "videos/a.mkv").apply { parentFile.mkdirs(); writeText("a") }
        File(current, "videos/b.mkv").apply { parentFile.mkdirs(); writeText("b") }
        assertEquals(legacy, CacheDirRename.adopt(legacy, current))
        assertTrue(File(legacy, "videos/a.mkv").isFile)
        assertFalse(File(current, "videos/a.mkv").exists())
    }

    @Test
    fun `a directory not made yet is measured at its parent`() {
        val measured = DiskInfo.of(File(dir, "not/made/yet"))
        assertTrue(measured.totalBytes > 0)
        assertEquals(DiskInfo.of(dir).totalBytes, measured.totalBytes)
    }
}
