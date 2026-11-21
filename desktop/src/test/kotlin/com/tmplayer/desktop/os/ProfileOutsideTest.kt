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
        val guarded = ProfileGuardedNotifier(inner, hidden = { true })
        guarded.notice("A new version", "2.0 is out", null)
        ProfileOutside.profileNotice(guarded, a, "New in Harbour", "3 videos", null, hidden = true, current = a)
        assertEquals(listOf("notice A new version|2.0 is out"), inner.calls)
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
}
