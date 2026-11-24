package com.tmplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Smart downloads' runner (WP-83): one pass per trigger, the open profile only, FLOOD_WAIT waited once, bounded. */
class KeepNextRunnerTest {

    private val gb = 1024L * 1024 * 1024

    private fun item(chat: Long, n: Int, saved: Boolean = true) = MediaItem(
        chatId = chat, messageId = n.toLong(), fileId = (chat * 1000 + n).toInt(), title = "E$n", sizeBytes = gb,
        durationSec = 2_400, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = n, canBeSaved = saved,
    )

    private inner class FakeHost : KeepNextRunner.Host {
        override var stamp = AccountStamp(1, null)
        var shows = (1L..3L).map { KeepNextShow(-it, "show$it") }.toSet()
        var listed: (KeepNextShow) -> KeepNextRunner.Listed? = { show ->
            KeepNextRunner.Listed("Show ${show.showKey}", (1..5).map { KeepNextPlanner.Episode(item(show.chatId, it), watched = it == 1) })
        }
        var free = 100 * gb
        var metered = false
        val queued = mutableListOf<Pair<KeepNextShow, List<Long>>>()
        val slept = mutableListOf<Long>()
        var failures = ArrayDeque<Throwable>()
        var onQueue: (KeepNextShow) -> Unit = {}
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun shows() = shows
        override suspend fun episodes(show: KeepNextShow) = listed(show)
        override suspend fun conditions() = KeepNextRunner.Conditions(free, metered)
        override suspend fun queue(show: KeepNextShow, title: String, items: List<MediaItem>): Int {
            gate?.await()
            failures.removeFirstOrNull()?.let { throw it }
            onQueue(show)
            queued += show to items.map { it.messageId }
            return items.size
        }
        override suspend fun sleep(ms: Long) {
            slept += ms
        }
    }

    private val host = FakeHost()
    private val runner = KeepNextRunner(host)

    @Test
    fun eachShowQueuesItsNextTwoAndTheReportSaysSo() = runBlocking {
        val summary = runner.pass()!!
        assertEquals(3, summary.shows)
        assertEquals(6, summary.queued)
        assertEquals(listOf(2L, 3L), host.queued.first().second)
        val report = runner.reports.value.getValue(KeepNextShow(-1, "show1").id)
        assertEquals(2, report.queued)
        assertEquals("Show show1", report.title)
    }

    @Test
    fun aSwitchMidPassQueuesNothingForTheOtherProfile() = runBlocking {
        host.onQueue = { host.stamp = AccountStamp(2, "work") }
        val summary = runner.pass()!!
        assertEquals(KeepNextRunner.Stop.Switched, summary.stopped)
        assertEquals("only the first show, queued before the switch", 1, host.queued.size)
        assertTrue("the old profile's reports never reach the new one", runner.reports.value.isEmpty())
    }

    @Test
    fun aFloodWaitIsWaitedOutOnceThenThePassEnds() = runBlocking {
        host.failures.addLast(IllegalStateException("FLOOD_WAIT_7"))
        val first = runner.pass()!!
        assertEquals(listOf(7_000L), host.slept)
        assertEquals(6, first.queued)
        host.failures.addLast(IllegalStateException("FLOOD_WAIT_3"))
        host.failures.addLast(IllegalStateException("FLOOD_WAIT_3"))
        host.queued.clear()
        val second = runner.pass()!!
        assertEquals(KeepNextRunner.Stop.FloodWait, second.stopped)
        assertTrue(host.queued.isEmpty())
        // A wait longer than the bound is not waited at all.
        host.failures.addLast(IllegalStateException("FLOOD_WAIT_86400"))
        host.slept.clear()
        assertEquals(KeepNextRunner.Stop.FloodWait, runner.pass()!!.stopped)
        assertTrue(host.slept.isEmpty())
    }

    @Test
    fun theBudgetBoundsAPass() = runBlocking {
        host.shows = (1L..50L).map { KeepNextShow(-it, "show$it") }.toSet()
        val summary = runner.pass()!!
        assertEquals(KeepNextRunner.MAX_QUEUED_PER_PASS, summary.queued)
        assertEquals(KeepNextRunner.Stop.Budget, summary.stopped)
        assertTrue(summary.shows <= KeepNextRunner.MAX_SHOWS_PER_PASS)
    }

    @Test
    fun meteredRestrictedAndUnlistedShowsQueueNothing() = runBlocking {
        host.metered = true
        runner.pass()
        assertTrue(host.queued.isEmpty())
        assertEquals(KeepNextPlanner.Reason.WaitingForWifi, runner.reports.value.values.first().plan.headline?.reason)
        host.metered = false
        host.listed = { show -> if (show.chatId == -1L) null else KeepNextRunner.Listed("x", (1..3).map { KeepNextPlanner.Episode(item(show.chatId, it, saved = false), false) }) }
        runner.pass()
        assertTrue("an unlisted chat is never fetched for, a restricted one never planned", host.queued.isEmpty())
    }

    @Test
    fun aFailingShowLetsTheRestGoOn() = runBlocking {
        host.listed = { show -> if (show.chatId == -1L) error("garbage") else KeepNextRunner.Listed("x", (1..3).map { KeepNextPlanner.Episode(item(show.chatId, it), false) }) }
        assertEquals(4, runner.pass()!!.queued)
        host.failures.addLast(IllegalArgumentException("not a flood"))
        host.queued.clear()
        assertEquals(2, runner.pass()!!.queued)
    }

    @Test
    fun triggersDuringAPassAreFoldedIntoOneMoreRound() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        host.gate = gate
        val first = async { runner.pass() }
        yield()
        assertNull("a second trigger while one runs returns at once", runner.pass())
        assertNull(runner.pass())
        gate.complete(Unit)
        first.await()
        assertEquals("the first round and one more for both triggers", 6, host.queued.size)
    }

    @Test
    fun noShowsIsNoWork() = runBlocking {
        host.shows = emptySet()
        assertEquals(KeepNextRunner.Summary(), runner.pass())
        assertTrue(host.queued.isEmpty())
    }

    @Test
    fun theStoredShowsRoundTripAndBadLinesAreSkipped() {
        val shows = setOf(KeepNextShow(-100123, "harbour notes"), KeepNextShow(42, "other"))
        assertEquals(shows, KeepNextShows.decode(KeepNextShows.encode(shows)))
        assertEquals(
            setOf(KeepNextShow(7, "ok")),
            KeepNextShows.decode("7|ok\n|nochat\nabc|x\n0|zero\n8|\n7|ok\n9|" + "k".repeat(500)),
        )
        val many = (1L..500L).map { KeepNextShow(it, "s$it") }.toSet()
        assertEquals(KeepNextShows.MAX_SHOWS, KeepNextShows.decode(KeepNextShows.encode(many)).size)
        assertEquals(emptySet<KeepNextShow>(), KeepNextShows.with(shows, KeepNextShow(42, "other"), on = false) - KeepNextShow(-100123, "harbour notes"))
    }

    @Test
    fun theRowSaysWhyWithTheNumbersAndARunningPassIsSeen() = runBlocking {
        val show = KeepNextShow(-1, "show1")
        fun report(plan: KeepNextPlanner.Plan) = KeepNextRunner.Report(show, "Show", plan, 0)
        val bytes: (Long) -> String = { "${it}B" }
        val item = item(-1, 2)
        assertEquals(com.tmplayer.i18n.L.downloadsKeptWaitingWifi, KeepNextRunner.detail(report(KeepNextPlanner.Plan(skipped = listOf(KeepNextPlanner.Skip(item, KeepNextPlanner.Reason.WaitingForWifi)))), bytes))
        val room = KeepNextRunner.detail(report(KeepNextPlanner.Plan(skipped = listOf(KeepNextPlanner.Skip(item, KeepNextPlanner.Reason.NoSpace, 300, 100)))), bytes)
        assertTrue(room, room.contains("300B") && room.contains("100B"))
        assertEquals(com.tmplayer.i18n.L.downloadsKeptReady(count = 2), KeepNextRunner.detail(report(KeepNextPlanner.Plan(queue = listOf(item, item))), bytes))
        for (reason in KeepNextPlanner.Reason.entries) {
            assertTrue(reason.name, KeepNextRunner.detail(report(KeepNextPlanner.Plan(skipped = listOf(KeepNextPlanner.Skip(item, reason)))), bytes).isNotBlank())
        }
        // The flag is up while a pass runs and down after.
        val gate = CompletableDeferred<Unit>()
        host.gate = gate
        val pass = async { runner.pass() }
        yield()
        assertTrue(runner.running.value)
        gate.complete(Unit)
        pass.await()
        assertTrue(!runner.running.value)
    }
}
