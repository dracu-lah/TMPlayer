package com.tmplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The HX88 review's cases: the stores move only once the old client has closed, an answer about
 * who signed in that comes after a switch is dropped, an existing profile is never wiped by the
 * code, a folder the live client holds is never deleted, a removed profile's keys go with it, and
 * PIN tries at once all count.
 */
class AccountReviewTest {

    private val dir: File = Files.createTempDirectory("account-review").toFile()
    private val base = TempPaths()
    private val host = FakeHost()

    // The stores of this "process", following this book as the real ones follow Accounts.
    private val folder = MutableStateFlow<String?>(null)
    private var stamp = AccountStamp(0, null)
    private val raw = openSafeDataStore(File(dir, SettingsStore.FILE_NAME))
    private val scoped = AccountScopedDataStore(raw, folder, stamp = { stamp })
    private val settings = SettingsStore(scoped)
    private val book = AccountBook(
        AccountStore(File(dir, Accounts.FILE_NAME)),
        onChange = { list ->
            val next = list.active?.folder
            if (stamp.folder != next || folder.value != next) {
                stamp = AccountStamp(stamp.generation + 1, next)
                folder.value = next
            }
        },
        purge = { scoped.purge(it) },
    )
    private val switcher = AccountSwitcher(book, host, base = { base }, closeTimeoutMs = 100L)

    private suspend fun signedInProfile(telegramId: Long): Profile {
        val p = (switcher.add() as AddResult.Added).profile
        switcher.noteSignedIn(host.me(book, telegramId, "P$telegramId"))
        return book.list.value.byId(p.id)!!
    }

    private suspend fun rawNames(): Set<String> = raw.data.first().asMap().keys.map { it.name }.toSet()

    @Test
    fun `a write during a slow close is the old profile's, and the stores move only once it has closed`() = runBlocking {
        switcher.noteSignedIn(host.me(book, 1L, "Main"))
        val b = signedInProfile(2L)
        switcher.switchTo(AccountRules.ADOPTED_ID)
        host.closeMs = 600L
        val switching = async { switcher.switchTo(b.id) }
        withTimeout(2_000) { switcher.state.first { it is SwitchState.ClosingSlowly } }
        assertTrue(host.closing)
        assertNull("still the old profile's view", folder.value)
        // The old profile's player, saving its place while its client closes.
        settings.toggleFavorite(500L)
        settings.saveResumePosition(500L, 1L, positionMs = 600_000L, durationMs = 3_600_000L)
        assertEquals(SwitchResult.Switched, switching.await())
        assertEquals(b.folder, folder.value)
        assertTrue("not in the next profile's", settings.favorites.first().isEmpty())
        assertEquals(0L, settings.resumePosition(500L, 1L))
        assertTrue("the old profile's own keys", "favorite_chats" in rawNames())
        assertFalse(rawNames().any { it.startsWith("@${b.folder}:favorite") })
    }

    @Test
    fun `who signed in, read after a switch to another profile, is dropped and logs nobody out`() = runBlocking {
        switcher.noteSignedIn(host.me(book, 1L, "Main"))
        val b = signedInProfile(2L)
        val c = signedInProfile(3L)
        switcher.switchTo(b.id)
        // B's Ready, read slowly: by the time it is handed in, C is open.
        val late = host.me(book, 2L, "Late")
        switcher.switchTo(c.id)
        val logOuts = host.logOuts
        switcher.noteSignedIn(late)
        assertEquals("C is not logged out", logOuts, host.logOuts)
        assertEquals(c.id, book.list.value.activeId)
        assertNotNull(book.list.value.byId(c.id))
        assertTrue(AccountDirs.pathsFor(base, c).databaseDir.isDirectory)
        assertEquals("P3", book.list.value.byId(c.id)?.name)
        // The same profile, an older client of it: dropped too.
        val stale = host.me(book, 9L, "Old").copy(session = host.generation - 1)
        switcher.noteSignedIn(stale)
        assertEquals(3L, book.list.value.byId(c.id)?.telegramId)
    }

    @Test
    fun `an existing profile that cannot be opened is never wiped by the same account refusal`() = runBlocking {
        val a = signedInProfile(42L)
        switcher.switchTo(AccountRules.ADOPTED_ID)
        val aPaths = AccountDirs.pathsFor(base, a)
        aPaths.databaseDir.deleteRecursively()
        aPaths.databaseDir.writeText("not a folder")
        val added = (switcher.add() as AddResult.Added).profile
        switcher.noteSignedIn(host.me(book, 42L, "Again"))
        assertTrue("the broken profile's own file stays", aPaths.databaseDir.isFile)
        assertEquals("the new one stays open, empty, on its sign in", added.id, book.list.value.activeId)
        assertTrue(AccountDirs.pathsFor(base, added).databaseDir.isDirectory)
        assertEquals(0L, book.list.value.byId(added.id)?.telegramId)
    }

    @Test
    fun `a sign out whose next profile cannot be opened keeps the signed out folder the live client holds`() = runBlocking {
        switcher.noteSignedIn(host.me(book, 1L, "Main"))
        val b = signedInProfile(2L)
        // The adopted profile's folder turned into a file while B was open.
        base.databaseDir.deleteRecursively()
        base.databaseDir.writeText("x")
        val out = switcher.signOutProfile(b.id) { _, _ -> }
        assertEquals(SignOutResult.SignedOut(null), out)
        assertEquals("never wiped", true, base.databaseDir.isFile)
        assertEquals(b.id, book.list.value.activeId)
        assertTrue("held by the live client, so kept", AccountDirs.pathsFor(base, b).databaseDir.isDirectory)
        assertEquals(0L, book.list.value.byId(b.id)?.telegramId)
        assertTrue(book.list.value.trash.isEmpty())
    }

    @Test
    fun `a removed profile's keys go with its folders, and a new profile or the adopted one again sees none`() = runBlocking {
        switcher.noteSignedIn(host.me(book, 1L, "Main"))
        settings.toggleFavorite(1L)
        val b = signedInProfile(2L)
        settings.toggleFavorite(2L)
        settings.saveResumePosition(2L, 2L, positionMs = 600_000L, durationMs = 3_600_000L)
        switcher.switchTo(AccountRules.ADOPTED_ID)
        assertTrue(rawNames().any { it.startsWith("@${b.folder}:") })
        assertEquals(RemoveResult.Removed, switcher.remove(b.id))
        assertFalse("purged", rawNames().any { it.startsWith("@${b.folder}:") })
        val c = (switcher.add() as AddResult.Added).profile
        assertTrue(settings.favorites.first().isEmpty())
        assertEquals(c.id, book.list.value.activeId)

        // Remove all accounts: the fresh profile on the platform's folders starts empty too.
        switcher.removeAll { _, _ -> }
        assertNull(folder.value)
        assertTrue("the old adopted account's plain keys are gone", settings.favorites.first().isEmpty())
        assertFalse(rawNames().any { it.startsWith("@") })
    }

    @Test
    fun `two wrong PINs at once both count, and five make the pad wait`() = runBlocking {
        assertTrue(switcher.setPin(AccountRules.ADOPTED_ID, "1234"))
        val now = 50_000L
        val tries = (1..4).map { async(Dispatchers.Default) { switcher.checkPin(AccountRules.ADOPTED_ID, "0000", now) } }.awaitAll()
        assertTrue(tries.all { it is PinCheck.Wrong })
        assertEquals(4, book.list.value.active?.pinFailures)
        val fifth = switcher.checkPin(AccountRules.ADOPTED_ID, "0000", now)
        assertEquals(now + ProfilePin.WAIT_MS, (fifth as PinCheck.Wrong).attempts.waitUntil)
        assertEquals(PinCheck.Waiting(ProfilePin.WAIT_MS), switcher.checkPin(AccountRules.ADOPTED_ID, "1234", now))
        // A name written meanwhile is not lost by a PIN check.
        book.update { it.replace(it.active!!.copy(name = "Renamed")) }
        switcher.checkPin(AccountRules.ADOPTED_ID, "1234", now + ProfilePin.WAIT_MS)
        assertEquals("Renamed", book.list.value.active?.name)
        assertEquals(0, book.list.value.active?.pinFailures)
    }

    @Test
    fun `a wipe that calls the switcher is refused instead of waiting for itself`() = runBlocking {
        switcher.noteSignedIn(host.me(book, 1L, "Main"))
        val b = signedInProfile(2L)
        var inner: SwitchResult? = null
        withTimeout(5_000) {
            switcher.signOutProfile(b.id) { _, _ -> inner = switcher.switchTo(AccountRules.ADOPTED_ID) }
        }
        assertEquals(SwitchResult.Failed, inner)
    }

    // ---- the second review ------------------------------------------------------------------

    @Test
    fun `a reboot during a PIN wait ends it within the wait, never later`() = runBlocking {
        assertTrue(switcher.setPin(AccountRules.ADOPTED_ID, "1234"))
        val before = 9_000_000_000L
        repeat(ProfilePin.TRIES) { switcher.checkPin(AccountRules.ADOPTED_ID, "0000", before) }
        // The monotonic clock starts again from nothing after the reboot.
        val booted = 5_000L
        val waiting = switcher.checkPin(AccountRules.ADOPTED_ID, "1234", booted) as PinCheck.Waiting
        assertTrue("never longer than the wait", waiting.waitMs <= ProfilePin.WAIT_MS)
        assertTrue(switcher.checkPin(AccountRules.ADOPTED_ID, "1234", booted + ProfilePin.WAIT_MS / 2) is PinCheck.Waiting)
        assertEquals("over within the wait of the reboot", PinCheck.Right, switcher.checkPin(AccountRules.ADOPTED_ID, "1234", booted + ProfilePin.WAIT_MS))
    }

    @Test
    fun `a new profile that reached Ready is kept at the next start even when who it is was never read`() = runBlocking {
        val added = (switcher.add() as AddResult.Added).profile
        switcher.noteSignedIn(SignedInAs(added.id, host.generation, 0L, "", null))
        assertFalse(book.list.value.byId(added.id)!!.adding)
        val restarted = AccountBook(AccountStore(File(dir, Accounts.FILE_NAME))).list.value
        assertNotNull("not taken for a half made one", restarted.byId(added.id))
    }

    @Test
    fun `each Ready client is heard, a newer one cancels a slow read, and a failed read is tried again`() = runBlocking {
        val ready = kotlinx.coroutines.flow.MutableSharedFlow<ReadySession>(replay = 2)
        val heard = java.util.Collections.synchronizedList(mutableListOf<SignedInAs>())
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val flow = SignedInFlow.of(ready, read = { r ->
            if (r.generation == 1L) {
                kotlinx.coroutines.delay(2_000)
                SignedInAs(r.profileId, 1L, 11L, "Slow", null)
            } else {
                if (reads.incrementAndGet() == 1) null else SignedInAs(r.profileId, r.generation, 22L, "Second", null)
            }
        }, tries = 3, gapMs = 20L)
        val job = launch(Dispatchers.Default) { flow.collect { heard += it } }
        ready.emit(ReadySession("p1", 1L))
        kotlinx.coroutines.delay(50)
        ready.emit(ReadySession("p2", 2L))
        withTimeout(2_000) { while (heard.none { it.telegramId == 22L }) kotlinx.coroutines.delay(10) }
        job.cancel()
        assertEquals(
            listOf(0L to "p1", 0L to "p2", 22L to "p2"),
            heard.map { it.telegramId to it.profileId },
        )
    }

    @Test
    fun `a host that never ran the switch's hook moves nothing`() = runBlocking {
        val broken = object : ClientHost by host {
            override suspend fun reopen(paths: com.tmplayer.platform.Paths, timeoutMs: Long, onClosed: () -> Unit): Boolean =
                throw IllegalStateException("no client")
        }
        val b = signedInProfile(2L)
        switcher.switchTo(AccountRules.ADOPTED_ID)
        val stuck = AccountSwitcher(book, broken, base = { base })
        assertEquals(SwitchResult.ReopenFailed, stuck.switchTo(b.id))
        assertEquals(AccountRules.ADOPTED_ID, book.list.value.activeId)
        assertNull(book.list.value.switchingTo)
        assertNull(folder.value)
    }

    @Test
    fun `a removed profile's Home and chat listing files go with it, never another's`() = runBlocking {
        val homeBase = File(dir, "home-cache.json").apply { writeText("main") }
        val gridBase = File(dir, "chat-grids.json").apply { writeText("main") }
        val homeGone = File(dir, "home-cache-pgone1.json").apply { writeText("x") }
        val gridGone = File(dir, "chat-grids-pgone1.json").apply { writeText("x") }
        HomeCache.file = homeBase
        ChatGridDisk(gridBase)
        try {
            Accounts.purge("pgone1")
            assertFalse(homeGone.exists())
            assertFalse(gridGone.exists())
            assertTrue(homeBase.isFile)
            assertTrue(gridBase.isFile)
        } finally {
            HomeCache.file = null
        }
    }

    @Test
    fun `a wipe cut short by its own timeout still finishes the sign out and says so`() = runBlocking {
        switcher.noteSignedIn(host.me(book, 1L, "Main"))
        val b = signedInProfile(2L)
        settings.toggleFavorite(9L)
        val out = switcher.signOutProfile(b.id) { _, _ -> withTimeout(20) { kotlinx.coroutines.delay(5_000) } }
        assertEquals(SignOutResult.SignedOut(book.list.value.byId(AccountRules.ADOPTED_ID), partialWipe = true), out)
        assertNull(book.list.value.byId(b.id))
        assertFalse("its keys went with the sweep", rawNames().any { it.startsWith("@${b.folder}:") })
    }

    @Test
    fun `a resume save made for a profile no longer open is dropped`() = runBlocking {
        switcher.noteSignedIn(host.me(book, 1L, "Main"))
        val playerOpenedFor = stamp
        val b = signedInProfile(2L)
        settings.saveResumePosition(7L, 7L, positionMs = 600_000L, durationMs = 3_600_000L, owner = playerOpenedFor)
        settings.noteDownload(MediaItem(chatId = 7, messageId = 8, fileId = 1, title = "t", sizeBytes = 1, durationSec = 1, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1), "c", "/x", owner = playerOpenedFor)
        assertEquals(0L, settings.resumePosition(7L, 7L))
        assertTrue(settings.downloadsNow().isEmpty())
        switcher.switchTo(AccountRules.ADOPTED_ID)
        assertEquals("nor written to the old one after it closed", 0L, settings.resumePosition(7L, 7L))
        assertNotNull(b)
    }
}
