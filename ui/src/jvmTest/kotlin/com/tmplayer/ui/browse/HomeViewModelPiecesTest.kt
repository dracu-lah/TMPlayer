package com.tmplayer.ui.browse

import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.SplitPieces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A chat of videos kept in split ZIPs: Home reads the newest messages only, and the window ended
 * at Colony's second piece (the stick, 2026-11-14), so its row played "Part 1 of 18 is not in
 * this chat". Home now searches the chat for the rest of a set its window cut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPiecesTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        HomeViewModel.forgetPieceSearches()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun piece(set: String, n: Int, last: Int, date: Int, id: Long) = MediaItem(
        chatId = CHAT, messageId = id, fileId = id.toInt(), title = "$set.zip.%03d".format(n),
        sizeBytes = if (n == last) 3_769_561_462 else 4_194_304_000, durationSec = 0, mimeType = "application/zip",
        thumbnailFileId = 0, miniThumbnail = null, date = date, fileName = "$set.zip.%03d".format(n),
    )

    private val colony = (1..18).map { piece(COLONY, it, 18, 1_794_572_403, 1_000L + it) }
    private val avengers = (1..3).map { piece(AVENGERS, it, 3, 1_794_580_475, 2_000L + it) }

    /** What the newest messages hold: everything but Colony's first piece. */
    private val window = (avengers + colony.drop(1)).sortedByDescending { it.messageId }

    private var searches = 0

    private val source = object : HomeSource {
        override suspend fun chatMedia(chatId: Long) = window
        override suspend fun recentMedia() = window
        override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? = null
        override suspend fun searchPage(chatId: Long, topicId: Int, query: String, cursors: MediaCursors): MediaPage {
            searches++
            return MediaPage((avengers + colony).sortedByDescending { it.messageId }, cursors.copy(videoDone = true, documentDone = true, animationDone = true), endReached = true)
        }
    }

    @Test
    fun `a chat row and Recently added hold the whole set where the window cut it`() = runTest(dispatcher) {
        val model = HomeViewModel(source, disk = dispatcher)
        model.request(CHAT)
        model.requestRecent()
        advanceUntilIdle()
        for (items in listOf(model.loaded.value.getValue(CHAT), model.recent.value!!)) {
            assertEquals(21, items.size)
            assertTrue(items.any { it.id == colony[0].id })
            assertTrue(SplitPieces.openSets(items).isEmpty())
        }
    }

    @Test
    fun `a set searched for is not searched again on the next refresh`() = runTest(dispatcher) {
        val model = HomeViewModel(source, disk = dispatcher)
        model.request(CHAT)
        advanceUntilIdle()
        val first = searches
        assertTrue(first > 0)
        model.requestRecent()
        advanceUntilIdle()
        assertEquals(first, searches)
    }

    private companion object {
        const val CHAT = 7L
        const val COLONY = "Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil "
        const val AVENGERS = "Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL."
    }
}
