package com.tmplayer.platform

import com.tmplayer.player.StreamStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Telling the viewer, outside the app's own window, how a long transfer is going and when it is
 * done: a download, a move into Downloads, a whole storage location moving, a one time migration.
 *
 * Each platform has its own surface (Android's notification shade, a D-Bus notification and the
 * dock on Linux, the taskbar and a toast on Windows) and they differ in what they can draw, which
 * [capabilities] says. What they share is the policy, and that lives here rather than in each
 * implementation: wrap one in [CoalescingTransferNotifier] and it is told about progress at most
 * once a second per transfer, and only when what it would show has changed.
 *
 * [complete] is always a new notification, never an update of the progress one, so it is seen
 * even by a viewer who swiped the progress away.
 */
interface TransferNotifier {

    /** What is moving, which decides the wording and whether it is one of many or one aggregate. */
    enum class Kind {
        /** A video being fetched from Telegram. */
        Download,

        /** A fetched video going from the cache into the Downloads folder. */
        MoveToDownloads,

        /** Every download moving to a new storage location the viewer chose. */
        Relocate,

        /** Downloads from before they had a folder of their own, moved into it once. */
        Migrate,
    }

    /** Where pressing a completion notification takes the viewer. */
    sealed interface OpenTarget {
        /** The app's own Downloads screen or page. */
        data object DownloadsScreen : OpenTarget

        /** One file, opened in whatever the system opens it with. */
        data class File(val path: String) : OpenTarget

        /** A folder, shown in the system's file manager. */
        data class Folder(val path: String) : OpenTarget
    }

    /** What a platform's surface can do, so callers do not word things for a bar nobody draws. */
    enum class Capability {
        /** Draws a progress bar from a percentage. */
        ProgressBar,

        /** Updates a notification in place rather than stacking a new one. */
        InPlaceUpdate,

        /** Shows buttons, such as "Open folder", on a notification. */
        Actions,

        /** Shows progress on the app's dock or taskbar icon. */
        DockProgress,
    }

    /** A transfer has started. [id] is the caller's and is reused for every later call about it. */
    fun begin(id: Long, kind: Kind, title: String)

    /**
     * How far it has come. [total] is null when the size is not known, and [bytesPerSecond] when
     * there is no rate worth quoting yet.
     */
    fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?)

    /** Finished. Posted as a new notification. [open] is where pressing it goes, if anywhere. */
    fun complete(id: Long, title: String, body: String, open: OpenTarget?)

    /** Stopped by a failure. [retryable] is whether pressing Try again could help. */
    fun fail(id: Long, title: String, reason: String, retryable: Boolean)

    /** Gone without finishing or failing, such as cancelled by the viewer: nothing is left shown. */
    fun cancel(id: Long)

    val capabilities: Set<Capability>
}

/** The notifier for a target that has nowhere to show anything, and for tests. */
object NoTransferNotifier : TransferNotifier {
    override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) = Unit
    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) = Unit
    override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) = Unit
    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) = Unit
    override fun cancel(id: Long) = Unit
    override val capabilities: Set<TransferNotifier.Capability> = emptySet()
}

/**
 * The shared policy over any [TransferNotifier]: fewer, meaningful progress updates, and one
 * figure for all of them together.
 *
 * Progress reaches [inner] for a transfer at most once every [minIntervalMs], and then only when
 * the whole percentage or the size as [formatSize] writes it has changed since the last one that
 * went through. Android drops updates above five a second per app, a Windows toast update is a
 * round trip to another process, and a D-Bus notification server redraws for every one; a
 * notification that changes from "41%" to "41%" is noise on all of them. The first progress after
 * [begin] always goes through, so a bar appears as soon as there is something to put on it.
 *
 * [aggregate] is the byte weighted progress of everything in flight with a known size (Chrome's
 * rule, so one small file finishing does not make a large one look nearly done), for a dock or
 * taskbar icon that has room for one bar. Null when nothing with a size is moving.
 *
 * Every call is passed on from whichever thread makes it; the bookkeeping is synchronised.
 */
class CoalescingTransferNotifier(
    private val inner: TransferNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val formatSize: (Long) -> String = StreamStats::formatBytes,
    private val minIntervalMs: Long = 1_000L,
) : TransferNotifier {

    private class Tracked(
        var lastEmittedAt: Long = Long.MIN_VALUE,
        var lastPercent: Int? = null,
        var lastSize: String? = null,
        var done: Long = 0,
        var total: Long? = null,
        var emittedOnce: Boolean = false,
    )

    private val lock = Any()
    private val tracked = mutableMapOf<Long, Tracked>()
    private val _aggregate = MutableStateFlow<Float?>(null)

    /** Byte weighted progress of every transfer in flight with a known size, 0 to 1, or null. */
    val aggregate: StateFlow<Float?> = _aggregate.asStateFlow()

    override val capabilities: Set<TransferNotifier.Capability> get() = inner.capabilities

    override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) {
        synchronized(lock) {
            tracked[id] = Tracked()
            publishAggregate()
        }
        inner.begin(id, kind, title)
    }

    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) {
        val emit = synchronized(lock) {
            val entry = tracked.getOrPut(id) { Tracked() }
            entry.done = done
            entry.total = total?.takeIf { it > 0 }
            publishAggregate()

            val now = clock()
            val percent = entry.total?.let { ((done.coerceAtMost(it) * 100) / it).toInt() }
            val size = formatSize(done)
            val changed = percent != entry.lastPercent || size != entry.lastSize
            val due = !entry.emittedOnce || now - entry.lastEmittedAt >= minIntervalMs
            if (changed && due) {
                entry.lastEmittedAt = now
                entry.lastPercent = percent
                entry.lastSize = size
                entry.emittedOnce = true
                true
            } else {
                false
            }
        }
        if (emit) inner.progress(id, done, total, bytesPerSecond)
    }

    override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) {
        drop(id)
        inner.complete(id, title, body, open)
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) {
        drop(id)
        inner.fail(id, title, reason, retryable)
    }

    override fun cancel(id: Long) {
        drop(id)
        inner.cancel(id)
    }

    private fun drop(id: Long) = synchronized(lock) {
        tracked.remove(id)
        publishAggregate()
    }

    /** Called with [lock] held. */
    private fun publishAggregate() {
        val sized = tracked.values.filter { it.total != null }
        _aggregate.value = byteWeighted(sized.map { it.done to it.total!! })
    }

    companion object {
        /**
         * Everything done over everything there is to do, each transfer counted by its bytes.
         * Null for an empty list, since "no transfers" and "no progress" are different things.
         */
        fun byteWeighted(transfers: List<Pair<Long, Long>>): Float? {
            val total = transfers.sumOf { it.second.coerceAtLeast(0) }
            if (transfers.isEmpty() || total <= 0) return null
            val done = transfers.sumOf { (d, t) -> d.coerceIn(0, t.coerceAtLeast(0)) }
            return (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
    }
}
