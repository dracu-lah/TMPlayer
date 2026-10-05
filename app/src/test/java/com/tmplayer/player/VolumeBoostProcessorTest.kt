package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class VolumeBoostProcessorTest {

    /** 48 kHz mono 16 bit. */
    private val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)

    private fun processor(on: Boolean) = VolumeBoostProcessor().apply {
        configure(format)
        flush()
        enabled = on
    }

    /** A steady square wave at [level] of full scale, which keeps the peak follower simple. */
    private fun square(level: Float, frames: Int) =
        ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { putShort(((if (it % 2 == 0) level else -level) * 32767).toInt().toShort()) }
            flip()
        }

    private fun run(processor: VolumeBoostProcessor, input: ByteBuffer): List<Float> {
        processor.queueInput(input)
        val out = processor.output
        return buildList { while (out.hasRemaining()) add(out.short / 32768f) }
    }

    @Test
    fun `off passes the sound through untouched`() {
        val out = run(processor(false), square(0.5f, 4800))
        assertEquals(4800, out.size)
        assertEquals(0.5f, abs(out.last()), 0.001f)
    }

    @Test
    fun `quiet sound is lifted by the whole boost`() {
        val out = run(processor(true), square(0.05f, 48_000))
        assertEquals(0.05f * VolumeBoostProcessor.BOOST, abs(out.last()), 0.005f)
    }

    @Test
    fun `loud sound is squeezed well below full scale once the follower has caught it`() {
        val out = run(processor(true), square(1f, 48_000))
        val settled = abs(out.last())
        assertTrue("settled at $settled", settled in 0.5f..0.7f)
        assertTrue(out.all { abs(it) <= 1f })
    }

    @Test
    fun `switching on ramps the gain instead of jumping`() {
        val out = run(processor(true), square(0.05f, 48_000))
        assertTrue(abs(out.first()) < 0.06f)
    }
}
