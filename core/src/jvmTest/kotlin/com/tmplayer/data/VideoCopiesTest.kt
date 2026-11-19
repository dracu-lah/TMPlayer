package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * A place, a pick and a habit belong to the video, not to the copy that was played: switching
 * from the 720p to the 1080p carries on where it was, and Continue watching keeps one card.
 */
class VideoCopiesTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-copies").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    private fun video(
        chat: Long,
        message: Long,
        name: String,
        size: Long = 900L shl 20,
        date: Int = message.toInt(),
        locality: MediaItem.Locality = MediaItem.Locality.Remote,
        width: Int = 0,
        height: Int = 0,
    ) = MediaItem(
        chatId = chat, messageId = message, fileId = message.toInt(), title = name, sizeBytes = size,
        durationSec = 7_200, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null,
        date = date, fileName = name, onDevice = locality != MediaItem.Locality.Remote, locality = locality,
        width = width, height = height,
    )

    private fun describe(item: MediaItem, at: Long) = ResumeRecord.encode(
        fileId = item.fileId, title = item.title, chatTitle = "Films", sizeBytes = item.sizeBytes,
        durationSec = item.durationSec, updatedAt = at, fileName = item.fileName,
    )

    private val hd = video(1, 10, "Night.Train.2019.1080p.WEB-DL.x265.mkv")
    private val sd = video(1, 11, "Night.Train.2019.720p.HDRip.x264.mkv", size = 700L shl 20)
    private val uhd = video(2, 50, "Night Train (2019) 2160p HEVC.mkv", size = 6_000L shl 20)
    private val other = video(1, 12, "Low.Tide.2022.1080p.mkv")

    // ---- the same file posted twice ----------------------------------------------------------

    @Test
    fun `the same file posted twice is one copy, whether forwarded or uploaded again`() {
        val forwarded = hd.copy(chatId = 3, messageId = 70) // the same Telegram file
        val again = video(1, 13, hd.fileName) // the same name and size, a new upload
        val files = VideoCopies.distinctFiles(listOf(hd, sd, forwarded, again, uhd))
        assertEquals(listOf(hd.id, sd.id, uhd.id), files.map { it.first.id })
        assertEquals(listOf(hd.id, forwarded.id, again.id), files[0].second.map { it.id })
        // The player lists the post playing for its file.
        assertEquals(listOf(again.id, sd.id, uhd.id), VideoCopies.oneOfEachFile(listOf(hd, sd, forwarded, again, uhd), again.id).map { it.id })
    }

    @Test
    fun `a copy in a chat the kids lock hides is never offered`() {
        val files = VideoCopies.distinctFiles(listOf(hd, sd, uhd), allowed = setOf(1L))
        assertEquals(listOf(hd.id, sd.id), files.map { it.first.id })
        assertEquals(listOf(hd.id, sd.id, uhd.id), VideoCopies.visible(listOf(hd, sd, uhd), allowed = null).map { it.id })
        assertEquals(listOf(hd.id, sd.id), VideoCopies.oneOfEachFile(listOf(hd, sd, uhd), hd.id, allowed = setOf(1L)).map { it.id })
    }

    // ---- keying ------------------------------------------------------------------------------

    @Test
    fun `copies of one film share a key, in one chat or two, and another film does not`() {
        assertEquals(VideoCopies.keyOf(hd), VideoCopies.keyOf(sd))
        assertEquals(VideoCopies.keyOf(hd), VideoCopies.keyOf(uhd))
        assert(VideoCopies.keyOf(other) != VideoCopies.keyOf(hd))
        assertNull(VideoCopies.keyOfTitle("Birthday highlights.mp4"))
    }

    @Test
    fun `a copy resumes from the newest place among the video's copies`() {
        val records = listOf(
            ResumeRecord(1, 10, 10, hd.title, "Films", 0, 0, 1_200_000, 7_200_000, updatedAt = 100),
            ResumeRecord(2, 50, 50, uhd.title, "Films", 0, 0, 3_000_000, 7_200_000, updatedAt = 200),
            ResumeRecord(1, 12, 12, other.title, "Films", 0, 0, 900_000, 7_200_000, updatedAt = 300),
        )
        assertEquals(3_000_000L, VideoCopies.resumeOf(sd, records)?.positionMs)
        assertEquals(3_000_000L, VideoCopies.resumeOf(hd, records)?.positionMs)
        assertEquals(900_000L, VideoCopies.resumeOf(other, records)?.positionMs)
    }

    @Test
    fun `a place carries over as it is, scaled for another frame rate, and not into another cut`() {
        assertEquals(600_000L, VideoCopies.carryOver(600_000, 7_200_000, 7_250_000))
        // 25 fps against 23.976: four per cent shorter, so the same scene comes four per cent sooner.
        assertEquals(576_000L, VideoCopies.carryOver(600_000, 7_200_000, 6_912_000))
        assertNull(VideoCopies.carryOver(600_000, 7_200_000, 8_400_000))
        assertEquals(600_000L, VideoCopies.carryOver(600_000, 0, 8_400_000))
        val extended = video(3, 70, "Night.Train.2019.Extended.1080p.mkv").copy(durationSec = 9_000)
        val records = listOf(ResumeRecord(1, 10, 10, hd.title, "Films", 0, 0, 1_200_000, 7_200_000, updatedAt = 100))
        assertNull(VideoCopies.resumeOf(extended, records))
    }

    @Test
    fun `old records only count as copies when their title names a year or an episode`() {
        // A caption's bare code, or a name whose only tag is its quality, groups nothing.
        val caption = ResumeRecord(1, 20, 20, "S02E04", "Shows", 0, 0, 600_000, 2_400_000, updatedAt = 1)
        val plain = ResumeRecord(1, 21, 21, "Lecture 1080p", "Class", 0, 0, 600_000, 2_400_000, updatedAt = 2)
        val plainToo = ResumeRecord(1, 22, 22, "Lecture 720p", "Class", 0, 0, 600_000, 2_400_000, updatedAt = 3)
        assertNull(VideoCopies.keyOf(caption))
        assertNull(VideoCopies.keyOf(plain))
        assertEquals(listOf(caption, plain, plainToo), HomeRows.foldCopies(listOf(caption, plain, plainToo)))
        // A record that kept its file name and caption is read like the video itself.
        val named = caption.copy(fileName = "harbour_notes_720p.mkv", caption = "Harbour Notes S02E04")
        assertEquals(VideoCopies.keyOf(named.toMediaItem()), VideoCopies.keyOf(named))
        assertEquals("episode:harbour notes|2|4", VideoCopies.keyOf(named))
    }

    @Test
    fun `a record keeps the file name and caption, and reads older lines as before`() {
        val line = ResumeRecord.encode(9, "S02E04", "Shows", 1, 2, 3, fileName = "harbour_notes_720p.mkv", caption = "Harbour Notes S02E04\nmore")
        val record = ResumeRecord.decode("1_2", line, 10, 20)!!
        assertEquals("harbour_notes_720p.mkv", record.fileName)
        assertEquals("Harbour Notes S02E04", record.caption)
        assertNull(record.state)
        val old = ResumeRecord.decode("1_2", ResumeRecord.encode(9, "S02E04", "Shows", 1, 2, 3), 10, 20)!!
        assertNull(old.fileName)
        assertEquals("S02E04", old.toMediaItem().fileName)
    }

    // ---- the store, and what was stored before ----------------------------------------------

    @Test
    fun `places stay per copy, and the newest wins on open`() = runBlocking {
        val settings = store()
        // Two copies of one film, both with a place, as earlier builds left them.
        settings.restoreEntries {
            mapOf(
                "resume_1_10" to 1_200_000L, "duration_1_10" to 7_200_000L, "meta_1_10" to describe(hd, 100),
                "resume_2_50" to 3_000_000L, "duration_2_50" to 7_200_000L, "meta_2_50" to describe(uhd, 200),
            )
        }
        // Nothing is moved or dropped by reading: both places are still there.
        assertEquals(2, settings.continueWatching.first().size)
        assertEquals(3_000_000L, settings.resumeRecordFor(sd)?.positionMs)
        assertEquals(3_000_000L, settings.resumeRecordFor(hd)?.positionMs)
        assertEquals(3_000_000L - ResumeRewind.DEFAULT_MS, settings.resumeStartFor(hd))
        assertNull(settings.resumeRecordFor(other))
        // Continue watching shows the film once, at its newest place.
        val folded = HomeRows.foldCopies(settings.continueWatching.first())
        assertEquals(listOf(50L), folded.map { it.messageId })
    }

    @Test
    fun `switching copy mid play carries the place and leaves one card`() = runBlocking {
        val settings = store()
        settings.saveResumePosition(1, 10, 1_800_000, 7_200_000, describe(hd, 100))
        settings.saveResumePosition(1, 12, 600_000, 7_200_000, describe(other, 110))
        // The viewer switches to the 720p at 30:00: it opens there, then carries on.
        assertEquals(1_800_000L, settings.resumeRecordFor(sd)?.positionMs)
        settings.saveResumePosition(1, 11, 1_810_000, 7_200_000, describe(sd, 120))
        val folded = HomeRows.foldCopies(settings.continueWatching.first())
        assertEquals(listOf(11L, 12L), folded.map { it.messageId })
        assertEquals(1_810_000L, settings.resumeRecordFor(hd)?.positionMs)
        // The 1080p's own line is still there, untouched.
        assertEquals(1_800_000L, settings.resumePosition(1, 10))
    }

    @Test
    fun `finishing or starting over in one copy clears the others`() = runBlocking {
        val settings = store()
        settings.saveResumePosition(1, 10, 1_800_000, 7_200_000, describe(hd, 100))
        settings.saveResumePosition(2, 50, 2_000_000, 7_200_000, describe(uhd, 200))
        settings.saveResumePosition(1, 12, 600_000, 7_200_000, describe(other, 210))
        settings.clearResumePosition(sd.chatId, sd.messageId, sd)
        assertEquals(listOf(12L), settings.continueWatching.first().map { it.messageId })

        settings.saveResumePosition(1, 10, 1_800_000, 7_200_000, describe(hd, 300))
        // The credits of the 4K copy.
        settings.saveResumePosition(2, 50, 7_000_000, 7_200_000, describe(uhd, 400))
        assertEquals(listOf(12L), settings.continueWatching.first().map { it.messageId })
    }

    // ---- which copy plays --------------------------------------------------------------------

    @Test
    fun `the pick order is chosen, on device, started, usual quality, then the best picture for this device`() {
        val newest = video(1, 20, "Night.Train.2019.2160p.mkv", date = 900)
        val small = video(1, 21, "Night.Train.2019.720p.mkv", date = 500)
        val full = video(1, 22, "Night.Train.2019.1080p.mkv", date = 400)
        val kept = video(1, 23, "Night.Train.2019.480p.mkv", date = 300, locality = MediaItem.Locality.Downloaded)
        val all = listOf(newest, small, full, kept)
        val habit = QualityHabit(mapOf(1080 to 9, 2160 to 1))

        assertEquals(small, VideoCopies.pick(all, chosen = small.id, habit = habit))
        assertEquals(kept, VideoCopies.pick(all, habit = habit))
        val remote = all - kept
        assertEquals(small, VideoCopies.pick(remote, started = { it == small }, habit = habit))
        assertEquals(full, VideoCopies.pick(remote, habit = habit))
        // Nothing else to go on: the tallest picture a phone shows in full, not the newest post (a television takes the 4K one).
        assertEquals(full, VideoCopies.pick(remote))
        // A pick that no longer exists (the message was deleted) is passed over.
        assertEquals(full, VideoCopies.pick(remote, chosen = "1:999", habit = habit))
    }

    @Test
    fun `the viewer's audio language comes before the usual quality, and leads the next episode`() {
        val tamil = video(1, 30, "Film.2020.Tamil.720p.mkv", date = 100)
        val hindi = video(1, 31, "Film.2020.Hindi.1080p.mkv", date = 200)
        val habit = QualityHabit(mapOf(1080 to 5))
        assertEquals(tamil, VideoCopies.pick(listOf(tamil, hindi), habit = habit, language = "ta"))
        assertEquals(hindi, VideoCopies.pick(listOf(tamil, hindi), habit = habit, language = "fr"))
        val next = listOf(
            video(1, 40, "Show.S01E02.Hindi.720p.mkv"),
            video(1, 41, "Show.S01E02.Tamil.1080p.mkv"),
        )
        assertEquals(41L, EpisodeCopies.closest(video(1, 39, "Show.S01E01.Tamil.720p.mkv"), next)?.messageId)
    }

    @Test
    fun `the usual quality takes the nearest copy, the lower one on a tie`() {
        val a = video(1, 1, "Film.2020.720p.mkv")
        val b = video(1, 2, "Film.2020.1440p.mkv")
        assertEquals(a, VideoCopies.pick(listOf(a, b), habit = QualityHabit(mapOf(1080 to 3))))
        // Read off Telegram's own picture size when the name says nothing.
        val wide = video(1, 3, "Film (2020).mkv", width = 1920, height = 800)
        val small = video(1, 4, "Film (2020) small.mkv", width = 854, height = 480)
        assertEquals(1080, VideoCopies.heightOf(wide))
        assertEquals(wide, VideoCopies.pick(listOf(small, wide), habit = QualityHabit(mapOf(1080 to 1))))
    }

    @Test
    fun `the habit counts plays, survives a round trip and halves past its cap`() {
        var habit = QualityHabit()
        repeat(3) { habit = habit.noted(1080) }
        habit = habit.noted(2160).noted(null)
        assertEquals(1080, habit.usual)
        assertEquals(habit, QualityHabit.decode(habit.encode()))
        assertEquals(QualityHabit(), QualityHabit.decode("junk,1080:x,:3"))
        repeat(QualityHabit.CAP) { habit = habit.noted(1080) }
        assert(habit.counts.getValue(1080) <= QualityHabit.CAP)
    }

    @Test
    fun `the store remembers a pick per video and the habit`() = runBlocking {
        val settings = store()
        settings.setCopyPick(sd)
        assertEquals(sd.id, settings.copyPickFor(hd))
        assertEquals(sd.id, settings.copyPickFor(uhd))
        assertNull(settings.copyPickFor(other))
        settings.notePlayedQuality(hd)
        settings.notePlayedQuality(hd)
        settings.notePlayedQuality(uhd)
        assertEquals(1080, settings.qualityHabitNow().usual)
    }

    @Test
    fun `the next episode is the copy most like the one playing`() {
        val show = (1..2).flatMap { n ->
            listOf(
                video(4, 100L + n, "Harbour.Lights.S01E0$n.1080p.mkv", size = 1_800L shl 20),
                video(4, 200L + n, "Harbour.Lights.S01E0$n.720p.mkv", size = 350L shl 20),
            )
        }
        val steps = EpisodeNeighbours.around(show[1], show)
        assertEquals(202L, steps.next?.messageId)
        val other = EpisodeNeighbours.around(show[0], show)
        assertEquals(102L, other.next?.messageId)
    }

    @Test
    fun `a height written without its p still names the copy's quality`() {
        val bare = video(1, 60, "Night Train 2019 Malayalam HQ HDRip - 1080.mkv")
        assertEquals(1080, VideoCopies.heightOf(bare))
        assertEquals("1080p", bare.copyQuality())
        assertEquals("HDRip", MediaFacts.of(bare).source)
    }

    @Test
    fun `the page's quality badge names the copy as the copy line does`() {
        // The name says 1080p while Telegram states 1280 x 720: the stated size decides (HX38,
        // 2026-10-31), one answer for the badge, the copies list and the order.
        val copy = video(1, 70, "Night Train 2019 1080p WEBRip x265.mkv", width = 1280, height = 720)
        assertEquals("720p", VideoCopies.qualityLabel(copy))
        assertEquals(copy.copyQuality(), VideoCopies.qualityLabel(copy))
        assertEquals("720p", GlobalSearch.qualityOf(copy))
        // Only the stated size: it decides, a scope 4K film included.
        assertEquals("720p", VideoCopies.qualityLabel(video(1, 71, "Night Train.mkv", width = 1280, height = 720)))
        assertEquals("4K", VideoCopies.qualityLabel(video(1, 72, "Night Train.mkv", width = 3840, height = 1600)))
        // Nothing to go on: no badge, and the copy line falls back to the size.
        assertNull(VideoCopies.qualityLabel(video(1, 73, "Night Train.mkv")))
    }

    @Test
    fun `a search card counts files as the page counts versions`() {
        val one = video(1, 80, "Night Train 2019 1080p.mkv")
        // The same file forwarded to a second chat: one version, though two posts.
        val forwarded = one.copy(chatId = 2, messageId = 81)
        val other = video(1, 82, "Night Train 2019 720p.mkv", size = 500L shl 20)
        val group = ResultGroup("night train", listOf(one, forwarded, other))
        assertEquals(2, group.fileCount)
        assertEquals(VideoCopies.distinctFiles(group.asItem().versions).size, group.fileCount)
    }
}
