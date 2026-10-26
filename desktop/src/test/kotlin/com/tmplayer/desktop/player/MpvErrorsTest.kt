package com.tmplayer.desktop.player

import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mpv's error codes become a plain line and a next step, with the code kept for reports (HX10, #14). */
class MpvErrorsTest {

    @Test
    fun unknownFormatSaysWhatToDoAndKeepsTheCode() {
        val text = MpvErrors.describe("mpv playback failed (mpv_error=-17)", "UNSUPPORTED_FORMAT")
        assertTrue(text, text.startsWith(L.playerMpvUnknownFormat))
        assertTrue(text, text.endsWith("(mpv_error=-17)"))
    }

    @Test
    fun everyLoadFailureMpvCanGiveHasItsOwnLine() {
        for (code in listOf(-2, -13, -14, -15, -16, -17, -18)) {
            val plain = MpvErrors.plainFor(code)
            assertTrue("mpv_error=$code", !plain.isNullOrBlank())
            assertEquals("$plain\n(mpv_error=$code)", MpvErrors.describe("mpv_error=$code", null))
        }
    }

    @Test
    fun otherTextIsKeptAndNothingAtAllFallsBackToTheCode() {
        assertEquals("mpv playback failed (mpv_error=-20)", MpvErrors.describe("mpv playback failed (mpv_error=-20)", "INTERNAL"))
        assertEquals("disk on fire", MpvErrors.describe("disk on fire", "IO"))
        assertEquals(L.playerPlaybackFailed("IO"), MpvErrors.describe(null, "IO"))
        assertEquals(L.playerPlaybackFailed("?"), MpvErrors.describe("  ", null))
    }

    @Test
    fun theCodeIsReadOnlyWhenItIsThere() {
        assertEquals(-17, MpvErrors.codeIn("mpv playback failed (mpv_error=-17)"))
        assertEquals(-13, MpvErrors.codeIn("mpv_error = -13"))
        assertNull(MpvErrors.codeIn("mpv_error=abc"))
        assertNull(MpvErrors.codeIn("mpv_error=-99999999999999999"))
        assertNull(MpvErrors.codeIn(null))
        assertNull(MpvErrors.codeIn(""))
    }
}
