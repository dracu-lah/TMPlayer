package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the television player's More menu offers, and the key table it can show. */
class PlayerMenuTest {

    @Test
    fun `the menu carries the phone overflow's actions and the remote keys, in a fixed order`() {
        assertEquals(
            listOf(
                PlayerMenuEntry.LockScreen,
                PlayerMenuEntry.PlaybackDetails,
                PlayerMenuEntry.StartOver,
                PlayerMenuEntry.Speed,
                PlayerMenuEntry.VolumeBoost,
                PlayerMenuEntry.NightMode,
                PlayerMenuEntry.EvenVolume,
                PlayerMenuEntry.SleepTimer,
                PlayerMenuEntry.AbLoop,
                PlayerMenuEntry.AddBookmark,
                PlayerMenuEntry.Bookmarks,
                PlayerMenuEntry.SaveToDownloads,
                PlayerMenuEntry.MarkWatched,
                PlayerMenuEntry.PictureInPicture,
                PlayerMenuEntry.OpenInAnotherApp,
                PlayerMenuEntry.RemoteKeys,
            ),
            PlayerMenu.tvEntries(
                pictureInPicture = true,
                saveToDownloads = true,
                markWatched = true,
                bookmarks = true,
            ),
        )
    }

    @Test
    fun `the frame steps are offered only while paused, after the repeat line`() {
        val paused = PlayerMenu.tvEntries(pictureInPicture = true, paused = true)
        val at = paused.indexOf(PlayerMenuEntry.AbLoop)
        assertEquals(
            listOf(PlayerMenuEntry.NextFrame, PlayerMenuEntry.PreviousFrame),
            paused.subList(at + 1, at + 3),
        )
        assertFalse(PlayerMenuEntry.NextFrame in PlayerMenu.tvEntries(pictureInPicture = true))
        assertFalse(PhoneMenuEntry.NextFrame in PlayerMenu.phoneEntries(true, true, true, true))
        assertTrue(PhoneMenuEntry.PreviousFrame in PlayerMenu.phoneEntries(true, true, true, true, paused = true))
    }

    @Test
    fun `the chapter steps come only with a chaptered file, after the repeat line`() {
        assertFalse(PlayerMenuEntry.NextChapter in PlayerMenu.tvEntries(pictureInPicture = true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, chapters = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.AbLoop) + 1, tv.indexOf(PlayerMenuEntry.NextChapter))
        assertFalse(PhoneMenuEntry.PreviousChapter in PlayerMenu.phoneEntries(true, true, true, true))
        assertTrue(PhoneMenuEntry.PreviousChapter in PlayerMenu.phoneEntries(true, true, true, true, chapters = true))
    }

    @Test
    fun `lock the screen is the first line on the phone and the television alike`() {
        assertEquals(PhoneMenuEntry.LockScreen, PlayerMenu.phoneEntries(false, false, false, false).first())
        assertEquals(PlayerMenuEntry.LockScreen, PlayerMenu.tvEntries(pictureInPicture = false, openInAnotherApp = false).first())
        assertEquals("Lock the screen", PhoneMenuEntry.LockScreen.label())
        assertTrue(RemoteKeys.ROWS.any { it.first == "Hold OK, screen locked" })
    }

    @Test
    fun `the repeat line says what choosing it does next`() {
        assertEquals("Repeat: set the start (A)", PlayerMenu.loopLabel(null))
        assertEquals("Repeat: set the end (B)", PlayerMenu.loopLabel(AbLoop(10_000)))
        assertEquals("Stop repeating", PlayerMenu.loopLabel(AbLoop(10_000, 20_000)))
    }

    @Test
    fun `the episode steps are left to the Episodes button and the media keys`() {
        assertEquals(PlayerMenuEntry.LockScreen, PlayerMenu.tvEntries(pictureInPicture = true).first())
        assertFalse(PlayerMenuEntry.entries.any { it.name.contains("Episode") })
    }

    @Test
    fun `mark as watched is only offered for a video that came from a message`() {
        assertFalse(PlayerMenuEntry.MarkWatched in PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true))
        assertTrue(PlayerMenuEntry.MarkWatched in PlayerMenu.tvEntries(pictureInPicture = true, markWatched = true))
    }

    @Test
    fun `bookmarks are only offered for a video that came from a message`() {
        assertFalse(PlayerMenuEntry.AddBookmark in PlayerMenu.tvEntries(pictureInPicture = true, markWatched = true))
        assertTrue(PlayerMenuEntry.Bookmarks in PlayerMenu.tvEntries(pictureInPicture = true, bookmarks = true))
        assertFalse(PhoneMenuEntry.AddBookmark in PlayerMenu.phoneEntries(true, true, true, true))
    }

    @Test
    fun `save to downloads is only offered for a video that is not a download already`() {
        assertFalse(PlayerMenuEntry.SaveToDownloads in PlayerMenu.tvEntries(pictureInPicture = true))
        assertTrue(PlayerMenuEntry.SaveToDownloads in PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true))
    }

    @Test
    fun `open in another app is left off a video that may not be saved`() {
        assertFalse(
            PlayerMenuEntry.OpenInAnotherApp in
                PlayerMenu.tvEntries(pictureInPicture = true, openInAnotherApp = false),
        )
        assertTrue(PlayerMenuEntry.OpenInAnotherApp in PlayerMenu.tvEntries(pictureInPicture = true))
    }

    @Test
    fun `picture in picture is only offered where the device can do it`() {
        val entries = PlayerMenu.tvEntries(
            pictureInPicture = false,
            saveToDownloads = true,
            markWatched = true,
            paused = true,
            bookmarks = true,
            chapters = true,
            stereo = true,
            pictureShape = true,
        )
        assertFalse(PlayerMenuEntry.PictureInPicture in entries)
        assertEquals(PlayerMenuEntry.entries.size - 1, entries.size)
    }

    @Test
    fun `every entry is reachable on a device with picture in picture`() {
        assertEquals(
            PlayerMenuEntry.entries.toSet(),
            PlayerMenu.tvEntries(
                pictureInPicture = true,
                saveToDownloads = true,
                markWatched = true,
                paused = true,
                bookmarks = true,
                chapters = true,
                stereo = true,
                pictureShape = true,
            ).toSet(),
        )
    }

    @Test
    fun `3D video sits by the picture's own lines and only for a picture`() {
        assertFalse(PlayerMenuEntry.Stereo in PlayerMenu.tvEntries(pictureInPicture = true))
        val tv = PlayerMenu.tvEntries(pictureInPicture = true, stereo = true)
        assertEquals(tv.indexOf(PlayerMenuEntry.StartOver) + 1, tv.indexOf(PlayerMenuEntry.Stereo))
        val shaped = PlayerMenu.tvEntries(pictureInPicture = true, stereo = true, pictureShape = true)
        assertEquals(shaped.indexOf(PlayerMenuEntry.PictureShape) + 1, shaped.indexOf(PlayerMenuEntry.Stereo))
        val phone = PlayerMenu.phoneEntries(true, true, true, true, pictureShape = true, stereo = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.PictureShape) + 1, phone.indexOf(PhoneMenuEntry.Stereo))
        assertFalse(PhoneMenuEntry.Stereo in PlayerMenu.phoneEntries(true, true, true, true))
    }

    @Test
    fun `the phone's panel reads in a fixed order and carries no start over`() {
        assertEquals(
            listOf(
                PhoneMenuEntry.LockScreen,
                PhoneMenuEntry.PictureInPicture,
                PhoneMenuEntry.Speed,
                PhoneMenuEntry.VolumeBoost,
                PhoneMenuEntry.NightMode,
                PhoneMenuEntry.EvenVolume,
                PhoneMenuEntry.SleepTimer,
                PhoneMenuEntry.AbLoop,
                PhoneMenuEntry.NextChapter,
                PhoneMenuEntry.PreviousChapter,
                PhoneMenuEntry.NextFrame,
                PhoneMenuEntry.PreviousFrame,
                PhoneMenuEntry.SaveFrame,
                PhoneMenuEntry.AddBookmark,
                PhoneMenuEntry.Bookmarks,
                PhoneMenuEntry.OpenInAnotherApp,
                PhoneMenuEntry.LoadSubtitleFile,
                PhoneMenuEntry.SaveToDownloads,
                PhoneMenuEntry.MarkWatched,
                PhoneMenuEntry.PlaybackDetails,
            ),
            PlayerMenu.phoneEntries(
                pictureInPicture = true,
                openInAnotherApp = true,
                saveToDownloads = true,
                markWatched = true,
                paused = true,
                bookmarks = true,
                chapters = true,
                saveFrame = true,
            ),
        )
        assertEquals(PhoneMenuEntry.entries.toSet(), PlayerMenu.phoneEntries(true, true, true, true, paused = true, bookmarks = true, chapters = true, saveFrame = true, pictureShape = true, stereo = true).toSet())
    }

    @Test
    fun `picture shape is in the phone's panel for any picture, before the speed`() {
        assertFalse(PhoneMenuEntry.PictureShape in PlayerMenu.phoneEntries(true, true, true, true))
        val picture = PlayerMenu.phoneEntries(true, true, true, true, pictureShape = true)
        // After the lock and picture in picture, the other lines about the screen itself.
        assertEquals(2, picture.indexOf(PhoneMenuEntry.PictureShape))
        assertEquals(3, picture.indexOf(PhoneMenuEntry.Speed))
        assertEquals("Picture shape (Fill)", PhoneMenuEntry.PictureShape.label(shape = VideoScale.Crop))
    }

    @Test
    fun `the television's panel offers every shape as a page, for a picture only`() {
        val all = PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true, markWatched = true, paused = true, bookmarks = true, chapters = true)
        assertFalse(PlayerMenuEntry.PictureShape in all)
        val shaped = PlayerMenu.tvEntries(pictureInPicture = true, pictureShape = true)
        assertEquals(shaped.indexOf(PlayerMenuEntry.StartOver) + 1, shaped.indexOf(PlayerMenuEntry.PictureShape))
        assertTrue(PlayerMenuEntry.Speed in all)
    }

    @Test
    fun `the phone's and the television's lines share the panel's names`() {
        // The panel maps both by name onto one set of lines, so a new entry needs a line there.
        val names = (PhoneMenuEntry.entries.map { it.name } + PlayerMenuEntry.entries.map { it.name }).toSet()
        assertTrue(names.containsAll(listOf("LockScreen", "PictureShape", "Speed", "Stereo", "SleepTimer", "Bookmarks")))
    }

    @Test
    fun `save a frame is left off a video from a chat that restricts saving`() {
        assertFalse(PhoneMenuEntry.SaveFrame in PlayerMenu.phoneEntries(true, false, true, true))
        assertTrue(PhoneMenuEntry.SaveFrame in PlayerMenu.phoneEntries(true, true, true, true, saveFrame = true))
        assertEquals("Save a frame", PhoneMenuEntry.SaveFrame.label())
    }

    @Test
    fun `the phone overflow leaves out what the device or the video cannot do`() {
        val bare = PlayerMenu.phoneEntries(
            pictureInPicture = false,
            openInAnotherApp = false,
            saveToDownloads = false,
            markWatched = false,
        )
        assertEquals(
            listOf(
                PhoneMenuEntry.LockScreen,
                PhoneMenuEntry.Speed,
                PhoneMenuEntry.VolumeBoost,
                PhoneMenuEntry.NightMode,
                PhoneMenuEntry.EvenVolume,
                PhoneMenuEntry.SleepTimer,
                PhoneMenuEntry.AbLoop,
                PhoneMenuEntry.LoadSubtitleFile,
                PhoneMenuEntry.PlaybackDetails,
            ),
            bare,
        )
    }

    @Test
    fun `the key table names every key the player answers, each once`() {
        val keys = RemoteKeys.ROWS.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        listOf("OK", "Play / Pause", "Next / Previous", "Back", "Hold OK, controls up", "OK on the time", "0 to 9", "Up, after a long jump").forEach {
            assertTrue("missing $it", it in keys)
        }
        assertTrue(RemoteKeys.ROWS.all { (key, does) -> key.isNotBlank() && does.isNotBlank() })
    }
}
