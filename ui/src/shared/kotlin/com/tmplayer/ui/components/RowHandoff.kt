package com.tmplayer.ui.components

/**
 * Where the remote or the keyboard goes once [gone] has been taken out of a list that showed
 * [before], in that order, and now holds [now]: the first row after it that is still there, else
 * the nearest one above it, else null (the list's own header or tab). [gone] itself when it is
 * still there (its file would not delete). The one rule every list shares (rule 11 of the UX
 * interaction plan), phone's TV Downloads and the desktop's alike.
 */
fun rowAfterRemoving(before: List<String>, gone: String, now: Set<String>): String? {
    if (gone in now) return gone
    val at = before.indexOf(gone)
    if (at < 0) return null
    return before.subList(at + 1, before.size).firstOrNull { it in now }
        ?: before.subList(0, at).lastOrNull { it in now }
}
