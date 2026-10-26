package com.tmplayer.player

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.tmplayer.data.DecodeJudge
import com.tmplayer.data.DecodeMemory
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.VideoCopies
import com.tmplayer.platform.Background
import java.io.File
import kotlinx.coroutines.launch

/**
 * [DecodeJudge] on this device (HX2): its codec list for the hardware answer (Media3's
 * [HardwareDecoders], which asks each hardware decoder for the profile, the level, the size and
 * the frame rate, as `VideoCapabilities.areSizeAndRateSupported` has it), its CPU for the
 * software budget, and what it remembered of earlier plays ([DecodeMemory]).
 *
 * Set up once by the app ([start]), after which the copy chooser ([VideoCopies.tooSlowHere]),
 * the player and the watchdog all ask the same question here.
 */
@UnstableApi
object DeviceDecode {

    private lateinit var appContext: Context

    @Volatile
    private var memory: Map<String, Boolean> = emptyMap()

    /** The CPU, read once: cores, the fastest core's top clock, whether the app runs 64-bit. */
    val cpu: DecodeJudge.Cpu by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        val khz = (0 until cores).mapNotNull { index ->
            runCatching { File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }.maxOrNull()
        // The file is in kHz; an emulator's may say anything, so a figure outside what a phone's
        // core runs at counts as not known.
        val ghz = khz?.let { it / 1_000_000f }?.takeIf { it in 0.3f..5f } ?: UNKNOWN_GHZ
        DecodeJudge.Cpu(cores, ghz, android.os.Process.is64Bit())
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        Background.scope.launch {
            memory = runCatching { SettingsStore(appContext).decodeMemoryNow() }.getOrDefault(emptyMap())
        }
        VideoCopies.tooSlowHere = { item -> verdictOf(item) == DecodeJudge.Verdict.SOFTWARE_TOO_SLOW }
        VideoCopies.playsSmoothlyHere = { item ->
            when (verdictOf(item)) {
                DecodeJudge.Verdict.HARDWARE, DecodeJudge.Verdict.SOFTWARE_FITS -> true
                DecodeJudge.Verdict.SOFTWARE_TOO_SLOW -> false
                DecodeJudge.Verdict.UNKNOWN -> null
            }
        }
        VideoCopies.deviceWord = if (com.tmplayer.data.FormFactor.isTv(appContext)) "tv" else "phone"
        installLightVideoPolicy()
        // The codec list read once off the main thread, so the first copies list or Play press
        // does not pay for it on a stick.
        Background.scope.launch {
            for (mime in listOf(DecodeJudge.MIME_AVC, DecodeJudge.MIME_HEVC, DecodeJudge.MIME_AV1, DecodeJudge.MIME_VP9)) {
                runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false) }
            }
        }
    }

    /** What a Media3 format asks of the device. */
    fun pictureOf(format: Format): DecodeJudge.Picture? {
        val mime = format.sampleMimeType ?: return null
        val depth = format.colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: DecodeJudge.bitDepthOf(format.codecs) ?: 8
        return DecodeJudge.Picture(
            mime = mime,
            bitDepth = depth,
            width = format.width.coerceAtLeast(0),
            height = format.height.coerceAtLeast(0),
            fps = format.frameRate.takeIf { it > 0f } ?: 0f,
        )
    }

    /** The judgement of a picture the player has in hand. */
    fun verdictOf(format: Format): DecodeJudge.Verdict {
        val picture = pictureOf(format) ?: return DecodeJudge.Verdict.UNKNOWN
        return DecodeJudge.judge(picture, HardwareDecoders.has(appContext, format), cpu, remembered(picture))
    }

    /**
     * The judgement of a copy before it is opened, from its name and Telegram's stated size
     * ([DecodeJudge.pictureOfName]); [DecodeJudge.Verdict.UNKNOWN] when the name does not say the
     * codec. A copy the player already found too slow while the app runs counts as too slow.
     */
    fun verdictOf(item: MediaItem): DecodeJudge.Verdict {
        if (!::appContext.isInitialized) return DecodeJudge.Verdict.UNKNOWN
        if (com.tmplayer.data.SoftwareDecode.knownHeavy(item.chatId, item.messageId)) return DecodeJudge.Verdict.SOFTWARE_TOO_SLOW
        val picture = DecodeJudge.pictureOfName(
            item.fileName.ifBlank { item.title },
            item.width,
            item.height,
            VideoCopies.heightOf(item),
        ) ?: return DecodeJudge.Verdict.UNKNOWN
        return DecodeJudge.judge(picture, runCatching { HardwareDecoders.has(appContext, formatOf(picture)) }.getOrDefault(false), cpu, remembered(picture))
    }

    /**
     * Whether the FFmpeg video decoder takes [format] in its light mode
     * ([DecodeJudge.wantsLightDecode]): asked by the decoder each time it is created.
     */
    fun wantsLightDecode(format: Format): Boolean {
        if (!::appContext.isInitialized) return false
        val picture = pictureOf(format) ?: return false
        val hardware = runCatching { HardwareDecoders.has(appContext, format) }.getOrDefault(false)
        return DecodeJudge.wantsLightDecode(picture, hardware, cpu, remembered(picture))
    }

    /**
     * Hands [wantsLightDecode] to NextLib's FFmpeg video decoder (HX2c). By reflection, because
     * `setLightVideoPolicy` comes from TMPlayer's patch (scripts/build-media3ext.sh): a debug build
     * without that AAR takes nextlib from Maven Central, which lacks it, and then nothing changes.
     */
    private fun installLightVideoPolicy() {
        val policy = com.google.common.base.Predicate<Format> { format -> format != null && wantsLightDecode(format) }
        runCatching {
            io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary::class.java
                .getMethod("setLightVideoPolicy", com.google.common.base.Predicate::class.java)
                .invoke(null, policy)
        }.onFailure { android.util.Log.i(TAG, "FFmpeg light video mode not in this build: $it") }
    }

    /** What this device saw before with a picture of [picture]'s kind, or null. */
    fun remembered(picture: DecodeJudge.Picture): Boolean? = memory[DecodeMemory.keyOf(picture)]

    /** Notes whether [picture] kept up in software here, for every later judgement of its kind. */
    fun note(picture: DecodeJudge.Picture, keptUp: Boolean) {
        memory = DecodeMemory.noted(memory, picture, keptUp)
        if (!::appContext.isInitialized) return
        Background.scope.launch { runCatching { SettingsStore(appContext).noteDecode(picture, keptUp) } }
    }

    /**
     * A format a decoder can be asked about for a picture known only by name: the codecs string
     * says the bit depth (a 10-bit picture on a chip that decodes only 8-bit is not hardware).
     */
    private fun formatOf(picture: DecodeJudge.Picture): Format {
        val deep = picture.bitDepth > 8
        val codecs = when (picture.mime) {
            DecodeJudge.MIME_AV1 -> "av01.0.${if (picture.height > 1088) "13" else "08"}M.${if (deep) "10" else "08"}"
            DecodeJudge.MIME_HEVC -> if (deep) FormatRoute.hevcMain10Codecs(picture.height) else null
            DecodeJudge.MIME_VP9 -> if (deep) "vp09.02.40.10" else null
            DecodeJudge.MIME_AVC -> if (deep) "avc1.6E0028" else null
            else -> null
        }
        return Format.Builder()
            .setSampleMimeType(picture.mime)
            .setCodecs(codecs)
            .apply { if (picture.width > 0 && picture.height > 0) setWidth(picture.width).setHeight(picture.height) }
            .build()
    }

    /** A clock no CPU file gave: an old small core's, so the guess errs towards caution. */
    private const val UNKNOWN_GHZ = 1.2f

    private const val TAG = "DeviceDecode"
}
