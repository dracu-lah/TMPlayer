package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

/** The named lists (HX43): names, ids, edits, the merge, the sync file and backups, the store and the page. */
class NamedListsTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun video(chat: Long, message: Long, title: String = "Clip $message", file: String = "clip.$message.mp4", at: Long = 0) = WatchLaterRecord(
        chatId = chat, messageId = message, fileId = 7, title = title, fileName = file, chatTitle = "Chat $chat",
        sizeBytes = 1_000, durationSec = 60, mimeType = "video/mp4", addedAt = at,
    )

    private fun episode(chat: Long, message: Long, season: Int, ep: Int, at: Long = 0) =
        video(chat, message, title = "The Show S0${season}E0$ep", file = "The.Show.S0${season}E0$ep.1080p.mkv", at = at)

    private fun make(vararg names: String): Pair<NamedLists, List<ListInfo>> {
        var state = NamedLists()
        val infos = names.mapIndexed { i, name ->
            val (next, info) = NamedListRules.create(state, name, now + i)!!
            state = next
            info
        }
        return state to infos
    }

    // ---- names and ids ---------------------------------------------------------------------------

    @Test
    fun `names are trimmed, folded and cut to sixty, an empty one refused`() {
        assertEquals("Tutorials", NamedListRules.cleanName("  Tutorials \n"))
        assertEquals("For the weekend", NamedListRules.cleanName("For \t the\u0000  weekend"))
        assertNull(NamedListRules.cleanName("   ​ "))
        assertNull(NamedListRules.cleanName(null))
        val long = NamedListRules.cleanName("a".repeat(500))!!
        assertEquals(60, long.length)
        // An emoji on the cut is left out whole rather than split.
        val emoji = NamedListRules.cleanName("a".repeat(59) + "😀b")!!
        assertEquals("a".repeat(59), emoji)
    }

    @Test
    fun `two lists of one name are two lists, and ids are home row keys`() {
        val (state, infos) = make("Kids", "Kids")
        assertEquals(2, state.lists.size)
        assertNotEquals(infos[0].id, infos[1].id)
        infos.forEach { info ->
            assertTrue(NamedListRules.isId(info.id))
            assertEquals(listOf(NamedListRules.homeKey(info.id)), HomeOrder.parse(NamedListRules.homeKey(info.id)))
            assertEquals(info.id, NamedListRules.idOfHomeKey(NamedListRules.homeKey(info.id)))
        }
        assertNull(NamedListRules.idOfHomeKey("chat-5"))
    }

    @Test
    fun `fifty lists are fine and the hundred and first is refused`() {
        var state = NamedLists()
        repeat(NamedListRules.MAX_LISTS) { state = NamedListRules.create(state, "List $it", now + it)!!.first }
        assertEquals(100, state.lists.size)
        assertNull(NamedListRules.create(state, "One more", now + 200))
        assertEquals((0 until 100).map { "List $it" }, state.ordered.map { it.name })
    }

    // ---- edits -----------------------------------------------------------------------------------

    @Test
    fun `the same video twice is one line that keeps its first place`() {
        val (state, infos) = make("Mine")
        val id = infos[0].id
        val (one, first) = NamedListRules.add(state, id, video(1, 10), now + 10)
        val (two, _) = NamedListRules.add(one, id, video(1, 11), now + 20)
        val (three, again) = NamedListRules.add(two, id, video(1, 10), now + 30)
        assertEquals(ListAdd.Added, first)
        assertEquals(ListAdd.Already, again)
        assertEquals(listOf(10L, 11L), three.entriesOf(id).map { it.messageId })
        assertEquals(now + 10, three.entriesOf(id).first().addedAt)
    }

    @Test
    fun `a full list refuses and a deleted one says so`() {
        val (state, infos) = make("Big")
        val id = infos[0].id
        var s = state
        repeat(NamedListRules.MAX_PER_LIST) { s = NamedListRules.add(s, id, video(1, it + 1L), now + it).first }
        assertEquals(ListAdd.Full, NamedListRules.add(s, id, video(1, 99_999), now + 9_999).second)
        assertEquals(ListAdd.NoList, NamedListRules.add(s, "nolist00", video(1, 1), now).second)
    }

    @Test
    fun `gone videos leave every list and a show's line stays`() {
        val (state, infos) = make("A", "B")
        var s = NamedListRules.add(state, infos[0].id, video(1, 5), now).first
        s = NamedListRules.add(s, infos[1].id, video(1, 5), now).first
        val show = video(1, 5).copy(series = "the show")
        s = NamedListRules.add(s, infos[1].id, show, now).first
        val (after, count) = NamedListRules.forgetVideos(s, setOf(ProgressKeys.of(1, 5)), now + 1)
        assertEquals(2, count)
        assertEquals(listOf(show.id), after.entries.map { it.id })
        assertEquals(2, after.removed.count { !it.wholeList })
    }

    // ---- the merge -------------------------------------------------------------------------------

    @Test
    fun `newest write wins per list and per line`() {
        val (base, infos) = make("Old name")
        val id = infos[0].id
        val phone = NamedListRules.rename(base, id, "Phone name", now + 100)
        val web = NamedListRules.rename(base, id, "Web name", now + 200)
        assertEquals("Web name", NamedListRules.merge(phone, web, now).list(id)!!.name)
        assertEquals("Web name", NamedListRules.merge(web, phone, now).list(id)!!.name)
        // The phone and the web app each add a video at once: both stay.
        val p = NamedListRules.add(phone, id, video(1, 1), now + 300).first
        val w = NamedListRules.add(web, id, video(2, 2), now + 300).first
        val merged = NamedListRules.merge(p, w, now)
        assertEquals(setOf(1L, 2L), merged.entriesOf(id).map { it.messageId }.toSet())
    }

    @Test
    fun `a removal is remembered and beats an older copy`() {
        val (base, infos) = make("L")
        val id = infos[0].id
        val both = NamedListRules.add(base, id, video(1, 1), now + 10).first
        val removedHere = NamedListRules.remove(both, id, video(1, 1).id, now + 20)
        // The other device still has the line: the merge drops it, either way round.
        assertTrue(NamedListRules.merge(removedHere, both, now).entriesOf(id).isEmpty())
        assertTrue(NamedListRules.merge(both, removedHere, now).entriesOf(id).isEmpty())
        // Added again after the removal: the newer add wins.
        val readded = NamedListRules.add(removedHere, id, video(1, 1), now + 30).first
        assertEquals(1, NamedListRules.merge(readded, removedHere, now).entriesOf(id).size)
    }

    @Test
    fun `a deleted list goes everywhere unless a later add brings it back`() {
        val (base, infos) = make("L")
        val id = infos[0].id
        val withLine = NamedListRules.add(base, id, video(1, 1), now + 10).first
        val deleted = NamedListRules.delete(withLine, id, now + 20)
        assertTrue(deleted.lists.isEmpty() && deleted.entries.isEmpty())
        val merged = NamedListRules.merge(withLine, deleted, now)
        assertNull(merged.list(id))
        assertTrue(merged.entries.isEmpty())
        // Offline, the other device added a video after the delete: the list comes back with it alone.
        val later = NamedListRules.add(withLine, id, video(2, 2), now + 30).first
        val back = NamedListRules.merge(deleted, later, now)
        assertNotNull(back.list(id))
        assertEquals(listOf(2L), back.entriesOf(id).map { it.messageId })
    }

    @Test
    fun `removals expire after ninety days and stay bounded`() {
        val (base, infos) = make("L")
        val id = infos[0].id
        val old = base.copy(removed = listOf(ListRemoval(id, "1_1", now - 91 * day), ListRemoval(id, "1_2", now - 10 * day)))
        val pruned = NamedListRules.prune(old, now)
        assertEquals(listOf("1_2"), pruned.removed.map { it.entryId })
        assertEquals(listOf("1_2"), NamedListRules.merge(old, NamedLists(), now).removed.map { it.entryId })
        // An expired removal no longer stops an old copy: what a device away for months had comes back.
        val stale = NamedListRules.add(base, id, video(1, 1), now - 100 * day).first
        assertEquals(1, NamedListRules.merge(stale, old, now).entriesOf(id).size)
        val many = base.copy(removed = List(5_000) { ListRemoval(id, "1_$it", now - it) })
        val bounded = NamedListRules.merge(many, NamedLists(), now).removed
        assertEquals(NamedListRules.MAX_REMOVALS, bounded.size)
        assertEquals(now, bounded.maxOf { it.at })
    }

    @Test
    fun `the merge is commutative and idempotent`() {
        val random = Random(43)
        val ids = listOf("aaaa1111", "bbbb2222", "cccc3333")
        fun at() = now - random.nextLong(0, 10) * 1000
        fun doc() = NamedLists(
            lists = List(random.nextInt(0, 4)) { ListInfo(ids.random(random), "n${random.nextInt(3)}", at(), random.nextBoolean()) },
            entries = List(random.nextInt(0, 8)) { ListEntry(ids.random(random), video(1, random.nextLong(1, 5), at = at())) },
            removed = List(random.nextInt(0, 4)) { ListRemoval(ids.random(random), if (random.nextBoolean()) "" else "1_${random.nextLong(1, 5)}", at()) },
        )
        repeat(500) {
            val a = NamedListRules.merge(doc(), NamedLists(), now)
            val b = NamedListRules.merge(doc(), NamedLists(), now)
            val ab = NamedListRules.merge(a, b, now)
            assertEquals(ab, NamedListRules.merge(b, a, now))
            assertEquals(ab, NamedListRules.merge(ab, ab, now))
            assertEquals(ab, NamedListRules.merge(ab, a, now))
        }
    }

    // ---- the sync file -------------------------------------------------------------------------

    private fun docWith(lists: NamedLists) = SyncDocument(account = 5, updatedAt = now, appVersion = "t", lists = lists)

    @Test
    fun `a file without lists stays version one, with lists it is version two`() {
        val plain = SyncDocuments.json(SyncDocument(account = 5))
        assertEquals(1, plain.getInt("version"))
        assertFalse(plain.has("lists"))
        val (state, _) = make("L")
        val listed = SyncDocuments.json(docWith(state))
        assertEquals(2, listed.getInt("version"))
        // A version one file still reads, with no lists.
        val read = SyncDocuments.decode(SyncDocuments.encode(SyncDocument(account = 5)).bytes, 5) as SyncRead.Ok
        assertTrue(read.doc.lists.isEmpty)
        // A file of a version past this build's is too new: never read nor written over.
        val newer = "{\"format\":\"tmplayer-sync\",\"version\":4,\"lists\":{}}".encodeToByteArray()
        assertEquals(SyncRead.TooNew(4), SyncDocuments.decode(newer, 5))
    }

    @Test
    fun `lists travel stripped and come back as they were`() {
        val (state, infos) = make("Tutorials")
        val id = infos[0].id
        val s = NamedListRules.add(state, id, video(1, 1, title = "t".repeat(200)), now + 5).first
        val bytes = SyncDocuments.encode(docWith(SyncDocuments.strip(s))).bytes
        val read = (SyncDocuments.decode(bytes, 5) as SyncRead.Ok).doc.lists
        assertEquals("Tutorials", read.list(id)!!.name)
        val line = read.entriesOf(id).single()
        assertEquals(60, line.title.length)
        assertEquals("", line.chatTitle)
        assertEquals(0, line.fileId)
        // This device keeps its own copy of a line both have, with its file id and names.
        val kept = NamedListRules.mergeInto(s, read, now)
        assertEquals("Chat 1", kept.entriesOf(id).single().chatTitle)
        assertEquals(7, kept.entriesOf(id).single().fileId)
    }

    @Test
    fun `a list of five thousand fits the file, and past the cap the oldest lines go first`() {
        val (state, infos) = make("Big")
        val id = infos[0].id
        val random = Random(7)
        val entries = List(5_000) { i ->
            val title = "Lesson ${random.nextInt()} " + (1..6).map { ('a'..'z').random(random) }.joinToString("")
            ListEntry(id, video(-1_000_000_000_000L - random.nextInt(1_000), 1_000L + i, title = title, at = now + i))
        }
        val doc = docWith(SyncDocuments.strip(state.copy(entries = entries)))
        val encoded = SyncDocuments.encode(doc)
        assertEquals(0, encoded.dropped.total)
        assertEquals(5_000, encoded.doc.lists.entries.size)
        assertTrue("${encoded.bytes.size} bytes", encoded.bytes.size < SyncDocuments.MAX_BYTES / 2)
        // A smaller cap: the oldest lines are dropped and counted, the list itself stays.
        val cut = SyncDocuments.encode(doc, maxBytes = encoded.bytes.size / 2)
        assertTrue(cut.dropped.listEntries > 0)
        assertEquals(cut.dropped.total, cut.dropped.listEntries)
        assertNotNull(cut.doc.lists.list(id))
        val keptOldest = cut.doc.lists.entries.minOf { it.at }
        assertTrue(cut.doc.lists.entries.size < 5_000)
        assertEquals(now + 5_000 - cut.doc.lists.entries.size, keptOldest)
    }

    @Test
    fun `the phone and the web app writing at once both land after a sync`() {
        val (base, infos) = make("Shared")
        val id = infos[0].id
        val phone = docWith(NamedListRules.add(base, id, video(1, 1), now + 1).first)
        val web = docWith(NamedListRules.add(NamedListRules.rename(base, id, "Renamed", now + 2), id, video(2, 2), now + 2).first)
        val one = SyncDocuments.merge(phone, web, now)
        val other = SyncDocuments.merge(web, phone, now)
        assertEquals(one.lists, other.lists)
        assertEquals("Renamed", one.lists.list(id)!!.name)
        assertEquals(2, one.lists.entriesOf(id).size)
    }

    // ---- backups -------------------------------------------------------------------------------

    @Test
    fun `a backup carries the lists and a restore brings back what was removed since`() {
        val (state, infos) = make("Keep")
        val id = infos[0].id
        val withLine = NamedListRules.add(state, id, video(1, 1), now + 1).first
        val backup = Backups.collect(emptyMap(), NamedListKeys.encode(withLine), com.tmplayer.online.MetaSettings(), now, "t")
        val text = Backups.encode(backup)
        assertTrue(text.contains("\"version\": 2"))
        val read = (Backups.decode(text) as BackupRead.Ok).backup
        assertEquals(withLine.entries.map { it.record }, read.lists.entries.map { it.record })
        val removedHere = NamedListRules.delete(withLine, id, now + 5)
        val restored = NamedListRules.restore(removedHere, read.lists, now + 6)
        assertEquals("Keep", restored.list(id)!!.name)
        assertEquals(1, restored.entriesOf(id).size)
        assertEquals(2, Backups.listsAdded(removedHere, read.lists))
        // A backup without lists stays the version every build reads.
        assertTrue(Backups.encode(Backups.collect(emptyMap(), emptyMap(), com.tmplayer.online.MetaSettings(), now, "t")).contains("\"version\": 1"))
    }

    // ---- the page --------------------------------------------------------------------------------

    @Test
    fun `a list's page folds a show into one card and keeps the order added`() {
        val records = listOf(
            video(1, 1, at = 1),
            episode(1, 2, 1, 1, at = 2),
            video(2, 9, at = 3),
            episode(1, 3, 1, 2, at = 4),
            // The same show in another chat is another card: it opens that chat's episodes.
            episode(2, 4, 1, 1, at = 5),
            episode(2, 5, 1, 2, at = 6),
        )
        val cards = ListPages.cards(records)
        assertEquals(4, cards.size)
        assertEquals(listOf(false, true, false, true), cards.map { it.isShow })
        assertEquals(listOf(2L, 3L), cards[1].records.map { it.messageId })
        assertEquals(listOf(1L, 1L, 2L, 2L), cards.map { it.tile.chatId })
        // A show saved whole takes its episodes saved one by one under it.
        val key = (SeriesShelf.arrange(listOf(records[1].toMediaItem(), records[3].toMediaItem())).single() as ShelfEntry.Show).series.key
        val show = episode(1, 3, 1, 2, at = 0).copy(series = key)
        val withShow = ListPages.cards(listOf(show) + records)
        val showCard = withShow.first()
        assertTrue(showCard.isShow)
        assertEquals(3, showCard.records.size)
    }

    @Test
    fun `the kids lock keeps a locked chat's lines out of every list`() {
        val (state, infos) = make("Mixed")
        val id = infos[0].id
        var s = NamedListRules.add(state, id, video(1, 1), now).first
        s = NamedListRules.add(s, id, video(2, 2), now + 1).first
        val shown = NamedListRules.visible(s, allowed = setOf(2L)).single()
        assertEquals(listOf(2L), shown.records.map { it.chatId })
        assertTrue(NamedListRules.visible(s, allowed = emptySet()).single().records.isEmpty())
        val rows = HomeRows.listRows(NamedListRules.visible(s, allowed = emptySet()))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a list on Home is a row that moves like any other`() {
        val (state, infos) = make("Weekend", "Hidden")
        var s = NamedListRules.add(state, infos[0].id, video(1, 1), now).first
        s = NamedListRules.add(s, infos[1].id, video(1, 2), now).first
        s = NamedListRules.setHome(s, infos[1].id, false, now + 1)
        val key = NamedListRules.homeKey(infos[0].id)
        val rows = HomeRows.build(
            continueWatching = listOf(
                ResumeRecord(
                    chatId = 1, messageId = 3, fileId = 5, title = "Part way", chatTitle = "", sizeBytes = 1,
                    durationSec = 60, positionMs = 10_000, durationMs = 60_000, updatedAt = now,
                ),
            ),
            favourites = emptyList(), loaded = emptyMap(), recent = emptyList(),
            layout = HomeLayout(order = listOf(key, HomeOrder.CONTINUE)),
            lists = NamedListRules.visible(s, null),
        )
        assertEquals(listOf(key, "continue"), rows.map { it.key })
        val row = rows.first() as HomeRow.Listed
        assertEquals("Weekend", row.name)
        val editor = HomeOrder.editorRows(emptyList(), emptyList(), s.lists.filter { it.home })
        assertEquals(listOf("continue", "recent", key), editor.map { it.key })
    }

    @Test
    fun `a list made on the phone shows on the TV through the sync file`() = runBlocking {
        val dir = Files.createTempDirectory("tm-lists-sync").toFile()
        try {
            val phone = WatchedStore(WatchedStore.openDataStore(dir.resolve("phone").resolve(WatchedStore.FILE_NAME)))
            val tv = WatchedStore(WatchedStore.openDataStore(dir.resolve("tv").resolve(WatchedStore.FILE_NAME)))
            val info = phone.lists.create("Weekend")!!
            assertEquals(ListAdd.Added, phone.lists.add(info.id, video(-100, 5)))
            fun docOf(raw: Map<String, Any>) = SyncDocuments.collect(emptyMap(), raw, SyncBase(), emptyMap(), 5, now, "t")
            val file = SyncDocuments.encode(docOf(phone.rawEntries())).bytes
            val remote = (SyncDocuments.decode(file, 5) as SyncRead.Ok).doc
            val merged = SyncDocuments.merge(remote, docOf(tv.rawEntries()), now)
            assertTrue(tv.lists.mergeIn(merged.lists) > 0)
            val shown = tv.lists.visible.first().single()
            assertEquals("Weekend", shown.info.name)
            assertEquals(listOf(5L), shown.records.map { it.messageId })
            // Taken off on the TV, it goes from the phone at the next sync, and not back again.
            tv.lists.remove(info.id, setOf(shown.records.single().id))
            val back = SyncDocuments.merge(docOf(phone.rawEntries()), docOf(tv.rawEntries()), now + 1)
            phone.lists.mergeIn(back.lists)
            assertTrue(phone.lists.visible.first().single().records.isEmpty())
            assertEquals(0, tv.lists.mergeIn(back.lists))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- the store, at scale -----------------------------------------------------------------------

    @Test
    fun `the store keeps lists beside Watch later and opens five thousand lines in under a second`() = runBlocking {
        val dir = Files.createTempDirectory("tm-lists").toFile()
        try {
            val file = dir.resolve(WatchedStore.FILE_NAME)
            val watched = WatchedStore(WatchedStore.openDataStore(file))
            val store = watched.lists
            val info = store.create("  Lessons  ")!!
            assertEquals("Lessons", info.name)
            watched.watchLater.add(video(1, 1, at = 1))
            store.mergeIn(NamedLists(entries = List(5_000) { ListEntry(info.id, episode(-100, 10L + it, 1 + it / 9, 1 + it % 9, at = now + it)) }))
            assertEquals(5_000, store.now().entriesOf(info.id).size)
            assertEquals(ListAdd.Already, store.add(info.id, episode(-100, 10, 1, 1)))
            // Watch later and the lists never read each other's lines.
            assertEquals(1, watched.watchLater.items.first().size)
            assertEquals(1, store.visible.first().size)

            // The budget is wall time, so one slow slice on a loaded machine must not fail it: the
            // best of three cold opens (each from its own copy of the file) is held to the second.
            var ms = Long.MAX_VALUE
            lateinit var list: VisibleList
            lateinit var cards: List<*>
            repeat(3) { attempt ->
                val reopened = WatchedStore(WatchedStore.openDataStore(dir.resolve("copy$attempt").also { file.copyTo(it.resolve(WatchedStore.FILE_NAME)) }.resolve(WatchedStore.FILE_NAME)))
                val start = System.nanoTime()
                val read = reopened.lists.visible.first().single()
                val made = ListPages.cards(read.records)
                ms = minOf(ms, (System.nanoTime() - start) / 1_000_000)
                list = read
                cards = made
            }
            assertEquals(5_000, list.records.size)
            assertTrue("cards ${cards.size}", cards.isNotEmpty())
            assertTrue("opened in $ms ms", ms < 1_000)

            store.remove(info.id, setOf(list.records.first().id))
            assertEquals(4_999, store.now().entriesOf(info.id).size)
            assertEquals(1, store.now().removed.size)
            store.delete(info.id)
            assertTrue(store.now().lists.isEmpty())
            assertTrue(store.now().entries.isEmpty())
            assertEquals(1, watched.watchLater.items.first().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
