package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A field's text and caret, held by the field itself, with the text its owner keeps (a screen's
 * state, a phone entry, a dialog's draft) told of each edit and read back on every composition.
 *
 * A field drawn straight from its owner's String keeps a caret of its own that the owner's text is
 * laid over, so when that text is not the one just typed the caret is left where the old text had
 * it: typing the same letter twice moved the caret (user, 2026-10-22). Here:
 *
 * - The owner keeping the edit whole: nothing changes, the caret is where the typing put it.
 * - The owner keeping it only in part (a length limit, digits only, a dropped trunk zero): the
 *   caret stays where the typing was, less what was dropped.
 * - A text from elsewhere (a recent search tapped, Clear, a pasted number split over two fields):
 *   the caret at its end.
 *
 * The owner must answer at once (plain Compose state, as every field here has): a text that only
 * arrives later through a flow or a debounce would be taken for one from elsewhere. Keep such a
 * field's text in Compose state and send it on from there.
 */
class HeldText(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set

    /** Set by the field's own edit, until the owner's answer to it has been read. */
    private var justEdited = false

    /**
     * Whether the last [sync] that changed [value] was a text from elsewhere rather than the
     * field's own typing (HX49): a field that shrinks this way while it still holds the IME's
     * focus can race a focus-rect callback still built for the old, longer text (a known class of
     * Compose foundation crash, `requireIndexInRange`, seen on a Haier TV on 1.28.3); a caller that
     * clears and reclaims focus on this flag gives the IME a fresh layout to ask about instead.
     */
    var externalChange: Boolean = false
        private set

    /** The text the owner holds now, read on every composition. */
    fun sync(text: String) {
        // Right after an edit the owner's text is what it made of that edit.
        val edited = justEdited
        justEdited = false
        if (text == value.text) {
            externalChange = false
            return
        }
        externalChange = !edited
        val caret = if (edited) {
            (value.selection.end - (value.text.length - text.length).coerceAtLeast(0)).coerceIn(0, text.length)
        } else {
            text.length
        }
        value = TextFieldValue(text, TextRange(caret))
    }

    /** The field's own edit: held at once, caret and all, and the owner told when the text changed. */
    fun edit(next: TextFieldValue, onText: (String) -> Unit) {
        val changed = next.text != value.text
        value = next
        if (changed) {
            justEdited = true
            onText(next.text)
        }
    }
}

/** A [HeldText] for a field whose owner keeps [text]: see [HeldText.sync]. */
@Composable
fun rememberHeldText(text: String): HeldText {
    val held = remember { HeldText(text) }
    held.sync(text)
    return held
}
