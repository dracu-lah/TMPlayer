package com.tmplayer.data

import com.tmplayer.online.MetaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCorrectionsTest {

    @Test
    fun `a correction reads back as it was written`() {
        val all = VideoCorrection(separate = true, wrongMatch = true, title = "Night Lantern (2022)")
        assertEquals(all, VideoCorrection.decode(all.encode()))
        assertEquals(VideoCorrection(title = "Two\nlines"), VideoCorrection.decode("title=Two\nlines").copy(title = "Two\nlines"))
        assertEquals("title=Two lines", VideoCorrection(title = "Two\nlines").encode())
        assertTrue(VideoCorrection.decode(null).isEmpty)
        assertTrue(VideoCorrection.decode("nonsense").isEmpty)
        assertTrue(VideoCorrection(title = "  ").isEmpty)
    }

    @Test
    fun `a poster pick reads back, and values stored before it still decode`() {
        val picked = VideoCorrection(separate = true, title = "Idiot (2022)", pick = PosterPick(123, show = true))
        assertEquals("separate\ntmdb=show:123\ntitle=Idiot (2022)", picked.encode())
        assertEquals(picked, VideoCorrection.decode(picked.encode()))
        assertEquals(PosterPick(9, show = false), VideoCorrection.decode("tmdb=film:9").pick)
        assertFalse(VideoCorrection(pick = PosterPick(9)).isEmpty)
        // Values written before the pick existed.
        assertEquals(VideoCorrection(wrongMatch = true), VideoCorrection.decode("wrong"))
        assertEquals(VideoCorrection(separate = true, wrongMatch = true, title = "Night Train"), VideoCorrection.decode("separate\nwrong\ntitle=Night Train"))
    }

    @Test
    fun `a malformed pick is no pick and the rest of the value still reads`() {
        for (bad in listOf("tmdb=", "tmdb=film:", "tmdb=film:0", "tmdb=film:-4", "tmdb=film:abc", "tmdb=movie:5", "tmdb=show:99999999999", "tmdb=film:1:2", "TMDB=film:5", "tmdb = film:5", "tmdb=film:5x")) {
            assertNull(bad, VideoCorrection.decode(bad).pick)
            assertEquals(bad, true, VideoCorrection.decode("wrong\n$bad").wrongMatch)
        }
        // A title that looks like a pick is a title.
        val sly = VideoCorrection.decode("title=tmdb=film:5")
        assertNull(sly.pick)
        assertEquals("tmdb=film:5", sly.title)
    }

    @Test
    fun `picking lifts a wrong match, a wrong match drops the pick, reset keeps the rest`() {
        val wrong = VideoCorrection(wrongMatch = true, title = "X")
        assertEquals(VideoCorrection(title = "X", pick = PosterPick(5)), wrong.withPick(PosterPick(5)))
        assertEquals(VideoCorrection(title = "X", wrongMatch = true), VideoCorrection(title = "X", pick = PosterPick(5)).withWrongMatch(true))
        assertEquals(VideoCorrection(title = "X", pick = PosterPick(5)), VideoCorrection(title = "X", pick = PosterPick(5)).withWrongMatch(false))
        val all = VideoCorrection(separate = true, wrongMatch = true, title = "X", pick = PosterPick(5))
        assertEquals(VideoCorrection(separate = true, title = "X"), all.resetMatch())
        assertTrue(VideoCorrection(pick = PosterPick(5)).resetMatch().isEmpty)
    }

    @Test
    fun `other files of the same title in the chat are found, copies and extras are not`() {
        fun file(id: Long, name: String, chat: Long = 7) = MediaItem(
            chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 1, durationSec = 60, mimeType = "video/x-matroska",
            thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(), fileName = name, caption = "",
        )
        val film = file(1, "Idiot.2022.1080p.WEB-DL.mkv")
        val sameFilm = file(2, "Idiot (2022) 720p.mp4")
        val otherYear = file(3, "Idiot.2019.1080p.mkv")
        val otherChat = file(4, "Idiot.2022.mkv", chat = 8)
        val other = file(5, "Something Else 2022.mkv")
        assertEquals(listOf(sameFilm.id), VideoCorrections.sameTitleIn(film, listOf(film, sameFilm, otherYear, otherChat, other)).map { it.id })
        val episode = file(10, "Harbour.Notes.S01E01.mkv")
        val episodes = (2..4).map { file(10L + it, "Harbour.Notes.S01E0$it.mkv") }
        assertEquals(episodes.map { it.id }, VideoCorrections.sameTitleIn(episode, listOf(episode) + episodes + listOf(film)).map { it.id })
        assertTrue(VideoCorrections.sameTitleIn(film, emptyList()).isEmpty())
    }

    @Test
    fun `stored names stand for message ids, channels' negative ones too`() {
        assertEquals("fix_-1001234_56", VideoCorrections.keyName(-1001234, 56))
        assertEquals("-1001234:56", VideoCorrections.idOf("fix_-1001234_56"))
        assertNull(VideoCorrections.idOf("fix_nonsense"))
        assertNull(VideoCorrections.idOf("topic_5"))
    }

    @Test
    fun `corrections and a chat's sort travel in a backup`() {
        val settings = mapOf<String, Any>(
            "fix_-1001234_56" to "separate",
            "vsort_-1001234" to "Name",
            "vfilter_-1001234" to "Unwatched",
        )
        val backup = Backups.collect(settings, emptyMap(), MetaSettings(), createdAt = 0, appVersion = "test")
        assertEquals("separate", backup.memory["fix_-1001234_56"])
        assertEquals("Name", backup.memory["vsort_-1001234"])
        assertEquals("Unwatched", backup.memory["vfilter_-1001234"])
    }

    @Test
    fun `a poster pick travels in a backup and reads back after a restore`() {
        val stored = VideoCorrection(wrongMatch = false, title = "Idiot", pick = PosterPick(321, show = true)).encode()
        val settings = mapOf<String, Any>("fix_-1001234_56" to stored, "fix_-1001234_57" to "tmdb=film:oops")
        val backup = Backups.collect(settings, emptyMap(), MetaSettings(), createdAt = 0, appVersion = "test")
        assertEquals(stored, backup.memory["fix_-1001234_56"])
        val restored = (Backups.decode(Backups.encode(backup)) as BackupRead.Ok).backup
        assertEquals(PosterPick(321, show = true), VideoCorrection.decode(restored.memory["fix_-1001234_56"] as? String).pick)
        // A hand-edited value that is no pick restores as no correction rather than breaking the restore.
        assertTrue(VideoCorrection.decode(restored.memory["fix_-1001234_57"] as? String).isEmpty)
    }
}
