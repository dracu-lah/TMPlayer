package com.tmplayer.player

/**
 * What the recorder read from the player and the device at one tick (reliability plan L1), before
 * it is turned into a [TraceSample]. Counters are what happened since the last tick.
 */
data class TraceReadings(
    val droppedFrames: Int,
    val underruns: Int,
    val bufferAheadMs: Long,
    val bitrateKbps: Int,
    val decoder: String,
    val hardware: Boolean,
    val reading: TdReadCounters.Reading,
    /** The app's own CPU time so far, milliseconds, or -1 when not known. */
    val cpuTimeMs: Long = -1L,
    val cores: Int = 1,
    val thermal: Int = TraceSample.UNKNOWN,
    val memoryLevel: Int = 0,
    val engine: String = "exoplayer",
)

/**
 * Turns [TraceReadings] into [TraceSample]s: the window each covers, and the CPU share between two
 * ticks. Pure, so a table test can feed it fixed numbers.
 */
class TraceSampler {

    private var lastElapsedMs = -1L
    private var lastCpuMs = -1L

    /** Starts a window now: after a pause, whose idle minutes must not be counted into the next sample. */
    fun rebase(elapsedMs: Long, cpuTimeMs: Long) {
        lastElapsedMs = elapsedMs
        lastCpuMs = cpuTimeMs
    }

    fun sample(wallMs: Long, elapsedMs: Long, r: TraceReadings): TraceSample {
        val interval = if (lastElapsedMs >= 0) (elapsedMs - lastElapsedMs).coerceAtLeast(0L) else 0L
        val cpu = cpuShare(interval, r)
        lastElapsedMs = elapsedMs
        if (r.cpuTimeMs >= 0) lastCpuMs = r.cpuTimeMs
        return TraceSample(
            atMs = wallMs,
            intervalMs = interval,
            droppedFrames = r.droppedFrames,
            underruns = r.underruns,
            bufferAheadMs = r.bufferAheadMs,
            bitrateKbps = r.bitrateKbps,
            decoder = r.decoder,
            hardware = r.hardware,
            readWaitMs = r.reading.waitMs,
            reopens = r.reading.opens,
            cpuPercent = cpu,
            memoryLevel = r.memoryLevel,
            thermal = r.thermal,
            engine = r.engine,
        ).clamped()
    }

    /** CPU time used over the window as a share of all cores, or [TraceSample.UNKNOWN]. */
    private fun cpuShare(intervalMs: Long, r: TraceReadings): Int {
        if (intervalMs <= 0 || r.cpuTimeMs < 0 || lastCpuMs < 0) return TraceSample.UNKNOWN
        val used = (r.cpuTimeMs - lastCpuMs).coerceAtLeast(0L)
        val share = used * 100.0 / (intervalMs * r.cores.coerceAtLeast(1))
        return share.toInt().coerceIn(0, 100)
    }
}

/** How often the recorder reads: often enough on a tester's build to see a stall, rarely on a viewer's. */
object TraceCadence {
    const val NORMAL_MS = 5_000L
    const val TESTER_MS = 2_000L

    /**
     * 2 s on a preview, beta or debug build, 5 s on a release. The build type and version name are
     * what the project tells builds apart by: `preview` is the testers' build (version name ends in
     * `-test`), a pre-release is `vX.Y.Z-beta.N`.
     */
    fun intervalMs(buildType: String, versionName: String): Long = if (isTesterBuild(buildType, versionName)) TESTER_MS else NORMAL_MS

    /** A preview, beta, promo or debug build: the ones that log and trace more (also the app log, L4). */
    fun isTesterBuild(buildType: String, versionName: String): Boolean =
        buildType == "preview" || buildType == "debug" || buildType == "promo" ||
            versionName.contains("-beta", ignoreCase = true) || versionName.endsWith("-test")
}

/** Which of Android's decoders run on the CPU, by name. */
object DecoderNames {

    /** The names a software decoder goes by: Google's reference codecs, the FFmpeg extension, libvpx and libdav1d. */
    fun isSoftware(name: String): Boolean {
        val n = name.lowercase()
        if (n.isEmpty()) return false
        return n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.contains("ffmpeg") ||
            n.contains("libvpx") || n.contains("dav1d") || n.startsWith("c2.ffmpeg") || n.contains(".sw.")
    }
}
