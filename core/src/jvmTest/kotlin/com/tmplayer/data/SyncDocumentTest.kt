package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class SyncDocumentTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun place(message: Long, at: Long, position: Long = 60_000, title: String = "Video $message") = ResumeRecord(
        chatId = -100L, messageId = message, fileId = 5, title = title, chatTitle = "", sizeBytes = 1_000,
        durationSec = 600, positionMs = position, durationMs = 600_000, updatedAt = at,
    )

    private fun mark(message: Long, at: Long, manual: Boolean = false) = WatchedRecord(
        chatId = -100L, messageId = message, fileId = 0, title = "Video $message", chatTitle = "", sizeBytes = 1_000,
        durationSec = 600, watchedAt = at, manual = manual,
    )

    private fun later(message: Long, at: Long) = WatchLaterRecord(
        chatId = -100L, messageId = message, fileId = 0, title = "Video $message", fileName = "", chatTitle = "",
        sizeBytes = 1_000, durationSec = 600, mimeType = "video/mp4", addedAt = at,
    )

    private fun key(message: Long) = ProgressKeys.of(-100L, message)

    // ---- the merge's algebra ------------------------------------------------------------------

    /** Small key spaces and times, so the same video turns up on both sides with ties and removals. */
    private fun randomDoc(random: Random): SyncDocument {
        fun at() = now - random.nextLong(0, 10) * 1000
        fun msg() = random.nextLong(1, 8)
        return SyncDocument(
            account = 42L,
            updatedAt = at(),
            appVersion = "1.${random.nextInt(0, 3)}",
            resume = List(random.nextInt(0, 6)) { place(msg(), at(), position = random.nextLong(1, 4) * 1000) },
            watched = List(random.nextInt(0, 6)) { mark(msg(), at(), manual = random.nextBoolean()) },
            watchLater = List(random.nextInt(0, 6)) { later(msg(), at()) },
            favourites = List(random.nextInt(0, 4)) { random.nextLong(1, 5) to at() }.toMap(),
            removed = List(random.nextInt(0, 6)) {
                val list = SyncList.entries[random.nextInt(SyncList.entries.size)]
                val k = if (list == SyncList.Favourite) random.nextLong(1, 5).toString() else key(msg())
                SyncRemoval(list, k, at())
            },
        )
    }

    /** One file per key in each list, as every file the merge writes is. */
    private fun normal(doc: SyncDocument) = SyncDocuments.merge(doc, SyncDocument(), now)

    @Test
    fun mergeIsCommutativeIdempotentAndAssociative() {
        val random = Random(20261115)
        repeat(2_000) {
            val a = normal(randomDoc(random))
            val b = normal(randomDoc(random))
            val c = normal(randomDoc(random))
            val ab = SyncDocuments.merge(a, b, now)
            assertEquals("commutative", ab, SyncDocuments.merge(b, a, now))
            assertEquals("idempotent", ab, SyncDocuments.merge(ab, ab, now))
            assertEquals("with itself", a, SyncDocuments.merge(a, a, now))
            assertEquals(
                "associative",
                SyncDocuments.merge(ab, c, now),
                SyncDocuments.merge(a, SyncDocuments.merge(b, c, now), now),
            )
        }
    }

    @Test
    fun mergeNeverLosesAMarkNoRemovalCovers() {
        val random = Random(7)
        repeat(1_000) {
            val a = normal(randomDoc(random))
            val b = normal(randomDoc(random))
            val merged = SyncDocuments.merge(a, b, now)
            for (m in a.watched + b.watched) {
                val removal = (a.removed + b.removed).filter { it.list == SyncList.Watched && it.key == m.key }.maxOfOrNull { it.at }
                if (removal == null || removal <= m.watchedAt) {
                    assertTrue("mark ${m.key} kept", merged.watched.any { it.key == m.key && it.watchedAt >= m.watchedAt })
                }
            }
        }
    }

    // ---- two devices --------------------------------------------------------------------------

    @Test
    fun twoDevicesWritingAtOnceKeepBothMarks() {
        val start = SyncDocument(account = 1, watched = listOf(mark(1, now - day)))
        // Both read the same file, each adds a mark, and the phone's write lands over the TV's.
        val tv = SyncDocument(account = 1, watched = listOf(mark(2, now)))
        val phone = SyncDocument(account = 1, watched = listOf(mark(3, now)))
        val afterTv = SyncDocuments.merge(start, tv, now)
        val afterPhone = SyncDocuments.merge(start, phone, now)
        assertFalse(afterPhone.watched.any { it.key == key(2) })
        // The TV's next sync finds its own mark missing from the file, with nothing removing it,
        // and puts it back: it is never taken off the TV either.
        assertTrue(SyncDocuments.apply(tv, afterPhone).watchedOut.isEmpty())
        val again = SyncDocuments.merge(afterPhone, tv, now)
        assertEquals(setOf(key(1), key(2), key(3)), again.watched.map { it.key }.toSet())
        assertEquals(afterTv.watched.size + 1, again.watched.size)
    }

    @Test
    fun aMarkAndARemovalAtTheSameMomentKeepTheMark() {
        val merged = SyncDocuments.merge(
            SyncDocument(watched = listOf(mark(1, now))),
            SyncDocument(removed = listOf(SyncRemoval(SyncList.Watched, key(1), now))),
            now,
        )
        assertEquals(listOf(key(1)), merged.watched.map { it.key })
        assertTrue(merged.removed.isEmpty())
    }

    @Test
    fun aNewerRemovalTakesTheEntryAwayAndStays() {
        val merged = SyncDocuments.merge(
            SyncDocument(watched = listOf(mark(1, now - 5)), resume = listOf(place(2, now - 5)), favourites = mapOf(9L to now - 5)),
            SyncDocument(
                removed = listOf(
                    SyncRemoval(SyncList.Watched, key(1), now),
                    SyncRemoval(SyncList.Resume, key(2), now),
                    SyncRemoval(SyncList.Favourite, "9", now),
                ),
            ),
            now,
        )
        assertTrue(merged.watched.isEmpty())
        assertTrue(merged.resume.isEmpty())
        assertTrue(merged.favourites.isEmpty())
        assertEquals(3, merged.removed.size)
    }

    @Test
    fun theNewestPlaceWinsAndAManualTieStaysManual() {
        val merged = SyncDocuments.merge(
            SyncDocument(resume = listOf(place(1, now - 10, position = 10_000)), watched = listOf(mark(2, now, manual = true))),
            SyncDocument(resume = listOf(place(1, now, position = 90_000)), watched = listOf(mark(2, now, manual = false))),
            now,
        )
        assertEquals(90_000L, merged.resume.single().positionMs)
        assertTrue(merged.watched.single().manual)
    }

    @Test
    fun oldRemovalsAreForgotten() {
        val merged = SyncDocuments.merge(
            SyncDocument(removed = listOf(SyncRemoval(SyncList.Watched, key(1), now - 91 * day))),
            SyncDocument(removed = listOf(SyncRemoval(SyncList.Watched, key(2), now - 89 * day))),
            now,
        )
        assertEquals(listOf(key(2)), merged.removed.map { it.key })
    }

    @Test
    fun capsKeepTheNewestAndCountWhatIsLeftOut() {
        val many = SyncDocument(resume = (1L..250L).map { place(it, now - it) })
        val (merged, dropped) = SyncDocuments.mergeCounting(many, SyncDocument(), now)
        assertEquals(SyncDocuments.MAX_RESUME, merged.resume.size)
        assertEquals(250 - SyncDocuments.MAX_RESUME, dropped.resume)
        assertEquals(1L, merged.resume.first().messageId)
    }

    // ---- what a device changes ----------------------------------------------------------------

    @Test
    fun aDeviceNeverDropsAnythingOnlyBecauseTheFileLacksIt() {
        val local = SyncDocument(
            resume = listOf(place(1, now)),
            watched = listOf(mark(2, now)),
            watchLater = listOf(later(3, now)),
            favourites = mapOf(4L to now),
        )
        // The viewer deleted the file: the next one starts empty.
        val apply = SyncDocuments.apply(local, SyncDocuments.merge(SyncDocument(), local, now))
        assertTrue(apply.nothing)
        assertEquals(0, SyncDocuments.apply(local, SyncDocument()).removing)
    }

    @Test
    fun aDeviceTakesInNewerEntriesAndRemovals() {
        val local = SyncDocument(
            resume = listOf(place(1, now - 100), place(5, now - 100)),
            watched = listOf(mark(2, now - 100), mark(6, now - 100)),
            watchLater = listOf(later(3, now - 100), later(7, now - 100)),
            favourites = mapOf(4L to now - 100),
        )
        val remote = SyncDocument(
            resume = listOf(place(1, now, position = 99_000)),
            watched = listOf(mark(8, now, manual = true)),
            watchLater = listOf(later(9, now)),
            favourites = mapOf(10L to now),
            removed = listOf(
                SyncRemoval(SyncList.Resume, key(5), now),
                SyncRemoval(SyncList.Watched, key(6), now),
                SyncRemoval(SyncList.WatchLater, key(7), now),
                SyncRemoval(SyncList.Favourite, "4", now),
            ),
        )
        val apply = SyncDocuments.apply(local, SyncDocuments.merge(local, remote, now))
        assertEquals(listOf(99_000L), apply.resumeIn.map { it.positionMs })
        assertEquals(setOf(key(5)), apply.resumeOut.keys)
        assertEquals(listOf(key(8)), apply.watchedIn.map { it.key })
        assertEquals(0, apply.watchedIn.single().fileId)
        assertEquals(setOf(key(6)), apply.watchedOut.keys)
        assertEquals(listOf(key(9)), apply.laterIn.map { it.id })
        assertEquals(setOf(key(7)), apply.laterOut.keys)
        assertEquals(setOf(10L), apply.favouritesIn)
        assertEquals(setOf(4L), apply.favouritesOut)
    }

    @Test
    fun aMarkFromAnotherDeviceTakesTheVideoOffWatchLater() {
        val local = SyncDocument(watchLater = listOf(later(1, now - 100), later(2, now)))
        val remote = SyncDocument(watched = listOf(mark(1, now - 50), mark(2, now - 50)))
        val apply = SyncDocuments.apply(local, SyncDocuments.merge(local, remote, now))
        // Saved again after it was watched: stays.
        assertEquals(setOf(key(1)), apply.laterOut.keys)
        // And a line the file still has for a video watched here is not brought back.
        val back = SyncDocuments.apply(SyncDocument(watched = listOf(mark(3, now))), SyncDocument(watchLater = listOf(later(3, now - 1))))
        assertTrue(back.laterIn.isEmpty())
    }

    @Test
    fun aNewerMarkKeepsThisDevicesNamesAndManualFlag() {
        val mine = mark(1, now - 100, manual = true).copy(fileId = 77, title = "Mine", chatTitle = "Films")
        val apply = SyncDocuments.apply(SyncDocument(watched = listOf(mine)), SyncDocument(watched = listOf(mark(1, now))))
        val written = apply.watchedIn.single()
        assertEquals(77, written.fileId)
        assertEquals("Films", written.chatTitle)
        assertTrue(written.manual)
        assertEquals(now, written.watchedAt)
    }

    // ---- collecting ---------------------------------------------------------------------------

    @Test
    fun collectStripsNamesAndTellsRemovalsFromTheBase() {
        val line = ResumeRecord.encode(
            fileId = 11, title = "A rather long title that goes on and on past sixty characters for sure",
            chatTitle = "My private group", sizeBytes = 1, durationSec = 2, updatedAt = now,
            localPath = "/sdcard/x.mkv", fileName = "secret.mkv", caption = "a caption",
        )
        val settings = mapOf<String, Any>(
            "resume_-100_1" to 5_000L,
            "meta_-100_1" to line,
            "favorite_chats" to setOf("7", "8"),
        )
        val watched = mapOf<String, Any>(
            "w_-100_2" to mark(2, now).copy(fileId = 31, chatTitle = "Films", fileName = "secret.mkv", caption = "a caption").encode(),
            SyncDocuments.WATCHED_REMOVAL_PREFIX + "-100_3" to now - 1,
            SyncDocuments.LATER_REMOVAL_PREFIX + "-100_4" to now - 2,
            SyncDocuments.WATCHED_REMOVAL_PREFIX + "garbage" to now,
            SyncDocuments.LATER_REMOVAL_PREFIX + "-100_5" to "not a time",
        )
        val doc = SyncDocuments.collect(
            settings, watched,
            base = SyncBase(resumeKeys = setOf(key(1), key(9)), favourites = setOf(7L, 6L), at = now - day),
            remoteFavourites = mapOf(8L to now - 2 * day),
            account = 5, now = now, appVersion = "1.29.0",
        )
        val place = doc.resume.single()
        assertEquals(SyncDocuments.TITLE_MAX, place.title.length)
        assertEquals("", place.chatTitle)
        assertEquals(null, place.localPath)
        assertEquals(null, place.fileName)
        assertEquals(null, place.caption)
        assertEquals("", doc.watched.single().chatTitle)
        assertEquals(0, doc.watched.single().fileId)
        assertEquals(mapOf(7L to now - day, 8L to now - 2 * day), doc.favourites)
        assertEquals(
            setOf(
                SyncRemoval(SyncList.Resume, key(9), now),
                SyncRemoval(SyncList.Favourite, "6", now),
                SyncRemoval(SyncList.Watched, key(3), now - 1),
                SyncRemoval(SyncList.WatchLater, key(4), now - 2),
            ),
            doc.removed.toSet(),
        )
        val text = SyncDocuments.json(doc).toString()
        for (name in listOf("My private group", "secret.mkv", "a caption", "/sdcard", "Films")) assertFalse(name, name in text)
    }

    // ---- the file -----------------------------------------------------------------------------

    @Test
    fun encodeAndDecodeGiveTheSameDocumentBack() {
        val doc = normal(
            SyncDocument(
                account = 5, updatedAt = now, appVersion = "1.29.0",
                resume = listOf(place(1, now)), watched = listOf(mark(2, now, manual = true)),
                watchLater = listOf(later(3, now), later(4, now).copy(series = "harbour")),
                favourites = mapOf(-1001L to now), removed = listOf(SyncRemoval(SyncList.Watched, key(9), now)),
            ),
        )
        val encoded = SyncDocuments.encode(doc)
        assertEquals(0x1f.toByte(), encoded.bytes[0])
        val read = SyncDocuments.decode(encoded.bytes, account = 5) as SyncRead.Ok
        assertEquals(doc, read.doc)
        // A plain, unpacked file reads too, for anybody who edits one by hand.
        val plain = SyncDocuments.decode(SyncDocuments.json(doc).toString().encodeToByteArray(), account = 5) as SyncRead.Ok
        assertEquals(doc, plain.doc)
    }

    @Test
    fun aHugeLibraryIsCutToFitOldestPlacesFirst() {
        val doc = SyncDocument(
            resume = (1L..200L).map { place(it, now - it, title = "x".repeat(60) + it) },
            watched = (1L..5_000L).map { mark(it, now - it) },
        )
        val encoded = SyncDocuments.encode(doc, maxBytes = 20_000)
        assertTrue(encoded.bytes.size <= 20_000)
        assertTrue(encoded.dropped.resume > 0)
        assertEquals(doc.resume.size - encoded.doc.resume.size, encoded.dropped.resume)
        // The newest places stay.
        if (encoded.doc.resume.isNotEmpty()) assertEquals(1L, encoded.doc.resume.first().messageId)
        // A real library of the largest size fits whole.
        assertEquals(0, SyncDocuments.encode(doc).dropped.total)
    }

    @Test
    fun anotherAccountsOrANewerFileIsRefused() {
        val doc = SyncDocument(account = 5, watched = listOf(mark(1, now)))
        val bytes = SyncDocuments.encode(doc).bytes
        assertEquals(SyncRead.OtherAccount(5), SyncDocuments.decode(bytes, account = 6))
        val past = SyncDocuments.VERSION + 1
        val newer = SyncDocuments.json(doc).put("version", past).toString().encodeToByteArray()
        assertEquals(SyncRead.TooNew(past), SyncDocuments.decode(newer, account = 5))
    }

    @Test
    fun garbageIsNotASyncFile() {
        val good = SyncDocuments.encode(SyncDocument(account = 5, watched = (1L..50L).map { mark(it, now) })).bytes
        val cases = listOf(
            ByteArray(0),
            good.copyOf(good.size / 2),
            byteArrayOf(0x1f, 0x8b.toByte(), 0, 0),
            "{}".encodeToByteArray(),
            "{\"format\":\"tmplayer-backup\",\"version\":1}".encodeToByteArray(),
            "{\"format\":\"tmplayer-sync\",\"version\":\"one\"}".encodeToByteArray(),
            "{\"format\":\"tmplayer-sync\",\"version\":0}".encodeToByteArray(),
            ("[".repeat(100_000)).encodeToByteArray(),
            ByteArray(SyncDocuments.MAX_READ_BYTES + 1),
            gzip(ByteArray(SyncDocuments.MAX_TEXT_BYTES + 10) { ' '.code.toByte() }),
        )
        for (bytes in cases) assertEquals(SyncRead.NotASyncFile, SyncDocuments.decode(bytes, account = 5))
    }

    @Test
    fun badEntriesAreSkippedAndTheRestRead() {
        val text = """
            {"format":"tmplayer-sync","version":1,"account":"five",
             "watched":[null,1,{"chat":"x"},{"chat":-100,"message":2,"watchedAt":5,"manual":"yes"},{"chat":-100,"message":3}],
             "resume":{"not":"a list"},
             "favourites":[{"chat":7,"at":"soon"},{"chat":"8"},null],
             "removed":[{"list":"films","key":"1_2","at":1},{"list":"watched","key":"nope","at":1},{"list":"watched","key":"-100_4","at":9}]}
        """.trimIndent()
        val doc = (SyncDocuments.decode(text.encodeToByteArray(), account = 5) as SyncRead.Ok).doc
        assertEquals(listOf(key(2)), doc.watched.map { it.key })
        assertTrue(doc.resume.isEmpty())
        assertEquals(mapOf(7L to 0L), doc.favourites)
        assertEquals(listOf(SyncRemoval(SyncList.Watched, key(4), 9)), doc.removed)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
