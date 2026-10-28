package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.platform.LruMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Videos this session learnt are gone from Telegram for good ([MessageLookup.Gone]), and the
 * tidying of what the viewer kept of them (HX21).
 *
 * Kept entries (Continue watching, Watched, Watch later) point at a message, and a message can be
 * deleted, its chat left, or the post edited into something else. Nothing checks them all at
 * start: an account with 100,000 videos would spend its TDLib budget on it. Instead the answers
 * come from what happens anyway: TDLib's own delete updates ([Td.videoChanges]), and every lookup
 * of one message ([ChatRepository.lookupMedia]) made as Home and History draw their cards or a
 * kept video is opened. A lookup that only failed (offline, a FLOOD_WAIT, a chat not loaded yet)
 * never lands here, so nothing kept is lost to a bad connection.
 *
 * [follow] turns the answers into removals, a few seconds' worth at a time, and says what went.
 */
object GoneVideos {

    /** One video found gone, with its ids for the stores and why, for what the viewer is told. */
    data class Gone(val chatId: Long, val messageId: Long, val reason: GoneReason) {
        val key: String get() = ProgressKeys.of(chatId, messageId)
    }

    /** What one [forget] took out, counted per list, for the line that says so. */
    data class Forgotten(val history: Int, val watchLater: Int, val videos: Int) {
        val isEmpty: Boolean get() = videos == 0
    }

    /** Enough for every card a few screens show; the oldest answer goes first past it. */
    private const val KEEP = 500

    /** Reports gathered for this long before the stores are touched, so a burst is one edit and one line. */
    private const val WINDOW_MS = 2_000L

    private const val TAG = "GoneVideos"

    private val known = LruMap<String, Gone>(KEEP)

    private val reports = MutableSharedFlow<List<Gone>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Telegram said [chatId]'s message [messageId] is gone for [reason]. */
    fun report(chatId: Long, messageId: Long, reason: GoneReason) {
        if (chatId == 0L || messageId <= 0L) return
        val gone = Gone(chatId, messageId, reason)
        known[gone.key] = gone
        reports.tryEmit(listOf(gone))
    }

    /** Why the video is gone, when this session has heard so; null for anything not known gone. */
    fun reasonFor(chatId: Long, messageId: Long): GoneReason? = known[ProgressKeys.of(chatId, messageId)]?.reason

    /** Signing out: another account's answers are not this one's. */
    fun clear() = known.clear()

    /** The line for opening a kept video that is gone. */
    fun openMessage(reason: GoneReason): String = when (reason) {
        GoneReason.Deleted -> L.errorsVideoDeleted
        GoneReason.ChatClosed -> L.errorsVideoChatClosed
        GoneReason.NotVideo -> L.errorsVideoNotVideo
    }

    /** What a download of a gone video says in place of TDLib's error; null when it is not known gone. */
    fun failureText(chatId: Long, messageId: Long): String? = reasonFor(chatId, messageId)?.let(::openMessage)

    /** The line for what [forget] took out of the viewer's lists. */
    fun message(forgotten: Forgotten): String = when {
        forgotten.history > 0 && forgotten.watchLater > 0 -> L.continueGoneLeftLists(forgotten.videos)
        forgotten.watchLater > 0 -> L.continueGoneLeftWatchLater(forgotten.videos)
        else -> L.continueGoneLeftHistory(forgotten.videos)
    }

    /** TDLib's permanent deletes and edits that took the video out, as reports. */
    private fun fromTelegram(): Flow<List<Gone>> = Td.videoChanges
        .filterIsInstance<VideoChange.Removed>()
        .map { removed ->
            val reason = if (removed.byEdit) GoneReason.NotVideo else GoneReason.Deleted
            removed.messageIds.map { id ->
                Gone(removed.chatId, id, reason).also { known[it.key] = it }
            }
        }

    /**
     * Takes the [gone] videos out of Continue watching, Watched and Watch later, leaving any whose
     * [keptOnDevice] copy still plays. Only the message named goes, never the other copies of the
     * same film a card folds in: those are other messages, still there.
     */
    suspend fun forget(
        settings: SettingsStore,
        watched: WatchedStore,
        gone: Collection<Gone>,
        keptOnDevice: suspend (chatId: Long, messageId: Long) -> Boolean,
    ): Forgotten {
        if (gone.isEmpty()) return Forgotten(0, 0, 0)
        val byKey = gone.associateBy { it.key }
        // What is kept, read once: a channel deleting a thousand posts asks the stores once, and a
        // message nobody kept costs nothing more.
        val inContinue = settings.continueWatching.first().mapTo(HashSet()) { ProgressKeys.of(it.chatId, it.messageId) }
        val inWatched = watched.watched.first().keys
        val inLater = watched.watchLater.keys.first()
        val candidates = byKey.keys.filter { it in inContinue || it in inWatched || it in inLater }
        val doomed = candidates.filter { key ->
            val entry = byKey.getValue(key)
            val kept = runCatching { keptOnDevice(entry.chatId, entry.messageId) }.getOrDefault(true)
            StaleEntries.shouldForget(MessageLookup.Gone(entry.reason), kept)
        }
        if (doomed.isEmpty()) return Forgotten(0, 0, 0)
        val history = doomed.filter { it in inContinue || it in inWatched }
        val later = doomed.filter { it in inLater }
        if (history.isNotEmpty()) HistoryEdits.remove(settings, watched, history, history.size)
        val taken = if (later.isNotEmpty()) watched.watchLater.take(later).size else 0
        Logger.i(TAG, "Forgot ${doomed.size} gone videos: ${history.size} from history, $taken from Watch later")
        return Forgotten(history = history.size, watchLater = taken, videos = doomed.size)
    }

    /**
     * Keeps the viewer's lists clear of gone videos for as long as it runs (the app's main screen):
     * Telegram's deletes as they arrive and every [report], gathered for [WINDOW_MS], then [forget]
     * and [onForgotten] with what went when anything did. A failure is logged and the next window
     * goes on; nothing here is worth taking a screen down for.
     */
    suspend fun follow(
        settings: SettingsStore,
        watched: WatchedStore,
        keptOnDevice: suspend (chatId: Long, messageId: Long) -> Boolean,
        onForgotten: suspend (Forgotten) -> Unit,
    ) {
        // What was heard while nothing followed (the screen being made again after a turn, or
        // before it first came up) goes first: a report is not kept for a follower that comes late.
        val heard = flowOf(known.values.toList()).filter { it.isNotEmpty() }
        windowed(merge(heard, reports, fromTelegram()), WINDOW_MS).collect { window ->
            try {
                val forgotten = forget(settings, watched, window, keptOnDevice)
                if (!forgotten.isEmpty) onForgotten(forgotten)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Logger.w(TAG, "Could not tidy gone videos", failure)
            }
        }
    }

    /** [source]'s reports gathered for [windowMs] after the first, handed on as one list, each video once. */
    internal fun windowed(source: Flow<List<Gone>>, windowMs: Long): Flow<List<Gone>> = channelFlow {
        val lock = Mutex()
        val pending = LinkedHashMap<String, Gone>()
        var timer: Job? = null
        source.collect { batch ->
            lock.withLock {
                batch.forEach { pending[it.key] = it }
                if (timer == null) {
                    timer = launch {
                        delay(windowMs)
                        val taken = lock.withLock {
                            val all = pending.values.toList()
                            pending.clear()
                            timer = null
                            all
                        }
                        if (taken.isNotEmpty()) send(taken)
                    }
                }
            }
        }
    }
}
