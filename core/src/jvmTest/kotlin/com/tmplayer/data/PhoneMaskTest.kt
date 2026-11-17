package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A phone number in a log line: enough to see a wrong one, not the number. */
class PhoneMaskTest {
    @Test
    fun `an Italian number keeps its country code and last two digits`() {
        assertEquals("+39 ••••••••34", PhoneMask.mask("+39 333 123 4534"))
    }

    @Test
    fun `spaces, dashes and brackets are not digits`() {
        assertEquals("+12 •••••••90", PhoneMask.mask("+1 (202) 555-1290"))
    }

    @Test
    fun `a short or empty number shows nothing of itself`() {
        assertEquals("••••", PhoneMask.mask("1234"))
        assertEquals("", PhoneMask.mask(""))
        assertEquals("", PhoneMask.mask("+ () -"))
    }
}
