package com.tmplayer.data

/**
 * The covers over an app's windows while it is away (HX88, invariant 6), apart from any UI kit so
 * a test can drive it: iOS's `SnapshotCover` keeps one per window of every connected scene. Each
 * [cover] covers every window of the list that has no cover yet (a second iPad window or scene, an
 * external display that came since) and forgets the windows that went away; [uncover] takes every
 * cover down. Not thread safe: the main thread only.
 */
class WindowCovers<W : Any, V : Any>(
    private val make: (W) -> V,
    private val remove: (V) -> Unit,
) {
    private val covers = LinkedHashMap<W, V>()

    /** The windows covered now. */
    val covered: Set<W> get() = covers.keys.toSet()

    /** Covers each of [windows] that has none; a window no longer in the list loses its cover. */
    fun cover(windows: List<W>) {
        val gone = covers.keys.filter { it !in windows }
        for (window in gone) covers.remove(window)?.let { runCatching { remove(it) } }
        for (window in windows) {
            if (window in covers) continue
            runCatching { make(window) }.getOrNull()?.let { covers[window] = it }
        }
    }

    /** Every cover off, the bookkeeping cleared. */
    fun uncover() {
        val all = covers.values.toList()
        covers.clear()
        for (view in all) runCatching { remove(view) }
    }
}
