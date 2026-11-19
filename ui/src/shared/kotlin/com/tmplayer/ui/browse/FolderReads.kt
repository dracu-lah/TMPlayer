package com.tmplayer.ui.browse

/**
 * When the folders' members are read (HX29), as a rule apart from the view model so it can be
 * tested without a Telegram session. Reading them on a large account asks Telegram for every
 * chat, so it happens only while the picker wants them ([want] until [unwant]): a chat list
 * loaded with no picker open reads nothing, and a folder that appears after the last read is
 * unread, not empty.
 */
class FolderReads {
    /** True while the picker is open and has asked for the folders. */
    var wanted: Boolean = false
        private set

    private var read: Set<Int>? = null

    fun want() {
        wanted = true
    }

    /** The picker left: no read starts for it any more, and what was read stays for its next opening. */
    fun unwant() {
        wanted = false
    }

    /** Whether some of [current] folders have not been read, or none ever was. */
    fun unread(current: Collection<Int>): Boolean =
        current.isNotEmpty() && read.let { done -> done == null || current.any { it !in done } }

    /** Whether the picker's opening should start a read now. */
    fun readOnWant(current: Collection<Int>): Boolean = wanted && unread(current)

    /** Whether a chat list load should read the folders after it (to keep them current): only for a picker that is open. */
    fun readAfterLoad(current: Collection<Int>): Boolean = wanted && current.isNotEmpty()

    /** A read covering [ids] finished. */
    fun markRead(ids: Collection<Int>) {
        read = ids.toSet()
    }

    /** A new account starts with nothing read and nothing wanted. */
    fun reset() {
        wanted = false
        read = null
    }
}
