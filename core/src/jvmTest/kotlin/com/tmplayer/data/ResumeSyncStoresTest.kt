package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Resume on other devices against the real stores: what each store notes as removed, what a sync
 * writes into it, and two devices of one account syncing through one file the way [ResumeSync]
 * does, with the Telegram half (finding, reading and writing the message) stood in for by a
 * byte array.
 */
class ResumeSyncStoresTest {

    private class Device(name: String) {
        val dir = Files.createTempDirectory("tm-sync-$name").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
    }

    private fun mark(message: Long, at: Long, manual: Boolean = false) = WatchedRecord(
        chatId = -100L, messageId = message, fileId = 40 + message.toInt(), title = "Video $message", chatTitle = "Films",
        sizeBytes = 1_000, durationSec = 600, watchedAt = at, manual = manual,
    )

    private fun later(message: Long, at: Long) = WatchLaterRecord(
        chatId = -100L, messageId = message, fileId = 60 + message.toInt(), title = "Video $message", fileName = "v.mp4",
        chatTitle = "Films", sizeBytes = 1_000, durationSec = 600, mimeType = "video/mp4", addedAt = at,
    )

    private fun key(message: Long) = ProgressKeys.of(-100L, message)

    /**
     * One sync of [device] against [file] (null: no file in Saved Messages), as [ResumeSync.run]
     * does it, places taken in with the record's own file id in place of a Telegram lookup.
     */
    private fun sync(device: Device, file: ByteArray?, now: Long): ByteArray = runBlocking {
        val remote = file?.let { (SyncDocuments.decode(it, 1L) as SyncRead.Ok).doc } ?: SyncDocument(account = 1L)
        val base = device.settings.resumeSyncBaseNow()
        val local = SyncDocuments.collect(
            device.settings.rawEntries(), device.watched.rawEntries(), base, remote.favourites, 1L, now, "test",
        )
        val (merged, _) = SyncDocuments.mergeCounting(remote, local, now)
        val apply = SyncDocuments.apply(local, merged)
        val imported = HashSet<String>()
        for (record in apply.resumeIn) {
            if (device.settings.importSyncedResume(record, record.fileId, record.title, "")) imported += ProgressKeys.of(record.chatId, record.messageId)
        }
        for ((k, at) in apply.resumeOut) device.settings.dropSyncedResume(k, at)
        device.watched.applySync(apply)
        device.settings.applySyncedFavourites(apply.favouritesIn, apply.favouritesOut)
        device.settings.setResumeSyncBase(
            SyncBase(
                local.resume.mapTo(HashSet()) { ProgressKeys.of(it.chatId, it.messageId) } - apply.resumeOut.keys + imported,
                local.favourites.keys - apply.favouritesOut + apply.favouritesIn,
                now,
            ),
        )
        SyncDocuments.encode(merged.copy(updatedAt = now)).bytes
    }

    private fun marks(device: Device) = runBlocking { device.watched.watched.first().keys }
    private fun laterIds(device: Device) = runBlocking { device.watched.watchLater.items.first().map { it.id }.toSet() }
    private fun places(device: Device) = runBlocking { device.settings.continueWatching.first().map { ProgressKeys.of(it.chatId, it.messageId) }.toSet() }

    @Test
    fun removalsAreNotedAndAnUndoTakesThemBack() = runBlocking {
        val device = Device("notes")
        device.watched.markWatched(mark(1, 10))
        device.watched.markWatched(mark(2, 10))
        device.watched.markUnwatched(-100L, 1)
        val taken = device.watched.takeMarks(listOf(key(2)))
        device.watched.markUnwatched(-100L, 9) // never marked: nothing noted
        var removed = SyncDocuments.storedRemovals(device.watched.rawEntries())
        assertEquals(setOf(key(1), key(2)), removed.map { it.key }.toSet())
        device.watched.putBackMarks(taken)
        device.watched.markWatched(mark(1, 20))
        removed = SyncDocuments.storedRemovals(device.watched.rawEntries())
        assertTrue(removed.isEmpty())

        device.watched.watchLater.add(later(3, 10))
        device.watched.watchLater.remove(-100L, 3)
        assertEquals(listOf(SyncList.WatchLater), SyncDocuments.storedRemovals(device.watched.rawEntries()).map { it.list })
        device.watched.watchLater.add(later(3, 30))
        assertTrue(SyncDocuments.storedRemovals(device.watched.rawEntries()).isEmpty())
        // The marks and lines still read as they did.
        assertEquals(setOf(key(1), key(2)), marks(device))
        assertEquals(setOf(key(3)), laterIds(device))
    }

    @Test
    fun applyNeverOverwritesSomethingNewerHere() = runBlocking {
        val device = Device("newer")
        device.watched.markWatched(mark(1, 100, manual = true))
        device.watched.watchLater.add(later(2, 100))
        device.watched.applySync(
            SyncApply(
                watchedIn = listOf(mark(1, 50)),
                watchedOut = mapOf(key(1) to 90L),
                laterIn = listOf(later(2, 50)),
                laterOut = mapOf(key(2) to 90L),
            ),
        )
        val kept = device.watched.watched.first().getValue(key(1))
        assertEquals(100L, kept.watchedAt)
        assertTrue(kept.manual)
        assertEquals(100L, device.watched.watchLater.items.first().single().addedAt)
        // A later removal does take them off.
        device.watched.applySync(SyncApply(watchedOut = mapOf(key(1) to 200L), laterOut = mapOf(key(2) to 200L)))
        assertTrue(marks(device).isEmpty())
        assertTrue(laterIds(device).isEmpty())
        // And a removal taken in from the file is not noted again as one made here.
        assertTrue(SyncDocuments.storedRemovals(device.watched.rawEntries()).isEmpty())
    }

    @Test
    fun aTvAndAPhoneWritingAtOnceKeepEverything() {
        val tv = Device("tv")
        val phone = Device("phone")
        runBlocking {
            tv.watched.markWatched(mark(1, 1_000))
            phone.watched.markWatched(mark(2, 1_000))
            phone.watched.watchLater.add(later(3, 1_000))
            phone.settings.toggleFavorite(77L)
        }
        // Both read "no file" and both send one; the phone's lands last.
        sync(tv, null, 2_000)
        var file = sync(phone, null, 2_000)
        // The TV's next sync finds its mark missing and puts it back; the phone then takes it in.
        file = sync(tv, file, 3_000)
        file = sync(phone, file, 4_000)
        assertEquals(setOf(key(1), key(2)), marks(tv))
        assertEquals(setOf(key(1), key(2)), marks(phone))
        assertEquals(setOf(key(3)), laterIds(tv))
        assertTrue(runBlocking { 77L in tv.settings.favorites.first() })

        // Unwatched on the TV, saved for later on the phone at the same time: both stand.
        runBlocking {
            tv.watched.markUnwatched(-100L, 2)
            phone.watched.watchLater.add(later(4, 5_000))
        }
        file = sync(tv, file, 6_000)
        file = sync(phone, file, 7_000)
        sync(tv, file, 8_000)
        assertEquals(setOf(key(1)), marks(phone))
        assertEquals(setOf(key(3), key(4)), laterIds(tv))
    }

    @Test
    fun offlineThenOnlineAndAFileTheViewerDeleted() {
        val tv = Device("tv2")
        val phone = Device("phone2")
        runBlocking { phone.watched.markWatched(mark(1, 1_000)) }
        var file = sync(phone, null, 1_500)
        file = sync(tv, file, 2_000)
        // The TV is offline for a while: its changes wait in its stores and go at the next sync.
        runBlocking {
            tv.watched.markUnwatched(-100L, 1)
            tv.watched.markWatched(mark(5, 3_000))
        }
        file = sync(tv, file, 9_000)
        sync(phone, file, 9_500)
        assertEquals(setOf(key(5)), marks(phone))
        // The viewer deletes the file: the next sync starts one from this device's lists and
        // never empties them.
        val fresh = sync(phone, null, 10_000)
        assertEquals(setOf(key(5)), marks(phone))
        val doc = (SyncDocuments.decode(fresh, 1L) as SyncRead.Ok).doc
        assertEquals(listOf(key(5)), doc.watched.map { it.key })
    }

    @Test
    fun turningSyncOffForgetsWhatWasSynced() = runBlocking {
        val device = Device("off")
        device.settings.setResumeSync(true)
        device.settings.setResumeSyncBase(SyncBase(setOf(key(1)), setOf(5L), 99L))
        device.settings.setResumeSyncAccount(42L)
        device.settings.setResumeSyncFile(7L)
        device.settings.setResumeSyncStatus(SyncStatus(SyncStatus.Kind.Done, 5L, 3))
        device.settings.setResumeSync(false)
        assertEquals(SyncBase(), device.settings.resumeSyncBaseNow())
        assertEquals(0L, device.settings.resumeSyncAccountNow())
        assertEquals(0L, device.settings.resumeSyncFileNow())
        assertNull(device.settings.resumeSyncStatus.first())
        assertFalse(device.settings.resumeSyncNow())
    }

    @Test
    fun statusLinesReadBackAndGarbageIsNoStatus() {
        val status = SyncStatus(SyncStatus.Kind.Failed, 123L, 4, 5, "line\nwith\u001Fbreaks")
        val read = SyncStatus.decode(status.encode())
        assertEquals(SyncStatus.Kind.Failed, read?.kind)
        assertEquals("line with breaks", read?.detail)
        for (bad in listOf(null, "", "done", "nope\u001F1\u001F2\u001F3", "done\u001Fx\u001F1\u001F1")) assertNull(SyncStatus.decode(bad))
        assertEquals(0, SyncStatus.decode("done\u001F1\u001F-5\u001Fbig")?.received)
    }
}
