package com.tmplayer.player

import com.tmplayer.data.CrashContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S2 wiring: what the player tells the crash context, and that it stops telling when the playback ends. */
class CrashPlayingNoteTest {

    private class Said(val engine: String, val decoder: String, val codec: String, val width: Int, val height: Int)

    private class FakeSink : CrashPlayingNote.Sink {
        val said = mutableListOf<Said>()
        var stops = 0
        override fun playing(engine: String, decoder: String, codec: String, width: Int, height: Int) {
            said += Said(engine, decoder, codec, width, height)
        }

        override fun stopped() {
            stops++
        }
    }

    @After
    fun reset() = CrashContext.stopped()

    @Test
    fun `the engine, the decoder and the format are put together as they arrive`() {
        val sink = FakeSink()
        val note = CrashPlayingNote(sink)
        note.began("media3")
        note.decoder("c2.mtk.hevc.decoder")
        note.format("video/hevc", 3840, 2160)
        val last = sink.said.last()
        assertEquals("media3", last.engine)
        assertEquals("c2.mtk.hevc.decoder", last.decoder)
        assertEquals("hevc", last.codec)
        assertEquals(3840, last.width)
        assertEquals(2160, last.height)
    }

    @Test
    fun `nothing is said before the playback began or after it ended`() {
        val sink = FakeSink()
        val note = CrashPlayingNote(sink)
        note.decoder("c2.x.avc.decoder")
        assertEquals(0, sink.said.size)
        note.began("libvlc")
        note.ended()
        assertEquals(1, sink.stops)
        val before = sink.said.size
        note.format("video/avc", 1920, 1080)
        note.decoder("late")
        assertEquals(before, sink.said.size)
        // A second end does not say it again.
        note.ended()
        assertEquals(1, sink.stops)
    }

    @Test
    fun `a new playback forgets the last one's decoder and size`() {
        val sink = FakeSink()
        val note = CrashPlayingNote(sink)
        note.began("media3")
        note.decoder("c2.a.avc.decoder")
        note.format("video/avc", 1280, 720)
        note.began("libvlc")
        val last = sink.said.last()
        assertEquals("libvlc", last.engine)
        assertEquals("", last.decoder)
        assertEquals("", last.codec)
        assertEquals(0, last.width)
    }

    @Test
    fun `the real context holds it while it plays and lets go when it ends`() {
        val note = CrashPlayingNote()
        note.began("media3")
        note.decoder("OMX.amlogic.avc.decoder.awesome")
        note.format("video/avc", 1920, 1080)
        val now = CrashContext.playbackNow()
        assertEquals("fhd", CrashContext.sizeClass(now!!.width, now.height))
        assertEquals("avc", CrashContext.tagsOf("", now)[CrashContext.CODEC])
        note.ended()
        assertNull(CrashContext.playbackNow())
    }

    @Test
    fun `a MIME type that is not a known family says nothing, and a private one cannot get through`() {
        assertEquals("", CrashPlayingNote.codecOf("video/x-secret-show-s01e01"))
        assertEquals("", CrashPlayingNote.codecOf(null))
        assertEquals("av1", CrashPlayingNote.codecOf("VIDEO/AV01"))
        // The FFmpeg extension's old-codec decoders are named in their MIME types.
        assertEquals("rv40", CrashPlayingNote.codecOf("video/x-ffmpeg-rv40"))
        assertEquals("wmav2", CrashPlayingNote.codecOf("audio/x-ffmpeg-wmav2"))
        assertEquals("", CrashPlayingNote.codecOf("video/x-ffmpeg-"))
        assertEquals("", CrashPlayingNote.codecOf("video/x-ffmpeg-Some Show S01E01"))
        assertEquals("", CrashPlayingNote.codecOf("video/x-ffmpeg-" + "a".repeat(40)))
    }
}
