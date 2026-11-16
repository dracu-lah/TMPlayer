package com.tmplayer.player

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.tmplayer.data.DiagnosticFiles
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Feeds [PlaybackTrace] while a video plays (reliability plan L1): one reading every
 * [intervalMs] (5 s, 2 s on a tester's build, see [TraceCadence]) of the frames dropped, the
 * audio underruns, the buffer ahead, the bitrate, the decoder and how long the data source was
 * blocked on TDLib ([TdReadCounters]).
 *
 * What it costs: one handler tick per interval on the main thread, which reads a handful of
 * numbers and makes one [TraceSample]; the disk is written on a single low-priority thread. It
 * ticks only while the player plays or buffers: a pause stops the ticks (one last reading is
 * taken as it pauses) and the next play starts them again, so an idle player costs nothing. It
 * stops with the player ([stop]), and never holds a title, a file name or a chat: a [TraceSample]
 * has no field for one.
 *
 * Main thread only, except the writer thread it owns.
 */
@UnstableApi
class PlaybackRecorder(
    private val context: Context,
    private val file: TraceFile,
    private val handler: Handler,
    val intervalMs: Long,
    private val engine: String = "exoplayer",
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {

    private var player: ExoPlayer? = null
    private var writer: ExecutorService? = null
    private val sampler = TraceSampler()
    private var ring = PlaybackTraceRing(maxSessions = 1)

    private var scheduled = false
    private var windowOpen = false

    /** Keeps [playing] at the number of recorders whose player is playing or buffering now. */
    private fun setWindow(open: Boolean) {
        if (open == windowOpen) return
        windowOpen = open
        if (open) playing.incrementAndGet() else playing.decrementAndGet()
    }

    private var dropped = 0
    private var underruns = 0
    private var decoder = ""
    private var hardware = true
    private var bitrateKbps = 0
    private var videoFormat: Format? = null
    private var audioFormat: Format? = null
    private var memoryLevel = 0
    private var memoryLevelAt = 0L

    private val tick = Runnable {
        scheduled = false
        takeSample()
        schedule()
    }

    private val listener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            dropped += droppedFrames
        }

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            underruns++
        }

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            decoder = decoderName
            hardware = !DecoderNames.isSoftware(decoderName) && !DecoderSwitch.softwareVideoChosen(player)
            // The decoder's claims and its start time (RL12), read off the main thread: the codec list is a few milliseconds.
            val format = videoFormat
            collect {
                val claims = if (format == null) mapOf("dec.name" to decoderName) else {
                    DecoderFacts.claims(decoderName, format.sampleMimeType.orEmpty(), format.width, format.height, format.frameRate.toDouble())
                }
                claims + SessionFacts.format(format, audioFormat) + ("t.decoderInit" to initializationDurationMs.toString())
            }
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            videoFormat = format
            val rate = maxOf(format.averageBitrate, format.bitrate)
            if (rate > 0) bitrateKbps = rate / 1000
            // The codec, kept for Send logs' list of the decoders the device has for it (L5).
            format.sampleMimeType?.let { mime -> writer?.let { w -> runCatching { w.execute { LastVideoCodec.remember(DiagnosticFiles.codecFile(context), mime) } } } }
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            audioFormat = format
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) = playStateChanged()

        override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) = playStateChanged()
    }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // Only the levels that say "this app is in front and the device is short": the later
            // ones (UI hidden, background) are about the app not being seen, not about the video.
            if (level in RUNNING_MODERATE..RUNNING_CRITICAL) {
                handler.post {
                    memoryLevel = level
                    memoryLevelAt = elapsedClock()
                }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() = Unit
    }

    private var registered = false

    /** Starts a session on [exo]: a new one in the file, the counters at zero. */
    fun attach(exo: ExoPlayer) {
        stop()
        player = exo
        ring = PlaybackTraceRing(maxSessions = 1)
        dropped = 0
        underruns = 0
        decoder = ""
        hardware = true
        bitrateKbps = 0
        videoFormat = null
        audioFormat = null
        memoryLevel = 0
        TdReadCounters.reset()
        val started = wallClock()
        ring.startSession(started)
        val thread = Executors.newSingleThreadExecutor { task ->
            Thread(task, "playback-trace").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        writer = thread
        runCatching { thread.execute { file.startSession(started) } }
        runCatching { exo.addAnalyticsListener(listener) }
        // The device, the screen and how the last run ended: once per session, off the main thread.
        collect { SessionFacts.device(context) + SessionFacts.display(context) + SessionFacts.earlierExit(context) }
        runCatching {
            context.applicationContext.registerComponentCallbacks(trimCallbacks)
            registered = true
        }
        playStateChanged()
    }

    /** Stops the ticks, takes the last reading, and lets the writer finish what it has. Safe to call twice. */
    fun stop() {
        val exo = player ?: return
        handler.removeCallbacks(tick)
        scheduled = false
        if (windowOpen) takeSample()
        setWindow(false)
        noteCounters()
        runCatching { exo.removeAnalyticsListener(listener) }
        if (registered) {
            runCatching { context.applicationContext.unregisterComponentCallbacks(trimCallbacks) }
            registered = false
        }
        player = null
        writer?.shutdown()
        writer = null
    }

    /** The readings of this session so far, oldest first (at most 720). */
    fun currentSamples(): List<TraceSample> = ring.currentSamples()

    /** The readings of the last [windowMs] of this session. */
    fun lastWindow(windowMs: Long): List<TraceSample> {
        val all = ring.currentSamples()
        val newest = all.lastOrNull()?.atMs ?: return emptyList()
        return all.filter { it.atMs > newest - windowMs }
    }

    /**
     * A reading of this moment, then the last [windowMs] of the session: what "Report stutter"
     * keeps (L3). Pressed twice, during a seek or before the first reading it gives what there is.
     */
    fun momentWindow(windowMs: Long): List<TraceSample> {
        if (windowOpen) takeSample()
        return lastWindow(windowMs)
    }

    /**
     * Adds [facts] to this session (research section 6: the ladder, the decoder, the counters, the
     * device): kept in the ring for the verdict and written on the writer thread. Unknown keys are
     * dropped by [TraceFacts.clean]. Cheap and safe to call at any moment, also before [attach].
     */
    fun noteFacts(facts: Map<String, String>) {
        val clean = TraceFacts.clean(facts)
        if (clean.isEmpty()) return
        ring.addFacts(clean)
        val at = wallClock()
        val thread = writer ?: return
        runCatching { thread.execute { file.appendFacts(at, clean) } }
    }

    /** The decoder's counters now, as facts: at a hop and at the end of the session (research: "frozen versus slow"). */
    fun noteCounters() = noteFacts(SessionFacts.counters(player))

    /**
     * Runs [read] on the writer thread and notes what it returns as facts, on the main thread. A
     * reading that throws, or that comes back after the session ended, is dropped.
     */
    private fun collect(read: () -> Map<String, String>) {
        val thread = writer ?: return
        runCatching {
            thread.execute {
                val facts = runCatching(read).getOrNull() ?: return@execute
                handler.post { if (player != null) noteFacts(facts) }
            }
        }
    }

    /** Adds one ladder hop to this session. */
    fun noteHop(hop: TraceHop) {
        ring.addHop(hop)
        val thread = writer ?: return
        runCatching { thread.execute { file.appendHop(hop) } }
    }

    /** The facts of this session so far. */
    fun facts(): Map<String, String> = ring.currentFacts()

    /** What the verdict says about this session so far. */
    fun verdict(): StutterCause = StutterVerdict.classify(ring.currentSamples())

    private fun running(exo: ExoPlayer): Boolean =
        exo.playWhenReady && (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING)

    /** The play state moved: start the ticks when it plays or buffers, close the window when it does not. */
    private fun playStateChanged() {
        val exo = player ?: return
        if (running(exo)) {
            if (!windowOpen) {
                setWindow(true)
                sampler.rebase(elapsedClock(), cpuTimeMs())
            }
            schedule()
        } else if (windowOpen) {
            handler.removeCallbacks(tick)
            scheduled = false
            takeSample()
            setWindow(false)
        }
    }

    private fun schedule() {
        val exo = player ?: return
        if (scheduled || !windowOpen) return
        if (!running(exo)) {
            playStateChanged()
            return
        }
        scheduled = true
        handler.postDelayed(tick, intervalMs)
    }

    private fun takeSample() {
        val exo = player ?: return
        val now = elapsedClock()
        val ahead = runCatching { (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L) }.getOrDefault(0L)
        val readings = TraceReadings(
            droppedFrames = dropped,
            underruns = underruns,
            bufferAheadMs = ahead,
            bitrateKbps = bitrateKbps,
            decoder = decoder,
            hardware = hardware,
            reading = TdReadCounters.take(),
            cpuTimeMs = cpuTimeMs(),
            cores = Runtime.getRuntime().availableProcessors(),
            thermal = thermalStatus(),
            memoryLevel = if (now - memoryLevelAt <= MEMORY_STICKY_MS) memoryLevel else 0,
            engine = engine,
        )
        dropped = 0
        underruns = 0
        val sample = sampler.sample(wallClock(), now, readings)
        // A reading that covers no time (the pause came a moment after a tick) is not worth a line.
        if (sample.intervalMs < MIN_WINDOW_MS && sample.droppedFrames == 0 && sample.underruns == 0 && sample.readWaitMs == 0L) return
        ring.add(sample)
        val thread = writer ?: return
        runCatching { thread.execute { file.append(sample) } }
    }

    private fun cpuTimeMs(): Long = runCatching { android.os.Process.getElapsedCpuTime() }.getOrDefault(-1L)

    private val power: PowerManager? by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    private fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return TraceSample.UNKNOWN
        return runCatching { power?.currentThermalStatus }.getOrNull() ?: TraceSample.UNKNOWN
    }

    companion object {
        /** Recorders whose player plays or buffers now: above zero, a video is playing (Playback test refuses then). */
        val playing = java.util.concurrent.atomic.AtomicInteger(0)

        /** A window shorter than this with nothing in it is dropped. */
        const val MIN_WINDOW_MS = 1_000L

        /** A trim-memory level counts for this long after it was heard. */
        const val MEMORY_STICKY_MS = 30_000L

        /** `TRIM_MEMORY_RUNNING_MODERATE` and `_CRITICAL`, spelt as numbers: the constants are deprecated from Android 14 though the levels still arrive. */
        private const val RUNNING_MODERATE = 5
        private const val RUNNING_CRITICAL = 15

        /** The trace file in the app's private folder. */
        fun fileIn(context: Context): TraceFile = TraceFile(java.io.File(context.filesDir, TraceFile.NAME))
    }
}
