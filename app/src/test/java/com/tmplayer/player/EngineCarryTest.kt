package com.tmplayer.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What crosses between engines (PR2): languages as either engine names them, and which failures count. */
class EngineCarryTest {

    @Test
    fun `a language is the same whichever engine or file names it`() {
        assertTrue(EngineCarry.sameLanguage("en", "eng"))
        assertTrue(EngineCarry.sameLanguage("EN-gb", "eng"))
        assertTrue(EngineCarry.sameLanguage("fr", "fre"))
        assertTrue(EngineCarry.sameLanguage("fra", "fre"))
        assertTrue(EngineCarry.sameLanguage("de", "ger"))
        assertTrue(EngineCarry.sameLanguage("ar", "ara"))
        assertTrue(EngineCarry.sameLanguage("he", "heb"))
        assertTrue(EngineCarry.sameLanguage("zh", "chi"))
        // libVLC's name when the file had no code.
        assertTrue(EngineCarry.sameLanguage("fr", "French"))
        assertTrue(EngineCarry.sameLanguage("ja", "japanese"))
    }

    @Test
    fun `different, unknown or garbage languages never match`() {
        assertFalse(EngineCarry.sameLanguage("en", "fr"))
        assertFalse(EngineCarry.sameLanguage(null, "en"))
        assertFalse(EngineCarry.sameLanguage("en", null))
        assertFalse(EngineCarry.sameLanguage("", ""))
        assertFalse(EngineCarry.sameLanguage("und", "en"))
        assertFalse(EngineCarry.sameLanguage("e1", "e1"))
        assertFalse(EngineCarry.sameLanguage("x".repeat(500), "x".repeat(500)))
        assertFalse(EngineCarry.sameLanguage("Track 1", "Track 1"))
    }

    @Test
    fun `a pick keeps the engine's own choice when nothing matches`() {
        val tracks = listOf(
            EngineTrack("1", EngineTrack.Type.Audio, "eng", "English", true),
            EngineTrack("2", EngineTrack.Type.Audio, "fre", "French", false),
            EngineTrack("3", EngineTrack.Type.Subtitle, "fre", "French", false),
        )
        assertEquals("2", EngineCarry.pick(tracks, EngineTrack.Type.Audio, "fr")?.id)
        assertEquals("3", EngineCarry.pick(tracks, EngineTrack.Type.Subtitle, "fr")?.id)
        assertNull(EngineCarry.pick(tracks, EngineTrack.Type.Audio, "de"))
        assertNull(EngineCarry.pick(tracks, EngineTrack.Type.Audio, null))
        assertNull(EngineCarry.pick(emptyList(), EngineTrack.Type.Audio, "en"))
    }

    @Test
    fun `only the decoder's own failures are the engine's fault`() {
        assertEquals(EngineFailure.Decoder, EngineFailures.of(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(EngineFailure.Decoder, EngineFailures.of(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(EngineFailure.Decoder, EngineFailures.of(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(EngineFailure.Source, EngineFailures.of(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(EngineFailure.Source, EngineFailures.of(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(EngineFailure.Other, EngineFailures.of(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(EngineFailure.Other, EngineFailures.of(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(EngineFailure.Other, EngineFailures.of(-1))
    }

    @Test
    fun `a stream source is handed over as its URL and a file as a file URI`() {
        assertEquals("file:///data/v.mkv", EngineSource.File("/data/v.mkv").address)
        assertEquals("content://x/1", EngineSource.Content("content://x/1").address)
        assertEquals("http://127.0.0.1:9/t/video", EngineSource.Stream("http://127.0.0.1:9/t/video").address)
    }
}
