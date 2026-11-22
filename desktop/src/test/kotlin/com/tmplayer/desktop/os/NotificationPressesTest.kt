package com.tmplayer.desktop.os

import com.tmplayer.data.AccountList
import com.tmplayer.data.Profile
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

        // One profile, no PIN: the file's own link, nothing in memory.
        assertEquals(WindowsToast.launchUri(file), launch(file, false, pressable = true, gated = false))
        assertTrue(registered.isEmpty())
        // Not started or no scheme: never a keyed link, and nothing is asked or opened.
        assertEquals(WindowsToast.launchUri(file), launch(file, false, pressable = false, gated = true))
        assertTrue(registered.isEmpty() && asked == 0 && opened == null)
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

    @Test
    fun `the Windows notifier answers a result before any start without throwing or pressing`() {
        var asked = 0
        val notifier = WindowsNotifications(window = { null }, gatePresses = { true })
        notifier.result(1, 1, "Done", "Episode", OpenTarget.File("/nope/Episode.mkv"), failed = false) { asked++; true }
        notifier.result(2, 2, "Failed", "Episode", null, failed = true) { asked++; true }
        notifier.flush()
        assertEquals(0, asked)
    }
}
