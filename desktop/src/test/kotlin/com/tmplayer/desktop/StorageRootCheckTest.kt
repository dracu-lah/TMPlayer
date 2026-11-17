package com.tmplayer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A chosen storage location that cannot be used at launch is skipped for that run (#35): a drive
 * that is gone, a folder that was removed, a path that names a file, a read only folder. The
 * check makes nothing but `<root>/TMPlayer` and leaves no probe behind.
 */
class StorageRootCheckTest {

    private val tmp = Files.createTempDirectory("tm-root-check").toFile()

    @Test
    fun `a folder that is there and writable is used, and gets its TMPlayer folder`() {
        assertTrue(StorageRootCheck.usable(tmp))
        val base = File(tmp, "TMPlayer")
        assertTrue(base.isDirectory)
        assertEquals("the probe file is removed", 0, base.listFiles()?.size ?: -1)
        // Again, with the TMPlayer folder already there and holding the viewer's files.
        File(base, "downloads").mkdirs()
        assertTrue(StorageRootCheck.usable(tmp))
        assertTrue(File(base, "downloads").isDirectory)
    }

    @Test
    fun `a drive or folder that is gone is not used, and is not made`() {
        val gone = File(tmp, "unplugged/TM player")
        assertFalse(StorageRootCheck.usable(gone))
        assertFalse("the chosen folder itself is never created", gone.exists())
        // As on Windows: a drive letter that is not there.
        assertFalse(StorageRootCheck.usable(File("Q:\\TM player")))
    }

    @Test
    fun `a path that names a file is not used`() {
        val file = File(tmp, "a-file").apply { writeText("x") }
        assertFalse(StorageRootCheck.usable(file))
        assertEquals("x", file.readText())
    }

    @Test
    fun `a TMPlayer name taken by a file is not used, and the file is kept`() {
        val file = File(tmp, "TMPlayer").apply { writeText("keep") }
        assertFalse(StorageRootCheck.usable(tmp))
        assertEquals("keep", file.readText())
    }

    @Test
    fun `a read only folder is not used`() {
        val locked = File(tmp, "locked").apply { mkdirs() }
        assumeTrue("needs a file system that honours permissions", locked.setWritable(false, false))
        try {
            assumeTrue("running as a user that ignores permissions", !locked.canWrite())
            assertFalse(StorageRootCheck.usable(locked))
        } finally {
            locked.setWritable(true, false)
        }
    }
}
