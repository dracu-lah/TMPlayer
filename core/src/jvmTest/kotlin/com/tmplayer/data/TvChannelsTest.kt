package com.tmplayer.data

import com.tmplayer.data.TvChannels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvChannelsTest {

    private var nextId = 1L

    private fun plainName(n: Long) = "Holiday " + ('a' + (n % 26).toInt()) + ('a' + (n / 26 % 26).toInt()) + ".mp4"

    private fun video(chatId: Long, fileName: String = plainName(nextId), canBeSaved: Boolean = true) = MediaItem(
        chatId = chatId,
        messageId = nextId,
        fileId = nextId.toInt(),
        title = fileName,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 1000 + nextId.toInt(),
        miniThumbnail = null,
        date = (nextId++).toInt(),
        fileName = fileName,
        canBeSaved = canBeSaved,
    )

    private fun chat(id: Long) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel)

    private val titles: (Long) -> String = { "Chat $it" }

    private fun later(item: MediaItem) = WatchLaterRecord(
        chatId = item.chatId,
        messageId = item.messageId,
        fileId = item.fileId,
        title = item.title,
        fileName = item.fileName,
        chatTitle = "Chat ${item.chatId}",
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        addedAt = item.messageId,
    )

    private fun ids(programs: List<TvChannels.Program>, channel: Channel) =
        programs.filter { it.channel == channel }.map { it.internalId }

    @Test
    fun `recently added fills the default channel, newest first`() {
        val a = video(1)
        val b = video(2)
        val rows = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), mapOf(1L to listOf(a), 2L to listOf(b)), recent = listOf(a, b))
        val programs = TvChannels.plan(rows, allowed = null, chatTitle = titles)!!
        assertEquals(listOf("recent:2_${b.messageId}", "recent:1_${a.messageId}"), ids(programs, Channel.Recent))
        val first = programs.first()
        assertEquals("Chat 2", first.description)
        assertEquals(60_000L, first.durationMs)
        assertTrue(first.weight > programs[1].weight)
        assertEquals(b.thumbnailFileId, first.art.thumbnailFileId)
        val target = first.target as TvChannels.Target.Play
        assertEquals(b.messageId, target.video.messageId)
    }

    @Test
    fun `nothing is planned while recently added is still loading`() {
        val rows = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), emptyMap(), recent = null)
        assertNull(TvChannels.plan(rows, allowed = null, chatTitle = titles))
    }

    @Test
    fun `recently added fills the default channel with nothing starred`() {
        val a = video(5)
        val chats = listOf(chat(5))
        fun rows(recent: List<MediaItem>?) = HomeRows.build(
            emptyList(), HomeRows.homeChats(chats, emptySet(), recent), emptyMap(), recent,
            recentMinStarred = 1, starred = emptySet(), autoFill = true,
        )
        assertNull(TvChannels.plan(rows(null), allowed = null, chatTitle = titles))
        val programs = TvChannels.plan(rows(listOf(a)), allowed = null, chatTitle = titles)!!
        assertEquals(listOf("recent:5_${a.messageId}"), ids(programs, Channel.Recent))
    }

    @Test
    fun `the default channel holds only a few of the newest`() {
        val many = (1..12).map { video(it.toLong()) }
        val rows = HomeRows.build(emptyList(), many.map { chat(it.chatId) }, emptyMap(), recent = many)
        val programs = TvChannels.plan(rows, allowed = null, chatTitle = titles)!!
        assertEquals(TvChannels.RECENT_LIMIT, ids(programs, Channel.Recent).size)
    }

    @Test
    fun `no recently added leaves the default channel empty`() {
        val programs = TvChannels.plan(emptyList(), allowed = null, chatTitle = titles)!!
        assertTrue(programs.isEmpty())
    }

    @Test
    fun `the kids lock keeps every other chat off the home screen`() {
        val a = video(1)
        val b = video(2)
        val c = video(2)
        val rows = HomeRows.build(
            emptyList(),
            listOf(chat(1), chat(2)),
            mapOf(1L to listOf(a), 2L to listOf(b)),
            recent = listOf(a, b),
            watchLater = listOf(later(c)),
        )
        val programs = TvChannels.plan(rows, allowed = setOf(1L), chatTitle = titles)!!
        assertEquals(listOf(1L), programs.map { it.chatId }.distinct())
        assertTrue(ids(programs, Channel.WatchLater).isEmpty())
        // An empty set is a lock with nothing chosen: nothing at all.
        assertTrue(TvChannels.plan(rows, allowed = emptySet(), chatTitle = titles)!!.isEmpty())
    }

    @Test
    fun `a video whose chat restricts saving stays off`() {
        val open = video(1)
        val closed = video(2, canBeSaved = false)
        val rows = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), emptyMap(), recent = listOf(open, closed))
        val programs = TvChannels.plan(rows, allowed = null, chatTitle = titles)!!
        assertEquals(listOf("recent:1_${open.messageId}"), ids(programs, Channel.Recent))
    }

    @Test
    fun `a folded show is one card that opens the show`() {
        val e1 = video(3, "Some Show S01E01.mkv")
        val e2 = video(3, "Some Show S01E02.mkv")
        val other = video(4)
        val rows = HomeRows.build(emptyList(), listOf(chat(3), chat(4)), emptyMap(), recent = listOf(e1, e2, other))
        val programs = TvChannels.plan(rows, allowed = null, chatTitle = titles)!!
        val show = programs.single { it.isShow }
        assertTrue(show.art.titled)
        val target = show.target as TvChannels.Target.Show
        assertEquals(3L, target.chatId)
        // Films and shows only: the plain video beside the show stays off the home screen.
        assertEquals(1, programs.count { it.channel == Channel.Recent })
    }

    @Test
    fun `plain videos fill the row only when there is no film or show`() {
        val a = video(1)
        val b = video(2)
        val rows = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), emptyMap(), recent = listOf(a, b))
        val programs = TvChannels.plan(rows, allowed = null, chatTitle = titles)!!
        assertEquals(2, programs.count { it.channel == Channel.Recent })
    }

    @Test
    fun `watch later and new episodes get channels of their own, with the resume point`() {
        val a = video(1)
        val b = video(2)
        val saved = video(7)
        val fresh = video(8, "Other Show S02E05.mkv")
        val rows = HomeRows.build(
            emptyList(),
            listOf(chat(1), chat(2)),
            emptyMap(),
            recent = listOf(a, b),
            watchLater = listOf(later(saved)),
            fresh = listOf(NewEpisode(fresh, "Other Show", "S02E05")),
        )
        val art = mapOf(later(saved).key to saved)
        val programs = TvChannels.plan(
            rows,
            allowed = null,
            chatTitle = titles,
            progress = { _, message -> if (message == saved.messageId) WatchPoint(20_000, 60_000) else null },
            art = art,
        )!!
        val laterCard = programs.single { it.channel == Channel.WatchLater }
        assertEquals(20_000L, laterCard.positionMs)
        assertEquals(saved.thumbnailFileId, laterCard.art.thumbnailFileId)
        val episode = programs.single { it.channel == Channel.NewEpisodes }
        assertEquals("Other Show", episode.title)
        assertTrue(episode.description.startsWith("S02E05"))
    }

    @Test
    fun `unchanged cards are not written again`() {
        val a = video(1)
        val b = video(2)
        val rows = HomeRows.build(emptyList(), listOf(chat(1), chat(2)), emptyMap(), recent = listOf(a, b))
        val programs = TvChannels.plan(rows, allowed = null, chatTitle = titles)!!
        val existing = programs.mapIndexed { i, p -> p.internalId to TvChannels.Existing(i.toLong(), p.fingerprint) }.toMap()
        assertTrue(TvChannels.diff(existing, programs).isEmpty)
    }

    @Test
    fun `a card whose picture copy is gone is written again`() {
        val a = video(1)
        val rows = HomeRows.build(emptyList(), listOf(chat(1)), emptyMap(), recent = listOf(a))
        val programs = TvChannels.plan(rows, allowed = null, chatTitle = titles)!!
        // The publisher reads a row whose copy has left the disk as one with no fingerprint.
        val existing = programs.mapIndexed { i, p -> p.internalId to TvChannels.Existing(i.toLong(), null) }.toMap()
        val diff = TvChannels.diff(existing, programs)
        assertEquals(programs.map { it.internalId }, diff.update.map { it.second.internalId })
        assertTrue(diff.insert.isEmpty() && diff.delete.isEmpty())
    }

    @Test
    fun `new, changed and gone cards are inserted, updated and deleted`() {
        val a = video(1)
        val b = video(2)
        val c = video(1)
        val before = TvChannels.plan(
            HomeRows.build(emptyList(), listOf(chat(1), chat(2)), emptyMap(), recent = listOf(a, b)),
            allowed = null, chatTitle = titles,
        )!!
        val existing = before.mapIndexed { i, p -> p.internalId to TvChannels.Existing(100L + i, p.fingerprint) }.toMap()
        // c arrives, b drops out, and a has been started meanwhile, so its card shows a place.
        val after = TvChannels.plan(
            HomeRows.build(emptyList(), listOf(chat(1), chat(2)), emptyMap(), recent = listOf(a, c)),
            allowed = null, chatTitle = titles,
            progress = { _, message -> if (message == a.messageId) WatchPoint(5_000, 60_000) else null },
        )!!
        val diff = TvChannels.diff(existing, after)
        assertEquals(listOf("recent:1_${c.messageId}"), diff.insert.map { it.internalId })
        assertEquals(listOf(existing.getValue("recent:1_${a.messageId}").rowId), diff.update.map { it.first })
        assertEquals(listOf(existing.getValue("recent:2_${b.messageId}").rowId), diff.delete)
    }

    @Test
    fun `a card the viewer removed is not put back while it is still wanted`() {
        val a = video(1)
        val b = video(2)
        val programs = TvChannels.plan(
            HomeRows.build(emptyList(), listOf(chat(1), chat(2)), emptyMap(), recent = listOf(a, b)),
            allowed = null, chatTitle = titles,
        )!!
        val existing = programs.associate { it.internalId to TvChannels.Existing(1, "stale", browsable = false) }
        assertTrue(TvChannels.diff(existing, programs).isEmpty)
    }
}
