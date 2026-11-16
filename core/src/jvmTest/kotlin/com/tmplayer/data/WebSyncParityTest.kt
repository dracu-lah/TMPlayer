package com.tmplayer.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.random.Random

/**
 * The web app (webapp/src/sync) reads and writes the same sync file as the apps, with its own
 * port of [SyncDocuments]. This test keeps the two honest from this side:
 *
 * - `core-v3.json.gz` is a file this build writes (every list, a show line, named lists, bookmarks,
 *   corrections, removals). The web reads it in format.test.ts; here it is checked to still be what
 *   [SyncDocuments.encode] writes now, so a change to the format shows up as a red test until the
 *   fixtures are written again (`TMPLAYER_WRITE_WEB_FIXTURES=1 ./gradlew :core:jvmTest --tests "*WebSyncParityTest"`) and the web's tests are green.
 * - `parity.json` holds seeded pairs of files, the merge core made of each and what the first
 *   would change to match it ([SyncDocuments.apply]); the web's merge must give the same answers.
 * - `web-written.json.gz` is a file the web wrote (format.test.ts); here it is read by
 *   [SyncDocuments.decode], the way an app reads what the web put in Saved Messages.
 */
class WebSyncParityTest {

    private val dir = File("../webapp/src/sync/fixtures")
    private val writing = System.getenv("TMPLAYER_WRITE_WEB_FIXTURES") == "1"
    private val now = 1_800_000_000_000L
    private val account = 777L
    private val shift = 1L shl 20

    private fun place(chat: Long, message: Long, at: Long, position: Long = 60_000, state: ResumeState? = null) = ResumeRecord(
        chatId = chat, messageId = message * shift, fileId = 5, title = "Video $message", chatTitle = "", sizeBytes = 1_000,
        durationSec = 600, positionMs = position, durationMs = 600_000, updatedAt = at, state = state,
    )

    private fun mark(chat: Long, message: Long, at: Long, manual: Boolean = false) = WatchedRecord(
        chatId = chat, messageId = message * shift, fileId = 0, title = "Video $message", chatTitle = "", sizeBytes = 1_000,
        durationSec = 600, watchedAt = at, manual = manual,
    )

    private fun later(chat: Long, message: Long, at: Long, series: String = "") = WatchLaterRecord(
        chatId = chat, messageId = message * shift, fileId = 0, title = "Video $message", fileName = "", chatTitle = "",
        sizeBytes = 1_000, durationSec = 600, mimeType = "video/mp4", addedAt = at, series = series,
    )

    private fun key(chat: Long, message: Long) = ProgressKeys.of(chat, message * shift)

    /** The fixed file the web reads: every list holds something, names and numbers the web test checks. */
    private fun coreFile(): SyncDocument {
        val chat = -1001234567890L
        val t = now - 5_000
        val weekend = later(chat, 9, t - 3_000)
        return SyncDocument(
            account = account,
            updatedAt = now,
            appVersion = "1.99.0",
            resume = listOf(
                place(chat, 5, t, position = 754_000, state = ResumeState(audioTrack = 1, audioLanguage = "ja", subtitleTrack = 0, subtitleLanguage = "en", speed = 1.25f)),
                place(42L, 77, t - 1_000, position = 61_000),
            ),
            watched = listOf(mark(chat, 6, t - 2_000, manual = true), mark(chat, 7, t - 4_000)),
            watchLater = listOf(later(chat, 8, t - 6_000), later(chat, 3, t - 7_000, series = "harbour notes")),
            favourites = mapOf(chat to t - 9_000, 42L to t - 10_000),
            removed = listOf(
                SyncRemoval(SyncList.Resume, key(chat, 4), t - 500),
                SyncRemoval(SyncList.Bookmark, "${chat}_${5 * shift}_1799999000001", t - 600),
                SyncRemoval(SyncList.Correction, key(chat, 12), t - 700),
            ),
            lists = NamedLists(
                lists = listOf(ListInfo("wkend1", "Weekend", t - 8_000, home = true)),
                entries = listOf(ListEntry("wkend1", weekend)),
            ),
            bookmarks = listOf(SyncBookmark(Bookmark(chat, 5 * shift, 1_799_999_000_000L, 120_000, "the lantern scene"), t - 1_500)),
            corrections = listOf(SyncCorrection(chat, 6 * shift, "tmdb=show:123\ntitle=Night Lantern (2022)", t - 2_500)),
        )
    }

    /** Small key spaces and times, so the same video turns up on both sides with ties and removals; one text per key and time. */
    private fun randomDoc(random: Random): SyncDocument {
        fun at() = now - random.nextLong(0, 10) * 1000
        fun msg() = random.nextLong(1, 8)
        val chat = -100L
        return SyncDocument(
            account = account,
            updatedAt = at(),
            appVersion = "1.${random.nextInt(0, 3)}",
            resume = List(random.nextInt(0, 6)) { place(chat, msg(), at(), position = random.nextLong(1, 4) * 1000) },
            watched = List(random.nextInt(0, 6)) { mark(chat, msg(), at(), manual = random.nextBoolean()) },
            watchLater = List(random.nextInt(0, 6)) { later(chat, msg(), at()) },
            favourites = List(random.nextInt(0, 4)) { random.nextLong(1, 5) to at() }.toMap(),
            removed = List(random.nextInt(0, 8)) {
                when (SyncList.entries[random.nextInt(SyncList.entries.size)]) {
                    SyncList.Favourite -> SyncRemoval(SyncList.Favourite, random.nextLong(1, 5).toString(), at())
                    SyncList.WatchLater -> SyncRemoval(SyncList.WatchLater, key(chat, msg()), at())
                    SyncList.Bookmark -> SyncRemoval(SyncList.Bookmark, "${chat}_${msg() * shift}_${random.nextLong(1, 3)}", at())
                    SyncList.Resume -> SyncRemoval(SyncList.Resume, key(chat, msg()), at())
                    SyncList.Watched -> SyncRemoval(SyncList.Watched, key(chat, msg()), at())
                    SyncList.Correction -> SyncRemoval(SyncList.Correction, key(chat, msg()), at())
                }
            },
            bookmarks = List(random.nextInt(0, 4)) {
                val m = msg()
                val id = random.nextLong(1, 3)
                SyncBookmark(Bookmark(chat, m * shift, id, 1_000 * id, "note $m $id"), at())
            },
            corrections = List(random.nextInt(0, 4)) {
                val m = msg()
                SyncCorrection(chat, m * shift, "tmdb=film:$m", at())
            },
        )
    }

    /** One file per key in each list, as every file the merge writes is. */
    private fun normal(doc: SyncDocument) = SyncDocuments.merge(doc, SyncDocument(), now)

    private fun b64(doc: SyncDocument) = Base64.getEncoder().encodeToString(SyncDocuments.encode(doc).bytes)

    private fun read(text: String): SyncDocument = ((SyncDocuments.decode(Base64.getDecoder().decode(text), account)) as SyncRead.Ok).doc

    private fun sorted(keys: Collection<String>) = JSONArray(keys.sorted())

    private fun summary(a: SyncApply): JSONObject = JSONObject()
        .put("resumeIn", sorted(a.resumeIn.map { ProgressKeys.of(it.chatId, it.messageId) + "@" + it.updatedAt }))
        .put("resumeOut", sorted(a.resumeOut.map { "${it.key}@${it.value}" }))
        .put("watchedIn", sorted(a.watchedIn.map { it.key + "@" + it.watchedAt + "@" + it.manual }))
        .put("watchedOut", sorted(a.watchedOut.map { "${it.key}@${it.value}" }))
        .put("laterIn", sorted(a.laterIn.map { it.id + "@" + it.addedAt }))
        .put("laterOut", sorted(a.laterOut.map { "${it.key}@${it.value}" }))
        .put("favouritesIn", sorted(a.favouritesIn.map { it.toString() }))
        .put("favouritesOut", sorted(a.favouritesOut.map { it.toString() }))
        .put("bookmarksIn", sorted(a.bookmarksIn.map { it.key + "@" + it.at }))
        .put("bookmarksOut", sorted(a.bookmarksOut.map { "${it.key}@${it.value}" }))
        .put("correctionsIn", sorted(a.correctionsIn.map { it.key + "@" + it.at }))
        .put("correctionsOut", sorted(a.correctionsOut.map { "${it.key}@${it.value}" }))

    private fun parity(): JSONObject {
        val random = Random(20261116)
        val cases = JSONArray()
        repeat(100) {
            val a = normal(randomDoc(random))
            val b = normal(randomDoc(random))
            val merged = SyncDocuments.merge(a, b, now)
            cases.put(
                JSONObject().put("a", b64(a)).put("b", b64(b)).put("merged", b64(merged))
                    .put("apply", summary(SyncDocuments.apply(a, merged))),
            )
        }
        return JSONObject().put("now", now).put("account", account).put("cases", cases)
    }

    @Test
    fun theFileCoreWritesIsTheFixtureTheWebReads() {
        val expected = coreFile()
        val bytes = SyncDocuments.encode(expected).bytes
        val file = File(dir, "core-v3.json.gz")
        if (writing) {
            dir.mkdirs()
            file.writeBytes(bytes)
        }
        assertTrue("fixture missing: ${file.absolutePath}", file.isFile)
        val stored = (SyncDocuments.decode(file.readBytes(), account) as SyncRead.Ok).doc
        val now = (SyncDocuments.decode(bytes, account) as SyncRead.Ok).doc
        assertEquals("the checked in file is what core writes now", now, stored)
        assertEquals(3, stored.version.coerceAtMost(3))
    }

    @Test
    fun theMergeParityCasesAreWhatCoreMakesNow() {
        val file = File(dir, "parity.json")
        val made = parity()
        if (writing) {
            dir.mkdirs()
            file.writeText(made.toString())
        }
        assertTrue("fixture missing: ${file.absolutePath}", file.isFile)
        val stored = JSONObject(file.readText()).getJSONArray("cases")
        val fresh = made.getJSONArray("cases")
        assertEquals(fresh.length(), stored.length())
        for (i in 0 until fresh.length()) {
            val s = stored.getJSONObject(i)
            val f = fresh.getJSONObject(i)
            assertEquals("case $i merged", read(f.getString("merged")), read(s.getString("merged")))
            assertEquals("case $i apply", f.getJSONObject("apply").toString(), s.getJSONObject("apply").toString())
        }
    }

    @Test
    fun coreReadsTheFileTheWebWrote() {
        val file = File(dir, "web-written.json.gz")
        assertTrue("fixture missing (written by webapp/src/sync/format.test.ts): ${file.absolutePath}", file.isFile)
        val read = SyncDocuments.decode(file.readBytes(), account)
        val doc = (read as SyncRead.Ok).doc
        val chat = -1001234567890L
        assertEquals(account, doc.account)
        assertEquals("web", doc.appVersion)
        assertEquals(listOf(ProgressKeys.of(chat, 5 * shift)), doc.resume.map { ProgressKeys.of(it.chatId, it.messageId) })
        assertEquals(754_000L, doc.resume.single().positionMs)
        assertEquals("Night Lantern", doc.resume.single().title)
        assertTrue(doc.resume.single().fileId > 0)
        assertEquals(listOf(ProgressKeys.of(chat, 6 * shift)), doc.watched.map { it.key })
        assertEquals(listOf(ProgressKeys.of(chat, 8 * shift)), doc.watchLater.map { it.id })
        assertEquals(setOf(chat), doc.favourites.keys)
        assertEquals(listOf(SyncList.Watched to key(chat, 7)), doc.removed.map { it.list to it.key })
        assertEquals(listOf("tmdb=film:99"), doc.corrections.map { it.text })
        assertEquals(1, doc.bookmarks.size)
        assertEquals("a note the web kept untouched", doc.bookmarks.single().bookmark.note)
        assertEquals(listOf("Weekend"), doc.lists.lists.map { it.name })
    }
}
