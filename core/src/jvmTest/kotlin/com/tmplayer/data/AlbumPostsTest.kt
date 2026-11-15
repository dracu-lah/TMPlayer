package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An album that posts a photo with a caption and some videos reads as one post (EchoGram B11, B16):
 * the photo's caption and picture go to the videos, and a hit on the photo leads to the videos.
 */
class AlbumPostsTest {

    private val chat = 5L

    private fun video(
        message: Long,
        album: Long,
        name: String = "",
        caption: String = "",
        cover: Boolean = false,
        frame: Int = 900,
    ) = MediaItem(
        chatId = chat, messageId = message, fileId = message.toInt() + 100,
        title = MediaText.displayTitle(name, caption, "Video"), sizeBytes = 1_200L * 1024 * 1024, durationSec = 3_000,
        mimeType = "video/mp4", thumbnailFileId = if (cover) 700 else frame, miniThumbnail = byteArrayOf(1, 2),
        date = 1_700_000_000, fileName = name, caption = caption, albumId = album, hasCover = cover,
    )

    private fun photo(message: Long, album: Long, caption: String = "", file: Int = 500) =
        AlbumPhoto(chat, album, message, file, byteArrayOf(9), caption)

    private fun photos(vararg p: AlbumPhoto) = AlbumPosts.pick(p.toList())

    @Test
    fun photoAndOneVideoReadAsOnePost() {
        val v = video(11, 7, name = "video.mp4")
        val out = AlbumPosts.fold(listOf(v), photos(photo(10, 7, caption = "Harbour Notes S01E01")))
        assertEquals("Harbour Notes S01E01", out.single().caption)
        assertEquals("Harbour Notes S01E01", out.single().title)
        assertEquals(500, out.single().thumbnailFileId)
        assertEquals(500, out.single().albumPhotoFileId)
        assertEquals(listOf<Byte>(9), out.single().miniThumbnail?.toList())
    }

    @Test
    fun photoAndThreeEpisodesAllTakeTheCaptionAndThePicture() {
        val items = listOf(
            video(11, 7, name = "Harbour.Notes.S01E01.mkv"),
            video(12, 7, name = "Harbour.Notes.S01E02.mkv"),
            video(13, 7, name = "Harbour.Notes.S01E03.mkv"),
        )
        val out = AlbumPosts.fold(items, photos(photo(10, 7, caption = "Harbour Notes, season 1")))
        assertTrue(out.all { it.caption == "Harbour Notes, season 1" && it.thumbnailFileId == 500 })
        // The names stay what the files say.
        assertEquals(listOf("Harbour.Notes.S01E01.mkv", "Harbour.Notes.S01E02.mkv", "Harbour.Notes.S01E03.mkv"), out.map { it.title })
    }

    @Test
    fun theVideosOwnCaptionKeepsItsNameParsing() {
        val v = video(11, 7, name = "video.mp4", caption = "Night Lantern 2022")
        val out = AlbumPosts.fold(listOf(v), photos(photo(10, 7, caption = "Something else")))
        assertEquals("Night Lantern 2022", out.single().caption)
        assertEquals("Night Lantern 2022", out.single().title)
        // The picture still comes: the caption and the picture are two ladders.
        assertEquals(500, out.single().thumbnailFileId)
    }

    @Test
    fun theUploadersOwnCoverBeatsTheAlbumPhoto() {
        val v = video(11, 7, name = "video.mp4", cover = true)
        val out = AlbumPosts.fold(listOf(v), photos(photo(10, 7, caption = "Harbour Notes")))
        assertEquals(700, out.single().thumbnailFileId)
        assertEquals(0, out.single().albumPhotoFileId)
        // Its caption is still the album's.
        assertEquals("Harbour Notes", out.single().caption)
    }

    @Test
    fun captionsOnTwoMembersTheEarliestWinsForTheOnesWithout() {
        val a = video(11, 7, name = "Harbour.Notes.S01E02.mkv", caption = "Second words")
        val b = video(12, 7, name = "Harbour.Notes.S01E03.mkv")
        // The photo is the earlier message: its caption is the album's.
        val first = AlbumPosts.fold(listOf(a, b), photos(photo(10, 7, caption = "First words")))
        assertEquals(listOf("Second words", "First words"), first.map { it.caption })
        // A video earlier than the photo wins.
        val early = video(9, 7, name = "Harbour.Notes.S01E01.mkv", caption = "Video words")
        val second = AlbumPosts.fold(listOf(early, b), photos(photo(10, 7, caption = "Photo words")))
        assertEquals(listOf("Video words", "Video words"), second.map { it.caption })
    }

    @Test
    fun albumIdZeroIsNeverAnAlbum() {
        val v = video(11, 0, name = "video.mp4")
        val items = listOf(v)
        assertSame(items, AlbumPosts.fold(items, photos(photo(10, 0, caption = "x"))))
        assertTrue(photos(photo(10, 0, caption = "x")).isEmpty())
    }

    @Test
    fun aPhotoOnlyAlbumLeavesNoVideoToChange() {
        val other = video(30, 99, name = "other.mkv")
        val items = listOf(other)
        assertSame(items, AlbumPosts.fold(items, photos(photo(10, 7, caption = "Only a photo"))))
    }

    @Test
    fun theVideoMayComeBeforeThePhoto() {
        val v = video(9, 7, name = "video.mp4")
        val out = AlbumPosts.fold(listOf(v), photos(photo(10, 7, caption = "Posted video first")))
        assertEquals("Posted video first", out.single().caption)
        assertEquals(500, out.single().thumbnailFileId)
    }

    @Test
    fun anAlbumOfTwoDifferentShowsIsLeftAlone() {
        val a = video(11, 7, name = "Harbour.Notes.S01E01.mkv")
        val b = video(12, 7, name = "Night.Lantern.2022.1080p.mkv")
        val items = listOf(a, b)
        assertEquals(items, AlbumPosts.fold(items, photos(photo(10, 7, caption = "Harbour Notes"))))
    }

    @Test
    fun aPhotoWithoutASizeStillGivesItsCaption() {
        val v = video(11, 7, name = "video.mp4")
        val out = AlbumPosts.fold(listOf(v), photos(photo(10, 7, caption = "Sizes missing", file = 0)))
        assertEquals("Sizes missing", out.single().caption)
        assertEquals(900, out.single().thumbnailFileId)
        assertEquals(0, out.single().albumPhotoFileId)
    }

    @Test
    fun pickTakesTheFirstPictureAndTheFirstCaption() {
        val picked = photos(photo(12, 7, caption = "Words", file = 502), photo(10, 7, file = 500), photo(11, 7, file = 501))
        val one = picked.getValue(7)
        assertEquals(500, one.fileId)
        assertEquals("Words", one.caption)
        assertEquals(12, one.messageId)
    }

    @Test
    fun anAlbumOfTenPhotosAndNoVideoChangesNothing() {
        val ten = (1L..10L).map { photo(it, 7, caption = if (it == 1L) "Ten photos" else "", file = 400 + it.toInt()) }
        val items = listOf(video(50, 8, name = "unrelated.mkv"))
        assertSame(items, AlbumPosts.fold(items, AlbumPosts.pick(ten)))
    }

    @Test
    fun foldingIsSafeToRepeat() {
        val v = video(11, 7, name = "video.mp4")
        val once = AlbumPosts.fold(listOf(v), photos(photo(10, 7, caption = "Again")))
        val twice = AlbumPosts.fold(once, photos(photo(10, 7, caption = "Again")))
        assertEquals(once, twice)
    }

    // ---- hits: one read per album ----

    private class Reader(val windows: Map<Long, AlbumWindow>, val failures: ArrayDeque<Exception> = ArrayDeque()) : AlbumReader {
        val reads = ArrayList<Pair<Long, Long>>()
        override suspend fun read(chatId: Long, anchorMessageId: Long): AlbumWindow {
            reads += chatId to anchorMessageId
            failures.removeFirstOrNull()?.let { throw it }
            return windows.values.firstOrNull { w -> w.items.any { it.messageId == anchorMessageId } || w.photo?.messageId == anchorMessageId }
                ?: AlbumWindow(emptyList(), null)
        }
    }

    private fun window(album: Long, p: AlbumPhoto, vararg videos: MediaItem): AlbumWindow {
        val folded = AlbumPosts.fold(videos.toList(), AlbumPosts.pick(listOf(p)))
        return AlbumWindow(folded, p)
    }

    @Test
    fun aHitOnThePhotoLeadsToTheAlbumsVideos() = runBlocking {
        val p = photo(10, 7, caption = "Harbour Notes S01")
        val reader = Reader(mapOf(7L to window(7, p, video(11, 7, name = "video.mp4"), video(12, 7, name = "video2.mp4"))))
        val out = AlbumPosts.enrich(emptyList(), listOf(p), reader)
        assertEquals(listOf(11L, 12L), out.map { it.messageId })
        assertTrue(out.all { it.caption == "Harbour Notes S01" && it.albumPhotoFileId == 500 })
        assertEquals(1, reader.reads.size)
    }

    @Test
    fun aVideoHitGetsItsPhotoWithOneRead() = runBlocking {
        val p = photo(10, 7, caption = "Harbour Notes S01")
        val hit = video(11, 7, name = "video.mp4")
        val reader = Reader(mapOf(7L to window(7, p, hit)))
        val out = AlbumPosts.enrich(listOf(hit), emptyList(), reader)
        assertEquals("Harbour Notes S01", out.single().caption)
        assertEquals(500, out.single().thumbnailFileId)
        assertEquals(listOf(chat to 11L), reader.reads)
    }

    @Test
    fun aPhotoHitWithNoCaptionAddsNoVideo() = runBlocking {
        val p = photo(10, 7, caption = "")
        val reader = Reader(mapOf(7L to window(7, p, video(11, 7, name = "video.mp4"))))
        assertEquals(emptyList(), AlbumPosts.enrich(emptyList(), listOf(p), reader))
    }

    @Test
    fun aMixedAlbumsPhotoHitAddsNothing() = runBlocking {
        val p = photo(10, 7, caption = "Harbour Notes")
        val reader = Reader(mapOf(7L to window(7, p, video(11, 7, name = "Harbour.Notes.S01E01.mkv"), video(12, 7, name = "Night.Lantern.2022.1080p.mkv"))))
        assertEquals(emptyList(), AlbumPosts.enrich(emptyList(), listOf(p), reader))
    }

    @Test
    fun anAlbumWithTheVideoAlreadyListedIsNotListedTwice() = runBlocking {
        val p = photo(10, 7, caption = "Harbour Notes")
        val v = video(11, 7, name = "video.mp4")
        val reader = Reader(mapOf(7L to window(7, p, v, video(12, 7, name = "video2.mp4"))))
        val out = AlbumPosts.enrich(listOf(v), listOf(p), reader)
        assertEquals(listOf(11L, 12L), out.map { it.messageId })
        assertEquals(1, reader.reads.size)
    }

    @Test
    fun readsAreBoundedPerPageAndOnePerAlbum() = runBlocking {
        // Twenty albums of three videos on the page: one read each for six, none per item.
        val items = (1L..20L).flatMap { a -> (0L..2L).map { video(a * 100 + it, a, name = "video${a}_$it.mp4") } }
        val reader = Reader(emptyMap())
        AlbumPosts.enrich(items, emptyList(), reader)
        assertEquals(AlbumPosts.ALBUMS_PER_PAGE, reader.reads.size)
        assertEquals(reader.reads.size, reader.reads.toSet().size)
    }

    @Test
    fun aListingWithNoQueryIsNotEnrichedHere() = runBlocking {
        // enrich is only ever called for hits; with nothing to read it answers at once.
        val items = listOf(video(11, 0, name = "alone.mkv"))
        val reader = Reader(emptyMap())
        assertSame(items, AlbumPosts.enrich(items, emptyList(), reader))
        assertEquals(0, reader.reads.size)
    }

    @Test
    fun aFailedReadLeavesTheHitAsItWas() = runBlocking {
        val v = video(11, 7, name = "video.mp4")
        val reader = Reader(emptyMap(), ArrayDeque(listOf(IllegalStateException("boom"))))
        val out = AlbumPosts.enrich(listOf(v), emptyList(), reader)
        assertEquals(listOf(v), out)
    }

    @Test
    fun aFloodWaitIsWaitedOutOnceByThatReadAlone() = runBlocking {
        val p = photo(10, 7, caption = "Harbour Notes")
        val hit = video(11, 7, name = "video.mp4")
        val reader = Reader(mapOf(7L to window(7, p, hit)), ArrayDeque(listOf(RuntimeException("FLOOD_WAIT_12"))))
        val waited = ArrayList<Long>()
        val out = AlbumPosts.enrich(listOf(hit), emptyList(), reader) { waited += it }
        assertEquals(listOf(12_250L), waited)
        assertEquals("Harbour Notes", out.single().caption)
        assertEquals(2, reader.reads.size)
    }

    @Test
    fun aLongFloodWaitIsNotWaitedOut() = runBlocking {
        val hit = video(11, 7, name = "video.mp4")
        val reader = Reader(emptyMap(), ArrayDeque(listOf(RuntimeException("FLOOD_WAIT_600"))))
        val waited = ArrayList<Long>()
        val out = AlbumPosts.enrich(listOf(hit), emptyList(), reader) { waited += it }
        assertEquals(emptyList(), waited)
        assertEquals(listOf(hit), out)
        assertNotEquals(0, reader.reads.size)
    }

    // ---- persisted forms ----

    @Test
    fun theAlbumPhotoAndCoverSurviveTheHomeCache() {
        val folded = AlbumPosts.fold(listOf(video(11, 7, name = "video.mp4")), photos(photo(10, 7, caption = "Kept")))
        val withCover = video(12, 7, name = "other.mp4", cover = true)
        val text = HomeCache.encode(HomeCache.Snapshot(mapOf(chat to folded + withCover), null, emptySet(), emptyMap(), 1L))
        val back = HomeCache.decode(text)!!.loaded.getValue(chat)
        assertEquals(500, back[0].albumPhotoFileId)
        assertEquals(500, back[0].thumbnailFileId)
        assertEquals("Kept", back[0].caption)
        assertTrue(back[1].hasCover)
        assertEquals(0, back[1].albumPhotoFileId)
    }

    @Test
    fun anOlderHomeCacheWithoutTheKeysReadsAsNoPhotoAndNoCover() {
        val old = video(11, 7, name = "video.mp4")
        val text = HomeCache.encode(HomeCache.Snapshot(mapOf(chat to listOf(old)), null, emptySet(), emptyMap(), 1L))
        // What a build before this one wrote has neither key: encode writes neither for an item without them.
        assertTrue("aph" !in text && "cov" !in text)
        val back = HomeCache.decode(text)!!.loaded.getValue(chat).single()
        assertEquals(0, back.albumPhotoFileId)
        assertTrue(!back.hasCover)
        assertEquals(900, back.thumbnailFileId)
    }

    @Test
    fun theGridOnTheDiskKeepsThemToo() {
        val folded = AlbumPosts.fold(listOf(video(11, 7, name = "video.mp4")), photos(photo(10, 7, caption = "Kept")))
        val json = HomeCache.itemJson(folded.single())
        assertEquals(500, HomeCache.itemOf(json)!!.albumPhotoFileId)
    }
}
