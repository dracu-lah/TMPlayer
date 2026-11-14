package com.tmplayer.data

import com.tmplayer.player.LastVideoCodec
import com.tmplayer.player.TraceFile
import com.tmplayer.player.TraceSample
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playback part of Send logs (L5): sessions as blocks, and the last codec file that picks the decoder list. */
class LogReportTest {

    private fun temp(): File = File.createTempFile("logreport", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `each session in the trace file is one block with a header and its readings`() {
        val file = File(temp(), TraceFile.NAME)
        val trace = TraceFile(file)
        trace.startSession(1_700_000_000_000L)
        trace.append(TraceSample(atMs = 1_700_000_005_000L, intervalMs = 5_000L, droppedFrames = 2, decoder = "c2.qti.avc.decoder"))
        trace.startSession(1_700_001_000_000L)
        trace.append(TraceSample(atMs = 1_700_001_005_000L, intervalMs = 5_000L))
        trace.append(TraceSample(atMs = 1_700_001_010_000L, intervalMs = 5_000L))

        val blocks = LogReport.sessionBlocks(file)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0][0].startsWith("Session started 2023-11-14"))
        assertTrue("1 reading(s)" in blocks[0][0])
        assertEquals(2, blocks[0].size)
        assertTrue("decoder=c2.qti.avc.decoder" in blocks[0][1])
        assertEquals(3, blocks[1].size)
    }

    @Test
    fun `no file, an empty one and a garbage one give no blocks and no error`() {
        val dir = temp()
        assertEquals(emptyList<List<String>>(), LogReport.sessionBlocks(File(dir, "none.jsonl")))
        val garbage = File(dir, "garbage.jsonl").apply { writeText("{{{{ not json\n\u0000\u0000\n[1,2") }
        assertEquals(emptyList<List<String>>(), LogReport.sessionBlocks(garbage))
    }

    @Test
    fun `the last codec is kept only when it is a mime type`() {
        val file = File(temp(), DiagnosticFiles.LAST_CODEC)
        LastVideoCodec.remember(file, "Video/HEVC")
        assertEquals("video/hevc", LastVideoCodec.read(file))
        LastVideoCodec.remember(file, "My Title: S01E02.mkv")
        LastVideoCodec.remember(file, null)
        LastVideoCodec.remember(file, "video/x\nevil")
        assertEquals("video/hevc", LastVideoCodec.read(file))
        assertNull(LastVideoCodec.read(File(temp(), "nothing")))
        file.writeText("a chat title with spaces")
        assertNull(LastVideoCodec.read(file))
        assertTrue(LastVideoCodec.clean("video/" + "a".repeat(200))!!.length <= 64)
        assertNull(LastVideoCodec.clean("../../etc/passwd"))
    }
}
