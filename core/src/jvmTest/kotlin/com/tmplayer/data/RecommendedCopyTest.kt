package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HX38: one answer for a copy's picture, the recommended copy first, one count, no leading "~". */
class RecommendedCopyTest {

    private fun video(
        chatId: Long,
        messageId: Long,
        fileName: String,
        date: Int = messageId.toInt(),
        size: Long = 900L shl 20,
        width: Int = 0,
        height: Int = 0,
        fileId: Int = messageId.toInt(),
        onDevice: Boolean = false,
    ) = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        title = fileName,
        sizeBytes = size,
        durationSec = 6_000,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        width = width,
        height = height,
        onDevice = onDevice,
    )

    @Test
    fun theStatedSizeBeatsTheNamesTag() {
        val mislabelled = video(1, 1, "Night.Train.2022.720p.WEB-DL.mkv", width = 1920, height = 1080)
        assertEquals("1080p", QualityRead.of(mislabelled))
        assertEquals("720p", QualityRead.named(mislabelled))
        assertTrue(QualityRead.mismatch(mislabelled))
        assertEquals(1080, VideoCopies.heightOf(mislabelled))
        // Agreeing, or only one of the two: no flag.
        assertFalse(QualityRead.mismatch(video(1, 2, "Night.Train.2022.1080p.mkv", width = 1920, height = 1080)))
        assertEquals("720p", QualityRead.of(video(1, 3, "Night.Train.720p.mkv")))
        assertNull(QualityRead.of(video(1, 4, "Night.Train.mkv")))
        // A cropped 720p keeps its name's height, not the odd snapped one.
        assertEquals(720, VideoCopies.heightOf(video(1, 5, "Night.Train.720p.mkv", width = 1280, height = 536)))
        assertEquals("720p", QualityRead.of(video(1, 5, "Night.Train.720p.mkv", width = 1280, height = 536)))
    }

    @Test
    fun garbageNamesNeverThrow() {
        val names = listOf("", " ", "\u0000", "x".repeat(5000), "1080p".repeat(500), "....", "720p\u0000")
        for (name in names) {
            val item = video(1, 9, name, width = -4, height = Int.MAX_VALUE)
            QualityRead.of(item)
            QualityRead.mismatch(item)
            QualityRead.lowGrade(item)
            VideoCopies.heightOf(item)
        }
    }

    /** The shape seen in Cinema_paradasio: the badge said 1080p, the page opened a 720p copy. */
    @Test
    fun theRecommendedCopyLeadsAndItsBadgeIsItsOwn() {
        val tamil1080 = video(1, 10, "The.Paradise.2026.1080p.Tamil.E-AC3.mkv", date = 50, width = 1920, height = 1080)
        val hindiMal720 = video(1, 11, "The.Paradise.2026.720p.Hindi.Malayalam.mkv", date = 60, width = 1280, height = 720)
        val mislabelled = video(1, 12, "The.Paradise.2026.1080p.WEB-DL.mkv", date = 70, width = 1280, height = 720)
        val ordered = GlobalSearch.copyOrder(listOf(hindiMal720, mislabelled, tamil1080), cap = Int.MAX_VALUE)
        // The 1080p that really is 1080p leads; the name-only "1080p" that is 720p does not.
        assertEquals(10L, ordered.first().messageId)
        assertEquals("1080p", QualityRead.of(ordered.first()))
        assertEquals(listOf(10L, 12L, 11L), ordered.map { it.messageId })
    }

    @Test
    fun aCamIsNeverRecommendedAndADownloadStillWins() {
        val cam = video(1, 20, "Night.Train.2022.HDCAM.1080p.mkv", date = 90, width = 1920, height = 1080)
        val real = video(1, 21, "Night.Train.2022.720p.WEB-DL.mkv", date = 10, width = 1280, height = 720)
        assertTrue(QualityRead.lowGrade(cam))
        assertFalse(QualityRead.lowGrade(real))
        assertEquals(21L, GlobalSearch.copyOrder(listOf(cam, real), cap = Int.MAX_VALUE).first().messageId)
        val downloaded = video(1, 22, "Night.Train.2022.CAMRip.mkv", onDevice = true)
        assertEquals(22L, GlobalSearch.copyOrder(listOf(real, downloaded), cap = Int.MAX_VALUE).first().messageId)
    }

    @Test
    fun aFourKCopyDoesNotLeadWhereTheDeviceCapsAt1080() {
        val uhd = video(1, 30, "Night.Train.2022.2160p.mkv", date = 90, width = 3840, height = 2160)
        val hd = video(1, 31, "Night.Train.2022.1080p.mkv", date = 10, width = 1920, height = 1080)
        val sd = video(1, 32, "Night.Train.2022.480p.mkv", date = 99, width = 854, height = 480)
        assertEquals(listOf(31L, 32L, 30L).sorted(), GlobalSearch.copyOrder(listOf(uhd, hd, sd), cap = 1080).map { it.messageId }.sorted())
        // At or under the cap by picture, then the 4K stays selectable after them.
        assertEquals(listOf(31L, 32L, 30L), GlobalSearch.copyOrder(listOf(uhd, hd, sd), cap = 1080).map { it.messageId })
        assertEquals(listOf(30L, 31L, 32L), GlobalSearch.copyOrder(listOf(uhd, hd, sd), cap = Int.MAX_VALUE).map { it.messageId })
    }

    @Test
    fun aCopyThisDeviceDecodesTooSlowlyDoesNotLead() {
        val slow = video(1, 40, "Night.Train.2022.1080p.AV1.mkv", date = 90, width = 1920, height = 1080)
        val fine = video(1, 41, "Night.Train.2022.720p.x264.mkv", date = 10, width = 1280, height = 720)
        val ordered = GlobalSearch.copyOrder(listOf(slow, fine), tooSlow = { it.id == slow.id }, cap = Int.MAX_VALUE)
        assertEquals(41L, ordered.first().messageId)
    }

    @Test
    fun twoEqualCopiesKeepTheNewerAndNoPictureIsLast() {
        val old = video(1, 50, "Night.Train.2022.1080p.mkv", date = 10)
        val new = video(1, 51, "Night.Train.2022.1080p.x265.mkv", date = 20)
        val unknown = video(1, 52, "Night.Train.2022.mkv", date = 99)
        assertEquals(listOf(51L, 50L, 52L), GlobalSearch.copyOrder(listOf(old, unknown, new), cap = Int.MAX_VALUE).map { it.messageId })
    }

    @Test
    fun theVersionCountFoldsTheSameFilePostedTwice() {
        val a = video(1, 60, "Night.Train.2022.1080p.mkv", fileId = 7)
        val forwarded = video(2, 61, "Night.Train.2022.1080p.mkv", fileId = 7)
        val b = video(1, 62, "Night.Train.2022.720p.mkv", fileId = 8, size = 400L shl 20)
        val folded = a.copy(versions = listOf(a, forwarded, b))
        assertEquals(2, VideoCopies.versionCount(folded))
        assertEquals(1, VideoCopies.versionCount(a))
        assertEquals(1, VideoCopies.versionCount(a.copy(versions = emptyList())))
    }

    /** The grid showed "~SAW X" (HX31 shape): a leading marker is not part of a title. A leading # can be one ("#Alive") and stays. */
    @Test
    fun aLeadingMarkerIsNotPartOfTheTileTitle() {
        for (lead in listOf("~", "-", ".", "_", "*", "~ ", "~~")) {
            val title = MediaName.tileTitle("${lead}SAW X (2023) 1080p.mkv")
            assertEquals("lead [$lead]", "SAW X (2023)", title)
            assertEquals("lead [$lead] plain", "SAW X", MediaName.tileTitle("${lead}SAW X.mkv"))
        }
    }

    /** A large listing folds and orders fast: 3,000 titles of 6 copies each. */
    @Test
    fun scaleOrderingManyCopiesStaysQuick() {
        val started = System.nanoTime()
        var id = 1L
        repeat(3_000) { film ->
            val copies = (0 until 6).map { n ->
                val height = listOf(2160, 1080, 720, 480, 1080, 720)[n]
                video(1, id++, "Film $film 2022 ${height}p.mkv", date = n, width = height * 16 / 9, height = height)
            }
            assertEquals(6, GlobalSearch.copyOrder(copies).size)
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 10_000)
    }

    @Test
    fun theVersionCountIsLinearForHundredsOfCopies() {
        val copies = (0 until 600).map { n -> video(1 + n % 7L, 1000L + n, "Film.2022.${n % 50}.mkv", fileId = n % 300 + 1, size = (n % 300 + 1).toLong() shl 20) }
        val started = System.nanoTime()
        val film = copies.first().copy(versions = copies)
        val count = VideoCopies.versionCount(film)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(300, count)
        assertTrue("took $millis ms", millis < 2_000)
    }
}
