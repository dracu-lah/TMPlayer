package com.tmplayer.desktop.os

import com.tmplayer.platform.TransferNotifier
import com.tmplayer.data.AccountStamp
import com.tmplayer.platform.TransferNotifier.Kind

import com.tmplayer.platform.TransferNotifier.OpenTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the desktop shows outside its window while a PIN profile is hidden (HX88, invariant 6):
 * the system's notifications and media controls carry the app's name only, the profile's alerts
 * are not posted, the device's notices are unchanged, and each transfer is drawn again with its
 * real title once the profile shows.
 */
class ProfileOutsideTest {

    /** Every call the system's notifier is given, in order. */
    private class Recording : TransferNotifier, DesktopNotices, DockProgress {
        val calls = mutableListOf<String>()
        override val capabilities: Set<TransferNotifier.Capability> = emptySet()
        override fun begin(id: Long, kind: Kind, title: String) { calls += "begin $id $title" }
        override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) { calls += "progress $id $done" }
        override fun complete(id: Long, title: String, body: String, open: OpenTarget?) { calls += "complete $id $title|$body|$open" }
        override fun fail(id: Long, title: String, reason: String, retryable: Boolean) { calls += "fail $id $title|$reason" }
        override fun cancel(id: Long) { calls += "cancel $id" }
        override fun notice(title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>) { calls += "notice $title|$body" }
        override fun dockProgress(fraction: Float?) { calls += "dock $fraction" }
    }

    private val app = ProfileOutside.APP_NAME
    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")

    @Test
    fun `the media controls name the video only while the profile shows`() {
        assertEquals("Harbour Notes", ProfileOutside.mediaTitle("Harbour Notes", hidden = false))
        assertEquals(app, ProfileOutside.mediaTitle("Harbour Notes", hidden = true))
    }

    @Test
    fun `a transfer begun while hidden is neutral and drawn again for real once shown`() {
        var hidden = true
        val inner = Recording()
        val guarded = ProfileGuardedNotifier(inner, hidden = { hidden })
        guarded.begin(7, Kind.Download, "Harbour Notes")
        guarded.progress(7, 500, 1000, null)
        assertEquals(listOf("begin 7 $app", "progress 7 500"), inner.calls)
        hidden = false
        guarded.refresh()
        assertEquals(listOf("begin 7 Harbour Notes", "progress 7 500"), inner.calls.drop(2))
        // Hidden again (the window minimised): neutral again, the title gone from the system.
        hidden = true
        guarded.refresh()
        assertEquals("begin 7 $app", inner.calls[4])
        assertTrue(inner.calls.none { it.contains("Harbour") && inner.calls.indexOf(it) > 3 })
    }

    @Test
    fun `results while hidden carry the app's name and open nothing of the profile`() {
        val inner = Recording()
        val guarded = ProfileGuardedNotifier(inner, hidden = { true })
        guarded.complete(1, "Downloaded", "Harbour Notes is in Downloads", OpenTarget.File("/d/Harbour.mkv"))
        guarded.fail(2, "Harbour Notes did not download", "No space", retryable = true)
        assertEquals(listOf("complete 1 $app||${OpenTarget.DownloadsScreen}", "fail 2 $app|"), inner.calls)
        assertTrue(inner.calls.none { "Harbour" in it || "/d/" in it })
    }

    @Test
    fun `results while shown are as before, and a refresh of nothing posts nothing`() {
        val inner = Recording()
        val guarded = ProfileGuardedNotifier(inner, hidden = { false })
        guarded.complete(1, "Downloaded", "Harbour Notes is in Downloads", OpenTarget.File("/d/Harbour.mkv"))
        guarded.refresh()
        assertEquals(listOf("complete 1 Downloaded|Harbour Notes is in Downloads|${OpenTarget.File("/d/Harbour.mkv")}"), inner.calls)
    }

    @Test
    fun `the device's notices are unchanged and a profile's alert waits while hidden`() {
        val inner = Recording()
        var hidden = true
        val guarded = ProfileGuardedNotifier(inner, hidden = { hidden }, current = { a })
        guarded.notice("A new version", "2.0 is out", null)
        ProfileOutside.profileNotice(guarded, a, "New in Harbour", "3 videos", null, hidden = true, current = a)
        assertEquals(listOf("notice A new version|2.0 is out"), inner.calls)
        hidden = false
        ProfileOutside.profileNotice(guarded, a, "New in Harbour", "3 videos", null, hidden = false, current = a)
        assertEquals("notice New in Harbour|3 videos", inner.calls.last())
    }

    @Test
    fun `an alert made for A and posted after a switch to B is dropped`() {
        val inner = Recording()
        // Built while A was open, posted after the wait for its client, with B open by then.
        ProfileOutside.profileNotice(inner, a, "New in Harbour", "3 videos", null, hidden = false, current = b)
        assertEquals(emptyList<String>(), inner.calls)
    }

    @Test
    fun `a transfer begun under A with a PIN and done after a switch to B posts nothing of A`() {
        var open = a
        var hidden = false
        val inner = Recording()
        val guarded = ProfileGuardedNotifier(inner, hidden = { hidden }, current = { open })
        // A is open and unlocked: its real title, as before.
        guarded.begin(1, Kind.Download, "Harbour Notes")
        guarded.begin(2, Kind.MoveToDownloads, "Harbour Notes 2")
        // A locks as the switch begins, then B (no PIN, shown) is the open profile.
        hidden = true
        open = b
        hidden = false
        guarded.progress(1, 10, 100, null)
        guarded.refresh()
        guarded.complete(1, "Downloaded", "Harbour Notes is in Downloads", OpenTarget.File("/d/Harbour.mkv"))
        guarded.fail(2, "Harbour Notes 2 did not move", "No space", retryable = false)
        val after = inner.calls.drop(2)
        assertTrue("nothing of A after the switch: $after", after.none { "Harbour" in it || "/d/" in it })
        assertTrue("A's progress is not forwarded for B: $after", after.none { it.startsWith("progress 1") })
    }

    @Test
    fun `after a sign out or switch took them down, a late update of A's transfers posts nothing`() {
        var open = a
        val inner = Recording()
        val guarded = ProfileGuardedNotifier(inner, hidden = { false }, current = { open })
        guarded.begin(1, Kind.Download, "Harbour Notes")
        guarded.clearProfile()
        open = b
        guarded.progress(1, 10, 100, null)
        guarded.complete(1, "Downloaded", "Harbour Notes is in Downloads", null)
        guarded.fail(1, "Harbour Notes did not download", "", retryable = true)
        assertEquals(listOf("begin 1 Harbour Notes", "cancel 1"), inner.calls)
    }

    @Test
    fun `the media controls show A's title only while A is the open profile`() {
        assertEquals("Harbour Notes", ProfileOutside.mediaTitle("Harbour Notes", hidden = false, owner = a, current = a))
        assertEquals(app, ProfileOutside.mediaTitle("Harbour Notes", hidden = false, owner = a, current = b))
    }

    @Test
    fun `a switch or a sign out takes the profile's transfers off the system's list`() {
        val inner = Recording()
        val guarded = ProfileGuardedNotifier(inner, hidden = { false })
        guarded.begin(1, Kind.Download, "One")
        guarded.begin(2, Kind.MoveToDownloads, "Two")
        guarded.cancel(2)
        guarded.clearProfile()
        assertEquals(listOf("begin 1 One", "begin 2 Two", "cancel 2", "cancel 1"), inner.calls)
        // Nothing left to draw again.
        guarded.refresh()
        assertEquals(4, inner.calls.size)
    }

    @Test
    fun `with no PIN profile open nothing is hidden`() {
        // The process's accounts are never opened in the desktop's tests: one profile, no PIN.
        assertEquals(false, ProfileOutside.hiddenNow())
    }

    /** A platform that can take down what it posted, and keeps the press checks it was handed. */
    private class Keyed : TransferNotifier, DesktopNotices, KeyedResults {
        val calls = mutableListOf<String>()
        val gates = mutableMapOf<Long, () -> Boolean>()
        val presses = mutableMapOf<Long, (() -> Unit)?>()
        override val capabilities: Set<TransferNotifier.Capability> = emptySet()
        override fun begin(id: Long, kind: Kind, title: String) { calls += "begin $id $title" }
        override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) { calls += "progress $id $done" }
        override fun complete(id: Long, title: String, body: String, open: OpenTarget?) { calls += "complete $id $title" }
        override fun fail(id: Long, title: String, reason: String, retryable: Boolean) { calls += "fail $id $title" }
        override fun cancel(id: Long) { calls += "cancel $id" }
        override fun notice(title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>) { calls += "notice $title" }
        override fun result(key: Long, id: Long, title: String, body: String, open: OpenTarget?, failed: Boolean, mayOpen: () -> Boolean) {
            calls += "result $key $title"
            gates[key] = mayOpen
        }
        override fun keyedNotice(key: Long, title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>) {
            calls += "alert $title"
            presses[key] = onPress
        }
        override fun dismiss(key: Long) { calls += "dismiss $key" }
    }

    @Test
    fun `a result and an alert come down with their profile on a switch, and a stale press opens nothing`() {
        var open = a
        val inner = Keyed()
        val guarded = ProfileGuardedNotifier(inner, hidden = { false }, current = { open })
        guarded.begin(1, Kind.Download, "Harbour Notes")
        guarded.complete(1, "Downloaded", "Harbour Notes is in Downloads", OpenTarget.File("/d/Harbour.mkv"))
        var pressed = 0
        ProfileOutside.profileNotice(guarded, a, "New in Harbour", "3 videos", { pressed++ }, hidden = false, current = a)
        assertEquals(listOf("begin 1 Harbour Notes", "result 1 Downloaded", "alert New in Harbour"), inner.calls)
        assertTrue("A open: its result opens", inner.gates.getValue(1)())

        open = b
        // A press on the stale ones before they come down: nothing of A opens.
        assertEquals(false, inner.gates.getValue(1)())
        inner.presses.values.single()!!.invoke()
        assertEquals(0, pressed)
        guarded.clearProfile()
        assertEquals(2, inner.calls.count { it.startsWith("dismiss") })
        assertTrue(inner.calls.contains("dismiss 1"))
        // Taken down once: nothing more to take down.
        guarded.clearProfile()
        assertEquals(2, inner.calls.count { it.startsWith("dismiss") })
    }

    @Test
    fun `a PIN profile's results leave the system's list when it hides, a shown one's stay`() {
        var hidden = false
        val inner = Keyed()
        val guarded = ProfileGuardedNotifier(inner, hidden = { hidden }, current = { a })
        guarded.complete(5, "Downloaded", "Harbour Notes is in Downloads", OpenTarget.File("/d/Harbour.mkv"))
        guarded.refresh()
        assertTrue("shown: it stays", inner.calls.none { it.startsWith("dismiss") })
        hidden = true
        guarded.refresh()
        assertEquals("dismiss 5", inner.calls.last())
        assertEquals(false, inner.gates.getValue(5)())
    }

    @Test
    fun `a transfer begun for A while B is open is A's, and so is a result never begun`() {
        val inner = Recording()
        val guarded = ProfileGuardedNotifier(inner, hidden = { false }, current = { b })
        ProfileOutside.asOwner(a) { guarded.begin(7, Kind.Download, "Harbour Notes") }
        ProfileOutside.asOwner(a) { guarded.fail(9, "Harbour Notes 2 did not download", "No space", retryable = true) }
        assertEquals(listOf("begin 7 $app", "fail 9 $app|"), inner.calls)
        // Outside asOwner: the open profile's, as before.
        guarded.begin(8, Kind.Download, "Lighthouse")
        assertEquals("begin 8 Lighthouse", inner.calls.last())
    }

    @Test
    fun `a Windows result is tagged so it can be taken off the Action Center`() {
        val line = WindowsToast.showDone("<toast/>", tag = "r5")
        assertTrue(line, line.contains(".Tag = ") && line.contains("r5"))
        assertTrue(WindowsToast.remove("r5").contains("r5"))
        // Without a tag, as before.
        assertTrue(!WindowsToast.showDone("<toast/>").contains(".Tag"))
    }
}
