package com.tmplayer.data

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The profile list (HX88): adopted in place, added to, removed from, at most 3, and read back whatever is on the disk. */
class AccountStoreTest {

    private val dir: File = Files.createTempDirectory("account-store").toFile()
    private val base = TempPaths()
    private val file = File(dir, Accounts.FILE_NAME)

    private val host = FakeHost()

    private fun switcher(book: AccountBook, host: FakeHost = this.host) =
        AccountSwitcher(book, host, base = { base }, clock = { 5_000L }, closeTimeoutMs = 2_000L)

    @Test
    fun `the login on the device is adopted in place, nothing moved, and written down`() {
        base.databaseDir.mkdirs()
        File(base.databaseDir, "td.binlog").writeText("session")
        val book = testBook(dir)
        val list = book.list.value
        assertEquals(1, list.profiles.size)
        assertEquals(AccountRules.ADOPTED_ID, list.activeId)
        assertNull("the platform's own folders", list.active?.folder)
        assertSame("TDLib is handed the platform's folders themselves", base, AccountDirs.pathsFor(base, list.active))
        assertTrue(File(base.databaseDir, "td.binlog").isFile)
        assertTrue("recorded", file.isFile)
        assertEquals(list, testBook(dir).list.value)
    }

    @Test
    fun `an added profile gets folders of its own beside the platform's`() = runBlocking {
        val book = testBook(dir)
        val added = switcher(book).add() as AddResult.Added
        val paths = AccountDirs.pathsFor(base, added.profile)
        assertEquals(File(base.root, "tdlib-${added.profile.folder}"), paths.databaseDir)
        assertEquals(File(base.root, "cache-${added.profile.folder}"), paths.filesDir)
        assertEquals(File(base.root, "downloads-${added.profile.folder}"), paths.downloadsDir)
        assertTrue(paths.databaseDir.isDirectory)
        assertEquals(added.profile.id, book.list.value.activeId)
        assertTrue("not signed in yet", book.list.value.active?.adding == true)
    }

    @Test
    fun `no more than three profiles`() = runBlocking {
        val book = testBook(dir)
        val s = switcher(book)
        assertTrue(s.add() is AddResult.Added)
        assertTrue(s.add() is AddResult.Added)
        assertEquals(AddResult.Full, s.add())
        assertEquals(3, book.list.value.profiles.size)
    }

    @Test
    fun `no new profile without room for its folders`() = runBlocking {
        base.free = 10L * 1024 * 1024
        val result = switcher(testBook(dir)).add()
        assertTrue(result is AddResult.NoSpace)
        assertEquals(AccountSwitcher.MIN_FREE_BYTES, (result as AddResult.NoSpace).neededBytes)
        assertFalse(base.root.listFiles().orEmpty().any { it.name.startsWith("tdlib-") })
    }

    @Test
    fun `removing a sleeping profile deletes its folders and keeps downloads that are there`() = runBlocking {
        val book = testBook(dir)
        val s = switcher(book)
        val added = (s.add() as AddResult.Added).profile
        val paths = AccountDirs.pathsFor(base, added)
        paths.downloadsDir.mkdirs()
        File(paths.downloadsDir, "kept.mp4").writeText("x")
        File(paths.filesDir, "videos").mkdirs()
        assertEquals(SwitchResult.Switched, s.switchTo(AccountRules.ADOPTED_ID))
        assertEquals(RemoveResult.Removed, s.remove(added.id))
        assertNull(book.list.value.byId(added.id))
        assertFalse(paths.databaseDir.exists())
        assertFalse(paths.filesDir.exists())
        assertTrue("the viewer's files", File(paths.downloadsDir, "kept.mp4").isFile)
        assertTrue(book.list.value.trash.isEmpty())
    }

    @Test
    fun `the open profile and the last one are not removed`() = runBlocking {
        val book = testBook(dir)
        val s = switcher(book)
        assertEquals(RemoveResult.Last, s.remove(AccountRules.ADOPTED_ID))
        val added = (s.add() as AddResult.Added).profile
        assertEquals(RemoveResult.Active, s.remove(added.id))
    }

    @Test
    fun `the same Telegram account on a second profile is refused and the first opens again`() = runBlocking {
        val book = testBook(dir)
        val s = switcher(book, host)
        s.noteSignedIn(host.me(book, 42L, "Asha", null))
        assertEquals(42L, book.list.value.active?.telegramId)
        val added = (s.add() as AddResult.Added).profile
        val events = mutableListOf<AccountEvent>()
        val listen = launchCollect(s, events)
        s.noteSignedIn(host.me(book, 42L, "Asha", null))
        assertEquals(1, host.logOuts)
        assertNull("the duplicate is gone", book.list.value.byId(added.id))
        assertEquals(AccountRules.ADOPTED_ID, book.list.value.activeId)
        assertEquals("tdlib", host.openOn)
        assertFalse(AccountDirs.pathsFor(base, added).databaseDir.exists())
        kotlinx.coroutines.delay(50)
        listen.cancel()
        assertEquals(listOf<AccountEvent>(AccountEvent.SameAccount(book.list.value.byId(AccountRules.ADOPTED_ID)!!)), events)
    }

    @Test
    fun `a signed in profile records who it is and its photo`() = runBlocking {
        val book = testBook(dir)
        val s = switcher(book)
        val added = (s.add() as AddResult.Added).profile
        s.noteSignedIn(host.me(book, 7L, "Ravi", byteArrayOf(1, 2, 3)))
        val now = book.list.value.byId(added.id)!!
        assertEquals(7L, now.telegramId)
        assertEquals("Ravi", now.name)
        assertFalse(now.adding)
        assertTrue(File(now.photo!!).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a file that does not parse starts again with the device's own login, the copy before when there is one`() {
        file.writeText("{\"v\":1,\"profiles\":[{\"id\":")
        assertEquals(AccountRules.adopted(1_000L), testBook(dir).list.value)

        val book = testBook(dir)
        book.update { it.copy(profiles = it.profiles + Profile("p2", "p2", telegramId = 9L)) }
        book.update { it.replace(it.byId("p2")!!.copy(name = "Second")) }
        file.writeText("garbage")
        val read = testBook(dir).list.value
        assertEquals("the backup copy from the write before", listOf(AccountRules.ADOPTED_ID, "p2"), read.profiles.map { it.id })
    }

    @Test
    fun `entries that are not plain names, repeats and a fourth profile are skipped`() {
        val text = """{"v":1,"active":"zz","profiles":[
            {"id":"main","folder":null},
            {"id":"../x","folder":"p1"},
            {"id":"p2","folder":"../../etc"},
            {"id":"p3","folder":"p3","telegramId":"lots"},
            {"id":"p3","folder":"p9"},
            {"id":"p4","folder":"p4"},
            {"id":"p5","folder":"p5"},
            {"id":"p6","folder":null}
        ]}"""
        val list = AccountCodec.decode(text)!!
        assertEquals(listOf("main", "p3", "p4"), list.profiles.map { it.id })
        assertEquals("an active id that is not there is the first", "main", list.activeId)
        assertEquals(0L, list.byId("p3")?.telegramId)
    }

    @Test
    fun `a signed in profile whose folder is gone is recoverable, not a crash`() = runBlocking {
        val book = testBook(dir)
        val s = switcher(book)
        val added = (s.add() as AddResult.Added).profile
        s.noteSignedIn(host.me(book, 5L, "Gone", null))
        s.switchTo(AccountRules.ADOPTED_ID)
        val paths = AccountDirs.pathsFor(base, added)
        paths.databaseDir.deleteRecursively()
        val signedIn = book.list.value.byId(added.id)!!
        assertEquals(ProfileState.Recoverable(ProfileState.Reason.FolderMissing), s.stateOf(signedIn))
        val result = s.switchTo(added.id)
        assertEquals(SwitchResult.Recoverable(ProfileState.Recoverable(ProfileState.Reason.FolderMissing)), result)
        assertEquals("nothing opened", AccountRules.ADOPTED_ID, book.list.value.activeId)
        assertEquals(SwitchResult.Switched, s.switchTo(added.id, signInAgain = true))
        assertTrue(paths.databaseDir.isDirectory)

        paths.databaseDir.deleteRecursively()
        paths.databaseDir.writeText("not a folder")
        assertEquals(ProfileState.Recoverable(ProfileState.Reason.NotAFolder), AccountDirs.stateOf(paths, signedIn))
    }

    @Test
    fun `a profile PIN waits 30 seconds after five wrong tries`() = runBlocking {
        val book = testBook(dir)
        val s = switcher(book)
        assertFalse(s.setPin(AccountRules.ADOPTED_ID, "12a4"))
        assertTrue(s.setPin(AccountRules.ADOPTED_ID, "1234"))
        val stored = book.list.value.active!!.pin!!
        assertTrue("a salted hash, never the digits", stored.startsWith("v1:") && "1234" !in stored)
        var now = 100_000L
        repeat(4) { assertTrue(s.checkPin(AccountRules.ADOPTED_ID, "0000", now) is PinCheck.Wrong) }
        val fifth = s.checkPin(AccountRules.ADOPTED_ID, "0000", now) as PinCheck.Wrong
        assertEquals(now + ProfilePin.WAIT_MS, fifth.attempts.waitUntil)
        now += 10_000
        assertEquals(PinCheck.Waiting(20_000L), s.checkPin(AccountRules.ADOPTED_ID, "1234", now))
        // The wait outlives a restart.
        val again = AccountSwitcher(testBook(dir), FakeHost(), base = { base })
        assertTrue(again.checkPin(AccountRules.ADOPTED_ID, "1234", now + 1_000) is PinCheck.Waiting)
        now += 20_000
        assertEquals(PinCheck.Right, s.checkPin(AccountRules.ADOPTED_ID, "1234", now))
        assertEquals(0, book.list.value.active?.pinFailures)
        assertTrue(s.setPin(AccountRules.ADOPTED_ID, null))
        assertEquals(PinCheck.NoPin, s.checkPin(AccountRules.ADOPTED_ID, "1234", now))
    }

    @Test
    fun `killed part way through adding, the half made profile and its folder go and the last one reopens`() = runBlocking {
        val book = testBook(dir)
        val half = Profile("pabc123", "pabc123", adding = true)
        AccountDirs.make(AccountDirs.pathsFor(base, half))
        book.update { it.copy(profiles = it.profiles + half, switchingTo = half.id) }

        val restarted = testBook(dir)
        val list = restarted.list.value
        assertEquals(AccountRules.ADOPTED_ID, list.activeId)
        assertNull(list.switchingTo)
        assertNull(list.byId(half.id))
        assertEquals(listOf("pabc123"), list.trash)
        restarted.sweep(base)
        assertFalse(AccountDirs.pathsFor(base, half).databaseDir.exists())
        assertTrue(restarted.list.value.trash.isEmpty())
    }

    @Test
    fun `the open profile's cache leaves room for the sleeping ones, never under a quarter`() {
        assertEquals(700L, AccountRules.sharedCeiling(1_000L, 300L))
        assertEquals(250L, AccountRules.sharedCeiling(1_000L, 2_000L))
        assertEquals(1_000L, AccountRules.sharedCeiling(1_000L, -5L))
    }

    @Test
    fun `scoped files are the adopted profile's own names, another profile's beside them`() {
        assertNotNull(Accounts.scopedFile(File("/x/home-cache.json")))
        assertEquals("home-cache.json", Accounts.scopedFile(File("/x/home-cache.json")).name)
        assertEquals(File("/a/tdlib-p1"), AccountDirs.beside(File("/a/tdlib"), "p1"))
    }
}

private fun launchCollect(s: AccountSwitcher, into: MutableList<AccountEvent>) =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        s.events.collect { synchronized(into) { into += it } }
    }
