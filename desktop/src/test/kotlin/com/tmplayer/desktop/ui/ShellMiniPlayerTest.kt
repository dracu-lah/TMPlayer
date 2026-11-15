package com.tmplayer.desktop.ui

import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.player.MiniContext
import com.tmplayer.player.MiniPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** What the shell does with the mini player (U8): dock, expand, replace, dismiss. */
class ShellMiniPlayerTest {

    private val shell = ShellState(
        SettingsStore(SettingsStore.openDataStore(Files.createTempDirectory("tm-mini").resolve(SettingsStore.FILE_NAME).toFile())),
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-mini-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = { error("not used by these tests") },
    )

    private fun video(n: Long) = MediaItem(
        chatId = 2, messageId = n, fileId = 0, title = "Video $n", sizeBytes = 300L * 1024 * 1024,
        durationSec = 1500, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
        fileName = "Video $n.mkv",
    )

    @Test
    fun `back docks the video, the page is in front and the player is still held`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        assertTrue(shell.playerUp)
        shell.leavePlayer(MiniContext())
        assertEquals(MiniPhase.Docked, shell.mini.phase)
        assertFalse(shell.playerUp)
        assertEquals(1L, shell.nowPlaying?.item?.messageId)
        assertTrue(shell.mini.barVisible)
    }

    @Test
    fun `a tap on the bar brings the player back, the same video`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.leavePlayer(MiniContext())
        shell.expandPlayer()
        assertTrue(shell.playerUp)
        assertEquals(1L, shell.nowPlaying?.item?.messageId)
    }

    @Test
    fun `the x closes it and frees the player`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.leavePlayer(MiniContext())
        shell.closePlayer()
        assertNull(shell.nowPlaying)
        assertFalse(shell.mini.holdsPlayer)
        assertNull(shell.miniBar)
    }

    @Test
    fun `back from a video that cannot dock closes it`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.leavePlayer(MiniContext(locked = true))
        assertNull(shell.nowPlaying)
        shell.openPlayer(video(2), startFromBeginning = false)
        shell.leavePlayer(MiniContext(casting = true))
        assertNull(shell.nowPlaying)
        shell.openPlayer(video(3), startFromBeginning = false)
        shell.leavePlayer(MiniContext(platformAllowed = false))
        assertNull(shell.nowPlaying)
    }

    @Test
    fun `another video while docked takes the player over, never a second one`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.leavePlayer(MiniContext())
        shell.openPlayer(video(2), startFromBeginning = false)
        assertEquals(MiniPhase.Full, shell.mini.phase)
        assertTrue(shell.playerUp)
        assertEquals(2L, shell.nowPlaying?.item?.messageId)
    }

    @Test
    fun `a cast hides the bar while the player is held, and it comes back`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.leavePlayer(MiniContext())
        shell.miniCasting(true)
        assertFalse(shell.mini.barVisible)
        assertTrue(shell.mini.holdsPlayer)
        shell.miniCasting(false)
        assertTrue(shell.mini.barVisible)
    }
}
