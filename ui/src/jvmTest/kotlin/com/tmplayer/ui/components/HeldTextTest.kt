package com.tmplayer.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.tmplayer.data.PhoneFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldTextTest {

    /** A field and its owner as a screen wires them: the owner's text read back after each edit. */
    private class Wired(initial: String, val keep: (String) -> String = { it }) {
        var owner = initial
        val held = HeldText(initial)

        fun type(next: TextFieldValue) {
            held.edit(next) { owner = keep(it) }
            held.sync(owner)
        }

        /** One character typed at the caret. */
        fun key(ch: Char) {
            val v = held.value
            val at = v.selection.end
            type(TextFieldValue(v.text.substring(0, at) + ch + v.text.substring(at), TextRange(at + 1)))
        }
    }

    @Test
    fun `the same letter twice keeps the caret at the end`() {
        val field = Wired("")
        field.key('a')
        field.key('a')
        assertEquals("aa", field.held.value.text)
        assertEquals(TextRange(2), field.held.value.selection)
        assertEquals("aa", field.owner)
    }

    @Test
    fun `typing in the middle keeps the caret there`() {
        val field = Wired("harbour")
        field.type(TextFieldValue("harbour", TextRange(3)))
        field.key('x')
        assertEquals("harxbour", field.held.value.text)
        assertEquals(TextRange(4), field.held.value.selection)
    }

    @Test
    fun `a letter the owner drops leaves the caret where the typing was`() {
        val field = Wired("123456", keep = { raw -> raw.filter(Char::isDigit) })
        field.type(TextFieldValue("123456", TextRange(3)))
        field.key('x')
        assertEquals("123456", field.held.value.text)
        assertEquals(TextRange(3), field.held.value.selection)
    }

    @Test
    fun `a length limit keeps the caret on the last letter kept`() {
        val field = Wired("abcd", keep = { it.take(4) })
        field.key('e')
        assertEquals("abcd", field.held.value.text)
        assertEquals(TextRange(4), field.held.value.selection)
    }

    @Test
    fun `a text from elsewhere puts the caret at its end`() {
        val field = Wired("harbour")
        field.type(TextFieldValue("harbour", TextRange(2)))
        // A recent search tapped, then Clear.
        field.owner = "night train"
        field.held.sync(field.owner)
        assertEquals(TextRange(11), field.held.value.selection)
        field.owner = ""
        field.held.sync(field.owner)
        assertEquals(TextFieldValue(""), field.held.value)
    }

    @Test
    fun `externalChange tells a text from elsewhere apart from the field's own typing`() {
        // HX49: a caller reacts to a text from elsewhere (Clear) differently from ordinary typing,
        // since only the former can leave a focus-rect query stale against a shorter text.
        val field = Wired("")
        field.key('a')
        assertEquals("typing is not external", false, field.held.externalChange)
        field.owner = "a recent search"
        field.held.sync(field.owner)
        assertEquals("a text from elsewhere is external", true, field.held.externalChange)
        field.key('!')
        assertEquals("typing after a text from elsewhere is not external", false, field.held.externalChange)
        // An owner echo that changes nothing is not a change at all.
        field.held.sync(field.owner)
        assertEquals(false, field.held.externalChange)
    }

    @Test
    fun `a grouped phone number keeps the caret after the last digit typed`() {
        // The field holds bare digits; the grouping is drawn over them, the caret mapped through it.
        val field = Wired("", keep = { raw -> raw.filter(Char::isDigit) })
        "7700900".forEach(field::key)
        val formatted = PhoneFormat.format(field.held.value.text, "---- ------")
        val caret = PhoneFormat.toFormatted(field.held.value.selection.end, formatted)
        assertEquals("7700900", field.held.value.text)
        assertEquals(formatted.length, caret)
        // Back into the middle: a digit typed after the second stays after it.
        field.type(TextFieldValue(field.held.value.text, TextRange(2)))
        field.key('5')
        assertEquals("77500900", field.held.value.text)
        assertEquals(TextRange(3), field.held.value.selection)
    }
}
