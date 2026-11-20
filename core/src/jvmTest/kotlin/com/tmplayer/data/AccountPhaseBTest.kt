package com.tmplayer.data

import com.tmplayer.platform.Paths
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The core half of HX88's phase B: the picker's model, a profile's own name, and the phase A
 * review's LOW items 1, 3, 4 and 5 (the first sweep after the stores, a failed reopen that keeps
 * the profile, the next client pointed back, Remove all accounts that says a wipe was cut short).
 */
class AccountPhaseBTest {

    private val dir: File = Files.createTempDirectory("account-phase-b").toFile()
    private val base = TempPaths()
    private val fake = FakeHost()

    /** [fake], with its reopen made to throw while [failReopen] is on, as a TDLib failing part way would. */
    private val host = object : ClientHost by fake {
        @Volatile
        var failReopen = false

        override suspend fun reopen(paths: Paths, timeoutMs: Long, onClosed: () -> Unit): Boolean {
            if (failReopen) throw IllegalStateException("TDLib could not be closed")
            return fake.reopen(paths, timeoutMs, onClosed)
        }
    }
    private val book = testBook(dir)
    private val switcher = AccountSwitcher(book, host, base = { base }, closeTimeoutMs = 500L)

    @After
    fun tearDown() {
        Accounts.resetForTest()
    }

    private suspend fun signedIn(id: Long, name: String): Profile {
        val p = (switcher.add() as AddResult.Added).profile
        switcher.noteSignedIn(fake.me(book, id, name))
        return book.list.value.byId(p.id)!!
    }

    // ---- the picker's model ------------------------------------------------------------------

    @Test
    fun `one profile never sees the picker, two do`() = runBlocking {
        assertFalse(ProfilePicker.atLaunch(book.list.value))
        signedIn(21L, "A")
        assertTrue(ProfilePicker.atLaunch(book.list.value))
    }

    @Test
    fun `the tiles are the profiles in order with Add until the limit, which the line then says`() = runBlocking {
        val a = signedIn(21L, "Asha")
        var model = ProfilePicker.model(book.list.value, switcher::stateOf, activeNeedsSignIn = false)
        assertEquals(listOf(AccountRules.ADOPTED_ID, a.id), model.tiles.map { it.id })
        assertEquals(listOf(1, 2), model.tiles.map { it.number })
        assertEquals("Asha", model.tiles[1].name)
        assertTrue(model.tiles[1].active)
        assertTrue(model.canAdd)
        assertFalse(model.atLimit)
        signedIn(22L, "Ben")
        model = ProfilePicker.model(book.list.value, switcher::stateOf, activeNeedsSignIn = false)
        assertEquals(3, model.tiles.size)
        assertFalse("no Add tile at the limit of three", model.canAdd)
        assertTrue(model.atLimit)
        assertEquals(AddResult.Full, switcher.add())
    }

    @Test
    fun `a PIN puts the lock on the tile, a broken folder says needs attention and asks no PIN`() = runBlocking {
        val a = signedIn(21L, "A")
        switcher.switchTo(AccountRules.ADOPTED_ID)
        assertTrue(switcher.setPin(a.id, "1234"))
        var tile = ProfilePicker.model(book.list.value, switcher::stateOf, false).tiles.first { it.id == a.id }
        assertTrue(tile.locked)
        assertEquals(TileStatus.Ready, tile.status)
        assertTrue(ProfilePicker.needsPin(tile))
        // Its database folder is deleted behind the app's back.
        AccountDirs.pathsFor(base, a).databaseDir.deleteRecursively()
        tile = ProfilePicker.model(book.list.value, switcher::stateOf, false).tiles.first { it.id == a.id }
        assertEquals(TileStatus.NeedsAttention, tile.status)
        assertFalse(ProfilePicker.needsPin(tile))
        assertTrue(switcher.switchTo(a.id) is SwitchResult.Recoverable)
    }

    @Test
    fun `an ended login marks only the open profile, and a profile being added says so`() = runBlocking {
        val a = signedIn(21L, "A")
        switcher.switchTo(AccountRules.ADOPTED_ID)
        val ended = ProfilePicker.model(book.list.value, switcher::stateOf, activeNeedsSignIn = true)
        assertEquals(TileStatus.SignInAgain, ended.tiles.first { it.id == AccountRules.ADOPTED_ID }.status)
        assertEquals("the other profile is unaffected", TileStatus.Ready, ended.tiles.first { it.id == a.id }.status)
        val adding = (switcher.add() as AddResult.Added).profile
        val model = ProfilePicker.model(book.list.value, switcher::stateOf, activeNeedsSignIn = true)
        assertEquals(TileStatus.Adding, model.tiles.first { it.id == adding.id }.status)
    }

    @Test
    fun `a stateOf that throws reads as ready, never a crash`() {
        val model = ProfilePicker.model(book.list.value, { error("disk gone") }, false)
        assertEquals(TileStatus.Ready, model.tiles.single().status)
    }

    @Test
    fun `switching asks first while a video plays or a download moves`() {
        assertFalse(ProfilePicker.asksFirst(playing = false, downloading = false))
        assertTrue(ProfilePicker.asksFirst(playing = true, downloading = false))
        assertTrue(ProfilePicker.asksFirst(playing = false, downloading = true))
    }

    @Test
    fun `the hooks before a switch all run, a failing one does not stop the rest`() = runBlocking {
        var ran = 0
        val bad: suspend () -> Unit = { error("player gone") }
        val good: suspend () -> Unit = { ran++ }
        BeforeSwitch.add(bad)
        BeforeSwitch.add(good)
        try {
            BeforeSwitch.runAll()
            assertEquals(1, ran)
        } finally {
            BeforeSwitch.remove(bad)
            BeforeSwitch.remove(good)
        }
    }

    // ---- a profile's own name and its PIN's wait ----------------------------------------------

    @Test
    fun `rename keeps the viewer's name over Telegram's, survives the file and a new sign in`() = runBlocking {
        val a = signedIn(21L, "Telegram name")
        assertTrue(switcher.rename(a.id, "  Kids\u0007 room  "))
        assertEquals("Kids room", book.list.value.byId(a.id)!!.shownName)
        // Telegram's name read again on the next sign in leaves the viewer's own.
        switcher.noteSignedIn(fake.me(book, 21L, "Renamed in Telegram"))
        assertEquals("Kids room", book.list.value.byId(a.id)!!.shownName)
        assertEquals("Kids room", testBook(dir).list.value.byId(a.id)!!.label)
        assertTrue(switcher.rename(a.id, "   "))
        assertEquals("Renamed in Telegram", book.list.value.byId(a.id)!!.shownName)
        assertFalse(switcher.rename("gone", "x"))
        assertEquals(AccountRules.LABEL_MAX, AccountRules.cleanLabel("x".repeat(500))!!.length)
    }

    @Test
    fun `a label of the wrong type in the file is dropped, the profile kept`() {
        val text = AccountCodec.encode(AccountRules.adopted(0L)).replace("\"label\":null", "\"label\":42")
        val list = AccountCodec.decode(text)
        assertNotNull(list)
        assertNull(list!!.profiles.single().label)
    }

    @Test
    fun `the PIN wait left and the tries left before it`() {
        val p = ProfilePin.set(Profile("p", "p"), "1234")!!
        assertEquals(0L, ProfilePin.waitLeft(p, 10_000L))
        val waiting = p.copy(pinFailures = 5, pinWaitLeftMs = ProfilePin.WAIT_MS, pinWaitStamp = 10_000L)
        assertEquals(20_000L, ProfilePin.waitLeft(waiting, 20_000L))
        assertEquals("a rebooted clock behind the stamp serves none of it", ProfilePin.WAIT_MS, ProfilePin.waitLeft(waiting, 5L))
        assertEquals(4, ProfilePin.triesLeft(1))
        assertEquals(1, ProfilePin.triesLeft(4))
        assertEquals(5, ProfilePin.triesLeft(5))
    }

    // ---- LOW 3: a reopen that fails keeps the profile ------------------------------------------

    @Test
    fun `signing out a sleeping profile whose reopen fails keeps it, wipes nothing and logs nobody out`() = runBlocking {
        val a = signedIn(21L, "A")
        switcher.switchTo(AccountRules.ADOPTED_ID)
        val outsBefore = fake.logOuts
        var wiped = 0
        host.failReopen = true
        val result = switcher.signOutProfile(a.id) { _, _ -> wiped++ }
        assertEquals(SignOutResult.Failed, result)
        assertEquals(0, wiped)
        assertEquals(outsBefore, fake.logOuts)
        assertNotNull("kept", book.list.value.byId(a.id))
        assertEquals(AccountRules.ADOPTED_ID, book.list.value.activeId)
        assertTrue("its folders are still there", AccountDirs.pathsFor(base, a).databaseDir.isDirectory)
        assertTrue(book.list.value.trash.isEmpty())
        assertNull(book.list.value.switchingTo)
        host.failReopen = false
        assertEquals(SwitchResult.Switched, switcher.switchTo(a.id))
    }

    @Test
    fun `a failed reopen is its own switch result`() = runBlocking {
        val a = signedIn(21L, "A")
        switcher.switchTo(AccountRules.ADOPTED_ID)
        host.failReopen = true
        assertEquals(SwitchResult.ReopenFailed, switcher.switchTo(a.id))
        assertEquals(AccountRules.ADOPTED_ID, book.list.value.activeId)
    }

    // ---- LOW 5: Remove all accounts says a wipe was cut short ---------------------------------

    @Test
    fun `remove all accounts keeps the partial wipe of any profile`() = runBlocking {
        signedIn(21L, "A")
        signedIn(22L, "B")
        var calls = 0
        val result = switcher.removeAll { _, _ ->
            calls++
            if (calls == 2) error("the disk refused")
        }
        assertEquals(3, calls)
        assertEquals(SignOutResult.SignedOut(null, partialWipe = true), result)
        assertEquals(1, book.list.value.profiles.size)
        val clean = switcher.removeAll { _, _ -> }
        assertEquals(SignOutResult.SignedOut(null, partialWipe = false), clean)
    }

    @Test
    fun `remove all accounts stops at a profile that cannot be opened and keeps it`() = runBlocking {
        val a = signedIn(21L, "A")
        switcher.switchTo(AccountRules.ADOPTED_ID)
        host.failReopen = true
        assertEquals(SignOutResult.Failed, switcher.removeAll { _, _ -> })
        assertNotNull(book.list.value.byId(a.id))
    }

    // ---- LOW 1: the first sweep waits for the stores ------------------------------------------

    /** A book whose sweep purges through [Accounts], as the process's does, with a removed profile's folders in the trash. */
    private fun bookWithTrash(): Pair<AccountBook, Profile> {
        val own = AccountBook(AccountStore(File(Files.createTempDirectory("sweep").toFile(), Accounts.FILE_NAME)), purge = { Accounts.purge(it) })
        val gone = Profile("pgone01", "pgone01")
        AccountDirs.make(AccountDirs.pathsFor(base, gone))
        own.update { it.copy(trash = listOf("pgone01")) }
        return own to gone
    }

    @Test
    fun `the first sweep waits until the stores are registered`() = runBlocking {
        val (own, gone) = bookWithTrash()
        Accounts.resetForTest(own)
        val purged = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sweeping = async(kotlinx.coroutines.Dispatchers.Default) { Accounts.sweepOnceRegistered(own, base, waitMs = 5_000L) }
        delay(150)
        assertFalse("not before the stores are there", sweeping.isCompleted)
        assertEquals(listOf("pgone01"), own.list.value.trash)
        // A store registers late, as HomeCache and the chat grids do on the desktop.
        Accounts.onPurge { folder -> purged += folder }
        Accounts.storesRegistered()
        sweeping.await()
        assertTrue(purged.contains("pgone01"))
        assertTrue(own.list.value.trash.isEmpty())
        assertFalse(AccountDirs.pathsFor(base, gone).databaseDir.exists())
    }

    @Test
    fun `a platform that never says still gets its sweep after the wait`() = runBlocking {
        val (own, _) = bookWithTrash()
        Accounts.resetForTest(own)
        Accounts.sweepOnceRegistered(own, base, waitMs = 50L)
        assertTrue(own.list.value.trash.isEmpty())
    }

    // ---- LOW 4: a reopen that fails points the next client back -------------------------------

    @Test
    fun `a close that fails points the next client back at the open profile and drops the hook`() = runBlocking {
        val field = Td::class.java.getDeclaredField("currentPaths").apply { isAccessible = true }
        val was = field.get(null)
        val before = TempPaths()
        val target = ProfilePaths(before, "pzzzzz")
        field.set(null, before)
        Td.closeRequest = { error("TDLib refused the close") }
        try {
            var ran = false
            val failed = runCatching { Td.reopenFor(target) { ran = true } }
            assertTrue(failed.isFailure)
            assertTrue("the old profile's folders again", Td.paths === before)
            assertNull("no hook left for the next client", Td.pendingSwitchHook)
            assertFalse(ran)
        } finally {
            Td.closeRequest = { AuthReducer.close() }
            field.set(null, was)
        }
    }

    @Test
    fun `undoing an older reopen never drops a newer one's hook`() {
        val field = Td::class.java.getDeclaredField("currentPaths").apply { isAccessible = true }
        val was = field.get(null)
        try {
            val before = TempPaths()
            field.set(null, before)
            Td.undoReopen(TempPaths()) { }
            assertTrue("nothing asked, nothing moved", Td.paths === before)
        } finally {
            field.set(null, was)
        }
    }
}
