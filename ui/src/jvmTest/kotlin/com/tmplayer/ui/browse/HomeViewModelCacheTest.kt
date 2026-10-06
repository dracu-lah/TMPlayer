package com.tmplayer.ui.browse

import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaItem.Locality
import com.tmplayer.data.ResumeRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Home's Cached badges follow the watch cache after the rows have been fetched. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelCacheTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: Long, cached: Boolean) = MediaItem(
        chatId = 7, messageId = id, fileId = id.toInt(), title = "Video $id", sizeBytes = 600L * 1024 * 1024,
        durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
        onDevice = cached,
    )

    /** The one cached video, as the disk has it now. */
    private var onDisk = 1L

    private val source = object : HomeSource {
        override suspend fun chatMedia(chatId: Long) = listOf(video(1, cached = true), video(2, cached = false))
        override suspend fun recentMedia() = listOf(video(1, cached = true), video(2, cached = false))
        override suspend fun mediaItem(chatId: Long, messageId: Long) = video(messageId, cached = messageId == 1L)
        override suspend fun wholeOnDevice(item: MediaItem) = item.messageId == onDisk
    }

    @Test
    fun `playing another video moves the Cached badge to it`() = runTest(dispatcher) {
        val model = HomeViewModel(source)
        model.request(7)
        model.requestRecent()
        model.requestArt(
            ResumeRecord(
                chatId = 7, messageId = 1, fileId = 1, title = "Video 1", chatTitle = "Chat", sizeBytes = 1,
                durationSec = 60, positionMs = 1000, durationMs = 60_000, updatedAt = 0,
            ),
        )
        advanceUntilIdle()
        assertEquals(Locality.Cached, model.recent.value!!.first { it.messageId == 1L }.locality)

        // The second video played and the one-video cache let the first go.
        onDisk = 2
        model.refreshLocalAvailability()
        advanceUntilIdle()

        fun localities(items: List<MediaItem>) = items.associate { it.messageId to it.locality }
        val expected = mapOf(1L to Locality.Remote, 2L to Locality.Cached)
        assertEquals(expected, localities(model.recent.value!!))
        assertEquals(expected, localities(model.loaded.value.getValue(7)))
        assertEquals(Locality.Remote, model.art.value.values.single().locality)
    }
}
