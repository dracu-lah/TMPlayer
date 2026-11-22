package com.tmplayer.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * The download queue across profiles and databases (HX88), as a seeded model test: thousands of
 * random sequences of what a viewer and Telegram can do (enqueue, pause, persists, a persist that
 * lands after a switch, switches, failed and cancelled switches, cancels, sign outs, an ended
 * session, Sign in again, a restore from backup, Remove all accounts, a restart, an upgrade from
 * before markers) run on the real rows ([OfflineDownloads]), stamps ([DownloadStamps]), markers
 * ([DatabaseMarker]), scoped store ([AccountScopedDataStore]) and switch pause ([SwitchPause]).
 *
 * A TDLib file id means a video only in its database: here id `n` of database `e` of profile `p`
 * is the video (chat [chatOf] p e, message n), so the same id in two databases is two videos.
 * After every step:
 * - (a) a row, a start and a resume are the video their id is in the open profile's database now;
 * - (b) a stored queue is tagged with the marker of the database its videos are of;
 * - (c) a delete (Cancel) targets the open profile's database, and a stored queue of the profile's
 *   database now is never dropped;
 * - (d) one profile with no switch events keeps its queue across restarts, as before profiles.
 *
 * A failure prints the seed and the steps, shrunk to the fewest that still fail.
 */
class DownloadQueueModelTest {

    private val root: File = Files.createTempDirectory("queue-model").toFile()
    private val base = TempPaths(File(root, "device"))
    private val a = Profile("main", null)
    private val b = Profile("pbbbbb", "pbbbbb")
    private val profiles = listOf(a, b)
    private val book = testBook(File(root, "book"))
    private val raw = openSafeDataStore(File(root, SettingsStore.FILE_NAME))
    private val scoped = AccountScopedDataStore(raw)
    private val settings = SettingsStore(scoped)

    @After
    fun tearDown() {
        OfflineDownloads.resetForTest()
        Td.platformPathsForTest(null)
        Accounts.resetForTest()
    }

    private enum class Kind {
        Enqueue, Start, LateUpdate, PauseAll, Persist, PersistAcrossSwitch, PersistAcrossRenew, Switch, FailedSwitch,
        StrayCancelSwitch, Cancel, CancelThenRestore, RestoreAcrossSwitch, RestoreAcrossRenew, SignOutLast, SignOutNotLast, SessionEnded, SignInAgain,
        RestoreBackup, RemoveAll, Restart, RunnerCatchesUp, MarkerUnreadable,
    }

    /** How often each kind comes up: the work that races (queue, start, late updates) more than the rest. */
    private fun weight(kind: Kind): Int = when (kind) {
        Kind.Enqueue -> 5
        Kind.Start, Kind.LateUpdate, Kind.RunnerCatchesUp -> 3
        else -> 1
    }

    private data class Step(val kind: Kind, val pick: Int) {
        override fun toString() = "$kind($pick)"
    }

    private val singleProfileKinds = listOf(
        Kind.Enqueue, Kind.Start, Kind.LateUpdate, Kind.PauseAll, Kind.Persist, Kind.Cancel, Kind.CancelThenRestore,
        Kind.RestoreBackup, Kind.Restart, Kind.RunnerCatchesUp, Kind.MarkerUnreadable,
    )

    private class Violation(message: String) : Exception(message)

    private class Job(val request: DownloadRequest, val rows: Long)

    /** One run's world: the model's truth, and the runner's own state as DownloadService keeps it. */
    private inner class World(val single: Boolean) {
        val epoch = IntArray(profiles.size)
        var open = 0
        var stamps = DownloadStamps()
        val requests = LinkedHashMap<Int, DownloadRequest>()
        val waiting = ArrayList<Int>()
        val jobs = ArrayList<Job>()
        var held: SwitchPause.Held? = null

        /** Which profile and database each marker ever seen is. */
        val markers = HashMap<String, Pair<Int, Int>>()

        fun chatOf(p: Int, e: Int): Long = -(1_000L * (p + 1) + e)

        fun dirOf(p: Int): File = AccountDirs.pathsFor(base, profiles[p]).databaseDir

        fun noteMarker(p: Int) {
            DatabaseMarker.ensure(dirOf(p))?.let { markers[it] = p to epoch[p] }
        }

        fun check(ok: Boolean, what: () -> String) {
            if (!ok) throw Violation(what())
        }

        /** (a) for one id: it is the video it means in the open profile's database now. */
        fun isOpenVideo(request: DownloadRequest, what: String) {
            check(request.chatId == chatOf(open, epoch[open]) && request.messageId == request.fileId.toLong()) {
                "$what ${request.fileId} is chat ${request.chatId}, but in profile $open database ${epoch[open]} it is chat ${chatOf(open, epoch[open])}"
            }
        }

        fun request(p: Int, e: Int, id: Int) = DownloadRequest(
            fileId = id, title = "v$id", sizeBytes = 1_000, chatId = chatOf(p, e), messageId = id.toLong(),
            chatTitle = "c", durationSec = 60, mimeType = "video/mp4", fileName = "v$id.mp4",
        )

        /** The service's own write (`DownloadService.persistNow` runs this same call). */
        suspend fun persist(afterSnapshot: suspend () -> Unit = {}) = OfflineDownloads.persistQueue(settings, afterSnapshot = afterSnapshot)

        suspend fun storedOf(p: Int): Pair<List<DownloadRequest>, String?> {
            val view = scoped.everyProfileNow()[profiles[p].folder] ?: return emptyList<DownloadRequest>() to null
            return DownloadRequest.decodeAll(view[QUEUE]) to view[QUEUE_DB]
        }

        /** DownloadService.stopForSwitch: every job of the last rows stops (a late update may still come). */
        fun stopRunner() {
            requests.clear()
            waiting.clear()
            stamps.clear()
        }

        fun publish(p: Int) {
            book.update { it.copy(activeId = profiles[p].id) }
            open = p
            Accounts.resetForTest(book)
        }

        suspend fun restore(cancelled: Int? = null) {
            val before = (0 until profiles.size).associateWith { storedOf(it) }
            OfflineDownloads.restore(settings)
            noteMarker(open)
            // (c) a queue of the open database now is never dropped.
            val (was, _) = before.getValue(open)
            val (now, tag) = storedOf(open)
            // What restore wrote is tagged with the marker of the database it is for.
            if (now.isNotEmpty() && Td.platformPaths != null) {
                check(tag == DatabaseMarker.ensure(dirOf(open))) { "restore wrote the open database's queue tagged $tag" }
            }
            val current = was.filter { it.chatId == chatOf(open, epoch[open]) }.map { it.fileId }.toSet() - setOfNotNull(cancelled)
            check(now.map { it.fileId }.containsAll(current)) { "restore dropped the open database's queue $current" }
        }

        /** The profile's database is a new one: TDLib kept the folder, the marker is renewed (forgetAccount, forgetSession). */
        fun renewOpen() {
            epoch[open]++
            Td.renewDatabaseMarker()
            noteMarker(open)
        }

        /** Its folder deleted and made again (Sign in again, Remove all accounts). */
        fun remake(p: Int) {
            epoch[p]++
            AccountDirs.wipe(AccountDirs.pathsFor(base, profiles[p]))
            AccountDirs.make(AccountDirs.pathsFor(base, profiles[p]))
            // TDLib writes its files once it opens it.
            File(dirOf(p), "td.binlog").writeText("db")
            noteMarker(p)
        }

        fun beforeSwitch() {
            val now = OfflineDownloads.active.value.values.filter { it.busy }
                .map { SwitchPause.Paused(it.fileId, it.request.chatId, it.request.messageId) }
            held = SwitchPause.hold(held, Accounts.stamp, OfflineDownloads.rowsGeneration, now)
            pauseAll()
        }

        fun pauseAll() {
            for (row in OfflineDownloads.active.value.values) OfflineDownloads.stage(row.fileId, OfflineDownloads.Stage.Paused)
            waiting.clear()
            requests.clear()
        }

        fun switchCancelled() {
            val was = held
            held = null
            val rows = OfflineDownloads.active.value.mapValues { (_, row) -> row.request.chatId to row.request.messageId }
            for (id in SwitchPause.toResume(was, Accounts.stamp, OfflineDownloads.rowsGeneration, rows)) {
                val row = OfflineDownloads.active.value[id] ?: continue
                isOpenVideo(row.request, "resumed")
                requests[id] = row.request
                stamps.queued(id)
                waiting += id
                OfflineDownloads.stage(id, OfflineDownloads.Stage.Queued)
            }
        }

        suspend fun step(step: Step) {
            val pick = step.pick
            when (step.kind) {
                Kind.Enqueue -> {
                    val id = 1 + pick % 3
                    if (OfflineDownloads.active.value.containsKey(id)) return
                    val request = request(open, epoch[open], id)
                    requests[id] = request
                    stamps.queued(id)
                    waiting += id
                    OfflineDownloads.note(OfflineDownloads.Progress(request, 0, request.sizeBytes, OfflineDownloads.Stage.Queued, order = OfflineDownloads.nextOrder()))
                    persist()
                }
                Kind.Start -> {
                    val id = waiting.removeFirstOrNull() ?: return
                    val request = requests[id] ?: return
                    if (!stamps.startable(id)) {
                        // As DownloadService.pump (the same two calls): the row of the same id is removed only in the rows it was queued in.
                        val rowBefore = OfflineDownloads.active.value[id]
                        requests.remove(id)
                        val rows = stamps.discard(id)
                        OfflineDownloads.forgetQueuedIn(id, rows)
                        // (e) another database's video under the same id is never removed by a skipped start.
                        check(rowBefore == null || rows == OfflineDownloads.rowsGeneration || OfflineDownloads.active.value[id] != null) {
                            "a skipped start of id $id removed the open database's row of that id"
                        }
                        return
                    }
                    isOpenVideo(request, "started")
                    jobs += Job(request, stamps.rowsOf(id))
                    OfflineDownloads.stage(id, OfflineDownloads.Stage.Running)
                }
                Kind.LateUpdate -> {
                    // A job's update, however late: of the rows it was queued in, or dropped.
                    val job = jobs.getOrNull(pick % (jobs.size.coerceAtLeast(1))) ?: return
                    // As the runner does: onto the row of its id, when there is one (another video's, after a new database).
                    OfflineDownloads.ifRows(job.rows) {
                        if (OfflineDownloads.active.value.containsKey(job.request.fileId)) {
                            OfflineDownloads.note(OfflineDownloads.Progress(job.request, 500, job.request.sizeBytes, OfflineDownloads.Stage.Running))
                        }
                    }
                    persist()
                }
                Kind.PauseAll -> {
                    pauseAll()
                    persist()
                }
                Kind.Persist -> persist()
                Kind.PersistAcrossSwitch -> {
                    // A conflated persist: the snapshot is taken, then the switch lands, then it writes.
                    persist {
                        beforeSwitch()
                        publish(1 - open)
                    }
                    restore()
                }
                Kind.PersistAcrossRenew -> {
                    // The same across a session end: the snapshot is taken, the rows drop and the marker is
                    // renewed, then it writes. Its rows are the old database's and must not get the new marker.
                    persist { renewOpen() }
                    restore()
                }
                Kind.Switch -> {
                    beforeSwitch()
                    persist()
                    publish(1 - open)
                    if (pick % 2 == 0) persist()
                    restore()
                }
                Kind.RunnerCatchesUp -> stopRunner()
                Kind.MarkerUnreadable -> {
                    // The marker cannot be read for one write (a full disk, a folder gone a moment).
                    Td.platformPathsForTest(null)
                    try {
                        persist()
                    } finally {
                        Td.platformPathsForTest(base)
                    }
                }
                Kind.FailedSwitch -> {
                    beforeSwitch()
                    persist()
                    switchCancelled()
                }
                Kind.StrayCancelSwitch -> switchCancelled()
                Kind.Cancel -> {
                    val row = OfflineDownloads.active.value.values.sortedBy { it.fileId }.getOrNull(pick % 5) ?: return
                    // (c) TDLib's delete of the file goes to the open profile's database.
                    isOpenVideo(row.request, "deleted")
                    requests.remove(row.fileId)
                    waiting.remove(row.fileId)
                    stamps.forget(row.fileId)
                    OfflineDownloads.forget(row.fileId)
                    persist()
                }
                Kind.CancelThenRestore -> {
                    // A Cancel whose write is still pending, and the screen composing again (restore runs
                    // again): the row must not come back from the stored queue, which still has it. Only
                    // once the first restore ran (before it, the stored queue is what the rows are made of).
                    if (OfflineDownloads.rowsOwner == null) return
                    val row = OfflineDownloads.active.value.values.sortedBy { it.fileId }.getOrNull(pick % 5) ?: return
                    isOpenVideo(row.request, "deleted")
                    requests.remove(row.fileId)
                    waiting.remove(row.fileId)
                    stamps.forget(row.fileId)
                    OfflineDownloads.forget(row.fileId)
                    restore(cancelled = row.fileId)
                    check(!OfflineDownloads.active.value.containsKey(row.fileId)) { "a cancelled download ${row.fileId} came back on a second restore" }
                    persist()
                }
                Kind.RestoreAcrossSwitch -> {
                    // A restore reads the stored queue, then a switch lands, then it merges: its rows are the
                    // old profile's, so it must merge nothing.
                    OfflineDownloads.afterStoredRead = {
                        OfflineDownloads.afterStoredRead = null
                        beforeSwitch()
                        publish(1 - open)
                    }
                    try {
                        restore()
                    } finally {
                        OfflineDownloads.afterStoredRead = null
                    }
                    restore()
                }
                Kind.RestoreAcrossRenew -> {
                    // A restore (the screen composing again) lands between the drop of the rows and the new
                    // marker: it reads the old marker and the old queue, so what it merged must go again.
                    Td.betweenDropAndRenew = {
                        Td.betweenDropAndRenew = null
                        runBlocking { OfflineDownloads.restore(settings) }
                    }
                    try {
                        renewOpen()
                    } finally {
                        Td.betweenDropAndRenew = null
                    }
                    restore()
                }
                Kind.SignOutLast -> {
                    // Its store keys may outlive it (a purge cut short): the marker is what keeps them out.
                    renewOpen()
                    restore()
                }
                Kind.SignOutNotLast -> {
                    renewOpen()
                    publish(1 - open)
                    if (pick % 2 == 0) persist()
                    restore()
                }
                Kind.SessionEnded -> {
                    renewOpen()
                    restore()
                }
                Kind.SignInAgain -> {
                    val other = 1 - open
                    remake(other)
                    beforeSwitch()
                    publish(other)
                    restore()
                }
                Kind.RestoreBackup -> restore()
                Kind.RemoveAll -> {
                    profiles.indices.filter { it != 0 }.forEach(::remake)
                    if (open != 0) publish(0)
                    renewOpen()
                    restore()
                }
                Kind.Restart -> {
                    OfflineDownloads.resetForTest()
                    stamps = DownloadStamps()
                    stopRunner()
                    jobs.clear()
                    held = null
                    stopRunner()
                    restore()
                }
            }
        }

        suspend fun invariants(baseline: Set<Int>?) {
            // (a) every row is the open database's video.
            for ((id, row) in OfflineDownloads.active.value) {
                check(id == row.request.fileId) { "row key $id holds ${row.request.fileId}" }
                isOpenVideo(row.request, "row")
            }
            // (b) every stored queue is tagged with its own videos' database.
            for (p in profiles.indices) {
                val (queue, tag) = storedOf(p)
                if (queue.isEmpty()) continue
                val chats = queue.map { it.chatId }.toSet()
                if (tag == null) {
                    check(chats == setOf(chatOf(p, 0))) { "profile $p keeps an unmarked queue of chats $chats" }
                } else {
                    val (owner, e) = markers[tag] ?: (-1 to -1)
                    check(owner == p && chats == setOf(chatOf(p, e))) { "profile $p's queue of chats $chats is tagged $tag, which is profile $owner database $e" }
                }
            }
            // (d) one profile, no switches: what was queued is what is kept, rows and store alike.
            if (baseline != null) {
                val stored = storedOf(open).first.map { it.fileId }.toSet()
                check(OfflineDownloads.active.value.keys == baseline) { "rows ${OfflineDownloads.active.value.keys}, expected $baseline" }
                check(stored == baseline) { "stored $stored, expected $baseline" }
            }
        }
    }

    private fun steps(seed: Long, length: Int, single: Boolean): List<Step> {
        val random = Random(seed)
        val kinds = (if (single) singleProfileKinds else Kind.entries).flatMap { kind -> List(weight(kind)) { kind } }
        return List(length) { Step(kinds[random.nextInt(kinds.size)], random.nextInt(1_000)) }
    }

    /** A clean device with A open (and B beside it unless [single]), and the world over it. */
    private suspend fun freshWorld(single: Boolean, beforeMarkers: Boolean): World {
        root.resolve("device").deleteRecursively()
        raw.edit { it.clear() }
        OfflineDownloads.resetForTest()
        book.update { AccountList(profiles = if (single) listOf(a) else profiles, activeId = a.id) }
        Accounts.resetForTest(book)
        Td.platformPathsForTest(base)
        val world = World(single)
        // A's database is on the device already (TDLib's files); B's is made with its profile.
        File(world.dirOf(0).apply { mkdirs() }, "td.binlog").writeText("db")
        if (!single) {
            AccountDirs.make(AccountDirs.pathsFor(base, b))
            world.noteMarker(1)
        }
        if (beforeMarkers) {
            // The version before markers: A's queue was written with none, and A has no marker yet.
            raw.edit { it[QUEUE] = DownloadRequest.encodeAll(listOf(world.request(0, 0, 1), world.request(0, 0, 2))) }
        } else {
            world.noteMarker(0)
        }
        if (single || !beforeMarkers) world.restore()
        return world
    }

    /** Runs [steps] from a clean device; the first violation, or null. */
    private fun run(steps: List<Step>, single: Boolean, beforeMarkers: Boolean): String? = runBlocking {
        val world = freshWorld(single, beforeMarkers)
        var baseline: Set<Int>? = if (single) OfflineDownloads.active.value.keys.toSet() else null
        for ((index, step) in steps.withIndex()) {
            try {
                val before = profiles.indices.associateWith { world.storedOf(it).first }
                val cancelled = OfflineDownloads.active.value.values.sortedBy { it.fileId }.getOrNull(step.pick % 5)?.fileId
                world.step(step)
                for (p in profiles.indices) {
                    val kept = world.storedOf(p).first.map { it.fileId }.toSet()
                    val own = before.getValue(p).filter { it.chatId == world.chatOf(p, world.epoch[p]) }.map { it.fileId }
                        .filter { !((step.kind == Kind.Cancel || step.kind == Kind.CancelThenRestore) && it == cancelled && p == world.open) }
                    world.check(kept.containsAll(own)) { "profile $p's queue of its database now lost ${own - kept}" }
                }
                if (single) {
                    when (step.kind) {
                        Kind.Enqueue, Kind.Cancel, Kind.CancelThenRestore -> baseline = OfflineDownloads.active.value.keys.toSet()
                        else -> Unit
                    }
                }
                world.invariants(baseline)
            } catch (violation: Violation) {
                return@runBlocking "step $index ${step}: ${violation.message}"
            }
        }
        null
    }

    private fun shrink(steps: List<Step>, single: Boolean, beforeMarkers: Boolean): List<Step> {
        var current = steps
        var changed = true
        while (changed) {
            changed = false
            for (i in current.indices.reversed()) {
                val shorter = current.filterIndexed { index, _ -> index != i }
                if (run(shorter, single, beforeMarkers) != null) {
                    current = shorter
                    changed = true
                }
            }
        }
        return current
    }

    @Test
    fun `random sequences keep every download in its own profile's database`() {
        val runs = System.getProperty("queueModel.runs")?.toIntOrNull() ?: 2_000
        val first = System.getProperty("queueModel.seed")?.toLongOrNull() ?: 20261121L
        for (n in 0 until runs) {
            val seed = first + n
            val single = n % 4 == 0
            val beforeMarkers = n % 3 == 0
            val steps = steps(seed, length = 24, single = single)
            val failure = run(steps, single, beforeMarkers) ?: continue
            val shrunk = shrink(steps, single, beforeMarkers)
            fail(
                "seed $seed (single=$single, beforeMarkers=$beforeMarkers): $failure\n" +
                    "shrunk to ${shrunk.size} steps: $shrunk\n  -> ${run(shrunk, single, beforeMarkers)}",
            )
        }
    }

    /**
     * The service's own write ([OfflineDownloads.persistQueue]) on real threads: writers hammer it
     * while the queue changes under them (enqueue, cancel, sign out, ended session, switch, and a
     * write that straddles a switch or a session end), then the same invariants are checked. The
     * model above runs one step at a time; this is the interleaving it cannot make. Each round is
     * seeded, and a failure prints the seed.
     */
    @Test
    fun `writes racing on real threads never tag a database's videos with another's marker`() {
        val rounds = System.getProperty("queueModel.raceRounds")?.toIntOrNull() ?: 120
        val first = System.getProperty("queueModel.seed")?.toLongOrNull() ?: 20261122L
        val kinds = listOf(
            Kind.Enqueue, Kind.Enqueue, Kind.Enqueue, Kind.Cancel, Kind.CancelThenRestore, Kind.SessionEnded, Kind.SignOutLast,
            Kind.Switch, Kind.SignOutNotLast, Kind.PersistAcrossRenew, Kind.PersistAcrossSwitch, Kind.RestoreBackup,
        )
        for (round in 0 until rounds) {
            val seed = first + round
            val random = Random(seed)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            runBlocking {
                val world = freshWorld(single = false, beforeMarkers = false)
                val stop = java.util.concurrent.atomic.AtomicBoolean(false)
                val writers = List(3) {
                    Thread {
                        runBlocking {
                            while (!stop.get()) {
                                try {
                                    OfflineDownloads.persistQueue(settings)
                                } catch (e: Throwable) {
                                    failures += e
                                }
                                Thread.yield()
                            }
                        }
                    }.also { it.start() }
                }
                try {
                    val trail = ArrayList<Step>()
                    repeat(30) { index ->
                        val step = Step(kinds[random.nextInt(kinds.size)], random.nextInt(1_000))
                        trail += step
                        try {
                            world.step(step)
                            world.invariants(null)
                        } catch (violation: Violation) {
                            failures += AssertionError("seed $seed, step $index $step: ${violation.message}\n  steps: $trail")
                        }
                    }
                } finally {
                    stop.set(true)
                    writers.forEach { it.join() }
                }
                // Quiet: the writers are done, so what is stored is what the last write left.
                try {
                    world.invariants(null)
                } catch (violation: Violation) {
                    failures += AssertionError("seed $seed, after the writers stopped: ${violation.message}")
                }
            }
            failures.firstOrNull()?.let { throw it }
        }
    }

    private companion object {
        val QUEUE = stringPreferencesKey("download_queue")
        val QUEUE_DB = stringPreferencesKey("download_queue_db")
    }
}
