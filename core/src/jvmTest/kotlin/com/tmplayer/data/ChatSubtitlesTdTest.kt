package com.tmplayer.data

import com.tmplayer.player.ChatSubtitles
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The finder's one search per video, its gates, and the reading of a fetched file (EG-F48). */
class ChatSubtitlesTdTest {

    private class FakeCalls(
        private val docs: List<ChatSubtitles.Doc> = emptyList(),
        private val answer: ChatSubtitleDocs? = null,
        private val known: ChatSubtitleVideo? = ChatSubtitleVideo(date = 1_000, albumId = 0),
    ) : ChatSubtitleCalls {
        var searches = 0
        var videos = 0
        override suspend fun video(chatId: Long, messageId: Long): ChatSubtitleVideo? = known.also { videos++ }
        override suspend fun documents(chatId: Long, topicId: Int, fromMessageId: Long, offset: Int, limit: Int): ChatSubtitleDocs {
            searches++
            kotlinx.coroutines.yield()
            return answer ?: ChatSubtitleDocs.Found(docs)
        }
        override suspend fun fetch(chatId: Long, doc: ChatSubtitles.Doc, maxBytes: Long): ChatSubtitleFetch = ChatSubtitleFetch.Gone
    }

    private fun doc(step: Long, name: String, size: Long = 30_000) =
        ChatSubtitles.Doc(messageId = step shl 20, fileId = step.toInt(), fileName = name, size = size, date = 1_000)

    private val video = 100L shl 20

    @Test
    fun oneSearchPerVideoWhateverTheChatHolds() = runBlocking<Unit> {
        val calls = FakeCalls((1..2_000).map { doc(50L + it, "Show.S01E02.$it.srt") })
        val finder = ChatSubtitleFinder(calls, kids = { null })
        val first = finder.find(1, video, "Show.S01E02.mkv")
        assertIs<ChatSubtitleFound.Hits>(first)
        assertEquals(ChatSubtitles.TOP, first.hits.size)
        // Opened again, and by two pickers at once: nothing more is asked.
        finder.find(1, video, "Show.S01E02.mkv")
        (1..3).map { async { finder.find(1, video, "Show.S01E02.mkv") } }.awaitAll()
        assertEquals(1, calls.searches)
    }

    @Test
    fun theOffsetWindowCoversBothSidesInOneCall() = runBlocking<Unit> {
        var seen = Triple(0L, 0, 0)
        val calls = object : ChatSubtitleCalls {
            override suspend fun video(chatId: Long, messageId: Long): ChatSubtitleVideo? = null
            override suspend fun documents(chatId: Long, topicId: Int, fromMessageId: Long, offset: Int, limit: Int): ChatSubtitleDocs {
                seen = Triple(fromMessageId, offset, limit)
                return ChatSubtitleDocs.Found(emptyList())
            }
            override suspend fun fetch(chatId: Long, doc: ChatSubtitles.Doc, maxBytes: Long) = ChatSubtitleFetch.Gone
        }
        assertEquals(ChatSubtitleFound.None, ChatSubtitleFinder(calls, kids = { null }).find(1, video, "Film.mkv"))
        assertEquals(Triple(video, -50, 100), seen)
    }

    @Test
    fun theKidsLockAsksNothingForAChatItLeavesOut() = runBlocking<Unit> {
        val calls = FakeCalls(listOf(doc(101, "Film.srt")))
        assertEquals(ChatSubtitleFound.Blocked, ChatSubtitleFinder(calls, kids = { setOf(5L) }).find(1, video, "Film.mkv"))
        assertEquals(0, calls.searches)
        assertIs<ChatSubtitleFound.Hits>(ChatSubtitleFinder(FakeCalls(listOf(doc(101, "Film.srt"))), kids = { setOf(1L) }).find(1, video, "Film.mkv"))
    }

    @Test
    fun aFailedSearchHidesTheEntryAndIsNotKept() = runBlocking<Unit> {
        val calls = FakeCalls(answer = ChatSubtitleDocs.Failed("FLOOD_WAIT_30"))
        val finder = ChatSubtitleFinder(calls, kids = { null })
        assertEquals(ChatSubtitleFound.Unavailable, finder.find(1, video, "Film.mkv"))
        finder.find(1, video, "Film.mkv")
        assertEquals(2, calls.searches)
    }

    @Test
    fun aSearchThatThrowsOrHangsHidesTheEntryToo() = runBlocking<Unit> {
        val throwing = object : ChatSubtitleCalls {
            override suspend fun video(chatId: Long, messageId: Long): ChatSubtitleVideo? = error("boom")
            override suspend fun documents(chatId: Long, topicId: Int, fromMessageId: Long, offset: Int, limit: Int): ChatSubtitleDocs = error("boom")
            override suspend fun fetch(chatId: Long, doc: ChatSubtitles.Doc, maxBytes: Long) = ChatSubtitleFetch.Gone
        }
        assertEquals(ChatSubtitleFound.Unavailable, ChatSubtitleFinder(throwing, kids = { null }).find(1, video, "Film.mkv"))
        val hanging = object : ChatSubtitleCalls {
            override suspend fun video(chatId: Long, messageId: Long): ChatSubtitleVideo? = null
            override suspend fun documents(chatId: Long, topicId: Int, fromMessageId: Long, offset: Int, limit: Int): ChatSubtitleDocs {
                kotlinx.coroutines.delay(60_000)
                return ChatSubtitleDocs.Found(emptyList())
            }
            override suspend fun fetch(chatId: Long, doc: ChatSubtitles.Doc, maxBytes: Long) = ChatSubtitleFetch.Gone
        }
        assertEquals(ChatSubtitleFound.Unavailable, ChatSubtitleFinder(hanging, kids = { null }, timeoutMs = 50).find(1, video, "Film.mkv"))
    }

    // ---- reading a fetched file ---------------------------------------------------------------

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n"

    private fun read(bytes: ByteArray, name: String, season: Int? = null, episode: Int? = null) =
        runBlocking { ChatSubtitleText.read(bytes, name, season, episode, "en") }

    @Test
    fun aPlainSrtIsReadAsText() {
        val result = read(srt.toByteArray(), "Show.S01E02.srt")
        assertIs<ChatSubtitleText.Result.Text>(result)
        assertEquals("srt", result.extension)
        assertTrue(result.text.contains("Hello"))
    }

    @Test
    fun binaryBytesNamedSrtAreRefused() {
        val bytes = ByteArray(5_000) { (it * 31 % 256).toByte() }
        assertEquals(ChatSubtitleText.Result.Unreadable, read(bytes, "x.srt"))
        assertEquals(ChatSubtitleText.Result.Unreadable, read(ByteArray(0), "x.srt"))
        assertEquals(ChatSubtitleText.Result.Unreadable, read("just words, no cues".toByteArray(), "x.srt"))
        assertEquals(ChatSubtitleText.Result.Unreadable, read(srt.toByteArray(), "x.txt"))
    }

    @Test
    fun aFiftyMegabyteSrtIsTooBigAndNeverDecoded() {
        assertEquals(ChatSubtitleText.Result.TooBig, read(ByteArray(50 * 1024 * 1024) { 'a'.code.toByte() }, "x.srt"))
    }

    @Test
    fun anOldCodePageIsDecodedByTheLanguageInTheName() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nПривет\n\n"
        val bytes = text.toByteArray(charset("windows-1251"))
        val result = read(bytes, "Show.S01E02.ru.srt")
        assertIs<ChatSubtitleText.Result.Text>(result)
        assertTrue(result.text.contains("Привет"))
        assertEquals("ru", result.language)
    }

    @Test
    fun utf16WithAMarkIsRead() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.toByteArray(Charsets.UTF_16LE)
        assertIs<ChatSubtitleText.Result.Text>(read(bytes, "x.srt"))
    }

    @Test
    fun microDvdIsKeptAndVobsubIsRefused() {
        val result = read("{100}{200}Hello|there\n".toByteArray(), "x.sub")
        assertIs<ChatSubtitleText.Result.Text>(result)
        assertEquals("sub", result.extension)
        assertEquals(ChatSubtitleText.Result.Vobsub, read(ByteArray(2_048) { 0x00 }.also { it[0] = 0x00; it[1] = 0x00; it[2] = 0x01; it[3] = 0xBA.toByte() }, "x.sub"))
    }

    private fun zip(stored: Boolean, vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in files) {
                val bytes = text.toByteArray()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().also { it.update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun aZipGivesTheEntryForThisEpisodeStoredOrCompressed() {
        for (stored in listOf(true, false)) {
            val bytes = zip(stored, "Show.S01E01.srt" to srt.replace("Hello", "One"), "Show.S01E02.srt" to srt.replace("Hello", "Two"))
            val result = read(bytes, "Show.S01.subs.zip", season = 1, episode = 2)
            assertIs<ChatSubtitleText.Result.Text>(result)
            assertTrue(result.text.contains("Two"), "stored=$stored")
        }
    }

    @Test
    fun aSeasonPackWithoutThisEpisodeGivesNothingAndBrokenZipsAreRefused() {
        val pack = zip(true, "Show.S01E01.srt" to srt, "Show.S01E03.srt" to srt)
        assertEquals(ChatSubtitleText.Result.Unreadable, read(pack, "Show.S01.zip", season = 1, episode = 2))
        assertEquals(ChatSubtitleText.Result.Unreadable, read(zip(true, "readme.txt" to "hi"), "x.zip"))
        assertEquals(ChatSubtitleText.Result.Unreadable, read(pack.copyOf(pack.size / 2), "x.zip"))
        assertEquals(ChatSubtitleText.Result.Unreadable, read("PK\u0003\u0004 not really".toByteArray(), "x.zip"))
    }
}
