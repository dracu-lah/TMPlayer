package com.tmplayer.desktop.ui

import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Back from the player on the desktop stops the video and returns to the page that opened it, as
 * the phone does (T12). There is no bar that keeps a video playing over a page; Ctrl+P, the small
 * always on top window, is the player's own and not Back.
 */
class ShellBackFromPlayerTest {

    private val shell = ShellState(
        SettingsStore(SettingsStore.openDataStore(Files.createTempDirectory("tm-back").resolve(SettingsStore.FILE_NAME).toFile())),
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-back-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = { error("not used by these tests") },
    )

    private fun video(n: Long) = MediaItem(
        chatId = 2, messageId = n, fileId = 0, title = "Video $n", sizeBytes = 300L * 1024 * 1024,
        durationSec = 1500, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
        fileName = "Video $n.mkv",
    )

    @Test
    fun `back from a playing video closes the player and the page that opened it is what is left`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        assertTrue(shell.playerUp)
        shell.closePlayer()
        assertNull(shell.nowPlaying)
        assertFalse(shell.playerUp)
    }

    @Test
    fun `two quick backs close it once and leave nothing behind`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.closePlayer()
        shell.closePlayer()
        assertNull(shell.nowPlaying)
        assertFalse(shell.fullscreen)
    }

    @Test
    fun `back leaves fullscreen, as a close always did`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.fullscreen = true
        shell.closePlayer()
        assertFalse(shell.fullscreen)
    }

    @Test
    fun `another video replaces the open one in the same player, never a second`() {
        shell.openPlayer(video(1), startFromBeginning = false)
        shell.openPlayer(video(2), startFromBeginning = false)
        assertEquals(2L, shell.nowPlaying?.item?.messageId)
        assertTrue(shell.playerUp)
    }

    @Test
    fun `no dock code is left on the desktop`() {
        val dir = java.io.File("src/main/kotlin/com/tmplayer/desktop")
        val left = dir.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { "MiniPlayerState" in it || "MiniBarHost" in it || "MiniLink" in it || "MiniPhase" in it } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), left)
    }
}
