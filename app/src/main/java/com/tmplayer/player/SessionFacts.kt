package com.tmplayer.player

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The collectors of [TraceFacts] that read the app's own surroundings (RL11, research section 6):
 * the device, the display, the format that plays, the decoder's counters, the earlier run's exit
 * and the ladder. Each returns only what it could read; none throws and none keeps a reference.
 * The decoder's claims and output are [DecoderFacts]'s.
 *
 * Dropped on purpose: `dev.vendorApi`, the vendor's API level, which an app cannot read (the
 * Android release and the security patch in the device lines of the bundle say as much).
 *
 * Not collected yet: the Timings group beyond the decoder's start (open to ready and seek times
 * need hooks in the data source), the Source group (moov position, warm-up, window restarts) and
 * the Audio group (sink and underrun detail). Their keys are declared in [TraceFacts], so a later
 * collector needs no format change.
 */
object SessionFacts {

    /** Once per session: the chip, the Android release, memory and heat, as [TraceFacts]' Device group. */
    fun device(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out["dev.soc"] = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        }
        out["dev.hardware"] = Build.HARDWARE
        out["dev.board"] = Build.BOARD
        out["dev.model"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        out["dev.device"] = Build.DEVICE
        out["dev.sdk"] = Build.VERSION.SDK_INT.toString()
        out["dev.bits"] = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && android.os.Process.is64Bit()) "64" else "32"
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            out["dev.lowRam"] = am.isLowRamDevice.toString()
            out["dev.memClass"] = am.memoryClass.toString()
        }
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            out["dev.saver"] = power.isPowerSaveMode.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) out["dev.thermal"] = power.currentThermalStatus.toString()
        }
        return out
    }

    /** The screen: its current mode, how many modes it has and which HDR types it takes. */
    fun display(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return@runCatching
            val mode = display.mode
            out["dsp.mode"] = "${mode.physicalWidth}x${mode.physicalHeight}@${(mode.refreshRate * 10).toInt() / 10.0}"
            out["dsp.modes"] = display.supportedModes.size.toString()
            @Suppress("DEPRECATION")
            val types: IntArray = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            out["dsp.hdr"] = if (types.isEmpty()) "none" else types.joinToString(",")
            out["dsp.dv"] = (android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types).toString()
        }
        return out
    }

    /** What the picture and the sound are, from the formats the player reports. */
    fun format(video: Format?, audio: Format?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (video != null) {
            out["fmt.container"] = video.containerMimeType.orEmpty().ifEmpty { video.sampleMimeType.orEmpty() }
            out["fmt.codecs"] = video.codecs.orEmpty()
            if (video.width > 0 && video.height > 0) out["fmt.size"] = "${video.width}x${video.height}"
            if (video.frameRate > 0f) out["fmt.fps"] = video.frameRate.toString()
            val rate = maxOf(video.averageBitrate, video.bitrate)
            if (rate > 0) out["fmt.bitrate"] = (rate / 1000).toString()
            video.colorInfo?.let { c ->
                out["fmt.color"] = "${c.colorSpace}/${c.colorTransfer}/${c.colorRange}"
                if (c.lumaBitdepth > 0) out["fmt.depth"] = c.lumaBitdepth.toString()
            }
            if (video.sampleMimeType == "video/dolby-vision" || video.codecs.orEmpty().startsWith("dvh") || video.codecs.orEmpty().startsWith("dav1")) {
                out["fmt.dv"] = video.codecs.orEmpty()
            }
        }
        if (audio != null) {
            out["fmt.audio"] = audio.sampleMimeType.orEmpty()
            if (audio.channelCount > 0) out["fmt.channels"] = audio.channelCount.toString()
        }
        return out
    }

    /** The decoder's counters now (research: "frozen versus slow"): rendered, dropped, skipped and the rest. */
    @UnstableApi
    fun counters(player: ExoPlayer?): Map<String, String> {
        val c = runCatching { player?.videoDecoderCounters?.also { it.ensureUpdated() } }.getOrNull() ?: return emptyMap()
        return linkedMapOf(
            "cnt.rendered" to c.renderedOutputBufferCount.toString(),
            "cnt.dropped" to c.droppedBufferCount.toString(),
            "cnt.skipped" to c.skippedOutputBufferCount.toString(),
            "cnt.dropRun" to c.maxConsecutiveDroppedBufferCount.toString(),
            "cnt.toKeyframe" to c.droppedToKeyframeCount.toString(),
            "cnt.offsetUs" to c.totalVideoFrameProcessingOffsetUs.toString(),
            "cnt.inits" to c.decoderInitCount.toString(),
            "cnt.releases" to c.decoderReleaseCount.toString(),
        )
    }

    /** How the last run of the app ended (Android 11 and later): the system's own reason number, nothing else. */
    fun earlierExit(context: Context): Map<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() ?: return@runCatching emptyMap()
            mapOf("crash.reason" to last.reason.toString())
        }.getOrDefault(emptyMap())
    }

    /** The ladder: its class, the remedies taken, the paths visited and the hops so far. */
    fun ladder(classKey: String?, taken: List<Remedy>, visited: Int, hops: Int): Map<String, String> = buildMap {
        classKey?.let { put("class", it) }
        put("rung", taken.joinToString(",") { it.code }.ifEmpty { "default" })
        put("visited", visited.toString())
        put("hops", hops.toString())
    }
}
