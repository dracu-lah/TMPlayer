package com.tmplayer.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP-97: a restore runs once at a time, says it is running, and says the truth when it fails. */
class RestoreRunTest {

    @Test
    fun `a second apply while the first runs is ignored, the busy flag is true then false`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = async {
            RestoreRun.run {
                runs++
                gate.await()
            }
        }
        yield()
        assertTrue(RestoreRun.running.value)
        // The same confirm pressed again, and a third time: none starts a second restore.
        assertEquals(RestoreRun.Outcome.AlreadyRunning, RestoreRun.run { runs++ })
        assertEquals(RestoreRun.Outcome.AlreadyRunning, RestoreRun.run { runs++ })
        gate.complete(Unit)
        assertEquals(RestoreRun.Outcome.Done, first.await())
        assertEquals(1, runs)
        assertFalse(RestoreRun.running.value)
        // Free again afterwards.
        assertEquals(RestoreRun.Outcome.Done, RestoreRun.run { runs++ })
        assertEquals(2, runs)
    }

    @Test
    fun `a restore that throws says failed, never done, and frees the next one`() = runBlocking {
        assertEquals(RestoreRun.Outcome.Failed, RestoreRun.run { error("disk full") })
        assertFalse(RestoreRun.running.value)
        assertEquals(RestoreRun.Outcome.Done, RestoreRun.run { })
    }

    @Test
    fun `a cancelled restore frees the flag and passes the cancellation on`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val job = launch { RestoreRun.run { gate.await() } }
        yield()
        assertTrue(RestoreRun.running.value)
        job.cancel()
        job.join()
        assertFalse(RestoreRun.running.value)
        assertTrue(job.isCancelled)
    }

    @AfterTest
    fun tearDown() {
        Accounts.resetForTest()
    }

    @Test
    fun `a switch part way stops the restore, nothing of it lands in the next profile and no Restored shows there (SEC2-04)`() = runBlocking {
        val root = Files.createTempDirectory("restore-owner").toFile()
        val a = AccountBook(AccountStore(File(File(root, "a"), Accounts.FILE_NAME)))
        val b = AccountBook(AccountStore(File(File(root, "b"), Accounts.FILE_NAME)))
        b.update { it.copy(profiles = it.profiles + Profile("pb", "pb"), activeId = "pb") }
        Accounts.resetForTest(a)
        val raw = openSafeDataStore(File(root, "settings.preferences_pb"))
        val store = AccountScopedDataStore(raw, isAccount = { true })
        val watched = stringPreferencesKey("watched")
        val lists = stringPreferencesKey("lists")
        val bookmarks = stringPreferencesKey("bookmarks")

        val firstWritten = CompletableDeferred<Unit>()
        val switched = CompletableDeferred<Unit>()
        val told = mutableListOf<RestoreRun.Outcome>()
        val job = RestoreRun.start(
            work = {
                store.edit { it[watched] = "from A" }
                firstWritten.complete(Unit)
                // A write already under way when the switch lands: the store drops it by its owner.
                withContext(NonCancellable) {
                    switched.await()
                    store.edit { it[lists] = "from A" }
                }
                // The rest never runs: the profile's scope was cancelled.
                store.edit { it[bookmarks] = "from A" }
            },
        ) { told += it }
        firstWritten.await()
        Accounts.resetForTest(b)
        switched.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(emptyList(), told)
        assertFalse(RestoreRun.running.value)
        // Profile B (open now) got nothing of A's backup.
        val seen = store.data.first()
        assertNull(seen[watched])
        assertNull(seen[lists])
        assertNull(seen[bookmarks])
        // A kept what it had written before the switch, and nothing after.
        Accounts.resetForTest(a)
        assertEquals("from A", store.data.first()[watched])
        assertNull(store.data.first()[lists])
    }
}
