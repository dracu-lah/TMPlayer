package com.tmplayer.desktop.player

import com.tmplayer.data.ResumeState
import com.tmplayer.desktop.os.UserDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Chapter navigation, A-B repeat, screenshot names and the resume state, without mpv. */
class PlayerExtrasTest {

    private val chapters = listOf(Chapter(0, "Opening"), Chapter(120_000, "The Station"), Chapter(300_000, null))

    @Test
    fun `next goes to the following mark, and nowhere from the last chapter`() {
        assertEquals(1, Chapters.next(chapters, 0))
        assertEquals(2, Chapters.next(chapters, 130_000))
        assertNull(Chapters.next(chapters, 310_000))
        // A seek that landed just short of a mark still counts as in that chapter.
        assertEquals(2, Chapters.next(chapters, 119_500))
    }

    @Test
    fun `previous restarts the chapter, or steps back when its start was just passed`() {
        assertEquals(1, Chapters.previous(chapters, 200_000))
        assertEquals(0, Chapters.previous(chapters, 121_000))
        assertEquals(0, Chapters.previous(chapters, 1_000))
        assertNull(Chapters.previous(emptyList(), 50_000))
        // A file whose first mark is not at zero: before it there is nothing to go back to.
        assertNull(Chapters.previous(listOf(Chapter(60_000)), 10_000))
    }

    @Test
    fun `labels count the chapters and add a name worth reading`() {
        assertEquals("Chapter 2 of 3: The Station", Chapters.label(chapters, 1))
        assertEquals("Chapter 3 of 3", Chapters.label(chapters, 2))
        assertEquals("Chapter 1 of 1", Chapters.label(listOf(Chapter(0, "Chapter 01")), 0))
        assertEquals(2, Chapters.at(chapters, 400_000))
        assertEquals(-1, Chapters.at(listOf(Chapter(60_000)), 0))
    }

    @Test
    fun `A-B repeat runs start, end, off`() {
        val a = AbLoop.press(null, 30_000)!!
        assertFalse(a.complete)
        val ab = AbLoop.press(a, 90_000)!!
        assertEquals(AbLoop(30_000, 90_000), ab)
        assertTrue(ab.complete)
        assertNull(AbLoop.press(ab, 100_000))
    }

    @Test
    fun `A-B marks go in time order, and a second press on the first mark waits`() {
        assertEquals(AbLoop(30_000, 90_000), AbLoop.press(AbLoop(90_000), 30_000))
        val start = AbLoop(30_000)
        assertEquals(start, AbLoop.press(start, 30_200))
    }

    @Test
    fun `screenshot names carry the title and the moment, and never overwrite`() {
        val dir = File("/shots")
        assertEquals(File(dir, "Night Train 01-05.png"), ScreenshotFiles.fileFor(dir, "Night Train.mkv", 65_000) { false })
        assertEquals(File(dir, "Night Train 1-02-03.png"), ScreenshotFiles.fileFor(dir, "Night Train", 3_723_000) { false })
        val taken = setOf(File(dir, "Night Train 01-05.png"), File(dir, "Night Train 01-05 (2).png"))
        assertEquals(File(dir, "Night Train 01-05 (3).png"), ScreenshotFiles.fileFor(dir, "Night Train", 65_000) { it in taken })
    }

    @Test
    fun `screenshot names leave out what a file system refuses`() {
        assertEquals("Mr. Robot S01E01 what now", ScreenshotFiles.safeName("Mr. Robot: S01E01 what/now?"))
        assertEquals("TMPlayer", ScreenshotFiles.safeName("???"))
        assertEquals("Ends with a dot", ScreenshotFiles.safeName("Ends with a dot."))
        assertTrue(ScreenshotFiles.safeName("x".repeat(300)).length <= 80)
    }

    @Test
    fun `the resume state counts each track's place among its kind`() {
        fun t(id: Int, type: TrackType, lang: String?, selected: Boolean) =
            MediaTrack(id, type, null, lang, null, null, selected, isDefault = false, external = false)
        val tracks = listOf(
            t(1, TrackType.Video, null, true),
            t(1, TrackType.Audio, "eng", false),
            t(2, TrackType.Audio, "eng", true),
            t(1, TrackType.Subtitle, "eng", false),
            t(2, TrackType.Subtitle, "mal", true),
        )
        assertEquals(
            ResumeState(audioTrack = 1, audioLanguage = "eng", subtitleTrack = 1, subtitleLanguage = "mal", speed = 1.25f),
            resumeState(tracks, 1.25f),
        )
        val off = tracks.map { if (it.type == TrackType.Subtitle) it.copy(selected = false) else it }
        assertEquals(ResumeState.SUBTITLES_OFF, resumeState(off, 1f).subtitleTrack)
        assertNull(resumeState(tracks.filter { it.type != TrackType.Subtitle }, 1f).subtitleTrack)
    }

    @Test
    fun `the pictures folder is read from user-dirs like the downloads folder`() {
        val home = File("/home/viewer")
        val text = "XDG_DOWNLOAD_DIR=\"\$HOME/Downloads\"\nXDG_PICTURES_DIR=\"\$HOME/Bilder\"\n"
        assertEquals(File(home, "Bilder"), UserDirs.parseUserDirs(text, home, UserDirs.PICTURES_KEY))
        assertEquals(File("/mnt/pics"), UserDirs.xdgDir(mapOf(UserDirs.PICTURES_KEY to "/mnt/pics"), home, UserDirs.PICTURES_KEY))
    }
}
