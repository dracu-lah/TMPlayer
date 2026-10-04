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
                PlayerMenuEntry.PictureInPicture,
                PlayerMenuEntry.OpenInAnotherApp,
                PlayerMenuEntry.RemoteKeys,
            ),
            PlayerMenu.tvEntries(pictureInPicture = true),
        )
    }

    @Test
    fun `picture in picture is only offered where the device can do it`() {
        val entries = PlayerMenu.tvEntries(pictureInPicture = false)
        assertFalse(PlayerMenuEntry.PictureInPicture in entries)
        assertEquals(PlayerMenuEntry.entries.size - 1, entries.size)
    }

    @Test
    fun `every entry is reachable on a device with picture in picture`() {
        assertEquals(PlayerMenuEntry.entries.toSet(), PlayerMenu.tvEntries(pictureInPicture = true).toSet())
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
