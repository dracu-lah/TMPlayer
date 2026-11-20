package com.tmplayer.online

import com.tmplayer.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** HX25: the online subtitle picked for a video comes back by itself, and so does Off. */
class SubtitlePicksTest {

    private val dir: File = Files.createTempDirectory("tm-picks").toFile()
    private val stateFile = File(dir, "picks.json")
    private val picks = SubtitlePicks(stateFile)
    private val hit = SubtitleHit(SubtitleProvider.OpenSubtitles, "4242", "es", "Night.Sky.S01E02", "Night.Sky.S01E02.srt")
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHola\n"

    private fun video(chat: Long, message: Long, name: String) = MediaItem(
        chatId = chat, messageId = message, fileId = message.toInt(), title = name, sizeBytes = 900L shl 20,
        durationSec = 2_700, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = name,
    )

    private fun downloaded(name: String = "dl.srt"): File = File(dir, name).also { it.writeText(srt) }

    @Test
    fun aPickSurvivesARestartAndKeepsItsOwnFile() {
        val keys = listOf("msg:1:10", "video:night sky|s01e02")
        picks.remember(keys, hit, downloaded())
        val again = SubtitlePicks(stateFile).recall(keys)
        assertNotNull(again)
        assertEquals("4242", again!!.hit?.id)
        assertEquals(srt, again.kept?.readText())
        assertFalse(again.off)
    }

    @Test
    fun aRepostOrAnotherCopyFindsThePickByTheVideosOwnKey() {
        val first = video(1, 10, "Night.Sky.S01E02.1080p.WEB.mkv")
        val repost = video(2, 99, "Night.Sky.S01E02.720p.mkv")
        picks.remember(SubtitlePicks.keysOf(first), hit, downloaded())
        assertEquals("4242", picks.recall(SubtitlePicks.keysOf(repost))?.hit?.id)
        // Another episode does not.
        assertNull(picks.recall(SubtitlePicks.keysOf(video(1, 11, "Night.Sky.S01E03.1080p.mkv"))))
    }

    @Test
    fun offIsRememberedAndAChosenTrackEndsIt() {
        val keys = listOf("msg:1:10")
        picks.rememberOff(keys)
        assertTrue(picks.recall(keys)!!.off)
        picks.clearOff(keys)
        assertNull(picks.recall(keys))
        // clearOff never drops a real pick.
        picks.remember(keys, hit, downloaded())
        picks.clearOff(keys)
        assertNotNull(picks.recall(keys))
    }

    @Test
    fun theNewestPickOfAVideoReplacesTheLastAndItsOldCopyGoes() {
        val keys = listOf("msg:1:10")
        picks.remember(keys, hit, downloaded())
        val other = hit.copy(id = "777")
        picks.remember(keys, other, downloaded("dl2.srt"))
        assertEquals("777", picks.recall(keys)?.hit?.id)
        val kept = File(dir, "picked-subtitles").listFiles().orEmpty()
        assertEquals(1, kept.size)
    }

    @Test
    fun theListIsBoundedAndTheOldestPickGoesFirst() {
        val small = SubtitlePicks(File(dir, "small.json"), maxEntries = 5)
        for (n in 1..12) small.rememberOff(listOf("msg:1:$n"))
        assertEquals(5, small.size())
        assertNull(small.recall(listOf("msg:1:1")))
        assertNotNull(small.recall(listOf("msg:1:12")))
    }

    @Test
    fun aStateFileThatIsGarbageIsNoPicksAndNeverAnError() {
        val junk = listOf(
            "", "{", "null", "[]", """{"picks":"x"}""", """{"picks":[1,2,{"key":5}]}""",
            """{"picks":[{"key":"a","hit":{"provider":"Nope","id":"1"}}]}""",
            """{"picks":[{"key":"a","off":true,"kept":"../../etc/passwd"}]}""",
            "x".repeat(10_000), "\u0000\u0000",
        )
        for (text in junk) {
            val f = File(dir, "junk.json").also { it.writeText(text) }
            val p = SubtitlePicks(f)
            p.recall(listOf("a", "msg:1:1"))
            p.rememberOff(listOf("b"))
            p.clearOff(listOf("b"))
            p.forget(listOf("a"))
        }
        // A kept name with a path in it is never followed out of the folder.
        val f = File(dir, "traversal.json").also {
            it.writeText("""{"picks":[{"key":"a","off":true,"kept":"../../etc/passwd"}]}""")
        }
        assertNull(SubtitlePicks(f).recall(listOf("a"))?.kept)
    }

    @Test
    fun signOutLeavesNoPicksAndNoCopies() {
        picks.remember(listOf("msg:1:10"), hit, downloaded())
        picks.rememberOff(listOf("msg:1:11"))
        assertTrue(stateFile.isFile)
        assertEquals(1, File(dir, "picked-subtitles").listFiles().orEmpty().size)
        // Through the live object.
        picks.clear()
        assertNull(picks.recall(listOf("msg:1:10")))
        assertFalse(stateFile.exists())
        assertFalse(File(dir, "picked-subtitles").exists())
        // By path alone, when no object is alive.
        val other = SubtitlePicks(File(dir, "other.json"))
        other.remember(listOf("msg:2:2"), hit, downloaded("dl3.srt"))
        SubtitlePicks.wipe(File(dir, "other.json"))
        assertFalse(File(dir, "other.json").exists())
        assertFalse(File(dir, "picked-subtitles").exists())
        assertNull(SubtitlePicks(File(dir, "other.json")).recall(listOf("msg:2:2")))
    }

    @Test
    fun noKeysRemembersNothing() {
        picks.remember(emptyList(), hit, downloaded())
        picks.rememberOff(emptyList())
        assertEquals(0, picks.size())
        assertTrue(SubtitlePicks.keysOf(video(0, 0, "x.mkv")).none { it.startsWith("msg:") })
    }

    // ---- through OnlineSubtitles --------------------------------------------------------------

    private var clock = 1_800_000_000_000L
    private val offline = HttpTransport { error("no network in this test") }
    private val cache = SubtitleCache(File(dir, "cache"), now = { clock })
    private fun online() = OnlineSubtitles(
        apiKey = "k", store = OnlineSubtitlesStore(File(dir, "account.properties")), cache = cache,
        appVersion = "1.0.0", http = offline, now = { clock }, sleep = { clock += it }, picks = picks,
    )

    @Test
    fun theCachedFileComesBackWithNoDownload() = runBlocking {
        val o = online()
        val keys = listOf("msg:1:10")
        val file = cache.putFile(hit.provider, hit.id, SubtitleText.Text("srt", srt))
        o.rememberPick(keys, hit, file)
        val restored = o.restorePick(keys, null, allowDownload = false)
        assertTrue(restored is OnlineSubtitles.Restored.Ready)
        assertTrue((restored as OnlineSubtitles.Restored.Ready).fromDevice)
    }

    @Test
    fun aCacheTheSystemClearedStillHasThePicksOwnCopy() = runBlocking {
        val o = online()
        val keys = listOf("msg:1:10")
        o.rememberPick(keys, hit, cache.putFile(hit.provider, hit.id, SubtitleText.Text("srt", srt)))
        cache.purge()
        val restored = o.restorePick(keys, null, allowDownload = false)
        assertTrue(restored is OnlineSubtitles.Restored.Ready)
        assertEquals(srt, (restored as OnlineSubtitles.Restored.Ready).file.readText())
    }

    @Test
    fun aVideoWithNoPickOrWithOffSaysSo() = runBlocking {
        val o = online()
        assertEquals(OnlineSubtitles.Restored.None, o.restorePick(listOf("msg:9:9"), null))
        o.rememberOff(listOf("msg:9:9"))
        assertEquals(OnlineSubtitles.Restored.Off, o.restorePick(listOf("msg:9:9"), null))
        o.clearOff(listOf("msg:9:9"))
        assertEquals(OnlineSubtitles.Restored.None, o.restorePick(listOf("msg:9:9"), null))
    }

    @Test
    fun aFileGoneEverywhereIsAskedForOnlyWhenAllowedAndSaysWhyWhenItCannot() = runBlocking {
        val o = online()
        val keys = listOf("msg:1:10")
        picks.remember(keys, hit, null)
        // Not allowed (metered, kids lock): Missing, no network touched, no sentence.
        val held = o.restorePick(keys, null, allowDownload = false)
        assertTrue(held is OnlineSubtitles.Restored.Missing)
        assertNull((held as OnlineSubtitles.Restored.Missing).notice)
        // Allowed, but the network is down: the failure is a sentence, never an exception.
        val failed = o.restorePick(keys, null, allowDownload = true)
        assertTrue(failed is OnlineSubtitles.Restored.Missing)
        assertNotNull((failed as OnlineSubtitles.Restored.Missing).notice)
    }

    @Test
    fun scaleManyPicksStayBoundedAndQuick() {
        val big = SubtitlePicks(File(dir, "big.json"))
        val started = System.nanoTime()
        for (n in 1..2_000) big.rememberOff(listOf("msg:1:$n", "video:film $n|2020"))
        assertEquals(SubtitlePicks.MAX_ENTRIES, big.size())
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 20_000)
        assertTrue(stateFile.length() < 4L * 1024 * 1024)
    }

    @Test
    fun aPickRecalledNowIsTheLastToGoWhenTheListIsFull() {
        val small = SubtitlePicks(File(dir, "recency.json"), maxEntries = 3)
        small.rememberOff(listOf("a"))
        small.rememberOff(listOf("b"))
        small.rememberOff(listOf("c"))
        assertNotNull(small.recall(listOf("a")))
        small.rememberOff(listOf("d"))
        assertNotNull("a was used last, b is the oldest used", small.recall(listOf("a")))
        assertNull(small.recall(listOf("b")))
    }

    @Test
    fun anotherChoiceForgetsThePick() = runBlocking {
        val o = OnlineSubtitles(
            apiKey = "k", store = OnlineSubtitlesStore(File(dir, "account2.properties")), cache = SubtitleCache(File(dir, "cache2")),
            appVersion = "1.0.0", http = HttpTransport { error("no network") }, picks = picks,
        )
        val keys = listOf("msg:1:10")
        o.rememberPick(keys, hit, downloaded())
        o.forgetPick(keys)
        assertEquals(OnlineSubtitles.Restored.None, o.restorePick(keys, null, allowDownload = false))
    }
}
