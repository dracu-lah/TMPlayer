package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserDirsTest {

    private val home = File("/home/viewer")

    @Test
    fun `reads the download folder a stock xdg-user-dirs writes`() {
        val text = """
            # This file is written by xdg-user-dirs-update
            # If you want to change or add directories, just edit the line you're
            # interested in. All local changes will be retained on the next run.
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
            XDG_DOWNLOAD_DIR="${'$'}HOME/Downloads"
            XDG_VIDEOS_DIR="${'$'}HOME/Videos"
        """.trimIndent()
        assertEquals(File(home, "Downloads"), UserDirs.parseUserDirs(text, home))
    }

    @Test
    fun `a localised name is taken as it is`() {
        val text = "XDG_DOWNLOAD_DIR=\"${'$'}HOME/Téléchargements\""
        assertEquals(File(home, "Téléchargements"), UserDirs.parseUserDirs(text, home))
    }

    @Test
    fun `an absolute path elsewhere is honoured`() {
        val text = "XDG_DOWNLOAD_DIR=\"/mnt/data/Downloads\""
        assertEquals(File("/mnt/data/Downloads"), UserDirs.parseUserDirs(text, home))
    }

    @Test
    fun `the braced spelling of HOME works too`() {
        val text = "XDG_DOWNLOAD_DIR=\"${'$'}{HOME}/Dl\""
        assertEquals(File(home, "Dl"), UserDirs.parseUserDirs(text, home))
    }

    @Test
    fun `a folder set to HOME itself means turned off`() {
        assertNull(UserDirs.parseUserDirs("XDG_DOWNLOAD_DIR=\"${'$'}HOME/\"", home))
        assertNull(UserDirs.parseUserDirs("XDG_DOWNLOAD_DIR=\"${'$'}HOME\"", home))
    }

    @Test
    fun `a relative path, a comment and a missing key give nothing`() {
        assertNull(UserDirs.parseUserDirs("XDG_DOWNLOAD_DIR=\"Downloads\"", home))
        assertNull(UserDirs.parseUserDirs("# XDG_DOWNLOAD_DIR=\"/x\"", home))
        assertNull(UserDirs.parseUserDirs("XDG_VIDEOS_DIR=\"${'$'}HOME/Videos\"", home))
        assertNull(UserDirs.parseUserDirs("", home))
    }

    @Test
    fun `the config home is read before the dot config fallback`() {
        val dir = Files.createTempDirectory("tm-userdirs").toFile()
        try {
            val fakeHome = File(dir, "home").apply { mkdirs() }
            File(fakeHome, ".config").mkdirs()
            File(fakeHome, ".config/user-dirs.dirs").writeText("XDG_DOWNLOAD_DIR=\"${'$'}HOME/FromDotConfig\"")
            val configHome = File(dir, "sandbox-config").apply { mkdirs() }

            // Nothing in the sandbox's config home: the fallback answers.
            assertEquals(
                File(fakeHome, "FromDotConfig"),
                UserDirs.xdgDownloads(mapOf("XDG_CONFIG_HOME" to configHome.path), fakeHome),
            )

            File(configHome, "user-dirs.dirs").writeText("XDG_DOWNLOAD_DIR=\"${'$'}HOME/FromConfigHome\"")
            assertEquals(
                File(fakeHome, "FromConfigHome"),
                UserDirs.xdgDownloads(mapOf("XDG_CONFIG_HOME" to configHome.path), fakeHome),
            )

            // And nothing anywhere is null, which the caller turns into ~/Downloads.
            assertNull(UserDirs.xdgDownloads(emptyMap(), File(dir, "nobody")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
