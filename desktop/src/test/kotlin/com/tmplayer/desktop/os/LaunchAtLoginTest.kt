package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** WP-52: Launch at login. Each OS's place is a fake or a temp folder, so every OS runs the same checks. */
class LaunchAtLoginTest {

    private class FakeBackend(var entry: String? = null, var failWrite: Boolean = false, var failRemove: Boolean = false) :
        LaunchAtLogin.LoginBackend {
        override fun read() = entry
        override fun write(command: String) {
            if (failWrite) error("denied")
            entry = command
        }
        override fun remove() {
            if (failRemove) error("denied")
            entry = null
        }
    }

    private val self = if (OsInfo.isWindows) "C:\\Apps\\TMPlayer\\TMPlayer.exe" else "/opt/tmplayer/bin/tmplayer"

    @Test
    fun `off by default and the state is what the OS holds`() {
        val backend = FakeBackend()
        val login = LaunchAtLogin(backend) { self }
        assertEquals(LaunchAtLogin.State.Off, login.state())
        backend.entry = self
        assertEquals(LaunchAtLogin.State.On, login.state())
        // Removed in Task Manager or Startup Applications: the switch follows.
        backend.entry = null
        assertEquals(LaunchAtLogin.State.Off, login.state())
    }

    @Test
    fun `an entry of another copy is said so and turning on takes it over`() {
        val backend = FakeBackend(entry = "/old/place/tmplayer")
        val login = LaunchAtLogin(backend) { self }
        assertEquals(LaunchAtLogin.State.OtherCopy, login.state())
        val result = login.set(true)
        assertTrue(result.ok)
        assertEquals(LaunchAtLogin.State.On, result.state)
        assertEquals(self, backend.entry)
    }

    @Test
    fun `a write that fails reads back the truth and says it did not work`() {
        val backend = FakeBackend(failWrite = true)
        val login = LaunchAtLogin(backend) { self }
        val on = login.set(true)
        assertFalse(on.ok)
        assertEquals(LaunchAtLogin.State.Off, on.state)

        backend.entry = self
        backend.failRemove = true
        val off = login.set(false)
        assertFalse(off.ok)
        assertEquals(LaunchAtLogin.State.On, off.state)
    }

    @Test
    fun `a backend that throws on read counts as off`() {
        val login = LaunchAtLogin(object : LaunchAtLogin.LoginBackend {
            override fun read(): String? = error("registry gone")
            override fun write(command: String) = Unit
            override fun remove() = Unit
        }) { self }
        assertEquals(LaunchAtLogin.State.Off, login.state())
    }

    @Test
    fun `a run that cannot register itself offers nothing and writes nothing`() {
        val backend = FakeBackend()
        val login = LaunchAtLogin(backend) { null }
        assertEquals(LaunchAtLogin.State.Unavailable, login.state())
        assertFalse(login.set(true).ok)
        assertNull(backend.entry)
    }

    @Test
    fun `a development run, a Flatpak and a Snap are not registered`() {
        assertNull(LaunchAtLogin.selfCommand(emptyMap()) { null })
        val file = Files.createTempFile("tmplayer-self", "").toFile()
        try {
            assertEquals(file.path, LaunchAtLogin.selfCommand(emptyMap()) { if (it == "jpackage.app-path") file.path else null })
            assertNull(LaunchAtLogin.selfCommand(mapOf("FLATPAK_ID" to "x")) { file.path })
            assertNull(LaunchAtLogin.selfCommand(mapOf("SNAP" to "/snap/x")) { file.path })
            // An AppImage is started by its own file.
            assertEquals(file.path, LaunchAtLogin.selfCommand(mapOf("APPIMAGE" to file.path)) { "/tmp/.mount/x" })
            // A path that is not there is not offered.
            assertNull(LaunchAtLogin.selfCommand(emptyMap()) { "/nowhere/tmplayer" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the Windows Run key holds a quoted command and reads it back unquoted`() {
        val values = mutableMapOf<String, String>()
        val key = object : RunKeyApi {
            override fun get(name: String) = values[name]
            override fun set(name: String, value: String) { values[name] = value }
            override fun delete(name: String) { values.remove(name) }
        }
        val backend = WindowsRunKey(key)
        backend.write("C:\\Program Files\\TMPlayer\\TMPlayer.exe")
        assertEquals("\"C:\\Program Files\\TMPlayer\\TMPlayer.exe\"", values["TMPlayer"])
        assertEquals("C:\\Program Files\\TMPlayer\\TMPlayer.exe", backend.read())
        // Another program's own entries are left alone.
        values["Other"] = "x"
        backend.remove()
        assertNull(backend.read())
        assertEquals("x", values["Other"])
        assertEquals("C:\\a\\b.exe", WindowsRunKey.unquote("C:\\a\\b.exe --flag"))
        assertEquals("", WindowsRunKey.unquote(""))
    }

    @Test
    fun `the Linux autostart entry is a desktop file, honours a desktop that disabled it, and survives odd paths`() {
        val dir = Files.createTempDirectory("tm-autostart").toFile().resolve("autostart")
        try {
            val backend = LinuxAutostart(dir)
            assertNull(backend.read())
            val odd = "/opt/my apps/tm \"player\" \$HOME %u/bin/tmplayer"
            backend.write(odd)
            val text = dir.resolve(LinuxAutostart.ENTRY).readText()
            assertTrue(text.startsWith("[Desktop Entry]"))
            assertTrue(text.contains("X-GNOME-Autostart-enabled=true"))
            assertEquals(odd, backend.read())
            // GNOME's Startup Applications switch writes a flag rather than deleting the file.
            dir.resolve(LinuxAutostart.ENTRY).writeText(text.replace("enabled=true", "enabled=false"))
            assertNull(backend.read())
            dir.resolve(LinuxAutostart.ENTRY).writeText(text + "Hidden=true\n")
            assertNull(backend.read())
            backend.remove()
            assertFalse(dir.resolve(LinuxAutostart.ENTRY).exists())
            // Removing what is not there is not an error.
            backend.remove()
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `the Mac launch agent is a plist with RunAtLoad, and an odd path is escaped`() {
        val dir = Files.createTempDirectory("tm-agents").toFile().resolve("LaunchAgents")
        try {
            val backend = MacLaunchAgent(dir)
            assertNull(backend.read())
            val path = "/Applications/TM & Player.app/Contents/MacOS/TMPlayer"
            backend.write(path)
            val text = dir.resolve("${MacLaunchAgent.LABEL}.plist").readText()
            assertTrue(text.contains("<key>RunAtLoad</key>"))
            assertTrue(text.contains("TM &amp; Player"))
            assertEquals(path, backend.read())
            // No half written file is left behind.
            assertEquals(listOf("${MacLaunchAgent.LABEL}.plist"), dir.list()?.toList())
            backend.remove()
            assertNull(backend.read())
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `a garbage file in the place reads as no entry`() {
        val dir = Files.createTempDirectory("tm-garbage").toFile()
        try {
            dir.resolve(LinuxAutostart.ENTRY).writeBytes(ByteArray(2000) { (it * 31).toByte() })
            assertNull(LinuxAutostart(dir).read())
            dir.resolve("${MacLaunchAgent.LABEL}.plist").writeBytes(ByteArray(2000) { (it * 17).toByte() })
            assertNull(MacLaunchAgent(dir).read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
