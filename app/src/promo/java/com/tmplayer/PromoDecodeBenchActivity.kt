package com.tmplayer

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.PlayerView
import com.tmplayer.player.DecoderSwitch
import com.tmplayer.player.DeviceDecode
import com.tmplayer.player.LevelLenientVideoRenderer
import com.tmplayer.player.PlaybackWatch
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.io.File

/**
 * A decode bench for slow TVs (HX2): plays a file from the disk through the renderers the real
 * player builds (the device's decoders first, NextLib's FFmpeg ones behind them, the level-lenient
 * video renderer, decoder fallback on) and the buffer a 1 GB stick gets, and logs every five
 * seconds which decoders took the tracks, the frames dropped and the process's CPU:
 *
 *     adb push clip.mkv /sdcard/Android/data/com.tmplayer.promo/files/
 *     adb shell am start -n com.tmplayer.promo/com.tmplayer.PromoDecodeBenchActivity --es file clip.mkv [--ei seconds 40]
 *     adb logcat -s DecodeBench
 *
 * The bytes come off the disk, not from TDLib, so this measures the decode and render side only.
 * Nothing here exists in a release build.
 */
@UnstableApi
class PromoDecodeBenchActivity : Activity() {

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var videoDecoder = "?"
    private var audioDecoder = "?"
    private var dropped = 0
    private var startedAt = 0L
    private var lastCpuTicks = 0L
    private var lastWall = 0L
    private var cpuSum = 0.0
    private var cpuSamples = 0
    private var stalls = 0
    private var underruns = 0
    private var readyWall = 0L
    private var readyPos = 0L
    private val watch = PlaybackWatch()
    private var verdict = "?"
    private var watchAnswer = "none"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val name = intent.getStringExtra("file") ?: run {
            Log.e(TAG, "no --es file given")
            finish()
            return
        }
        val file = if (name.startsWith("/")) File(name) else File(getExternalFilesDir(null), name)
        if (!file.isFile) {
            Log.e(TAG, "no such file: $file")
            finish()
            return
        }
        val seconds = intent.getIntExtra("seconds", 40)
        DeviceDecode.start(this)
        val view = PlayerView(this).apply { useController = false }
        setContentView(view)

        val renderers = object : NextRenderersFactory(this@PromoDecodeBenchActivity) {
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                val built = ArrayList<Renderer>()
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, built,
                )
                built.mapTo(out) { if (it is MediaCodecVideoRenderer) LevelLenientVideoRenderer(it) else it }
            }
        }
            .setExtensionRendererMode(
                if (intent.getBooleanExtra("software", false)) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                },
            )
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 4_000)
            .setTargetBufferBytes(20 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(3_000, true)
            .build()
        val exo = ExoPlayer.Builder(this, renderers).setLoadControl(loadControl).build()
        player = exo
        view.player = exo
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                val picture = DeviceDecode.pictureOf(format)
                verdict = DeviceDecode.verdictOf(format).name.lowercase()
                val load = picture?.let { com.tmplayer.data.DecodeJudge.load(it, DeviceDecode.cpu) }
                Log.i(TAG, "video format ${format.sampleMimeType} ${format.codecs} ${format.width}x${format.height} ${format.frameRate} fps ${format.bitrate} bps")
                Log.i(TAG, "judged $verdict, software load $load% of ${DeviceDecode.cpu}")
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                Log.i(TAG, "audio format ${format.sampleMimeType} ${format.channelCount} ch ${format.sampleRate} Hz")
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                underruns++
            }

            override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
                watch.frameOffsets(totalProcessingOffsetUs, frameCount)
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (state == Player.STATE_BUFFERING && startedAt != 0L) stalls++
                if (state == Player.STATE_READY && startedAt == 0L) {
                    startedAt = SystemClock.elapsedRealtime()
                    readyWall = startedAt
                    readyPos = exo.currentPosition
                    Log.i(TAG, "ready")
                }
                if (state == Player.STATE_ENDED) finishBench()
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                Log.e(TAG, "error ${error.errorCodeName}", error)
            }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.play()
        lastCpuTicks = cpuTicks()
        lastWall = SystemClock.elapsedRealtime()
        handler.postDelayed(object : Runnable {
            override fun run() {
                report()
                handler.postDelayed(this, REPORT_MS)
            }
        }, REPORT_MS)
        // The player's watchdog over the same play, for checking it against the figures.
        handler.postDelayed(object : Runnable {
            override fun run() {
                val p = player ?: return
                if (startedAt == 0L) {
                    handler.postDelayed(this, 1_000)
                    return
                }
                val answer = watch.sample(
                    SystemClock.elapsedRealtime(), p.currentPosition, p.isPlaying,
                    p.videoDecoderCounters?.renderedOutputBufferCount ?: 0, dropped, underruns,
                )
                if (answer != null) {
                    watchAnswer = answer.name.lowercase()
                    Log.i(TAG, "watch $watchAnswer: ${watch.summary()}")
                } else {
                    handler.postDelayed(this, 1_000)
                }
            }
        }, 1_000)
        handler.postDelayed({ finishBench() }, seconds * 1000L + 3_000)
    }

    private fun report() {
        val exo = player ?: return
        val now = SystemClock.elapsedRealtime()
        val ticks = cpuTicks()
        // Clock ticks are 100 a second on every Android kernel this runs on.
        val cpu = (ticks - lastCpuTicks) * 10.0 / (now - lastWall).coerceAtLeast(1) * 100.0
        lastCpuTicks = ticks
        lastWall = now
        if (startedAt != 0L) {
            cpuSum += cpu
            cpuSamples++
        }
        val counters = exo.videoDecoderCounters
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val ffmpegVideo = DecoderSwitch.softwareVideoChosen(exo)
        Log.i(
            TAG,
            "pos ${exo.currentPosition / 1000}s rendered $rendered dropped $dropped stalls $stalls cpu ${"%.0f".format(cpu)}% " +
                "video $videoDecoder${if (ffmpegVideo) " (ffmpeg)" else ""} audio $audioDecoder",
        )
    }

    private fun finishBench() {
        val exo = player ?: return
        report()
        val counters = exo.videoDecoderCounters
        // How far the picture got against the clock since it was first ready: 1.0 is real time.
        val wall = SystemClock.elapsedRealtime() - readyWall
        val speed = if (readyWall > 0 && wall > 0) (exo.currentPosition - readyPos).toDouble() / wall else 0.0
        Log.i(
            TAG,
            "SUMMARY file ${intent.getStringExtra("file")} software ${intent.getBooleanExtra("software", false)} judged $verdict watch $watchAnswer speed ${"%.2f".format(speed)} underruns $underruns video $videoDecoder audio $audioDecoder rendered ${counters?.renderedOutputBufferCount ?: 0} " +
                "dropped $dropped maxConsecutive ${counters?.maxConsecutiveDroppedBufferCount ?: 0} stalls $stalls " +
                "avgCpu ${"%.0f".format(if (cpuSamples > 0) cpuSum / cpuSamples else 0.0)}% of one core",
        )
        handler.removeCallbacksAndMessages(null)
        exo.release()
        player = null
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    /** utime plus stime of this process, fields 14 and 15 of /proc/self/stat. */
    private fun cpuTicks(): Long = runCatching {
        val stat = File("/proc/${Process.myPid()}/stat").readText()
        val fields = stat.substringAfterLast(')').trim().split(' ')
        // Fields after the name start at field 3, so 14 and 15 are indexes 11 and 12 here.
        fields[11].toLong() + fields[12].toLong()
    }.getOrDefault(0L)

    private companion object {
        const val TAG = "DecodeBench"
        const val REPORT_MS = 5_000L
    }
}
