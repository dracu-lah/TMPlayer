package com.tmplayer.ui.components

/**
 * Which notice shows now and which waits, for the desktop's snackbar (LS-21): the one on screen is
 * never cut off by a different notice (a "Backup restored" must not vanish under the next
 * message), the same notice again replaces the one on screen (a key held down does not stack), and
 * no more than one waits: a newer waiting notice takes the place of the older.
 *
 * Plain Kotlin and single threaded (the caller is the UI thread), so it is tested without a window.
 */
internal class ToastQueue<T : Any> {
    class Entry<T : Any>(val kind: String, val item: T)

    /** What [offer] decided: [show] now (null: it waits), whether it [replacesCurrent], and a waiting one it [dropped]. */
    class Offer<T : Any>(val show: Entry<T>?, val replacesCurrent: Boolean = false, val dropped: Entry<T>? = null)

    var current: Entry<T>? = null
        private set
    var waiting: Entry<T>? = null
        private set

    /** A notice of [kind] (its words, or a name for the kind of notice) is posted. */
    fun offer(kind: String, item: T): Offer<T> {
        val entry = Entry(kind, item)
        val now = current
        if (now == null) {
            current = entry
            return Offer(entry)
        }
        if (now.kind == kind) {
            // The same notice again: it replaces the one showing, and what waited still waits.
            current = entry
            return Offer(entry, replacesCurrent = true)
        }
        val older = waiting
        waiting = entry
        return Offer(null, dropped = older)
    }

    /** [entry] ended (timed out, dismissed or replaced); what to show next, or null. A replaced entry is ignored. */
    fun finished(entry: Entry<T>): Entry<T>? {
        if (current !== entry) return null
        current = waiting
        waiting = null
        return current
    }
}

/** The queue's name for a notice: its words, and an action (Undo) makes it a notice of its own so a plain one never replaces it. */
internal fun toastKey(message: String, action: String?): String = if (action == null) message else "undo:$message"
