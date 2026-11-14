package com.tmplayer.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StutterVerdictTest {

    /** A 5 s window with a fed buffer and a quiet line, over which the caller changes what it wants. */
    private fun s(
        dropped: Int = 0,
        underruns: Int = 0,
        buffer: Long = 8_000,
        wait: Long = 0,
        hardware: Boolean = true,
        cpu: Int = 40,
        memory: Int = 0,
        thermal: Int = 0,
    ) = TraceSample(
        atMs = 1, intervalMs = 5_000, droppedFrames = dropped, underruns = underruns, bufferAheadMs = buffer,
        readWaitMs = wait, hardware = hardware, cpuPercent = cpu, memoryLevel = memory, thermal = thermal,
    )

    private fun run(count: Int, make: () -> TraceSample) = List(count) { make() }

    @Test
    fun emptyAndCleanTracesAreUnknown() {
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(emptyList()))
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(run(60) { s() }))
        // The four field reports: hardware decoder, no drops, no underruns.
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(run(12) { s(hardware = true, cpu = 30) }))
    }

    @Test
    fun aFewDroppedFramesAreNotAStutter() {
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(run(12) { s(dropped = 1) }.take(10)))
    }

    @Test
    fun oneSample() {
        assertEquals(StutterCause.DECODER_BOUND, StutterVerdict.classify(listOf(s(dropped = 80, hardware = false))))
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(listOf(s())))
        assertEquals(StutterCause.NETWORK_BOUND, StutterVerdict.classify(listOf(s(underruns = 1, wait = 2_000, buffer = 0))))
    }

    @Test
    fun readWaitAndUnderrunsMeanTheLine() {
        val trace = run(12) { s(underruns = 1, wait = 1_500, buffer = 300, dropped = 2) }
        val a = StutterVerdict.analyse(trace)
        assertEquals(StutterCause.NETWORK_BOUND, a.cause)
        assertEquals(12, a.underruns)
        assertTrue(a.readWaitShare in 0.29..0.31)
    }

    @Test
    fun droppedFramesWithAFedBufferAndQuietLineMeanTheDecoder() {
        assertEquals(StutterCause.DECODER_BOUND, StutterVerdict.classify(run(12) { s(dropped = 30, hardware = false) }))
        assertEquals(StutterCause.DECODER_BOUND, StutterVerdict.classify(run(12) { s(dropped = 12, cpu = 95) }))
        assertEquals(StutterCause.DECODER_BOUND, StutterVerdict.classify(run(12) { s(dropped = 12 * 5) }))
    }

    @Test
    fun aHardwareDecoderWithCpuToSpareMeansRenderOrSync() {
        assertEquals(StutterCause.RENDER_OR_SYNC, StutterVerdict.classify(run(12) { s(dropped = 3, hardware = true, cpu = 35) }))
        // CPU unknown is not "busy".
        assertEquals(StutterCause.RENDER_OR_SYNC, StutterVerdict.classify(run(12) { s(dropped = 3, cpu = -1) }))
    }

    @Test
    fun anEmptyBufferWithDropsIsNotBlamedOnTheDecoder() {
        // Dropped frames while starved are the line's doing, and with no read wait nothing is named.
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(run(12) { s(dropped = 30, buffer = 100) }))
    }

    @Test
    fun heatIsNamedWhenItWasThereAndSomethingStuttered() {
        val trace = run(4) { s(dropped = 4, thermal = 4, cpu = 40) } + run(8) { s(dropped = 4, thermal = 0) }
        assertEquals(StutterCause.THERMAL, StutterVerdict.classify(trace))
        // Heat with no stutter says nothing.
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(run(12) { s(thermal = 5) }))
        // A unknown thermal reading is not heat.
        assertEquals(StutterCause.RENDER_OR_SYNC, StutterVerdict.classify(run(12) { s(dropped = 3, thermal = -1) }))
    }

    @Test
    fun lowMemoryIsNamedWhenItWasThere() {
        assertEquals(StutterCause.MEMORY, StutterVerdict.classify(run(12) { s(dropped = 4, memory = 15) }))
        assertEquals(StutterCause.RENDER_OR_SYNC, StutterVerdict.classify(run(12) { s(dropped = 3, memory = 5) }))
    }

    @Test
    fun mixedCausesPickTheStrongestAndTiesGoByPriority() {
        // Heavy software decode drops (strength 1.0) beat heat in a third of the run (0.33).
        val decoder = run(4) { s(dropped = 100, hardware = false, thermal = 4) } + run(8) { s(dropped = 100, hardware = false) }
        assertEquals(StutterCause.DECODER_BOUND, StutterVerdict.classify(decoder))
        // Heat throughout beats a weak render problem.
        assertEquals(StutterCause.THERMAL, StutterVerdict.classify(run(12) { s(dropped = 3, thermal = 3) }))
        // Heat and low memory at the same share: the priority order says heat.
        val tie = run(12) { s(dropped = 3, thermal = 3, memory = 20) }
        assertEquals(StutterCause.THERMAL, StutterVerdict.classify(tie))
        // Starved line and heat in half the run: the line (read wait 60 percent) is stronger.
        val net = run(6) { s(underruns = 1, wait = 3_000, buffer = 0, thermal = 4) } + run(6) { s(underruns = 1, wait = 3_000, buffer = 0) }
        assertEquals(StutterCause.NETWORK_BOUND, StutterVerdict.classify(net))
    }

    @Test
    fun theAnswerDoesNotDependOnTheOrderOfTheSamples() {
        val trace = run(4) { s(dropped = 4, thermal = 4) } + run(4) { s(underruns = 1, wait = 2_000, buffer = 200) } + run(4) { s(dropped = 30, hardware = false) }
        val verdict = StutterVerdict.classify(trace)
        assertEquals(verdict, StutterVerdict.classify(trace.reversed()))
        assertEquals(verdict, StutterVerdict.classify(trace.shuffled(kotlin.random.Random(3))))
    }

    @Test
    fun absurdSamplesAreClampedBeforeTheArithmetic() {
        val wild = TraceSample(
            atMs = -1, intervalMs = -5, droppedFrames = Int.MAX_VALUE, underruns = Int.MIN_VALUE, bufferAheadMs = Long.MAX_VALUE,
            readWaitMs = Long.MAX_VALUE, cpuPercent = Int.MAX_VALUE, memoryLevel = Int.MAX_VALUE, thermal = Int.MAX_VALUE,
        )
        val a = StutterVerdict.analyse(listOf(wild, wild))
        assertTrue(a.readWaitShare in 0.0..1.0)
        assertTrue(a.droppedFrames >= 0 && a.underruns >= 0)
        assertTrue(a.strengths.values.all { it in 0.0..1.0 })
    }

    @Test
    fun aLongRunWithOneBlipIsNotAStutter() {
        val trace = run(719) { s() } + listOf(s(dropped = 14))
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(trace))
        // 15 drops in an hour is under the per minute floor too.
        assertEquals(StutterCause.UNKNOWN, StutterVerdict.classify(run(719) { s() } + listOf(s(dropped = 15))))
    }
}
