package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

/**
 * Keep my place on all my devices, version 3: bookmarks and the viewer's own corrections travel
 * in the sync file beside the places. The merge table (conflicts, tombstones, cap, clock skew),
 * the file's reading, and two devices of one account syncing through one file against the real
 * stores, with Telegram stood in for by a byte array.
 */
class SyncBookmarksCorrectionsTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun mark(message: Long, id: Long, note: String = "", pos: Long = 5_000) = Bookmark(-100L, message, id, pos, note)
    private fun sb(message: Long, id: Long, at: Long = id, note: String = "") = SyncBookmark(mark(message, id, note), at)
    private fun fix(message: Long, at: Long, title: String = "Night Train") =
        SyncCorrection(-100L, message, VideoCorrection(title = title).encode(), at)

    private fun merge(a: SyncDocument, b: SyncDocument) = SyncDocuments.merge(a, b, now)

    // ---- the merge table ----------------------------------------------------------------------

    @Test
    fun theNewestChangeToABookmarkWinsWhicheverFileIsReadFirst() {
        val phone = SyncDocument(bookmarks = listOf(sb(1, 100, at = now - 5_000, note = "first")))
        val tv = SyncDocument(bookmarks = listOf(sb(1, 100, at = now - 1_000, note = "second")))
        val ab = merge(phone, tv)
        assertEquals("second", ab.bookmarks.single().bookmark.note)
        assertEquals(ab, merge(tv, phone))
    }

    @Test
    fun twoBookmarksOfOneVideoBothSurvive() {
        val merged = merge(SyncDocument(bookmarks = listOf(sb(1, 100))), SyncDocument(bookmarks = listOf(sb(1, 200))))
        assertEquals(setOf(100L, 200L), merged.bookmarks.map { it.bookmark.id }.toSet())
    }

    @Test
    fun aRemovalBeatsAnOlderCopyAndLosesToANewerEdit() {
        val gone = SyncRemoval(SyncList.Bookmark, "-100_1_100", now - 1_000)
        val older = SyncDocument(bookmarks = listOf(sb(1, 100, at = now - 5_000)))
        val newer = SyncDocument(bookmarks = listOf(sb(1, 100, at = now - 500)))
        val remover = SyncDocument(removed = listOf(gone))
        assertTrue(merge(older, remover).bookmarks.isEmpty())
        assertEquals(1, merge(newer, remover).bookmarks.size)
        // The removal stays while an older copy may still turn up; it is dropped once a newer edit stands over it.
        assertEquals(listOf(gone), merge(older, remover).removed)
        assertTrue(merge(newer, remover).removed.isEmpty())
    }

    @Test
    fun aRemovalOlderThanNinetyDaysIsForgotten() {
        val old = SyncRemoval(SyncList.Bookmark, "-100_1_100", now - 91 * day)
        val merged = merge(SyncDocument(bookmarks = listOf(sb(1, 100, at = now - 100 * day))), SyncDocument(removed = listOf(old)))
        // A device away for months brings its bookmark back: the documented limit of tombstones.
        assertEquals(1, merged.bookmarks.size)
        assertTrue(merged.removed.isEmpty())
    }

    @Test
    fun correctionsFollowTheSameRules() {
        val older = SyncDocument(corrections = listOf(fix(1, now - 5_000, "Old name")))
        val newer = SyncDocument(corrections = listOf(fix(1, now - 1_000, "New name")))
        assertEquals("title=New name", merge(older, newer).corrections.single().text)
        val cleared = SyncDocument(removed = listOf(SyncRemoval(SyncList.Correction, "-100_1", now - 2_000)))
        assertEquals("title=New name", merge(newer, cleared).corrections.single().text)
        assertTrue(merge(older, cleared).corrections.isEmpty())
    }

    @Test
    fun theCapKeepsTheNewestAndCountsWhatWentAndKeepsItsRemovals() {
        val marks = List(SyncDocuments.MAX_BOOKMARKS + 50) { sb(1 + it % 40L, 1_000L + it, at = now - 10_000 + it) }
        val fixes = List(SyncDocuments.MAX_CORRECTIONS + 20) { fix(1_000L + it, now - 10_000 + it) }
        val (merged, dropped) = SyncDocuments.mergeCounting(SyncDocument(bookmarks = marks, corrections = fixes), SyncDocument(), now)
        assertEquals(SyncDocuments.MAX_BOOKMARKS, merged.bookmarks.size)
        assertEquals(SyncDocuments.MAX_CORRECTIONS, merged.corrections.size)
        assertEquals(50, dropped.bookmarks)
        assertEquals(20, dropped.corrections)
        assertEquals(70, dropped.total)
        // The oldest are the ones left out.
        assertEquals(1_050L, merged.bookmarks.minOf { it.bookmark.id })
    }

    @Test
    fun aClockRunningFastCannotWinForEver() {
        // A device whose clock is a year ahead writes a note "from the future"; a real edit made today still wins within a day.
        val fast = SyncDocument(bookmarks = listOf(sb(1, 100, at = now + 365 * day, note = "from a fast clock")))
        val real = SyncDocument(bookmarks = listOf(sb(1, 100, at = now + 10 * 60_000, note = "real, ten minutes on")))
        val merged = merge(fast, real)
        // Both are read as "now" or later by at most the skew limit: the one inside the limit stays newer.
        assertEquals("real, ten minutes on", merged.bookmarks.single().bookmark.note)
        assertTrue(merged.bookmarks.single().at <= now + SyncDocuments.MAX_SKEW_MS)
        // A removal stamped in the future cannot outlive a note written a day later either.
        val lateRemoval = SyncDocument(removed = listOf(SyncRemoval(SyncList.Bookmark, "-100_1_100", now + 400 * day)))
        assertEquals(now, merge(lateRemoval, SyncDocument()).removed.single().at)
    }

    @Test
    fun theMergeStaysCommutativeIdempotentAndAssociativeWithTheNewLists() {
        val random = Random(3)
        fun doc(): SyncDocument {
            fun at() = now - random.nextLong(0, 10) * 1000
            return SyncDocument(
                account = 5,
                updatedAt = now,
                bookmarks = List(random.nextInt(0, 6)) { sb(random.nextLong(1, 4), random.nextLong(1, 5), at(), note = "n${random.nextInt(0, 3)}") },
                corrections = List(random.nextInt(0, 4)) { fix(random.nextLong(1, 4), at(), "t${random.nextInt(0, 3)}") },
                removed = List(random.nextInt(0, 5)) {
                    if (random.nextBoolean()) SyncRemoval(SyncList.Bookmark, "-100_${random.nextLong(1, 4)}_${random.nextLong(1, 5)}", at())
                    else SyncRemoval(SyncList.Correction, "-100_${random.nextLong(1, 4)}", at())
                },
            )
        }
        repeat(1_000) {
            val a = merge(doc(), SyncDocument())
            val b = merge(doc(), SyncDocument())
            val c = merge(doc(), SyncDocument())
            val ab = merge(a, b)
            assertEquals(ab, merge(b, a))
            assertEquals(ab, merge(ab, ab))
            assertEquals(merge(ab, c), merge(a, merge(b, c)))
        }
    }

    @Test
    fun applyTakesInOnlyWhatIsNewerAndRemovesOnlyWhatIsOlder() {
        val local = SyncDocument(
            bookmarks = listOf(sb(1, 100, at = now - 1_000), sb(2, 200, at = now - 9_000)),
            corrections = listOf(fix(1, now - 1_000, "Mine")),
        )
        val remote = SyncDocument(
            bookmarks = listOf(sb(1, 100, at = now - 5_000), sb(3, 300, at = now - 3_000)),
            corrections = listOf(fix(1, now - 5_000, "Theirs"), fix(2, now - 5_000, "Other")),
            removed = listOf(SyncRemoval(SyncList.Bookmark, "-100_2_200", now - 4_000)),
        )
        val merged = merge(local, remote)
        val apply = SyncDocuments.apply(local, merged)
        assertEquals(listOf(300L), apply.bookmarksIn.map { it.bookmark.id })
        assertEquals(setOf("-100_2_200"), apply.bookmarksOut.keys)
        assertEquals(listOf(2L), apply.correctionsIn.map { it.messageId })
        assertTrue(apply.correctionsOut.isEmpty())
        assertEquals(3, apply.received + apply.removing)
    }

    // ---- the file -----------------------------------------------------------------------------

    @Test
    fun theFileRoundTripsAndOnlyTheNewVersionHoldsTheNewLists() {
        val doc = SyncDocument(account = 7, updatedAt = now, bookmarks = listOf(sb(1, 100, note = "the answer")), corrections = listOf(fix(1, now)))
        val bytes = SyncDocuments.encode(doc).bytes
        val read = (SyncDocuments.decode(bytes, 7) as SyncRead.Ok).doc
        assertEquals(doc.bookmarks, read.bookmarks)
        assertEquals(doc.corrections, read.corrections)
        assertEquals(3, SyncDocuments.json(doc).getInt("version"))
        // Without them the file is the older shape, which a build before this one still reads.
        assertEquals(1, SyncDocuments.json(SyncDocument(account = 7)).getInt("version"))
        // An older build reads version 3 as too new and leaves it alone: nothing is written over it.
        assertTrue(SyncDocuments.decode("""{"format":"tmplayer-sync","version":4}""".encodeToByteArray(), 7) is SyncRead.TooNew)
    }

    @Test
    fun theFileCarriesNoFileNamesOrCaptionsAndACutTitle() {
        val doc = SyncDocument(
            bookmarks = listOf(sb(1, 100, note = "x".repeat(500))),
            corrections = listOf(SyncCorrection(-100L, 1, VideoCorrection(title = "t".repeat(500)).encode(), now)),
        )
        val text = SyncDocuments.json(doc).toString()
        val read = (SyncDocuments.decode(SyncDocuments.encode(doc).bytes, 0) as SyncRead.Ok).doc
        assertTrue(read.bookmarks.single().bookmark.note.length <= BookmarkCodec.MAX_NOTE)
        assertTrue(VideoCorrection.decode(read.corrections.single().text).title!!.length <= SyncDocuments.TITLE_MAX)
        assertFalse(text.contains("fileName"))
        assertFalse(text.contains("caption"))
    }

    @Test
    fun aFileTooBigIsCutOldestFirstAndCountedBeforeTheViewersOwnWritingGoes() {
        val doc = SyncDocument(
            watched = List(3_000) { i ->
                WatchedRecord(-100L, 10_000L + i, 0, "t".repeat(40) + i, "", 1_000, 600, now - i, false)
            },
            bookmarks = listOf(sb(1, 100, note = "keep")),
        )
        val encoded = SyncDocuments.encode(doc, maxBytes = 6_000)
        assertTrue(encoded.dropped.watched > 0)
        assertEquals(0, encoded.dropped.bookmarks)
        assertEquals(1, encoded.doc.bookmarks.size)
    }

    // ---- two devices, the real stores ---------------------------------------------------------

    private class Device(name: String) {
        val dir = Files.createTempDirectory("tm-sync3-$name").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
        val bookmarks = BookmarkStore(BookmarkStore.openDataStore(dir.resolve(BookmarkStore.FILE_NAME)))
    }

    private fun sync(device: Device, file: ByteArray?, at: Long): ByteArray = runBlocking {
        val remote = file?.let { (SyncDocuments.decode(it, 1L) as SyncRead.Ok).doc } ?: SyncDocument(account = 1L)
        val local = SyncDocuments.collect(
            device.settings.rawEntries(), device.watched.rawEntries(), device.settings.resumeSyncBaseNow(), remote.favourites,
            1L, at, "test", bookmarks = device.bookmarks.rawEntries(),
        )
        val (merged, _) = SyncDocuments.mergeCounting(remote, local, at)
        val apply = SyncDocuments.apply(local, merged)
        device.bookmarks.applySync(apply.bookmarksIn, apply.bookmarksOut)
        device.settings.applySyncedCorrections(apply.correctionsIn, apply.correctionsOut)
        SyncDocuments.encode(merged.copy(updatedAt = at)).bytes
    }

    private fun notes(device: Device, message: Long) = runBlocking { device.bookmarks.bookmarksNow(-100L, message).associate { it.id to it.note } }
    private fun item(message: Long): MediaItem {
        // corrections are held in one process-wide state ([VideoCorrections]); each test starts clean.
        VideoCorrections.set(emptyMap())
        return MediaItem(
            chatId = -100L, messageId = message, fileId = 1, title = "t", sizeBytes = 1, durationSec = 60, mimeType = "video/x-matroska",
            thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = "t.mkv", caption = "",
        )
    }

    @Test
    fun aBookmarkMadeOnOneDeviceArrivesOnTheOtherAndItsRemovalFollows() = runBlocking {
        val phone = Device("phone")
        val tv = Device("tv")
        val made = phone.bookmarks.add(-100L, 1, 90_000, "the twist", now = now - 3_000)
        var file = sync(phone, null, now - 2_000)
        file = sync(tv, file, now - 1_500)
        assertEquals(mapOf(made.id to "the twist"), notes(tv, 1))
        // The note is changed on the TV, later; the phone follows it.
        tv.bookmarks.rename(-100L, 1, made.id, "the twist, again", now = now - 1_000)
        file = sync(tv, file, now - 900)
        file = sync(phone, file, now - 800)
        assertEquals(mapOf(made.id to "the twist, again"), notes(phone, 1))
        // Taken off on the phone: gone on the TV, and not brought back by the TV's older copy.
        phone.bookmarks.delete(-100L, 1, made.id, now = now - 500)
        file = sync(phone, file, now - 400)
        file = sync(tv, file, now - 300)
        assertTrue(notes(tv, 1).isEmpty())
        sync(phone, file, now - 200)
        assertTrue(notes(phone, 1).isEmpty())
    }

    @Test
    fun twoDevicesChangingTheSameNoteAtOnceEndOnTheNewerOne() = runBlocking {
        val phone = Device("a")
        val tv = Device("b")
        val made = phone.bookmarks.add(-100L, 1, 90_000, "start", now = now - 9_000)
        var file = sync(phone, null, now - 8_000)
        file = sync(tv, file, now - 7_000)
        phone.bookmarks.rename(-100L, 1, made.id, "phone says", now = now - 3_000)
        tv.bookmarks.rename(-100L, 1, made.id, "tv says", now = now - 2_000)
        // Both read the same file, and the phone's write lands over the TV's.
        val fromTv = sync(tv, file, now - 1_000)
        val fromPhone = sync(phone, file, now - 900)
        // Next the TV reads the phone's file and the phone the TV's: both end on the later note.
        sync(tv, fromPhone, now - 800)
        sync(phone, fromTv, now - 700)
        assertEquals(mapOf(made.id to "tv says"), notes(tv, 1))
        assertEquals(mapOf(made.id to "tv says"), notes(phone, 1))
    }

    @Test
    fun aNewDeviceTakesEverythingOnItsFirstPullAndOneAwayForWeeksKeepsItsOwnNewerWork() = runBlocking {
        val phone = Device("first")
        val made = phone.bookmarks.add(-100L, 1, 1_000, "a", now = now - 40 * day)
        phone.settings.correct(listOf(item(1))) { it.copy(title = "Fixed") }
        val file = sync(phone, null, now - 40 * day + 1_000)
        val fresh = Device("fresh")
        sync(fresh, file, now - 39 * day)
        assertEquals(mapOf(made.id to "a"), notes(fresh, 1))
        assertEquals("Fixed", runBlocking { fresh.settings.corrections.first() }["-100:1"]?.title)
        // The phone stays away for weeks while the fresh device edits; the phone's note then comes back older.
        fresh.bookmarks.rename(-100L, 1, made.id, "edited elsewhere", now = now - 20 * day)
        val later = sync(fresh, file, now - 20 * day + 1_000)
        sync(phone, later, now)
        assertEquals(mapOf(made.id to "edited elsewhere"), notes(phone, 1))
    }

    @Test
    fun aDeletedSyncFileIsRebuiltFromTheDevicesNotLost() = runBlocking {
        val phone = Device("keep")
        val made = phone.bookmarks.add(-100L, 1, 1_000, "kept", now = now - 5_000)
        sync(phone, null, now - 4_000)
        // The pinned file was deleted in Telegram: the next sync finds none and writes a new one from this device.
        val rebuilt = sync(phone, null, now - 1_000)
        val read = (SyncDocuments.decode(rebuilt, 1L) as SyncRead.Ok).doc
        assertEquals(listOf(made.id), read.bookmarks.map { it.bookmark.id })
    }

    @Test
    fun aCorrectionClearedOnOneDeviceIsClearedOnTheOther() = runBlocking {
        val phone = Device("c1")
        val tv = Device("c2")
        phone.settings.correct(listOf(item(1))) { it.copy(separate = true) }
        var file = sync(phone, null, now - 5_000)
        file = sync(tv, file, now - 4_000)
        assertTrue(runBlocking { tv.settings.corrections.first() }["-100:1"]?.separate == true)
        Thread.sleep(5)
        phone.settings.correct(listOf(item(1))) { VideoCorrection() }
        // The store stamps with the real clock; the sync times are the test's, so sync at a later real-looking time.
        file = sync(phone, file, System.currentTimeMillis() + 1_000)
        sync(tv, file, System.currentTimeMillis() + 2_000)
        assertNull(runBlocking { tv.settings.corrections.first() }["-100:1"])
    }

    @Test
    fun clearingBookmarksOnSignOutAlsoClearsTheirTimesAndRemovals() = runBlocking {
        val device = Device("out")
        val a = device.bookmarks.add(-100L, 1, 1_000, "a")
        device.bookmarks.rename(-100L, 1, a.id, "b")
        val b = device.bookmarks.add(-100L, 2, 1_000, "x")
        device.bookmarks.delete(-100L, 2, b.id)
        assertTrue(device.bookmarks.rawEntries().keys.any { it.startsWith("bt_") })
        assertTrue(device.bookmarks.rawEntries().keys.any { it.startsWith("xb_") })
        device.bookmarks.clear()
        assertTrue(device.bookmarks.rawEntries().isEmpty())
    }

    @Test
    fun aVideoPastTheCapDropsItsOldestWithARemovalSoItDoesNotComeBackAtEverySync() = runBlocking {
        val device = Device("cap")
        val incoming = List(BookmarkStore.MAX_PER_VIDEO + 3) { SyncBookmark(mark(1, 1_000L + it, "n$it"), now - 10_000 + it) }
        val first = device.bookmarks.applySync(incoming, emptyMap())
        assertTrue(first > 0)
        assertEquals(BookmarkStore.MAX_PER_VIDEO, device.bookmarks.bookmarksNow(-100L, 1).size)
        // The three oldest are noted as removed, so the same file again changes nothing here.
        assertEquals(3, device.bookmarks.rawEntries().keys.count { it.startsWith("xb_") })
        assertEquals(0, device.bookmarks.applySync(incoming, emptyMap()))
        // ... and the removals go out to the other devices in the next file.
        val removals = SyncDocuments.extraRemovals(emptyMap(), device.bookmarks.rawEntries())
        assertEquals(3, removals.count { it.list == SyncList.Bookmark })
    }

    @Test
    fun aRestoreOfSomethingDeletedSinceIsNotUndoneByItsRemoval() = runBlocking {
        val device = Device("restore")
        val real = System.currentTimeMillis()
        val made = device.bookmarks.add(-100L, 1, 1_000, "keep", now = real - 5_000)
        device.bookmarks.delete(-100L, 1, made.id, now = real - 4_000)
        assertTrue(device.bookmarks.rawEntries().keys.any { it.startsWith("xb_") })
        device.bookmarks.restore(listOf(made))
        assertTrue(device.bookmarks.rawEntries().keys.none { it.startsWith("xb_") })
        // A synced file with the older removal does not take it off again.
        val doc = SyncDocument(removed = listOf(SyncRemoval(SyncList.Bookmark, SyncBookmark.keyOf(made), real - 4_000)))
        val local = SyncDocuments.collect(device.settings.rawEntries(), device.watched.rawEntries(), SyncBase(), emptyMap(), 1L, real, "t", bookmarks = device.bookmarks.rawEntries())
        val apply = SyncDocuments.apply(local, SyncDocuments.merge(doc, local, real))
        assertTrue(apply.bookmarksOut.isEmpty())

        VideoCorrections.set(emptyMap())
        device.settings.correct(listOf(item(1))) { it.copy(separate = true) }
        device.settings.correct(listOf(item(1))) { VideoCorrection() }
        assertTrue(device.settings.rawEntries().keys.any { it.startsWith("xfix_") })
        device.settings.restoreEntries { mapOf("fix_-100_1" to "separate") }
        assertTrue(device.settings.rawEntries().keys.none { it.startsWith("xfix_") })
        assertTrue(runBlocking { device.settings.corrections.first() }["-100:1"]?.separate == true)
    }
}
