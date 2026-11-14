package com.tmplayer.player

/** Why a playback stuttered, as far as a trace can say (reliability plan L2). */
enum class StutterCause {
    /** The decoder could not keep up: frames dropped with a full buffer and an idle line. */
    DECODER_BOUND,

    /** The bytes did not arrive in time: the data source waited and playback ran dry. */
    NETWORK_BOUND,

    /** Frames dropped while the decoder was a hardware one with CPU to spare: the render path or the clock. */
    RENDER_OR_SYNC,

    /** The system was throttling the device. */
    THERMAL,

    /** The system was short of memory. */
    MEMORY,

    /** No stutter in the trace, or not enough to say. */
    UNKNOWN,
}

/** What [StutterVerdict.analyse] saw, for a log line and for the could-not-keep-up card. */
data class StutterAnalysis(
    val cause: StutterCause,
    val samples: Int,
    val droppedFrames: Int,
    val underruns: Int,
    /** Share of the time the data source spent waiting for bytes, 0..1. */
    val readWaitShare: Double,
    /** How strongly each cause's evidence fired, 0..1; 0 means its gate was not met. */
    val strengths: Map<StutterCause, Double>,
)

/**
 * Sorts a run of [TraceSample]s into the cause a viewer would want named.
 *
 * Read wait and underruns separate a slow line from a slow decoder: a slow line waits for bytes and
 * runs dry (the buffer empties first), a slow decoder drops frames while the buffer is full and
 * the data source never waits. Heat and memory are named only when they were present for a good
 * part of the run and something did stutter.
 *
 * Each cause has a gate (named constants below) and, past it, a strength between 0 and 1. The
 * strongest wins; a tie goes by [PRIORITY], so the same trace always gives the same answer.
 */
object StutterVerdict {

    /** Under this many dropped frames in all, and no underrun, the trace shows no stutter. */
    const val MIN_DROPPED_FRAMES = 15

    /** Rates are taken over at least this long, so one short sample cannot make a storm of a blip. */
    const val MIN_WINDOW_MS = 10_000L

    /** A sample that does not say how long it covers counts as this long. */
    const val DEFAULT_INTERVAL_MS = 5_000L

    /** Network: at least this share of the time was spent waiting for bytes, with at least one underrun. */
    const val NETWORK_MIN_READ_WAIT_SHARE = 0.10
    const val NETWORK_MIN_UNDERRUNS = 1

    /** Network strength reaches 1 at this read wait share and at this many underruns a minute. */
    const val NETWORK_FULL_READ_WAIT_SHARE = 0.5
    const val NETWORK_FULL_UNDERRUNS_PER_MIN = 6.0

    /** The buffer counts as fed when its middle reading is at least this much. */
    const val FED_BUFFER_MS = 1_500L

    /** Decoder or render: dropped frames a minute needed to say anything but a blip. */
    const val MIN_DROPS_PER_MIN = 20.0

    /** Decoder strength reaches 1 at this many dropped frames a minute. */
    const val DECODER_FULL_DROPS_PER_MIN = 300.0

    /** Decoder bound when a software decoder ran in this share of the samples, or the CPU averaged this, or drops were this heavy. */
    const val SOFTWARE_SHARE = 0.5
    const val CPU_SATURATED_PERCENT = 85
    const val DECODER_HEAVY_DROPS_PER_MIN = 120.0

    /** Render strength is fixed and low (under PRESSURE_MIN_SHARE): the answer when frames drop and nothing else explains them. */
    const val RENDER_STRENGTH = 0.25

    /** Thermal or memory pressure must cover this share of the samples to be named. */
    const val PRESSURE_MIN_SHARE = 0.3

    /** Strength of two causes within this of each other counts as a tie, which [PRIORITY] settles. */
    const val TIE = 1e-9

    /** Tie order: the root causes first, then the line, then the decoder, then the render path. */
    val PRIORITY = listOf(
        StutterCause.THERMAL,
        StutterCause.MEMORY,
        StutterCause.NETWORK_BOUND,
        StutterCause.DECODER_BOUND,
        StutterCause.RENDER_OR_SYNC,
    )

    fun classify(samples: List<TraceSample>): StutterCause = analyse(samples).cause

    fun analyse(samples: List<TraceSample>): StutterAnalysis {
        val s = samples.map { it.clamped() }
        val none = StutterAnalysis(StutterCause.UNKNOWN, s.size, 0, 0, 0.0, emptyMap())
        if (s.isEmpty()) return none

        val dropped = s.sumOf { it.droppedFrames.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val underruns = s.sumOf { it.underruns.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val covered = s.sumOf { if (it.intervalMs > 0) it.intervalMs else DEFAULT_INTERVAL_MS }
        val window = covered.coerceAtLeast(MIN_WINDOW_MS)
        val minutes = window / 60_000.0
        val readWaitShare = (s.sumOf { it.readWaitMs } / window.toDouble()).coerceIn(0.0, 1.0)
        val base = StutterAnalysis(StutterCause.UNKNOWN, s.size, dropped, underruns, readWaitShare, emptyMap())

        val stutter = dropped >= MIN_DROPPED_FRAMES || underruns >= NETWORK_MIN_UNDERRUNS
        if (!stutter) return base

        val strengths = LinkedHashMap<StutterCause, Double>()

        // The line: it waited for bytes and ran dry.
        if (underruns >= NETWORK_MIN_UNDERRUNS && readWaitShare >= NETWORK_MIN_READ_WAIT_SHARE) {
            val byWait = (readWaitShare / NETWORK_FULL_READ_WAIT_SHARE).coerceAtMost(1.0)
            val byRuns = (underruns / minutes / NETWORK_FULL_UNDERRUNS_PER_MIN).coerceAtMost(1.0)
            strengths[StutterCause.NETWORK_BOUND] = (byWait + byRuns) / 2
        }

        // Frames dropped with the buffer fed and the line quiet: the decoder or the render path.
        val dropsPerMin = dropped / minutes
        val fed = median(s.map { it.bufferAheadMs }) >= FED_BUFFER_MS
        val lineQuiet = readWaitShare < NETWORK_MIN_READ_WAIT_SHARE
        if (dropped >= MIN_DROPPED_FRAMES && dropsPerMin >= MIN_DROPS_PER_MIN && fed && lineQuiet) {
            val software = s.count { !it.hardware } >= s.size * SOFTWARE_SHARE
            val cpus = s.map { it.cpuPercent }.filter { it >= 0 }
            val busy = cpus.isNotEmpty() && cpus.average() >= CPU_SATURATED_PERCENT
            if (software || busy || dropsPerMin >= DECODER_HEAVY_DROPS_PER_MIN) {
                strengths[StutterCause.DECODER_BOUND] = (dropsPerMin / DECODER_FULL_DROPS_PER_MIN).coerceIn(0.1, 1.0)
            } else {
                strengths[StutterCause.RENDER_OR_SYNC] = RENDER_STRENGTH
            }
        }

        // Heat and memory: a root cause, named when it was there for a good part of the run.
        val severe = s.count { it.thermal >= TraceLimits.THERMAL_SEVERE } / s.size.toDouble()
        if (severe >= PRESSURE_MIN_SHARE) strengths[StutterCause.THERMAL] = severe
        val short = s.count { it.memoryLevel >= TraceLimits.MEMORY_PRESSURE_LEVEL } / s.size.toDouble()
        if (short >= PRESSURE_MIN_SHARE) strengths[StutterCause.MEMORY] = short

        val best = strengths.values.maxOrNull() ?: return base
        val cause = PRIORITY.first { (strengths[it] ?: -1.0) >= best - TIE }
        return base.copy(cause = cause, strengths = strengths)
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
