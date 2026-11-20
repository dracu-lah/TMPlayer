package com.tmplayer.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Switching between profiles over a fake TDLib client (HX88): one client at a time, the last switch wins. */
class AccountSwitcherTest {

    private val dir: File = Files.createTempDirectory("account-switch").toFile()
    private val base = TempPaths()
    private val host = FakeHost()
    private val book = testBook(dir)
    private val switcher = AccountSwitcher(book, host, base = { base }, closeTimeoutMs = 200L)

    /** Two more signed in profiles beside the adopted one, the adopted one open again. */
    private suspend fun three(): List<Profile> {
        val a = (switcher.add() as AddResult.Added).profile
        switcher.noteSignedIn(host.me(book, 21L, "A"))
        val b = (switcher.add() as AddResult.Added).profile
        switcher.noteSignedIn(host.me(book, 22L, "B"))
        switcher.switchTo(AccountRules.ADOPTED_ID)
        host.opened.clear()
        return listOf(a, b)
    }

    @Test
    fun `two fast switches open one client at a time, and the last asked for is the one open`() = runBlocking {
        val (a, b) = three()
        host.closeMs = 100L
        val first = async { switcher.switchTo(a.id) }
        delay(10)
        val second = async { switcher.switchTo(b.id) }
        val third = async { switcher.switchTo(AccountRules.ADOPTED_ID) }
        val results = listOf(first.await(), second.await(), third.await())
        assertEquals(0, host.overlaps)
        assertEquals(SwitchResult.Switched, results[0])
        assertEquals("waited behind the first and was overtaken", SwitchResult.Superseded, results[1])
        assertEquals(SwitchResult.Switched, results[2])
        assertEquals(AccountRules.ADOPTED_ID, book.list.value.activeId)
        assertEquals("tdlib", host.openOn)
        assertEquals(listOf("tdlib-${a.folder}", "tdlib"), host.opened.toList())
        assertNull(book.list.value.switchingTo)
        assertFalse("the one in between never opened", host.opened.contains("tdlib-${b.folder}"))
    }

    @Test
    fun `a slow close is waited out with a spinner, never a second client`() = runBlocking {
        val (a, _) = three()
        host.closeMs = 600L
        val switching = async { switcher.switchTo(a.id) }
        withTimeout(2_000) { switcher.state.first { it is SwitchState.ClosingSlowly } }
        assertEquals("still the old client", "tdlib", host.openOn)
        assertEquals("only where it goes is recorded", a.id, book.list.value.switchingTo)
        assertEquals("the open profile moves only once the old client has closed", AccountRules.ADOPTED_ID, book.list.value.activeId)
        assertEquals(SwitchResult.Switched, switching.await())
        assertEquals("tdlib-${a.folder}", host.openOn)
        assertEquals(0, host.overlaps)
        assertEquals(SwitchState.Idle, switcher.state.value)
        assertNull(book.list.value.switchingTo)
    }

    @Test
    fun `killed in the middle of a switch, the next start reopens the profile it left`() = runBlocking {
        val (a, _) = three()
        host.closeMs = 1_000L
        val switching = async { switcher.switchTo(a.id) }
        withTimeout(2_000) { switcher.state.first { it is SwitchState.ClosingSlowly } }
        switching.cancel()
        // The process dies here: what is on the disk is all the next start has.
        val restarted = testBook(dir).list.value
        assertEquals(AccountRules.ADOPTED_ID, restarted.activeId)
        assertNull(restarted.switchingTo)
        assertTrue("a signed in profile is kept", restarted.byId(a.id) != null)
    }

    @Test
    fun `switching to the open profile does nothing`() = runBlocking {
        assertEquals(SwitchResult.Already, switcher.switchTo(AccountRules.ADOPTED_ID))
        assertTrue(host.opened.isEmpty())
        assertEquals(SwitchResult.Unknown, switcher.switchTo("nobody"))
    }

    @Test
    fun `signing out of one profile wipes only it and opens another, the last one goes to the sign in`() = runBlocking {
        val a = (switcher.add() as AddResult.Added).profile
        switcher.noteSignedIn(host.me(book, 11L, "A"))
        switcher.switchTo(AccountRules.ADOPTED_ID)
        switcher.noteSignedIn(host.me(book, 10L, "Main"))
        val wiped = mutableListOf<Pair<String, Boolean>>()
        val out = switcher.signOutProfile(a.id) { profile, last -> wiped += profile.id to last }
        assertEquals(listOf(a.id to false), wiped)
        assertEquals("opened to be signed out, while its stores were the open ones", 1, host.logOuts)
        assertEquals(AccountRules.ADOPTED_ID, (out as SignOutResult.SignedOut).next?.id)
        assertNull(book.list.value.byId(a.id))
        assertFalse(AccountDirs.pathsFor(base, a).databaseDir.exists())

        val last = switcher.signOutProfile(AccountRules.ADOPTED_ID) { profile, isLast -> wiped += profile.id to isLast }
        assertEquals(SignOutResult.SignedOut(null), last)
        assertEquals(AccountRules.ADOPTED_ID to true, wiped.last())
        assertEquals("the record stays, empty, on the normal sign in", 0L, book.list.value.active?.telegramId)
        assertEquals(1, book.list.value.profiles.size)
    }

    @Test
    fun `remove all accounts ends with one empty profile on the platform's own folders`() = runBlocking {
        (switcher.add() as AddResult.Added).profile.also { switcher.noteSignedIn(host.me(book, 1L, "One")) }
        (switcher.add() as AddResult.Added).profile.also { switcher.noteSignedIn(host.me(book, 2L, "Two")) }
        val wiped = mutableListOf<String>()
        switcher.removeAll { profile, _ -> wiped += profile.id }
        assertEquals(3, wiped.size)
        val list = book.list.value
        assertEquals(listOf(AccountRules.ADOPTED_ID), list.profiles.map { it.id })
        assertNull(list.active?.folder)
        assertEquals("tdlib", host.openOn)
        assertTrue(list.trash.isEmpty())
        assertFalse(base.root.listFiles().orEmpty().any { it.name.startsWith("tdlib-") })
    }

    @Test
    fun `the sleeping profiles' caches are measured for the shared budget`() = runBlocking {
        var sleeping = -1L
        val measured = AccountSwitcher(book, host, base = { base }, onSleepingBytes = { sleeping = it })
        base.filesDir.mkdirs()
        File(base.filesDir, "video.bin").writeBytes(ByteArray(4_096))
        measured.add()
        assertEquals(4_096L, sleeping)
    }
}
