package com.tmplayer.player

/**
 * "Playback test" (reliability plan L6): the first 30 seconds of a video played from the disk,
 * muted, with its whole first window already on the disk, so the network is out of the picture.
 * What drops frames then is the decoder or the render path; a clean run says a stutter is the
 * stream. This file is the rules, apart from the player: what a fetch needs, what the numbers
 * mean, and the lines Send logs carries.
 */
object PlaybackTest {

    /** How long the test plays. */
    const val WINDOW_MS = 30_000L

    /** The first window on disk: 30 MB covers 30 s of all but the heaviest remuxes. */
    const val WINDOW_BYTES = 30L * 1024 * 1024

    /** Left free after a fetch, so a test never fills a disk. */
    const val SPACE_RESERVE_BYTES = 200L * 1024 * 1024

    /** A run that played less than this says nothing: a clip shorter than it, or one that ended or failed early. */
    const val MIN_PLAYED_MS = 10_000L

    /** What fetching the first window comes to. */
    sealed interface Fetch {
        /** The window is already on the disk. */
        data object NotNeeded : Fetch

        /** [bytes] to fetch, with [freeBytes] free: shown before anything starts. */
        data class Needed(val bytes: Long, val freeBytes: Long) : Fetch

        /** [bytes] would not leave [SPACE_RESERVE_BYTES] free. */
        data class NoSpace(val bytes: Long, val freeBytes: Long) : Fetch
    }

    /** The bytes the window needs for a file of [sizeBytes] (all of a file smaller than the window). */
    fun windowBytes(sizeBytes: Long): Long = if (sizeBytes in 1 until WINDOW_BYTES) sizeBytes else WINDOW_BYTES

    /**
     * Whether, and how much, to fetch. [onDiskPrefix] is how much of the start is already on the disk
     * (a download, the watch cache); only the rest is counted against the free space.
     */
    fun fetchPlan(sizeBytes: Long, onDiskPrefix: Long, freeBytes: Long): Fetch {
        val window = windowBytes(sizeBytes)
        val missing = (window - onDiskPrefix.coerceAtLeast(0L)).coerceAtLeast(0L)
        if (missing == 0L) return Fetch.NotNeeded
        val free = freeBytes.coerceAtLeast(0L)
        return if (free - missing >= SPACE_RESERVE_BYTES) Fetch.Needed(missing, free) else Fetch.NoSpace(missing, free)
    }

    enum class Verdict {
        /** Smooth: a stutter on this video is the stream, not the device. */
        CLEAN,

        /** Frames dropped with a software decoder, a busy processor or a heavy load: the decoder cannot keep up. */
        DECODER,

        /** Frames dropped with a hardware decoder and processor to spare: the render path or the clock. */
        RENDER,

        THERMAL,
        MEMORY,

        /** Frames dropped, no cause the readings can name. */
        UNCLEAR,

        /** The file would not play from the disk. */
        COULD_NOT_PLAY,

        /** Played less than [MIN_PLAYED_MS]: too little to tell. */
        TOO_SHORT,
    }

    /** What the run came to. */
    data class Result(
        val verdict: Verdict,
        val droppedFrames: Int,
        val underruns: Int,
        val playedMs: Long,
        val decoder: String,
        val hardware: Boolean,
        val codec: String,
    )

    /**
     * The verdict for a run: [samples] are the readings taken while it played, [playedMs] how long it
     * really played, [failed] that the player reported an error. The network is out of it, so a
     * "network" reading (the audio ran dry) is not named as one: it reads as unclear.
     */
    fun verdict(samples: List<TraceSample>, playedMs: Long, failed: Boolean): Verdict {
        if (failed) return Verdict.COULD_NOT_PLAY
        if (playedMs < MIN_PLAYED_MS) return Verdict.TOO_SHORT
        val a = StutterVerdict.analyse(samples)
        val stutter = a.droppedFrames >= StutterVerdict.MIN_DROPPED_FRAMES || a.underruns >= StutterVerdict.NETWORK_MIN_UNDERRUNS
        if (!stutter) return Verdict.CLEAN
        return when (a.cause) {
            StutterCause.DECODER_BOUND -> Verdict.DECODER
            StutterCause.RENDER_OR_SYNC -> Verdict.RENDER
            StutterCause.THERMAL -> Verdict.THERMAL
            StutterCause.MEMORY -> Verdict.MEMORY
            StutterCause.NETWORK_BOUND, StutterCause.UNKNOWN -> Verdict.UNCLEAR
        }
    }

    /** The result as lines for Send logs: plain words, no title, chat or file name. */
    fun reportLines(result: Result, fetched: Boolean): List<String> = listOf(
        "Playback test: first ${WINDOW_MS / 1000} s of a video played from the disk, muted" +
            if (fetched) " (its first ${WINDOW_BYTES / (1024 * 1024)} MB fetched first)" else "",
        "  verdict ${result.verdict.name.lowercase()}, played ${result.playedMs / 1000} s, dropped ${result.droppedFrames}, " +
            "underruns ${result.underruns}",
        "  codec ${result.codec.filter { it.isLetterOrDigit() || it in "/.+-_" }.take(40).ifEmpty { "?" }}, decoder ${TraceLimits.safeName(result.decoder).ifEmpty { "?" }} " +
            if (result.hardware) "hardware" else "software",
    )
}
