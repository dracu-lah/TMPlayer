package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioOffsetProcessorTest {

    /** 1 kHz mono 16 bit, so a frame is two bytes and a millisecond is one frame. */
    private val format = AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT)

    private fun processor(offsetMs: Long) = AudioOffsetProcessor().apply {
        configure(format)
        flush()
        offsetUs = offsetMs * 1000
    }

    private fun input(frames: Int) =
        ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { putShort((it + 1).toShort()) }
            flip()
        }

    /** Feeds [buffer] until it is used up, collecting every frame that comes out. */
    private fun drain(processor: AudioOffsetProcessor, buffer: ByteBuffer): List<Short> = buildList {
        var guard = 0
        while (buffer.hasRemaining() && guard++ < 100) {
            processor.queueInput(buffer)
            val out = processor.output
            while (out.hasRemaining()) add(out.short)
        }
    }

    @Test
    fun `a positive offset plays silence before the sound`() {
        val out = drain(processor(250), input(50))
        assertEquals(300, out.size)
        assertEquals(List(250) { 0.toShort() }, out.take(250))
        assertEquals(1.toShort(), out[250])
    }

    @Test
    fun `a negative offset drops the start of the sound`() {
        val out = drain(processor(-30), input(50))
        assertEquals(20, out.size)
        assertEquals(31.toShort(), out.first())
    }

    @Test
    fun `a flush applies the offset again from scratch`() {
        val p = processor(100)
        drain(p, input(10))
        p.flush()
        assertEquals(110, drain(p, input(10)).size)
    }

    @Test
    fun `a change mid stream moves by the difference only`() {
        val p = processor(100)
        assertEquals(110, drain(p, input(10)).size)
        p.offsetUs = 150_000
        assertEquals(60, drain(p, input(10)).size)
        p.offsetUs = 140_000
        assertEquals(0, drain(p, input(10)).size)
    }
}
