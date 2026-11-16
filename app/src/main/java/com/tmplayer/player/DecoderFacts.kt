package com.tmplayer.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * What a decoder claims and what it outputs, as [TraceFacts] (RL12, research section 6 "Decoder"
 * and "Codec output"): the facts that tell a wrong claim from a real limit, and that show a green
 * or wrongly coloured picture. Read only: it asks `MediaCodecList` and the codec's own output
 * format and opens no codec, so it costs a few milliseconds once per decoder.
 *
 * Reuses [DecoderLister] for the one list of the device's decoders, so the names here are the ones
 * the Send logs list shows. Every call is guarded: a device whose codec list or capability call
 * throws gives fewer facts, never an error, and a call the Android release lacks (vendor and alias
 * flags and performance points are from Android 10) leaves its key out.
 */
object DecoderFacts {

    /**
     * The claims of the decoder named [name] for a picture of [width] x [height] at [fps]
     * (0 when not known) in [mime]: whether it is hardware, software only, vendor or an alias, how
     * many instances it allows, its profile levels, the performance points it lists, whether it
     * says it takes this picture and the rate it says it can achieve at this size.
     */
    fun claims(name: String, mime: String, width: Int, height: Int, fps: Double): Map<String, String> {
        val info = DecoderLister.infoOf(name) ?: return mapOf("dec.name" to name)
        return claims(info, mime, width, height, fps, Build.VERSION.SDK_INT)
    }

    /** [claims] for a decoder already looked up; [sdk] is a parameter so a test can pick the Android release. */
    fun claims(info: MediaCodecInfo, mime: String, width: Int, height: Int, fps: Double, sdk: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["dec.name"] = info.name
        if (sdk >= Build.VERSION_CODES.Q) {
            runCatching { out["dec.hw"] = info.isHardwareAccelerated.toString() }
            runCatching { out["dec.sw"] = info.isSoftwareOnly.toString() }
            runCatching { out["dec.vendor"] = info.isVendor.toString() }
            runCatching { out["dec.alias"] = info.isAlias.toString() }
        } else {
            out["dec.hw"] = (!DecoderNames.isSoftware(info.name)).toString()
            out["dec.sw"] = DecoderNames.isSoftware(info.name).toString()
        }
        runCatching {
            val caps = info.getCapabilitiesForType(mime)
            out["dec.instances"] = caps.maxSupportedInstances.toString()
            caps.profileLevels.orEmpty().take(MAX_LEVELS).joinToString(",") { "${it.profile}/${it.level}" }
                .takeIf { it.isNotEmpty() }?.let { out["dec.levels"] = it }
            caps.videoCapabilities?.let { video ->
                if (sdk >= Build.VERSION_CODES.Q) {
                    video.supportedPerformancePoints.orEmpty().take(MAX_POINTS).joinToString(",") { it.toString() }
                        .takeIf { it.isNotEmpty() }?.let { out["dec.perf"] = it }
                }
                if (width > 0 && height > 0) {
                    out["dec.fits"] = (if (fps > 0) video.areSizeAndRateSupported(width, height, fps) else video.isSizeSupported(width, height)).toString()
                    runCatching { video.getAchievableFrameRatesFor(width, height) }.getOrNull()?.let {
                        out["dec.fps"] = "${it.lower.toInt()}-${it.upper.toInt()}"
                    }
                }
            }
        }
        return out
    }

    /**
     * What a codec's output format says: colour standard, transfer and range (wrong ones are what a
     * green or washed picture shows), the picture size and crop, the rate and the pixel format.
     */
    fun output(format: MediaFormat): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun int(key: String): Int? = runCatching { if (format.containsKey(key)) format.getInteger(key) else null }.getOrNull()
        int(MediaFormat.KEY_COLOR_STANDARD)?.let { out["out.standard"] = it.toString() }
        int(MediaFormat.KEY_COLOR_TRANSFER)?.let { out["out.transfer"] = it.toString() }
        int(MediaFormat.KEY_COLOR_RANGE)?.let { out["out.range"] = it.toString() }
        val width = int(MediaFormat.KEY_WIDTH)
        val height = int(MediaFormat.KEY_HEIGHT)
        if (width != null && height != null) out["out.size"] = "${width}x$height"
        val crop = listOf("crop-left", "crop-top", "crop-right", "crop-bottom").map { int(it) }
        if (crop.all { it != null }) out["out.crop"] = crop.joinToString(",")
        runCatching { if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getFloat(MediaFormat.KEY_FRAME_RATE) else null }.getOrNull()
            ?.let { out["out.fps"] = it.toString() }
            ?: int(MediaFormat.KEY_FRAME_RATE)?.let { out["out.fps"] = it.toString() }
        int(MediaFormat.KEY_COLOR_FORMAT)?.let { out["out.pixfmt"] = it.toString() }
        return out
    }

    private const val MAX_LEVELS = 6
    private const val MAX_POINTS = 4
}

/**
 * Hands every video codec's output format to [onOutput] as [DecoderFacts.output] and otherwise is
 * the adapter it wraps. Media3 asks for the output format each time the codec reports a change, so
 * [onOutput] hears it at the first frame and again on a mid-stream change (a new colour standard
 * after a Dolby Vision layer switch, say). Audio codecs get the stock adapter untouched.
 */
@UnstableApi
internal class OutputFactsCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
    private val onOutput: (Map<String, String>) -> Unit,
) : MediaCodecAdapter.Factory {

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val adapter = delegate.createAdapter(configuration)
        val mime = configuration.format.sampleMimeType.orEmpty()
        if (!mime.startsWith("video/")) return adapter
        return object : MediaCodecAdapter by adapter {
            override fun getOutputFormat(): MediaFormat {
                val format = adapter.outputFormat
                runCatching { onOutput(DecoderFacts.output(format)) }
                return format
            }
        }
    }
}
