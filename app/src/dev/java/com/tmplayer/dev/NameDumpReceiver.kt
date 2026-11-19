package com.tmplayer.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.tmplayer.data.AddedChats
import com.tmplayer.data.AddedMode
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Failures
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.TdlError
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.Td
import com.tmplayer.data.runCatchingCancellable
import com.tmplayer.platform.Background
import com.tmplayer.platform.JobFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug and preview builds only: dumps how the videos of the viewer's chats are named and how the
 * shelf groups them, for reading off a device in one adb command. Declared only in the debug and
 * preview manifests, and compiled only into those builds (`src/dev`), so a release has no trace of it.
 *
 * `adb shell am broadcast -n com.tmplayer.test/com.tmplayer.dev.NameDumpReceiver -a com.tmplayer.DUMP_NAMES
 * [--es chats "Title A,Title B"] [--ez all true] [--ei cap 3000]`, then pull
 * `/sdcard/Android/data/com.tmplayer.test/files/dump/`. See docs/BUILDING.md.
 *
 * The work runs under [NameDumpService], in the foreground, so it is not frozen with the app out
 * of sight. Read only: it pages each chat the way the chat screen does ([ChatRepository.mediaPage]) and
 * writes files under the app's own external folder, nothing else. Guarded by the DUMP permission
 * in the manifest, which the adb shell holds and no ordinary app does.
 */
class NameDumpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        // A foreground service keeps the process from being frozen while it reads, screen off or
        // app out of sight; goAsync's few seconds are far short of a big chat. Where Android
        // refuses the service, the dump runs in the process anyway and needs the app kept open.
        val started = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, NameDumpService::class.java).putExtras(intent))
        }
        started.exceptionOrNull()?.let { refused ->
            Log.w(TAG, "foreground service refused (${refused.javaClass.simpleName}); running in the app, keep it open")
            start(app, intent) {}
        }
    }

    private class Request(val names: List<String>?, val all: Boolean, val cap: Int)

    private class Target(val id: Long, val title: String, val kind: String)

    private class Dump(private val context: Context, private val request: Request) {

        suspend fun run() {
            val dir = outDir(context)
            File(dir, "done").delete()
            dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            val session = withTimeoutOrNull(SIGN_IN_WAIT_MS) { Td.awaitAuthorizedSession() }
            if (session == null) {
                Log.w(TAG, "not signed in after ${SIGN_IN_WAIT_MS / 1000} s; nothing dumped")
                writeAtomically(File(dir, "done"), "failed: not signed in\n")
                return
            }
            val repository = ChatRepository(session.client)
            val (mode, targets) = targets(repository)
            Log.i(TAG, "start: mode=$mode chats=${targets.size} cap=${request.cap}")

            val names = File(dir, "names.jsonl.tmp")
            val summary = StringBuilder()
            summary.append("TMPlayer names dump, mode=$mode, cap=${request.cap}, chats=${targets.size}\n\n")
            summary.append("chat\tkind\tvideos\tshows\tfiles\tfilmCopyGroups\tcomplete\n")
            val leftovers = ArrayList<String>()
            var videos = 0
            names.bufferedWriter().use { out ->
                targets.forEachIndexed { index, target ->
                    val (items, complete) = read(repository, target)
                    val entries = SeriesShelf.arrange(items)
                    val placed = NameDump.placements(items, entries)
                    for (item in items) {
                        val placement = placed[item.id] ?: NameDump.Placement(NameDump.UNLISTED)
                        out.write(NameDump.jsonLine(target.title, target.kind, item, placement))
                        out.write("\n")
                    }
                    val shows = entries.count { it is ShelfEntry.Show }
                    val files = entries.count { it is ShelfEntry.File && it.item.versions.size <= 1 }
                    val groups = entries.count { it is ShelfEntry.File && it.item.versions.size > 1 }
                    summary.append("${clean(target.title)}\t${target.kind}\t${items.size}\t$shows\t$files\t$groups\t${if (complete) "yes" else "no"}\n")
                    leftovers += NameDump.leftovers(clean(target.title), items, placed)
                    videos += items.size
                    Log.i(TAG, "chat ${index + 1}/${targets.size}: videos=${items.size} shows=$shows files=$files groups=$groups")
                }
            }
            summary.append("\nLEFTOVERS (${leftovers.size}): files with a 1 to 3 digit number that is not a resolution, year or codec\n")
            leftovers.forEach { summary.append(it).append('\n') }

            moveInto(names, File(dir, "names.jsonl"))
            writeAtomically(File(dir, "summary.txt"), summary.toString())
            writeAtomically(File(dir, "done"), "ok chats=${targets.size} videos=$videos leftovers=${leftovers.size}\n")
            Log.i(TAG, "done: chats=${targets.size} videos=$videos leftovers=${leftovers.size}")
        }

        /** Which chats, and how they were chosen. */
        private suspend fun targets(repository: ChatRepository): Pair<String, List<Target>> {
            val listed = runCatchingCancellable { repository.cachedChats().chats }.getOrDefault(emptyList())
                .ifEmpty { runCatchingCancellable { repository.syncChats().chats }.getOrDefault(emptyList()) }
            fun ChatSummary.target() = Target(id, title, kind.name)
            val everyChannelAndGroup = { listed.filter { it.kind == ChatKind.Channel || it.kind == ChatKind.Group }.take(MAX_CHATS).map { it.target() } }
            request.names?.let { names ->
                val picked = listed.filter { chat -> names.any { chat.title.contains(it, ignoreCase = true) } }
                return "chats" to picked.take(MAX_CHATS).map { it.target() }
            }
            if (request.all) return "all" to everyChannelAndGroup()
            AddedChats.awaitReady()
            val choice = AddedChats.choice.value
            if (choice.mode != AddedMode.Picked) {
                Log.i(TAG, "no chats picked in the app (mode=${choice.mode}); reading every channel and group")
                return "all (nothing picked)" to everyChannelAndGroup()
            }
            val byId = listed.associateBy { it.id }
            return "added" to choice.chats.map { added ->
                val known = byId[added.id] ?: runCatchingCancellable { repository.summary(added.id) }.getOrNull()
                known?.target() ?: Target(added.id, added.title, "Unknown")
            }
        }

        /** The chat's videos, newest first, up to the cap, and whether its end was reached. */
        private suspend fun read(repository: ChatRepository, target: Target): Pair<List<MediaItem>, Boolean> {
            val seen = HashSet<Long>()
            val items = ArrayList<MediaItem>()
            var cursors = MediaCursors()
            var empty = 0
            while (items.size < request.cap) {
                val page = page(repository, target.id, cursors) ?: return items to false
                for (item in page.items) if (seen.add(item.messageId)) items += item
                if (page.endReached || page.cursors.allDone) return items.take(request.cap) to (items.size <= request.cap)
                // One search failing keeps its cursor and the page comes back empty; a few in a row is the end of it.
                empty = if (page.items.isEmpty()) empty + 1 else 0
                if (empty >= MAX_EMPTY_PAGES) return items to false
                cursors = page.cursors
            }
            return items.take(request.cap) to false
        }

        /** One page, a FLOOD_WAIT waited out here, other failures tried a couple of times more. */
        private suspend fun page(repository: ChatRepository, chatId: Long, cursors: MediaCursors): MediaPage? {
            var floods = 0
            var failures = 0
            while (true) {
                try {
                    return repository.mediaPage(chatId, cursors)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val wait = Failures.floodWaitSeconds(e.message)
                    if (wait != null && wait <= MAX_FLOOD_S && floods < MAX_FLOODS) {
                        floods += 1
                        Log.i(TAG, "FLOOD_WAIT ${wait}s, waiting")
                        delay(wait * 1000L + 500L)
                        continue
                    }
                    failures += 1
                    Log.w(TAG, "page failed (${(e as? TdlError)?.let { "TDLib ${it.code}: ${it.message.take(120)}" } ?: e.javaClass.simpleName}), try $failures")
                    if (failures >= MAX_FAILURES) return null
                    delay(2_000L * failures)
                }
            }
        }

        /** Tabs and line breaks out of a title, so summary.txt stays one row per chat. */
        private fun clean(title: String): String = title.replace(Regex("[\\t\\r\\n]+"), " ")
    }

    companion object {
        const val ACTION = "com.tmplayer.DUMP_NAMES"
        internal const val TAG = "TMDump"
        private const val DEFAULT_CAP = 3000
        private const val MAX_CAP = 100_000
        private const val MAX_CHATS = 60
        private const val MAX_EMPTY_PAGES = 3
        private const val MAX_FLOOD_S = 600
        private const val MAX_FLOODS = 10
        private const val MAX_FAILURES = 3
        private const val SIGN_IN_WAIT_MS = 120_000L
        private const val WAKE_MS = 60 * 60 * 1000L

        private val running = AtomicBoolean(false)

        /**
         * Starts a dump of what [intent]'s extras ask for on the app-wide scope, unless one is
         * running already: then it does nothing and answers false. [finished] is called once it is over.
         */
        fun start(app: Context, intent: Intent?, finished: () -> Unit): Boolean {
            val request = Request(
                names = intent?.getStringExtra("chats")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() },
                all = intent?.getBooleanExtra("all", false) ?: false,
                cap = (intent?.getIntExtra("cap", DEFAULT_CAP) ?: DEFAULT_CAP).coerceIn(1, MAX_CAP),
            )
            if (!running.compareAndSet(false, true)) {
                Log.w(TAG, "a dump is already running; ignored")
                return false
            }
            Background.scope.launch {
                // The wake lock keeps the CPU on with the screen off.
                val lock = runCatching {
                    (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tmplayer:namedump")
                        ?.apply { acquire(WAKE_MS) }
                }.getOrNull()
                try {
                    Dump(app, request).run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    JobFailures.handle("NameDump", e)
                    runCatching { writeAtomically(File(outDir(app), "done"), "failed: ${e.javaClass.simpleName}\n") }
                } finally {
                    runCatching { if (lock?.isHeld == true) lock.release() }
                    running.set(false)
                    runCatching { finished() }
                }
            }
            return true
        }

        private fun outDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "dump").apply { mkdirs() }

        /** [text] into [target] through a temporary file beside it, so a reader never sees half of it. */
        private fun writeAtomically(target: File, text: String) {
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(text)
            moveInto(temp, target)
        }

        private fun moveInto(temp: File, target: File) {
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw java.io.IOException("could not move ${temp.name} into place")
            }
        }
    }
}
