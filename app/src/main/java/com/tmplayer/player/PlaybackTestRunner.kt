package com.tmplayer.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Plays the first [PlaybackTest.WINDOW_MS] of one file from the disk, muted, and counts what the
 * player says about it (L6): frames dropped, audio underruns, the decoder. Its own short lived
 * player, never the viewer's: no audio focus is taken (nothing else is paused), nothing is
 * written to the playback trace, and it releases itself when it is done or [stop]ped.
 *
 * The renderers are the app's own (the platform decoders first, the FFmpeg extension as the
 * fallback), so a decoder that cannot keep up here is the one the player would use. Main thread only.
 */
@UnstableApi
class PlaybackTestRunner(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    /** The player while a test runs, for the preview picture; null otherwise. Read by the screen. */
    var player: ExoPlayer? by mutableStateOf(null)
        private set

    private var onProgress: (Long) -> Unit = {}
    private var onDone: (PlaybackTest.Result) -> Unit = {}

    private var dropped = 0
    private var underruns = 0
    private var decoder = ""
    private var hardware = true
    private var codec = ""
    private var failed = false
    private var sinceSample = 0
    private var sinceUnderrun = 0
    private var lastSampleAt = 0L
    private var lastProgressPositionMs = -1L
    private var lastProgressAt = 0L
    private var startedAt = 0L
    private val samples = ArrayList<TraceSample>()

    private val tick = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val position = exo.currentPosition.coerceAtLeast(0L)
            val now = wallClock()
            onProgress(position)
            if (position != lastProgressPositionMs) {
                lastProgressPositionMs = position
                lastProgressAt = now
            }
            if (now - lastSampleAt >= SAMPLE_MS) takeSample(now, exo)
            when {
                position >= PlaybackTest.WINDOW_MS || exo.playbackState == Player.STATE_ENDED -> finish(position)
                // The player is not moving (a window that is not whole on the disk, a stuck decoder).
                now - lastProgressAt >= STALL_MS && position < PlaybackTest.MIN_PLAYED_MS -> {
                    failed = true
                    finish(position)
                }
                else -> handler.postDelayed(this, TICK_MS)
            }
        }
    }

    private val listener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            dropped += droppedFrames
            sinceSample += droppedFrames
        }

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            underruns++
            sinceUnderrun++
        }

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            decoder = decoderName
            hardware = !DecoderNames.isSoftware(decoderName)
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            format.sampleMimeType?.let { codec = it }
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            failed = true
            handler.removeCallbacks(tick)
            finish(player?.currentPosition ?: 0L)
        }
    }

    /**
     * Starts the test of [uri]. [progress] gets the played milliseconds about every half second;
     * [done] the result, once, from the main thread. A second start while one runs is ignored.
     */
    fun start(uri: Uri, progress: (Long) -> Unit, done: (PlaybackTest.Result) -> Unit) {
        if (player != null) return
        onProgress = progress
        onDone = done
        dropped = 0
        underruns = 0
        decoder = ""
        hardware = true
        codec = ""
        failed = false
        sinceSample = 0
        sinceUnderrun = 0
        samples.clear()
        lastProgressPositionMs = -1L
        startedAt = wallClock()
        lastSampleAt = startedAt
        lastProgressAt = startedAt
        val exo = try {
            val renderers = NextRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            ExoPlayer.Builder(context, renderers).build()
        } catch (e: Exception) {
            failed = true
            finishWithoutPlayer()
            return
        }
        player = exo
        runCatching {
            exo.addAnalyticsListener(listener)
            // Muted, and no audio focus: the viewer's music or podcast carries on.
            exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false)
            exo.volume = 0f
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.playWhenReady = true
            exo.prepare()
        }.onFailure {
            failed = true
            finish(0L)
            return
        }
        handler.postDelayed(tick, TICK_MS)
    }

    /** Stops without a result: the viewer cancelled. Safe to call twice. */
    fun stop() {
        handler.removeCallbacks(tick)
        release()
    }

    private fun takeSample(now: Long, exo: ExoPlayer) {
        val interval = (now - lastSampleAt).coerceAtLeast(1L)
        val ahead = runCatching { (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L) }.getOrDefault(0L)
        samples += TraceSample(
            atMs = now, intervalMs = interval, droppedFrames = sinceSample, underruns = sinceUnderrun,
            bufferAheadMs = ahead, decoder = decoder, hardware = hardware, thermal = thermalStatus(), engine = "exoplayer-test",
        )
        sinceSample = 0
        sinceUnderrun = 0
        lastSampleAt = now
    }

    private fun finish(position: Long) {
        handler.removeCallbacks(tick)
        val exo = player
        if (exo != null) runCatching { takeSample(wallClock(), exo) }
        val played = position.coerceIn(0L, PlaybackTest.WINDOW_MS)
        release()
        val verdict = PlaybackTest.verdict(samples.toList(), played, failed)
        onDone(PlaybackTest.Result(verdict, dropped, underruns, played, decoder, hardware, codec))
    }

    private fun finishWithoutPlayer() {
        onDone(PlaybackTest.Result(PlaybackTest.Verdict.COULD_NOT_PLAY, 0, 0, 0L, "", true, ""))
    }

    private fun release() {
        val exo = player ?: return
        player = null
        runCatching { exo.removeAnalyticsListener(listener) }
        runCatching { exo.release() }
    }

    private fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return TraceSample.UNKNOWN
        return runCatching { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus }.getOrNull()
            ?: TraceSample.UNKNOWN
    }

    companion object {
        const val TICK_MS = 500L
        const val SAMPLE_MS = 5_000L

        /** No movement for this long in the first seconds: the file would not play from the disk. */
        const val STALL_MS = 15_000L
    }
}
