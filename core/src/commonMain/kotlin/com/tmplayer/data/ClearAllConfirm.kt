package com.tmplayer.data

/**
 * The rules for "Clear history", "Clear Watch later" and the other clear-the-whole-list actions
 * (HX59, user): anything that empties a list asks first, on a phone, a TV and the desktop alike.
 * Undo stays as a second chance after the answer, but it is never the only guard on a clear-all
 * (one press of the wrong button on a remote or a touch screen would otherwise empty the list).
 * Removing one card, or the cards ticked by hand, keeps Undo alone: the person chose each of them.
 *
 * The question can sit open for a while, so it is read again when it is answered: a list that
 * emptied meanwhile (another device, the last card removed by hand) closes the question and clears
 * nothing, and a second press while it is up asks once.
 */
object ClearAllConfirm {

    /** An empty list has nothing to clear, so nothing to ask and no empty Undo to show. */
    fun needed(count: Int): Boolean = count > 0

    /** The question stays up only while the list it names still has something in it. */
    fun stillAsking(asking: Boolean, count: Int): Boolean = asking && needed(count)

    /** What a press on the clear button does: ask, or nothing when already asking or empty. */
    fun press(asking: Boolean, count: Int): Boolean = !asking && needed(count)

    /** What the question counts for Clear history: the cards both lists show, copies folded. */
    fun historyCards(continuing: Int, watched: Int): Int = continuing.coerceAtLeast(0) + watched.coerceAtLeast(0)

    /**
     * What Clear history counts, the same on every screen (Settings, History, desktop): the cards
     * Continue watching and Watched show, copies of one video folded into one ([HomeRows.foldCopies],
     * [HomeRows.foldWatched]). Folding twice changes nothing, so lists already folded may be passed.
     */
    fun historyCards(continuing: List<ResumeRecord>, watched: List<WatchedRecord>): Int =
        HomeRows.foldCopies(continuing).size + HomeRows.foldWatched(watched).size

    /** Whether the answer clears: the list is read again then, never the count it had when asked. */
    fun confirmClears(countNow: Int): Boolean = needed(countNow)
}
