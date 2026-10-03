package com.tmplayer.desktop.os

import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.types.UInt32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises MPRIS and the sleep inhibit against the real session bus with the desktop's own tools
 * (`busctl`, `playerctl`, `gdbus`, `systemd-inhibit`). Skipped unless `TMPLAYER_LIVE_DBUS=1`, since
 * CI runners have no session bus:
 *
 * ```
 * TMPLAYER_LIVE_DBUS=1 ./gradlew :desktop:test --tests '*LinuxSessionBusLiveTest*' -i
 * ```
 */
class LinuxSessionBusLiveTest {

    private fun live() {
        assumeTrue(OsInfo.isLinux && System.getenv("TMPLAYER_LIVE_DBUS") == "1")
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        println("$ ${cmd.joinToString(" ")}\n${out.trimEnd()}")
        return out
    }

    @Test
    fun mprisAnswersBusctlPlayerctlAndGdbus() {
        live()
        val events = LinkedBlockingQueue<String>()
        val callbacks = object : MediaSessionCallbacks {
            override fun onPlay() { events.put("play") }
            override fun onPause() { events.put("pause") }
            override fun onPlayPause() { events.put("playpause") }
            override fun onStop() { events.put("stop") }
            override fun onNext() { events.put("next") }
            override fun onPrevious() { events.put("previous") }
            override fun onSeekBy(offsetMs: Long) { events.put("seekBy $offsetMs") }
            override fun onSeekTo(positionMs: Long) { events.put("seekTo $positionMs") }
            override fun onRaise() { events.put("raise") }
        }
        val session = MprisMediaSession.start(callbacks)
        val monitor = ProcessBuilder("gdbus", "monitor", "--session", "--dest", session.busName)
            .redirectErrorStream(true).start()
        val captured = StringBuffer()
        val reader = Thread { runCatching { monitor.inputStream.bufferedReader().forEachLine { captured.appendLine(it) } } }
            .apply { isDaemon = true; start() }
        Thread.sleep(500) // let the monitor subscribe before anything is sent
        try {
            assertEquals(MprisMapping.BUS_NAME, session.busName)
            session.update("Live test episode", durationMs = 1_440_000, positionMs = 60_000, playing = true, canGoNext = true)

            val intro = run("busctl", "--user", "introspect", session.busName, MprisMapping.OBJECT_PATH)
            assertTrue(intro.contains("org.mpris.MediaPlayer2.Player"))
            assertTrue(intro.contains(".PlayPause"))
            assertTrue(intro.contains(".PlaybackStatus"))
            assertTrue(intro.contains(".Seeked"))

            assertTrue(
                run("busctl", "--user", "get-property", session.busName, MprisMapping.OBJECT_PATH, MprisMapping.PLAYER, "PlaybackStatus")
                    .contains("\"Playing\""),
            )
            val meta = run("playerctl", "-p", "tmplayer", "metadata", "--format", "{{title}}|{{mpris:length}}|{{status}}")
            assertEquals("Live test episode|1440000000|Playing", meta.trim())
            val position = run("playerctl", "-p", "tmplayer", "position").trim().toDouble()
            assertTrue("position $position", position in 59.0..70.0)

            run("playerctl", "-p", "tmplayer", "play-pause")
            assertEquals("playpause", events.poll(5, TimeUnit.SECONDS))
            run("playerctl", "-p", "tmplayer", "next")
            assertEquals("next", events.poll(5, TimeUnit.SECONDS))
            run("playerctl", "-p", "tmplayer", "position", "10+")
            assertEquals("seekBy 10000", events.poll(5, TimeUnit.SECONDS))
            run("playerctl", "-p", "tmplayer", "position", "300")
            assertEquals("seekTo 300000", events.poll(5, TimeUnit.SECONDS))
            run("busctl", "--user", "call", session.busName, MprisMapping.OBJECT_PATH, MprisMapping.ROOT, "Raise")
            assertEquals("raise", events.poll(5, TimeUnit.SECONDS))

            // Signals: a pause (PropertiesChanged) and a jump (Seeked).
            session.update("Live test episode", 1_440_000, 61_000, playing = false, canGoNext = true)
            session.update("Live test episode", 1_440_000, 300_000, playing = false, canGoNext = true)
            assertEquals("Paused", run("playerctl", "-p", "tmplayer", "status").trim())
            Thread.sleep(500)
        } finally {
            session.release()
            monitor.destroy()
        }
        reader.join(2_000)
        val signals = captured.toString()
        println("gdbus monitor:\n$signals")
        assertTrue(signals.contains("org.freedesktop.DBus.Properties.PropertiesChanged ('org.mpris.MediaPlayer2.Player', {'PlaybackStatus': <'Paused'>}"))
        assertTrue(signals.contains("org.mpris.MediaPlayer2.Player.Seeked (int64 300000000,)"))
        assertTrue(!run("busctl", "--user", "list", "--no-pager").contains(MprisMapping.BUS_NAME))
    }

    @Test
    fun keepAwakeHoldsWhateverThisDesktopOffers() {
        live()
        val bus = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        val dbus = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        val desktopHasOne = listOf(LinuxKeepAwake.SCREENSAVER, LinuxKeepAwake.POWER, LinuxKeepAwake.GNOME).any { dbus.NameHasOwner(it) }

        val keep = LinuxKeepAwake({ null })
        keep.acquire("live test")
        keep.flush()
        println("held via ${keep.heldVia}")
        assertTrue(keep.isApplied)
        if (keep.heldVia == "systemd-inhibit") {
            assertTrue(run("systemd-inhibit", "--list", "--no-pager").contains("TMPlayer"))
        }
        if (keep.heldVia == LinuxKeepAwake.GNOME) {
            assertTrue(run("busctl", "--user", "call", "org.gnome.SessionManager", "/org/gnome/SessionManager", "org.gnome.SessionManager", "IsInhibited", "u", "8").contains("true"))
        }
        keep.release()
        keep.flush()
        if (!desktopHasOne) {
            Thread.sleep(300)
            assertTrue(!run("systemd-inhibit", "--list", "--no-pager").contains("TMPlayer"))
            // Neither systemd-inhibit nor its tail child is left running.
            assertEquals(0L, ProcessHandle.current().descendants().filter { it.isAlive }.count())
        }
        keep.close()

        // On a desktop without org.freedesktop.ScreenSaver (sway here), stand one up and check the
        // D-Bus path end to end: Inhibit on acquire, UnInhibit with the same cookie on release.
        if (!dbus.NameHasOwner(LinuxKeepAwake.SCREENSAVER)) {
            val inhibits = AtomicInteger()
            val released = LinkedBlockingQueue<Long>()
            val fake = object : FdoScreenSaver {
                override fun getObjectPath() = "/org/freedesktop/ScreenSaver"
                override fun Inhibit(applicationName: String, reasonForInhibit: String): UInt32 {
                    println("fake ScreenSaver: Inhibit($applicationName, $reasonForInhibit)")
                    return UInt32(4242L + inhibits.incrementAndGet())
                }
                override fun UnInhibit(cookie: UInt32) {
                    println("fake ScreenSaver: UnInhibit($cookie)")
                    released.put(cookie.toLong())
                }
            }
            bus.exportObject("/org/freedesktop/ScreenSaver", fake)
            bus.requestBusName(LinuxKeepAwake.SCREENSAVER)
            try {
                val viaBus = LinuxKeepAwake({ null })
                viaBus.acquire("live test")
                viaBus.acquire("again, ignored")
                viaBus.flush()
                assertEquals(LinuxKeepAwake.SCREENSAVER, viaBus.heldVia)
                assertEquals(1, inhibits.get())
                viaBus.release()
                viaBus.flush()
                assertEquals(4243L, released.poll(5, TimeUnit.SECONDS))
                viaBus.close()
            } finally {
                bus.releaseBusName(LinuxKeepAwake.SCREENSAVER)
            }
        }
        bus.close()
    }
}
