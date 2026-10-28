package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** HX20: a show's Mark as watched and Watch later, as its page and its menu offer them. */
class ShowMarksTest {

    private var nextId = 1L

    private fun video(fileName: String, chatId: Long = 7, size: Long = 1) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = nextId.toInt(),
        title = fileName,
        sizeBytes = size,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = nextId.toInt(),
        fileName = fileName,
    )

    private fun showOf(items: List<MediaItem>) =
        SeriesShelf.arrange(items).filterIsInstance<ShelfEntry.Show>().single().series

    /** Three episodes of season one, two of season two. */
    private fun show(): Series = showOf((1..3).map { video("Harbour.Notes.S01E0$it.mkv") } + (1..2).map { video("Harbour.Notes.S02E0$it.mkv") })

    private fun lines(choices: List<ShowMarks.Choice>) = choices.map { Triple(it.scope, it.watched, it.count) }

    @Test
    fun `a show not started offers its season and then the whole show, with their counts`() {
        val show = show()
        val lines = ShowMarks.choices(show, show.seasons.first()) { false }
        assertEquals(listOf(Triple(ShowMarks.Scope.Season, true, 3), Triple(ShowMarks.Scope.Show, true, 5)), lines(lines))
        assertEquals(1, lines.first().season)
    }

    @Test
    fun `each line counts only what it changes, and unwatched comes once something is watched`() {
        val show = show()
        val done = setOf(show.seasons[0].episodes[0].item.id, show.seasons[1].episodes[0].item.id)
        val lines = ShowMarks.choices(show, show.seasons.first()) { it.item.id in done }
        assertEquals(
            listOf(
                Triple(ShowMarks.Scope.Season, true, 2),
                Triple(ShowMarks.Scope.Show, true, 3),
                Triple(ShowMarks.Scope.Season, false, 1),
                Triple(ShowMarks.Scope.Show, false, 2),
            ),
            lines(lines),
        )
    }

    @Test
    fun `a show of one season is not offered twice, and from a card only the whole show is`() {
        val one = showOf((1..2).map { video("Lighthouse.S01E0$it.mkv") })
        assertEquals(listOf(ShowMarks.Scope.Season), ShowMarks.choices(one, one.seasons.first()) { false }.map { it.scope })
        assertEquals(listOf(ShowMarks.Scope.Show), ShowMarks.choices(one, null) { false }.map { it.scope })
        // Watched whole: nothing but unwatched.
        assertEquals(listOf(false), ShowMarks.choices(one, one.seasons.first()) { true }.map { it.watched })
    }

    @Test
    fun `marking takes one copy an episode and unmarking every copy`() {
        val series = showOf(
            (1..2).flatMap { n -> listOf(video("Harbour.Notes.S01E0$n.mkv", size = 350), video("Harbour.Notes.S01E0$n.mkv", size = 1800)) },
        )
        val episodes = series.episodes
        assertEquals(2, ShowMarks.toMark(episodes) { it.copies.last() }.size)
        assertEquals(4, ShowMarks.toUnmark(episodes).size)
        val marked = ShowMarks.toMark(episodes) { it.copies.first() }
        assertEquals(2, ShowMarks.copiesOf(episodes, marked).size)
    }

    @Test
    fun `five hundred episodes are one line of five hundred`() {
        val big = showOf((1..500).map { video("Long.Anime.S01E${it.toString().padStart(3, '0')}.mkv") })
        val lines = ShowMarks.choices(big, big.seasons.first()) { false }
        assertEquals(1, lines.size)
        assertEquals(500, lines.single().count)
    }

    @Test
    fun `a whole season marked in one edit ticks every episode, forgets their places and leaves Watch later`() = runBlocking {
        val dir = Files.createTempDirectory("tm-show-marks").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
        val show = show()
        val first = show.seasons.first().episodes.first().item
        settings.saveResumePosition(
            first.chatId, first.messageId, 600_000L, 3_600_000L,
            ResumeRecord.encode(fileId = 1, title = "Harbour Notes", chatTitle = "Shows", sizeBytes = 1, durationSec = 3_600, updatedAt = 5),
        )
        assertEquals(600_000L, settings.resumePosition(first.chatId, first.messageId))
        watched.watchLater.add(WatchLaterRecord.of(first, "Shows", 1L))
        val season = ShowMarks.choices(show, show.seasons.first()) { false }.first()

        assertEquals(3, ShowEdits.apply(settings, watched, season, "Shows", now = 1_000L))

        val marks = watched.watched.first()
        for (episode in show.seasons.first().episodes) assertTrue(ProgressKeys.of(episode.item.chatId, episode.item.messageId) in marks)
        assertFalse(ProgressKeys.of(show.seasons[1].episodes[0].item.chatId, show.seasons[1].episodes[0].item.messageId) in marks)
        assertEquals(0L, settings.resumePosition(first.chatId, first.messageId))
        assertFalse(watched.watchLater.contains(first.chatId, first.messageId))

        // Unwatched again: every mark off.
        val back = ShowMarks.choices(show, show.seasons.first()) { ProgressKeys.of(it.item.chatId, it.item.messageId) in marks }
            .first { !it.watched && it.scope == ShowMarks.Scope.Season }
        ShowEdits.apply(settings, watched, back, "Shows", now = 2_000L)
        val after = watched.watched.first()
        for (episode in show.seasons.first().episodes) assertFalse(ProgressKeys.of(episode.item.chatId, episode.item.messageId) in after)
    }

    @Test
    fun `a show on Watch later is one line, kept apart from its episodes and from finishing one`() = runBlocking {
        val dir = Files.createTempDirectory("tm-show-later").toFile()
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
        val later = watched.watchLater
        val show = show()
        val episode = show.episodes.first().item
        later.add(WatchLaterRecord.ofShow(show, "Shows", addedAt = 10))
        later.add(WatchLaterRecord.of(episode, "Shows", addedAt = 20))
        // Saved again: moved up, not listed twice.
        later.add(WatchLaterRecord.ofShow(show, "Shows", addedAt = 30))
        val items = later.items.first()
        assertEquals(2, items.size)
        val line = items.first()
        assertTrue(line.isShow)
        assertEquals("Harbour Notes", line.title)
        assertEquals(setOf(WatchLaterRecord.showId(7, show.key)), later.showIds.first())
        assertEquals(setOf(ProgressKeys.of(episode.chatId, episode.messageId)), later.keys.first())

        // Its page is built again from the line alone, on the episode it was saved by.
        val stub = line.stubSeries()
        assertNotNull(stub)
        stub ?: return@runBlocking
        assertEquals(show.key, stub.key)
        assertEquals(show.cover.messageId, stub.cover.messageId)

        // Finishing the anchor episode crosses off that episode's own line, never the show's.
        watched.markWatched(WatchedRecord.of(show.cover, "Shows", 40, manual = false))
        watched.markWatched(WatchedRecord.of(episode, "Shows", 41, manual = false))
        assertEquals(listOf(true), later.items.first().map { it.isShow })

        // Removed by its own id, with Undo putting it back.
        val undo = HistoryEdits.removeLater(watched, listOf(line.id), 1)
        assertTrue(later.items.first().isEmpty())
        HistoryEdits.undo(SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME))), watched, undo)
        assertEquals(1, later.items.first().size)
        later.removeShow(7, show.key)
        assertTrue(later.items.first().isEmpty())
    }

    @Test
    fun `a show's line reads back whole, and garbage or an old build's reading of it is refused`() {
        val show = show()
        val line = WatchLaterRecord.ofShow(show, "Shows", addedAt = 5)
        val back = WatchLaterRecord.decode(line.id, line.encode())
        assertEquals(line, back)
        assertNull(WatchLaterRecord.decode("7_s:", line.encode()))
        assertNull(WatchLaterRecord.decode("7_s:harbour notes", "1\u001F2"))
        // A show's line without its anchor's message.
        assertNull(WatchLaterRecord.decode(line.id, line.copy(series = "").encode()))
        // A video's line is unchanged by all this.
        val video = WatchLaterRecord.of(show.cover, "Shows", 6)
        assertEquals(video, WatchLaterRecord.decode(video.id, video.encode()))
        assertNull(video.stubSeries())
    }
}
