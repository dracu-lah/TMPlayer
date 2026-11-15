package com.tmplayer.player

import com.tmplayer.player.ChatSubtitles.Doc
import com.tmplayer.player.ChatSubtitles.Relation
import com.tmplayer.player.ChatSubtitles.Video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which of a chat's documents belong to the video, and in what order (EG-F48), on real name shapes. */
class ChatSubtitlesTest {

    private fun id(step: Long) = step shl 20

    private fun doc(step: Long, name: String, size: Long = 40_000, album: Long = 0, reply: Long = 0, date: Int = 1_000) =
        Doc(messageId = id(step), fileId = step.toInt(), fileName = name, size = size, date = date, albumId = album, replyToMessageId = reply)

    private fun video(step: Long, name: String, album: Long = 0, reply: Long = 0, date: Int = 1_000) =
        Video(messageId = id(step), fileName = name, date = date, albumId = album, replyToMessageId = reply)

    private fun names(hits: List<ChatSubtitles.Hit>) = hits.map { it.doc.fileName }

    @Test
    fun theFileNextToTheEpisodeComesFirst() {
        val v = video(100, "Show.S01E02.1080p.WEB.mkv")
        val hits = ChatSubtitles.rank(v, listOf(doc(101, "Show.S01E02.srt"), doc(103, "Show.S01E03.srt"), doc(99, "Show.S01E01.srt")))
        assertEquals(listOf("Show.S01E02.srt"), names(hits))
        assertEquals(ChatSubtitles.NameMatch.Exact, hits.single().nameMatch)
    }

    @Test
    fun albumThenReplyThenNeighbourThenName() {
        val v = video(100, "Film.2019.mkv", album = 77)
        val docs = listOf(
            doc(5, "Film.2019.srt", date = 90_000),
            doc(101, "subs.srt"),
            doc(300, "other.srt", reply = id(100)),
            doc(400, "english.srt", album = 77),
        )
        val hits = ChatSubtitles.rank(v, docs)
        assertEquals(listOf("english.srt", "other.srt", "subs.srt", "Film.2019.srt"), names(hits))
        assertEquals(listOf(Relation.Album, Relation.Reply, Relation.Neighbour, Relation.Name), hits.map { it.relation })
    }

    @Test
    fun theVideoThatRepliesToTheFileIsLinkedToo() {
        val v = video(100, "Film.2019.mkv", reply = id(40))
        assertEquals(Relation.Reply, ChatSubtitles.rank(v, listOf(doc(40, "whatever.srt"))).single().relation)
    }

    @Test
    fun aNeighbourForAnotherEpisodeOrFilmIsLeftOut() {
        val ep = video(100, "Show.S02E04.mkv")
        assertTrue(ChatSubtitles.rank(ep, listOf(doc(101, "Show.S02E05.srt"), doc(99, "Show.S01E04.srt"))).isEmpty())
        val film = video(100, "Film B 2020.mkv")
        assertTrue(ChatSubtitles.rank(film, listOf(doc(99, "Film A 2018.srt"))).isEmpty())
        // The same album is the poster's own word: a different film's name stays, a different episode does not.
        val inAlbum = video(100, "Film B 2020.mkv", album = 9)
        assertEquals(1, ChatSubtitles.rank(inAlbum, listOf(doc(99, "Film A 2018.srt", album = 9))).size)
        val epAlbum = video(100, "Show.S02E04.mkv", album = 9)
        assertTrue(ChatSubtitles.rank(epAlbum, listOf(doc(99, "Show.S02E05.srt", album = 9))).isEmpty())
    }

    @Test
    fun aFilmMatchesByBaseNameOrTitleAndYear() {
        val v = video(500, "The.Long.Road.2019.1080p.BluRay.x264.mkv")
        val far = listOf(doc(10, "The.Long.Road.2019.srt", date = 90_000), doc(11, "The Long Road (2019).en.srt", date = 90_000), doc(12, "Another.Film.2019.srt", date = 90_000))
        val hits = ChatSubtitles.rank(v, far)
        assertEquals(setOf("The.Long.Road.2019.srt", "The Long Road (2019).en.srt"), names(hits).toSet())
        assertTrue(hits.all { it.relation == Relation.Name && it.nameMatch == ChatSubtitles.NameMatch.Exact })
    }

    @Test
    fun aSeasonPackZipForThisSeasonIsListedAsAnArchive() {
        val v = video(500, "Show.S01E02.mkv")
        val hits = ChatSubtitles.rank(v, listOf(doc(10, "Show.S01.subs.zip", size = 900_000, date = 90_000), doc(11, "Show.S02.subs.zip", size = 900_000, date = 90_000)))
        assertEquals(listOf("Show.S01.subs.zip"), names(hits))
        assertTrue(hits.single().archive)
    }

    @Test
    fun aVideoArchiveIsNeverOfferedAsSubtitles() {
        val v = video(500, "Show.S01E02.mkv")
        assertTrue(ChatSubtitles.rank(v, listOf(doc(501, "Show.S01E02.zip", size = 2_000_000_000))).isEmpty())
    }

    @Test
    fun languageTagsAreRead() {
        assertEquals("en", ChatSubtitles.tagsOf("Show.S01E02.en.srt").language)
        val eng = ChatSubtitles.tagsOf("Show.S01E02.eng.forced.srt")
        assertEquals("en", eng.language)
        assertTrue(eng.forced)
        assertEquals("Show.S01E02", eng.base)
        assertEquals("pt-br", ChatSubtitles.tagsOf("Show.S01E02.pt-BR.srt").language)
        assertTrue(ChatSubtitles.tagsOf("Show.S01E02.English.SDH.srt").hearing)
        // A title that ends in a short word keeps it unless it is a language code.
        assertEquals("Do.Over", ChatSubtitles.tagsOf("Do.Over.srt").base)
        assertNull(ChatSubtitles.tagsOf("Show.S01E02.srt").language)
        assertNull(ChatSubtitles.tagsOf("noextension").language)
    }

    @Test
    fun twoLanguagesAreOrderedByTheViewersChoice() {
        val v = video(100, "Show.S01E02.mkv")
        val docs = listOf(
            doc(101, "Show.S01E02.fr.srt"),
            doc(102, "Show.S01E02.eng.forced.srt"),
            doc(103, "Show.S01E02.eng.srt"),
            doc(104, "Show.S01E02.srt"),
        )
        val order = names(ChatSubtitles.rank(v, docs, preferred = listOf("en")))
        assertEquals(listOf("Show.S01E02.eng.srt", "Show.S01E02.eng.forced.srt", "Show.S01E02.srt", "Show.S01E02.fr.srt"), order)
        val french = names(ChatSubtitles.rank(v, docs, preferred = listOf("fr", "en")))
        assertEquals("Show.S01E02.fr.srt", french.first())
    }

    @Test
    fun aChatOfTwoThousandSubtitleFilesListsTheTenBest() {
        val v = video(5_000, "Show.S01E02.mkv")
        val docs = (1..2_000).map { doc(4_000L + it, "Show.S01E02.$it.srt") } + doc(10, "Show.S01E02.eng.srt")
        val hits = ChatSubtitles.rank(v, docs)
        assertEquals(ChatSubtitles.TOP, hits.size)
        assertTrue(hits.all { it.nameMatch == ChatSubtitles.NameMatch.Exact })
    }

    @Test
    fun emptyHugeAndStrangeFilesAreNotListed() {
        val v = video(100, "Film.2019.mkv")
        val docs = listOf(
            doc(101, "Film.2019.srt", size = 0),
            doc(102, "Film.2019.en.srt", size = 50L * 1024 * 1024),
            doc(103, "Film.2019.txt"),
            doc(104, "Film.2019"),
            doc(105, ""),
            doc(106, "Film.2019.fr.srt"),
        )
        assertEquals(listOf("Film.2019.fr.srt"), names(ChatSubtitles.rank(v, docs)))
        assertTrue(ChatSubtitles.rank(v, listOf(Doc(id(100), 1, "Film.2019.srt", 100))).isEmpty()) // the video itself
    }

    @Test
    fun theSameFileForwardedTwiceIsListedOnce() {
        val v = video(100, "Film.2019.mkv")
        val docs = listOf(doc(101, "Film.2019.srt"), doc(102, "Film.2019.srt"))
        assertEquals(1, ChatSubtitles.rank(v, docs).size)
    }

    @Test
    fun postedTogetherCountsAsNextToItInAChatWhoseIdsAreFarApart() {
        val v = video(1_000, "Film.2019.mkv", date = 5_000)
        val hits = ChatSubtitles.rank(v, listOf(doc(9_000, "subs.srt", date = 5_060), doc(9_001, "late.srt", date = 90_000)))
        assertEquals(listOf("subs.srt"), names(hits))
    }

    @Test
    fun garbageNamesNeverThrow() {
        val v = video(100, "")
        val odd = listOf("", ".", "..srt", ".srt", "a.", "\u0000.srt", "x".repeat(5_000) + ".srt", "😀.srt", "a.b.c.d.e.f.g.srt")
        ChatSubtitles.rank(v, odd.mapIndexed { i, n -> doc(100L + i, n) })
        odd.forEach { ChatSubtitles.tagsOf(it) }
    }
}
