package com.tmplayer.desktop.os

import com.tmplayer.data.AccountList
import com.tmplayer.data.Profile
import com.tmplayer.platform.TransferNotifier
import com.tmplayer.platform.TransferNotifier.OpenTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HX88 lows: result presses and the ids kept for dismissing them, on any OS. */
class NotificationPressesTest {
    private val file = OpenTarget.File("/tmp/x/Episode.mkv")

    @Test
    fun `a gated press opens only while its gate says yes`() {
        assertEquals(file, PressEntry(file, { true }, gated = true).targetToOpen())
        assertNull(PressEntry(file, { false }, gated = true).targetToOpen())
        assertNull("a gate that throws refuses", PressEntry(file, { error("gone") }, gated = true).targetToOpen())
    }

    @Test
    fun `a gated press with no gate is refused, an ungated one opens`() {
        assertNull(PressEntry(file, null, gated = true).targetToOpen())
        assertEquals(file, PressEntry(file, null, gated = false).targetToOpen())
        assertNull("nothing to open", PressEntry(null, { true }, gated = true).targetToOpen())
    }

    @Test
    fun `a key used again keeps the earlier ids closable`() {
        val ids = KeyedServerIds()
        ids.add(7, 100)
        ids.add(7, 101)
        ids.add(8, 200)
        assertEquals(listOf(100L, 101L), ids.take(7))
        assertEquals(emptyList<Long>(), ids.take(7))
        assertEquals(listOf(200L), ids.take(8))
    }

    @Test
    fun `the ids kept are bounded by key and per key`() {
        val ids = KeyedServerIds(maxKeys = 3, maxPerKey = 2)
        for (key in 1L..5L) ids.add(key, key * 10)
        assertEquals(3, ids.size)
        assertEquals(emptyList<Long>(), ids.take(1))
        assertEquals(listOf(50L), ids.take(5))
        for (n in 1L..5L) ids.add(9, n)
        assertEquals("the oldest id of a key goes first", listOf(4L, 5L), ids.take(9))
        val big = KeyedServerIds()
        for (key in 0L until 5_000L) big.add(key, key)
        assertEquals(KeyedServerIds.MAX_KEYS, big.size)
    }

    private fun list(vararg profiles: Profile) = AccountList(profiles.toList(), profiles.first().id)

    @Test
    fun `presses are gated with two profiles or a PIN, not with one plain profile`() {
        val plain = Profile("a", null)
        assertFalse(PressGate.needed(list(plain)))
        assertTrue(PressGate.needed(list(plain, Profile("b", "pb"))))
        assertTrue(PressGate.needed(list(Profile("a", null, pin = "1:salt:hash"))))
        assertFalse("no list yet: a single profile", PressGate.needed(null))
    }

    @Test
    fun `a result's launch is the file link with one profile, the keyed link when gated`() {
        val registered = mutableListOf<() -> Unit>()
        val register: (() -> Unit) -> String = {
            registered += it
            "tmplayer:press/${registered.size}"
        }
        var asked = 0
        var opened: OpenTarget? = null
        fun launch(open: OpenTarget?, failed: Boolean, pressable: Boolean, gated: Boolean, allow: Boolean = true) =
            WindowsToast.resultLaunch(open, failed, pressable, gated, { asked++; allow }, { opened = it }, {}, register)

        // No scheme and no gate needed: the file's own link, nothing in memory.
        assertEquals(WindowsToast.launchUri(file), launch(file, false, pressable = false, gated = false))
        assertTrue(registered.isEmpty())
        // No scheme but a gate needed (2+ profiles or a PIN): no link at all, so no press.
        assertNull(launch(file, false, pressable = false, gated = true))
        assertTrue(registered.isEmpty() && asked == 0 && opened == null)
        // With the scheme the keyed link is used even when no gate is needed at the post: the gate
        // is asked at the press, so a profile added or a PIN set later cannot open a stale file.
        assertEquals("tmplayer:press/1", launch(file, false, pressable = true, gated = false))
        registered.clear()
        // Failures have no press.
        assertNull(launch(null, true, pressable = true, gated = true))
        // Gated: the keyed link, which asks first.
        assertEquals("tmplayer:press/1", launch(file, false, pressable = true, gated = true, allow = false))
        registered.single()()
        assertEquals(1, asked)
        assertNull("refused: nothing opens", opened)
        assertEquals("tmplayer:press/2", launch(file, false, pressable = true, gated = true))
        registered.last()()
        assertEquals(file, opened)
    }

    /** A fake toast host: records every script line, never starts a process. */
    private class FakeHost(pressable: Boolean) {
        val sent = java.io.StringWriter()
        val host = ToastHost(sent, { true }, {}, pressable)
        val lines: List<String> get() = sent.toString().split("\r\n").filter { it.isNotBlank() }
    }

    private fun notifier(host: FakeHost?, gated: Boolean, pressed: () -> Unit = {}) =
        WindowsNotifications(
            window = { null },
            gatePresses = { gated },
            launcher = { host?.host ?: error("no host") },
        ).also { it.result(1, 1, "Done", "Episode", file, failed = false) { pressed(); true } }

    @Test
    fun `the Windows notifier posts a result with the keyed link, the file link or none, through a fake host`() {
        var asked = 0
        val link = "activationType=\"protocol\" launch=\"tmplayer:"
        val fileLink = "launch=\"" + WindowsToast.xml(WindowsToast.launchUri(file)!!)

        val pressableHost = FakeHost(pressable = true)
        notifier(pressableHost, gated = false) { asked++ }.flush()
        val keyed = pressableHost.lines.single { "ToastNotification" in it && "Episode" in it }
        assertTrue(keyed, link in keyed)
        assertEquals("nothing is pressed by posting", 0, asked)

        val noSchemeUngated = FakeHost(pressable = false)
        notifier(noSchemeUngated, gated = false).flush()
        assertTrue(fileLink in noSchemeUngated.lines.single { "Episode" in it })

        val noSchemeGated = FakeHost(pressable = false)
        notifier(noSchemeGated, gated = true).flush()
        val unpressable = noSchemeGated.lines.single { "Episode" in it }
        assertFalse(unpressable, "launch=" in unpressable)
    }

    @Test
    fun `the Windows notifier survives a host that cannot start, and stays quiet`() {
        val notifier = WindowsNotifications(window = { null }, gatePresses = { true }, launcher = { error("no PowerShell") })
        notifier.result(1, 1, "Done", "Episode", file, failed = false) { true }
        notifier.result(2, 2, "Failed", "Episode", null, failed = true) { true }
        notifier.flush()
        assertFalse(TransferNotifier.Capability.ProgressBar in notifier.capabilities)
    }
}
