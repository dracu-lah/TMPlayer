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
                PlayerMenuEntry.PlaybackDetails,
                PlayerMenuEntry.StartOver,
                PlayerMenuEntry.Speed,
                PlayerMenuEntry.SaveToDownloads,
                PlayerMenuEntry.MarkWatched,
                PlayerMenuEntry.PictureInPicture,
                PlayerMenuEntry.OpenInAnotherApp,
                PlayerMenuEntry.RemoteKeys,
            ),
            PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true, markWatched = true),
        )
    }

    @Test
    fun `mark as watched is only offered for a video that came from a message`() {
        assertFalse(PlayerMenuEntry.MarkWatched in PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true))
        assertTrue(PlayerMenuEntry.MarkWatched in PlayerMenu.tvEntries(pictureInPicture = true, markWatched = true))
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
        val entries = PlayerMenu.tvEntries(pictureInPicture = false, saveToDownloads = true, markWatched = true)
        assertFalse(PlayerMenuEntry.PictureInPicture in entries)
        assertEquals(PlayerMenuEntry.entries.size - 1, entries.size)
    }

    @Test
    fun `every entry is reachable on a device with picture in picture`() {
        assertEquals(
            PlayerMenuEntry.entries.toSet(),
            PlayerMenu.tvEntries(pictureInPicture = true, saveToDownloads = true, markWatched = true).toSet(),
        )
    }

    @Test
    fun `the key table names every key the player answers, each once`() {
        val keys = RemoteKeys.ROWS.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        listOf("OK", "Back", "Hold OK, controls up", "OK on the time", "0 to 9").forEach {
            assertTrue("missing $it", it in keys)
        }
        assertTrue(RemoteKeys.ROWS.all { (key, does) -> key.isNotBlank() && does.isNotBlank() })
    }
}
