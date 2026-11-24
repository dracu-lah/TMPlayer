package com.tmplayer.data

import com.tmplayer.data.KeepNextPlanner.Copy
import com.tmplayer.data.KeepNextPlanner.Episode
import com.tmplayer.data.KeepNextPlanner.Input
import com.tmplayer.data.KeepNextPlanner.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Smart downloads' planner (WP-43): the window, space first with a running total, Wi-Fi only, restricted chats, removal. */
class KeepNextPlannerTest {

    private val gb = 1024L * 1024 * 1024
    private val headroom = CacheShelf.HEADROOM_BYTES

    private fun item(n: Int, size: Long = gb, saved: Boolean = true) = MediaItem(
        chatId = -100L, messageId = n.toLong(), fileId = n, title = "E$n", sizeBytes = size, durationSec = 2_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = n, canBeSaved = saved,
    )

    /** A show of [count] episodes, the first [watched] watched. */
    private fun show(count: Int, watched: Int = 0, size: Long = gb, saved: Boolean = true) =
        (1..count).map { Episode(item(it, size, saved), watched = it <= watched) }

    private fun ids(items: List<MediaItem>) = items.map { it.messageId.toInt() }

    @Test
    fun offPlansNothing() {
        val plan = KeepNextPlanner.plan(Input(show(5, 1), count = 0, freeBytes = 100 * gb, metered = false, removeAfterWatching = true))
        assertTrue(plan.empty)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun theNextTwoAfterTheFurthestWatchedAreQueuedWithTheirTotal() {
        // Episode 2 skipped by the viewer, 3 watched: next means 4 and 5, not 2.
        val episodes = show(8, 1).toMutableList().also { it[2] = it[2].copy(watched = true) }
        val plan = KeepNextPlanner.plan(Input(episodes, count = 2, freeBytes = 10 * gb, metered = false))
        assertEquals(listOf(4, 5), ids(plan.queue))
        assertEquals(2 * gb, plan.queueBytes)
        assertEquals(10 * gb - headroom, plan.roomBytes)
        assertEquals(null, plan.headline)
    }

    @Test
    fun aShowNotStartedBeginsAtTheFirstEpisode() {
        assertEquals(listOf(1, 2), ids(KeepNextPlanner.plan(Input(show(5), 2, 10 * gb, false)).queue))
    }

    @Test
    fun whatIsHereOrQueuedFillsItsPlaceAndAsksNothing() {
        val episodes = show(6, 2).toMutableList()
        episodes[2] = episodes[2].copy(copy = Copy.Here)
        episodes[3] = episodes[3].copy(copy = Copy.Queued)
        val plan = KeepNextPlanner.plan(Input(episodes, 3, 10 * gb, false))
        assertEquals(listOf(3, 4), ids(plan.kept))
        assertEquals(listOf(5), ids(plan.queue))
    }

    @Test
    fun spaceComesFirstAndNothingHalfFits() {
        // Room for one and a half after the headroom: the first fits, the second and third do not, even a small third.
        val episodes = show(6, 1).toMutableList().also { it[3] = Episode(item(4, size = 100L * 1024 * 1024), watched = false) }
        val plan = KeepNextPlanner.plan(Input(episodes, 3, freeBytes = headroom + gb + gb / 2, metered = false))
        assertEquals(listOf(2), ids(plan.queue))
        assertEquals(listOf(Reason.NoSpace, Reason.NoSpace), plan.skipped.map { it.reason })
        val first = plan.headline!!
        assertEquals(gb, first.neededBytes)
        assertEquals(gb / 2, first.freeBytes, "the space left after the running total")
    }

    @Test
    fun aDiskThatCouldNotBeReadFitsNothing() {
        val plan = KeepNextPlanner.plan(Input(show(3), 2, freeBytes = -1, metered = false))
        assertTrue(plan.queue.isEmpty())
        assertEquals(Reason.NoSpace, plan.headline?.reason)
    }

    @Test
    fun meteredWithWifiOnlyWaitsAndWithoutItGoesAhead() {
        val waiting = KeepNextPlanner.plan(Input(show(4, 1), 2, 10 * gb, metered = true))
        assertTrue(waiting.queue.isEmpty())
        assertEquals(listOf(Reason.WaitingForWifi, Reason.WaitingForWifi), waiting.skipped.map { it.reason })
        assertEquals(gb, waiting.headline?.neededBytes)
        assertEquals(listOf(2, 3), ids(KeepNextPlanner.plan(Input(show(4, 1), 2, 10 * gb, metered = true, wifiOnly = false)).queue))
    }

    @Test
    fun aRestrictedChatIsNeverPlannedAndNeverReachedPast() {
        val plan = KeepNextPlanner.plan(Input(show(6, 1, saved = false), 2, 100 * gb, false))
        assertTrue(plan.queue.isEmpty())
        assertEquals(listOf(2, 3), plan.skipped.map { it.item!!.messageId.toInt() })
        assertTrue(plan.skipped.all { it.reason == Reason.Restricted })
    }

    @Test
    fun aShowWatchedToTheEndHasNothingNext() {
        val plan = KeepNextPlanner.plan(Input(show(4, 4), 2, 10 * gb, false))
        assertEquals(Reason.NothingNext, plan.headline?.reason)
        assertTrue(plan.queue.isEmpty())
        assertEquals(Reason.NothingNext, KeepNextPlanner.plan(Input(emptyList(), 2, 10 * gb, false)).headline?.reason)
    }

    @Test
    fun removeAfterWatchingTakesOnlyWhatThePlannerKeptAndItsSpaceCounts() {
        val episodes = show(5, 2).toMutableList()
        episodes[0] = episodes[0].copy(copy = Copy.Here, keptForYou = true)
        // The viewer's own download of episode 2: theirs, never removed.
        episodes[1] = episodes[1].copy(copy = Copy.Here, keptForYou = false)
        val off = KeepNextPlanner.plan(Input(episodes, 2, freeBytes = headroom + gb, metered = false))
        assertTrue(off.remove.isEmpty())
        assertEquals(listOf(3), ids(off.queue))
        val on = KeepNextPlanner.plan(Input(episodes, 2, freeBytes = headroom + gb, metered = false, removeAfterWatching = true))
        assertEquals(listOf(1), ids(on.remove))
        assertEquals(listOf(3, 4), ids(on.queue), "the removed copy's gigabyte makes room for the second")
    }

    @Test
    fun duplicatesUnknownSizesAndAbsurdCountsAreHandled() {
        val episodes = show(4, 1).let { it + it[1] }
        val plan = KeepNextPlanner.plan(Input(episodes, 99, 100 * gb, false))
        assertEquals(listOf(2, 3, 4), ids(plan.queue), "once each, and never past the cap")
        val unknown = KeepNextPlanner.plan(Input(listOf(Episode(item(1, size = 0), false)), 2, 100 * gb, false))
        assertEquals(Reason.SizeUnknown, unknown.headline?.reason)
    }

    @Test
    fun aLongShowIsLinearAndBounded() {
        // A 20,000 episode chat as one show, the last episode watched near the end.
        val episodes = show(20_000, 19_990)
        val started = System.nanoTime()
        repeat(50) { KeepNextPlanner.plan(Input(episodes, KeepNextPlanner.DEFAULT_COUNT, 1_000 * gb, false)) }
        val plan = KeepNextPlanner.plan(Input(episodes, KeepNextPlanner.DEFAULT_COUNT, 1_000 * gb, false))
        assertEquals(listOf(19_991, 19_992), ids(plan.queue))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "fifty plans of 20,000 episodes well under five seconds")
    }

    @Test
    fun aSeriesBecomesEpisodesWatchedWhenAnyCopyIs() {
        val series = Series(
            key = "show",
            title = "Show",
            seasons = listOf(SeriesSeason(1, listOf(SeriesEpisode(item(1), 1, 1, copies = listOf(item(1), item(101))), SeriesEpisode(item(2), 1, 2)))),
        )
        val episodes = KeepNextPlanner.episodesOf(series, finished = { it.messageId == 101L }, copyOf = { Copy.None }, keptForYou = { false })
        assertEquals(listOf(true, false), episodes.map { it.watched })
        assertEquals(listOf(2), ids(KeepNextPlanner.plan(Input(episodes, 2, 100 * gb, false)).queue))
    }
}
