package com.tmplayer.player

import android.widget.TextView
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist

/**
 * The guided-step list with rows that answer a finger on the first tap, wherever it lands.
 *
 * Leanback 1.0 was built for remotes, and two things in it stop a tap from reaching a row:
 *
 * - Every row is styled `focusableInTouchMode`, which on a touch screen turns a tap into a focus
 *   request instead of a click. The first tap only selected the row, the list then slid that row
 *   to its fixed line, and the second tap landed on a different row and selected that one.
 * - A row's title and description are `EditText`s, there for the rows a viewer can type into. An
 *   `EditText` takes every touch on it for its cursor, editable or not, so a tap on the words,
 *   which on a phone is most of the row, never reached the row at all.
 *
 * So rows here are not focusable in touch mode, and in rows that are not edited in place (all of
 * the player's) the two lines are plain text that lets touches through. None of it changes a
 * remote: a window driven by keys is never in touch mode, where only `focusable` counts, and the
 * text lines were never focusable to begin with.
 */
class TouchActionsStylist : GuidedActionsStylist() {
    override fun onBindViewHolder(vh: ViewHolder, action: GuidedAction) {
        super.onBindViewHolder(vh, action)
        vh.itemView.isFocusableInTouchMode = false
        val typedInto = action.isEditable || action.isDescriptionEditable || action.hasEditableActivatorView()
        if (!typedInto) {
            vh.titleView?.let(::letTouchesThrough)
            vh.descriptionView?.let(::letTouchesThrough)
        }
    }

    /** No cursor and no keyboard: the text neither handles a touch nor claims a long press. */
    private fun letTouchesThrough(text: TextView) {
        text.movementMethod = null
        text.keyListener = null
        text.isClickable = false
        text.isLongClickable = false
        text.isFocusable = false
    }
}
