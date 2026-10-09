package com.tmplayer.desktop

import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WatchedWordsTest {

    @Test
    fun `the mark line reads the other way once the video is on the list`() {
        assertEquals("Mark as watched", WatchedWords.markLabel(onList = false))
        assertEquals("Mark as unwatched", WatchedWords.markLabel(onList = true))
    }

    @Test
    fun `a finished poster runs a full bar unless a second viewing is part way`() {
        assertNull(WatchedWords.posterProgress(null, finished = false))
        assertNull(WatchedWords.posterProgress(0f, finished = false))
        assertEquals(0.3f, WatchedWords.posterProgress(0.3f, finished = false))
        assertEquals(1f, WatchedWords.posterProgress(null, finished = true))
        assertEquals(1f, WatchedWords.posterProgress(0f, finished = true))
        assertEquals(0.25f, WatchedWords.posterProgress(0.25f, finished = true))
    }

    @Test
    fun `Watched shows under the title only when nothing is part way`() {
        assertTrue(WatchedWords.showsWatched(null, finished = true))
        assertTrue(WatchedWords.showsWatched(0f, finished = true))
        assertFalse(WatchedWords.showsWatched(0.4f, finished = true))
        assertFalse(WatchedWords.showsWatched(null, finished = false))
    }

    @Test
    fun `marking forgets the position and unmarking takes it off the list`() = runBlocking {
        val dir = Files.createTempDirectory("tm-watched").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
        val item = MediaItem(
            chatId = 7, messageId = 42, fileId = 3, title = "Episode 1", sizeBytes = 1_000, durationSec = 1_200,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
        )
        settings.saveResumePosition(7, 42, 300_000, 1_200_000, "")
        assertEquals(300_000L, settings.resumePosition(7, 42))

        setWatched(watched, settings, item, "Film Club", watched = true, now = 1_000L)
        assertEquals(0L, settings.resumePosition(7, 42))
        val record = watched.watched.first()[SettingsStore.progressKey(7, 42)]
        assertEquals("Film Club", record?.chatTitle)
        assertEquals(true, record?.manual)
        assertEquals(1_000L, record?.watchedAt)

        setWatched(watched, settings, item, "Film Club", watched = false)
        assertTrue(watched.history.first().isEmpty())
    }
}
