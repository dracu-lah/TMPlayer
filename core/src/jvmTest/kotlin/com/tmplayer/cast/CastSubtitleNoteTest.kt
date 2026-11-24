package com.tmplayer.cast

import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-82: the subtitle picker of a cast says the truth while subtitles are not sent to the TV. */
class CastSubtitleNoteTest {

    @Test
    fun `while subtitles are not sent a file with subtitles says so`() {
        assertEquals("Subtitles are not sent to the TV yet", L.castSubtitlesNotSent)
        assertEquals(L.castSubtitlesNotSent, CastSubtitleNote.note(sent = false, hasText = true, hasLoadedFiles = false, hls = false))
        assertEquals(L.castSubtitlesNotSent, CastSubtitleNote.note(sent = false, hasText = false, hasLoadedFiles = true, hls = true))
    }

    @Test
    fun `a file with no subtitles at all gets no line and no button`() {
        assertNull(CastSubtitleNote.note(sent = false, hasText = false, hasLoadedFiles = false, hls = false))
        assertFalse(CastSubtitleNote.buttonStays(sent = false, hasText = false, hasLoadedFiles = false))
    }

    @Test
    fun `the button stays for a file with subtitles so that it can say why`() {
        assertTrue(CastSubtitleNote.buttonStays(sent = false, hasText = true, hasLoadedFiles = false))
        assertTrue(CastSubtitleNote.buttonStays(sent = false, hasText = false, hasLoadedFiles = true))
        // While they are sent the button is always there, as before.
        assertTrue(CastSubtitleNote.buttonStays(sent = true, hasText = false, hasLoadedFiles = false))
    }

    @Test
    fun `while subtitles are sent the old note stays, inside subtitles are left behind unless the stream carries them`() {
        assertEquals(L.castSubtitlesInside, CastSubtitleNote.note(sent = true, hasText = true, hasLoadedFiles = false, hls = false))
        assertNull(CastSubtitleNote.note(sent = true, hasText = true, hasLoadedFiles = false, hls = true))
        assertNull(CastSubtitleNote.note(sent = true, hasText = false, hasLoadedFiles = false, hls = false))
    }
}
