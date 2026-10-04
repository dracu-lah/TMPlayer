package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which subtitle files the phone's player takes, and the MicroDVD rewrite that lets it read .sub. */
class ExternalSubtitleTest {

    @Test
    fun `the five desktop formats are accepted whatever their case, and nothing else`() {
        listOf("film.srt", "Film.ASS", "a.b.ssa", "x.vtt", "old.SUB").forEach {
            assertTrue(it, ExternalSubtitle.accepts(it))
        }
        listOf("film.mkv", "notes.txt", "srt", "film.srt.zip").forEach {
            assertFalse(it, ExternalSubtitle.accepts(it))
        }
    }

    @Test
    fun `media3 is told the type of every format it reads as it stands`() {
        assertEquals(ExternalSubtitle.MIME_SUBRIP, ExternalSubtitle.mimeTypeOf("srt"))
        assertEquals(ExternalSubtitle.MIME_SSA, ExternalSubtitle.mimeTypeOf("ASS"))
        assertEquals(ExternalSubtitle.MIME_SSA, ExternalSubtitle.mimeTypeOf("ssa"))
        assertEquals(ExternalSubtitle.MIME_VTT, ExternalSubtitle.mimeTypeOf("vtt"))
        assertNull(ExternalSubtitle.mimeTypeOf("sub"))
    }

    @Test
    fun `microdvd is told apart from a vobsub or anything else`() {
        assertTrue(ExternalSubtitle.isMicroDvd("﻿{0}{25}Hello\n"))
        assertFalse(ExternalSubtitle.isMicroDvd("1\n00:00:01,000 --> 00:00:02,000\nHi\n"))
        assertFalse(ExternalSubtitle.isMicroDvd("\u0000\u0000\u0001º binary"))
    }

    @Test
    fun `frames become subrip times at the rate given`() {
        assertEquals("00:00:01,000", ExternalSubtitle.clock(25, 25.0))
        assertEquals("01:01:01,040", ExternalSubtitle.clock(91_526, 25.0))
    }

    @Test
    fun `cues are numbered, bars become line breaks and style codes are dropped`() {
        val srt = ExternalSubtitle.microDvdToSrt("{25}{50}{y:i}Hello|there\n\n{75}{100}Bye\n", fps = 25.0)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\nHello\nthere\n\n" +
                "2\n00:00:03,000 --> 00:00:04,000\nBye\n\n",
            srt,
        )
    }

    @Test
    fun `a stated frame rate in the first cue wins and is not shown`() {
        val srt = ExternalSubtitle.microDvdToSrt("{1}{1}50\n{50}{100}Hi\n", fps = 25.0)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n", srt)
    }
}
