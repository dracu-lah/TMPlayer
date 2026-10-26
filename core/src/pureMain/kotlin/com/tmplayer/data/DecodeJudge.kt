package com.tmplayer.data

import kotlin.math.roundToInt

/**
 * What decides whether a picture plays here (HX2): one judgement, the same for every file and every
 * device, over what is known of the picture and what this device has. Pure, so the rules and the
 * table are tested alone; Android asks its codec list for the hardware answer and reads the CPU
 * for [Cpu].
 *
 * Why it exists: a 1080p AV1 10-bit copy played on the 1 GB Mi TV (4 Cortex-A53 cores at 1.4 GHz,
 * a 32-bit system, no AV1 silicon) at about a third of real time, the app's FFmpeg decoder (dav1d)
 * and the frame conversion after it using 175% of one core between them, the sound running dry 43
 * times in 69 seconds. No decoder library makes that real time: without the silicon it is the CPU's work, and
 * the CPU is the limit. What helps is knowing it before or as it plays, and steering to a copy
 * the device decodes in hardware.
 */
object DecodeJudge {

    /** What a picture asks of the device, as far as it is known. */
    data class Picture(
        /** The sample MIME type: `video/av01`, `video/hevc`, `video/avc`, `video/x-vnd.on2.vp9`... */
        val mime: String,
        /** 8 or 10 (12 counts as 10 and more); 8 when not known. */
        val bitDepth: Int = 8,
        val width: Int = 0,
        val height: Int = 0,
        /** Frames a second, 0 when not known: then [ASSUMED_FPS]. */
        val fps: Float = 0f,
    ) {
        val known: Boolean get() = width > 0 || height > 0

        /** The size, either side known, filled out to 16:9 where one is missing. */
        private val area: Long
            get() {
                val w = if (width > 0) width else height * 16 / 9
                val h = if (height > 0) height else width * 9 / 16
                return w.toLong() * h
            }

        /** Pixels a second at the frame rate, or [ASSUMED_FPS] when that is not known. */
        val pixelRate: Double get() = area * (if (fps > 1f) fps.toDouble() else ASSUMED_FPS)

        /** The short side snapped to the usual heights, for [DecodeMemory]: 480, 720, 1080, 1440, 2160. */
        val sizeBucket: Int
            get() {
                val w = if (width > 0) width else height * 16 / 9
                val h = if (height > 0) height else width * 9 / 16
                val short = minOf(w, h).coerceAtLeast(1)
                // A 1920 by 800 film is a 1080p release: judge by the long side when it says more.
                val byLong = SIZE_STEPS.firstOrNull { maxOf(w, h) <= it * 16 / 9 * 1.05 } ?: SIZE_STEPS.last()
                val byShort = SIZE_STEPS.firstOrNull { short <= it * 1.05 } ?: SIZE_STEPS.last()
                return maxOf(byLong, byShort)
            }

        /** 30 or 60, for [DecodeMemory]. */
        val fpsBucket: Int get() = if (fps > 31f) 60 else 30

        /** A short codec family name: `av1`, `hevc`, `avc`, `vp9`, or the MIME's own tail. */
        val family: String
            get() = when (mime) {
                MIME_AV1 -> "av1"
                MIME_HEVC -> "hevc"
                MIME_AVC -> "avc"
                MIME_VP9 -> "vp9"
                else -> mime.substringAfter('/')
            }

        /** What a viewer is told the picture is: "AV1 10-bit", "HEVC", "VP9". */
        val label: String
            get() {
                val name = when (mime) {
                    MIME_AV1 -> "AV1"
                    MIME_HEVC -> "HEVC"
                    MIME_AVC -> "H.264"
                    MIME_VP9 -> "VP9"
                    else -> mime.substringAfter('/').uppercase()
                }
                return if (bitDepth > 8) "$name $bitDepth-bit" else name
            }
    }

    /**
     * The CPU the app's software decoders get: its cores, the fastest core's clock and whether the
     * app runs 64-bit (a 32-bit build of the same decoder is markedly slower: fewer registers and
     * older NEON paths). [score] is in "64-bit GHz", the unit the [budget] table counts in.
     */
    data class Cpu(val cores: Int, val maxGhz: Float, val is64Bit: Boolean) {
        val score: Double
            get() = cores.coerceIn(1, MAX_CORES) * maxGhz.toDouble().coerceIn(MIN_GHZ, MAX_GHZ) *
                (if (is64Bit) 1.0 else BITS_32_FACTOR)
    }

    enum class Verdict {
        /** A hardware decoder takes it. */
        HARDWARE,

        /** Software, and the CPU has room for it. */
        SOFTWARE_FITS,

        /** Software, and the CPU cannot keep up: it will stutter. */
        SOFTWARE_TOO_SLOW,

        /** Not enough is known to say (no codec, or no size). */
        UNKNOWN,
    }

    /**
     * The judgement. [hardware] is the device's own answer for this picture (its codec list: a
     * hardware decoder whose profile, level, size and rate take it). [remembered] is what this
     * device saw last time with a picture of the same kind ([DecodeMemory]), which wins over the
     * table: true kept up, false did not.
     */
    fun judge(picture: Picture, hardware: Boolean, cpu: Cpu, remembered: Boolean? = null): Verdict {
        if (hardware) return Verdict.HARDWARE
        remembered?.let { return if (it) Verdict.SOFTWARE_FITS else Verdict.SOFTWARE_TOO_SLOW }
        if (!picture.known) return Verdict.UNKNOWN
        val perScore = budget(picture.mime, picture.bitDepth) ?: return Verdict.UNKNOWN
        val capacity = perScore * cpu.score * HEADROOM
        return if (picture.pixelRate <= capacity) Verdict.SOFTWARE_FITS else Verdict.SOFTWARE_TOO_SLOW
    }

    /**
     * Pixels a second the app's software decoders get through, decode and the conversion to the
     * screen together, per "64-bit GHz" of [Cpu.score]: the one table. The AV1 10-bit figure is the
     * 1 GB Mi TV's (4 Cortex-A53 cores at 1.4 GHz, a 32-bit system, a score of about 4), which got
     * through about 18 million pixels a second of a 1080p Web-DL (2026-10-26), so about 4.5 million
     * per unit, rounded down; the others are set from how much cheaper each codec is to decode, and
     * are to be checked with the decode bench forcing software (PromoDecodeBenchActivity, `--ez
     * software true`). Rounded down on purpose: a figure that is too high lets a file through that
     * stutters, which is the failure this exists to stop, while one too low is corrected by the
     * watchdog and [DecodeMemory] after one play. Null for a codec without a figure, not judged.
     */
    fun budget(mime: String, bitDepth: Int): Double? {
        val deep = bitDepth > 8
        return when (mime) {
            MIME_AVC -> if (deep) 6.0e6 else 9.0e6
            MIME_HEVC -> if (deep) 5.0e6 else 6.5e6
            MIME_VP9 -> if (deep) 5.0e6 else 6.5e6
            MIME_AV1 -> if (deep) 4.0e6 else 5.0e6
            else -> null
        }
    }

    /**
     * The bit depth a codecs string states, or null: `av01.0.08M.10` (the fourth field),
     * `vp09.02.40.10` (the fourth), `hvc1.2...` (profile 2 is Main 10), `avc1.6E...` (High 10).
     */
    fun bitDepthOf(codecs: String?): Int? {
        val parts = codecs?.trim()?.lowercase()?.split('.') ?: return null
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "av01", "vp09" -> parts.getOrNull(3)?.toIntOrNull()
            "hvc1", "hev1" -> when (parts.getOrNull(1)?.trimStart('a', 'b', 'c')) {
                "1", "3" -> 8
                "2" -> 10
                else -> null
            }
            "avc1", "avc3" -> parts.getOrNull(1)?.takeIf { it.length >= 2 }?.substring(0, 2)?.toIntOrNull(16)?.let {
                if (it == AVC_HIGH_10 || it == AVC_HIGH_422 || it == AVC_HIGH_444) 10 else 8
            }
            else -> null
        }
    }

    /**
     * A copy's picture as its file name says it, for judging a copy before it is opened: the
     * codec from the release words (`AV1`, `x265`, `HEVC`, `x264`, `VP9`), the bit depth from
     * `10bit` (or HDR and Dolby Vision, which are 10-bit), the size from [heightOf] or Telegram's
     * stated size. Null when the name does not say the codec: then nothing is assumed.
     */
    fun pictureOfName(name: String, width: Int, height: Int, heightOf: Int?): Picture? {
        val words = name.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }.toSet()
        val joined = name.lowercase()
        val mime = when {
            "av1" in words || "av01" in words -> MIME_AV1
            words.any { it in HEVC_WORDS } || "h.265" in joined -> MIME_HEVC
            "vp9" in words -> MIME_VP9
            words.any { it in AVC_WORDS } || "h.264" in joined -> MIME_AVC
            else -> return null
        }
        val deep = words.any { it in DEEP_WORDS } || "10 bit" in joined || "10-bit" in joined ||
            (mime != MIME_AVC && words.any { it in HDR_WORDS })
        val (w, h) = when {
            width > 0 && height > 0 -> width to height
            heightOf != null && heightOf > 0 -> (heightOf * 16 / 9) to heightOf
            else -> 0 to 0
        }
        return Picture(mime, if (deep) 10 else 8, w, h)
    }

    /**
     * Whether the app's FFmpeg decoder takes [picture] in its light mode (HX2c): for AV1 dav1d's
     * frame threads instead of low delay and no film grain, and for every codec a YV12 window
     * instead of the RGBA conversion. Yes for a picture this device decodes in software and either
     * the table says it cannot keep up with or that did not keep up here before; never for one a
     * hardware decoder takes, nor one the table says fits and nothing says otherwise, so a phone
     * that keeps up decodes as it always did. The table is asked without [remembered] on purpose:
     * a play in the light mode that kept up is remembered as kept up, and that must not turn the
     * light mode off for the next play of its kind.
     */
    fun wantsLightDecode(picture: Picture, hardware: Boolean, cpu: Cpu, remembered: Boolean?): Boolean {
        if (hardware) return false
        if (remembered == false) return true
        return judge(picture, hardware = false, cpu = cpu) == Verdict.SOFTWARE_TOO_SLOW
    }

    /** "1.0x" style for logs: how much of the CPU a picture asks against what it has. */
    fun load(picture: Picture, cpu: Cpu): Int? {
        val perScore = budget(picture.mime, picture.bitDepth) ?: return null
        return (picture.pixelRate / (perScore * cpu.score) * 100).roundToInt()
    }

    const val MIME_AV1 = "video/av01"
    const val MIME_HEVC = "video/hevc"
    const val MIME_AVC = "video/avc"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"

    /** A film's rate is 24, but a frame rate not known yet is taken as 30, to err towards caution. */
    const val ASSUMED_FPS = 30.0

    /** The share of the CPU the picture may take: the sound, the stream and the screen need the rest. */
    private const val HEADROOM = 0.75

    private const val MAX_CORES = 8
    private const val MIN_GHZ = 0.8
    private const val MAX_GHZ = 3.5
    private const val BITS_32_FACTOR = 0.7

    private const val AVC_HIGH_10 = 0x6E
    private const val AVC_HIGH_422 = 0x7A
    private const val AVC_HIGH_444 = 0xF4

    private val SIZE_STEPS = listOf(480, 720, 1080, 1440, 2160, 4320)
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val HEVC_WORDS = setOf("x265", "h265", "hevc")
    private val AVC_WORDS = setOf("x264", "h264", "avc")
    private val DEEP_WORDS = setOf("10bit", "10bits", "hi10", "hi10p", "main10", "12bit")
    private val HDR_WORDS = setOf("hdr", "hdr10", "dv", "dovi", "hdr10plus")
}

/**
 * What this device saw: whether a picture of one kind (codec, bit depth, size and rate bucket)
 * kept up when it played in software. Small and local, a line in the settings, gone with the
 * app's data; read before the table ([DecodeJudge.judge]'s `remembered`), so a file of a kind
 * that stuttered here is judged right from its first second next time, whatever the table says.
 */
object DecodeMemory {

    /** The kind a picture is remembered by: `av1:10:1080:30`. */
    fun keyOf(picture: DecodeJudge.Picture): String =
        "${picture.family}:${if (picture.bitDepth > 8) 10 else 8}:${picture.sizeBucket}:${picture.fpsBucket}"

    fun decode(text: String?): Map<String, Boolean> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        for (entry in text.split(';')) {
            val key = entry.substringBefore('=', "").takeIf { it.isNotBlank() } ?: continue
            when (entry.substringAfter('=', "")) {
                "1" -> out[key] = true
                "0" -> out[key] = false
            }
        }
        return out
    }

    fun encode(memory: Map<String, Boolean>): String =
        memory.entries.joinToString(";") { "${it.key}=${if (it.value) 1 else 0}" }

    /**
     * [memory] with [keptUp] noted for [picture]'s kind, newest last, the oldest dropped past [MAX].
     * A kind that did not keep up once is not cleared by one smooth play of a lighter file of the
     * same bucket; the newest result of the same kind simply replaces the old.
     */
    fun noted(memory: Map<String, Boolean>, picture: DecodeJudge.Picture, keptUp: Boolean): Map<String, Boolean> {
        val key = keyOf(picture)
        val out = LinkedHashMap(memory)
        out.remove(key)
        out[key] = keptUp
        while (out.size > MAX) out.remove(out.keys.first())
        return out
    }

    const val MAX = 48
}
