package com.tmplayer.cast

import java.util.Locale

/**
 * The pure half of the desktop's transcode: an `ffmpeg` executable the user has, run as its own
 * process (so nothing of it, libx264's GPL included, is linked into TMPlayer). What it can encode
 * ([encoders], [ability]), the command line for one [TranscodeJob] ([args]), and its `-progress`
 * output read back ([FfmpegProgress]). No process is started here, so all of it is tested without
 * ffmpeg.
 */
object Ffmpeg {

    /** H.264 encoders in the order they are tried: the hardware ones first, libx264, then the rest. */
    val H264_ENCODERS = listOf("h264_videotoolbox", "h264_nvenc", "h264_qsv", "h264_amf", "libx264", "h264_mf", "libopenh264")

    private val HARDWARE = setOf("h264_videotoolbox", "h264_nvenc", "h264_qsv", "h264_amf")

    fun isHardware(encoder: String): Boolean = encoder in HARDWARE

    /** At most this many lines of `ffmpeg -encoders` are read: a real build lists about 250. */
    private const val MAX_LINES = 5_000

    /**
     * The encoder names in `ffmpeg -hide_banner -encoders`' output: after the dashed line, each line
     * is six flag letters (V, A or S first), the name, and a description.
     */
    fun encoders(output: String): Set<String> {
        val names = LinkedHashSet<String>()
        var listing = false
        for (raw in output.lineSequence().take(MAX_LINES)) {
            val line = raw.trim()
            if (!listing) {
                if (line.startsWith("---")) listing = true
                continue
            }
            val parts = line.split(Regex("\\s+"), limit = 3)
            if (parts.size < 2) continue
            val flags = parts[0]
            if (flags.length != 6 || flags[0] !in "VAS") continue
            val name = parts[1]
            if (name.length in 1..64 && name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) names += name
        }
        return names
    }

    /** The H.264 encoders [available] has, in the order to try them. */
    fun h264Order(available: Set<String>): List<String> = H264_ENCODERS.filter { it in available }

    /**
     * What a computer with these encoders can make, or null with no H.264 or AAC encoder (then the
     * Transcode route is not offered at all). Anything ffmpeg reads is decoded, so no decoder list.
     */
    fun ability(available: Set<String>): TranscodeAbility? {
        val h264 = h264Order(available)
        if (h264.isEmpty() || "aac" !in available) return null
        return TranscodeAbility(
            hardware = h264.any(::isHardware),
            videoDecoders = null,
            audioDecoders = null,
            ac3 = "ac3" in available,
            containers = setOf(TranscodeContainer.Fmp4, TranscodeContainer.MpegTs),
        )
    }

    /**
     * The command for [job] reading [inputUrl] (the loopback stream of the video) with [encoder],
     * the converted stream on standard output and the progress on standard error. [toneMap] false
     * leaves out the HDR filter chain even where the target asks for it, for a build without zimg.
     */
    fun args(executable: String, inputUrl: String, job: TranscodeJob, encoder: String, toneMap: Boolean = job.route.target.toneMap): List<String> {
        val t = job.route.target
        val a = ArrayList<String>()
        a += listOf(executable, "-hide_banner", "-nostdin", "-loglevel", "error", "-nostats", "-progress", "pipe:2")
        // Before the input: a fast seek to the keyframe before, decoding on from there to the time.
        if (job.startMs > 0) a += listOf("-ss", seconds(job.startMs))
        a += listOf("-i", inputUrl)
        a += listOf("-map", "0:v:0?")
        val audioIndex = job.route.audioTrack?.let { n -> job.probe.audio.indexOfFirst { it.number == n } }
        if (t.audio == TranscodeAudio.None || job.route.audioTrack == null) {
            a += "-an"
        } else {
            a += listOf("-map", "0:a:${(audioIndex ?: 0).coerceAtLeast(0)}?")
        }
        a += listOf("-sn", "-dn")
        if (t.copyVideo) {
            a += listOf("-c:v", "copy")
        } else {
            val fps = if (isHardware(encoder)) t.maxFps else t.maxFps.coerceAtMost(30)
            val filters = ArrayList<String>()
            if (toneMap) filters += TONE_MAP
            if (t.width > 0 && t.height > 0) filters += "scale=${t.width}:${t.height}:force_original_aspect_ratio=decrease:force_divisible_by=2"
            filters += "format=yuv420p"
            a += listOf("-vf", filters.joinToString(","))
            // A cap, not a rate: a 24 fps source stays 24 (ffmpeg 4.4 and later).
            a += listOf("-fpsmax", fps.toString())
            a += listOf("-c:v", encoder)
            a += encoderOptions(encoder)
            val kbps = t.videoKbps.coerceAtLeast(300)
            a += listOf("-b:v", "${kbps}k", "-maxrate", "${kbps * 3 / 2}k", "-bufsize", "${kbps * 2}k")
            // A keyframe every two seconds: a receiver that joins or a fragment that starts needs one.
            a += listOf("-g", (fps * 2).coerceAtLeast(2).toString())
        }
        when (t.audio) {
            TranscodeAudio.Copy -> a += listOf("-c:a", "copy")
            TranscodeAudio.Aac -> a += listOf("-c:a", "aac", "-b:a", "${t.audioKbps}k", "-ac", "2")
            TranscodeAudio.Ac3 -> a += listOf("-c:a", "ac3", "-b:a", "${t.audioKbps}k", "-ac", "6")
            TranscodeAudio.None -> Unit
        }
        a += listOf("-max_muxing_queue_size", "1024", "-avoid_negative_ts", "make_zero")
        when (t.container) {
            TranscodeContainer.Fmp4 -> a += listOf("-f", "mp4", "-movflags", "frag_keyframe+empty_moov+default_base_moof")
            TranscodeContainer.MpegTs -> a += listOf("-f", "mpegts")
        }
        a += "pipe:1"
        return a
    }

    /** Settings that make each encoder quick enough to keep up and the stream playable everywhere. */
    private fun encoderOptions(encoder: String): List<String> = when (encoder) {
        "libx264" -> listOf("-preset", "veryfast", "-profile:v", "high", "-level", "4.1")
        "h264_nvenc" -> listOf("-preset", "p4", "-profile:v", "high")
        "h264_qsv" -> listOf("-preset", "veryfast", "-profile:v", "high")
        "h264_amf" -> listOf("-quality", "speed", "-profile:v", "high")
        "h264_videotoolbox" -> listOf("-profile:v", "high", "-realtime", "1")
        else -> emptyList()
    }

    /** HDR (PQ or HLG) to SDR BT.709, through zimg: only where the build has it, else left out. */
    private const val TONE_MAP = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv"

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000)
}

/**
 * ffmpeg's `-progress` output read line by line: `key=value` lines, each block ended by
 * `progress=continue` or `progress=end`. Lines that are not that (an error message, garbage) are
 * kept as the last error instead.
 */
class FfmpegProgress {

    /** One block: how much of the output is made and how fast, or the end. */
    data class Update(val outMs: Long, val speed: Float?, val end: Boolean)

    private var outUs: Long? = null
    private var speed: Float? = null
    private val errors = ArrayDeque<String>()

    /** The last few lines that were not progress: ffmpeg's own words on what went wrong. */
    val lastError: String? get() = errors.lastOrNull()
    val errorLines: List<String> get() = errors.toList()

    /** Reads [line]; an [Update] when it ends a block. */
    fun feed(line: String): Update? {
        val text = line.trim().take(MAX_LINE)
        if (text.isEmpty()) return null
        val eq = text.indexOf('=')
        val key = if (eq > 0) text.substring(0, eq) else null
        val value = if (eq > 0) text.substring(eq + 1).trim() else ""
        when (key) {
            // out_time_ms is microseconds too, an old ffmpeg naming mistake kept for compatibility.
            "out_time_us", "out_time_ms" -> value.toLongOrNull()?.takeIf { it >= 0 }?.let { outUs = it }
            "speed" -> speed = value.removeSuffix("x").trim().toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
            "progress" -> {
                val u = Update((outUs ?: 0L) / 1000, speed, end = value == "end")
                return u
            }
            null -> remember(text)
            else -> if (key !in KEYS && !key.startsWith("stream_")) remember(text)
        }
        return null
    }

    private fun remember(text: String) {
        errors.addLast(text)
        while (errors.size > MAX_ERRORS) errors.removeFirst()
    }

    private companion object {
        const val MAX_LINE = 2_000
        const val MAX_ERRORS = 8
        val KEYS = setOf(
            "frame", "fps", "bitrate", "total_size", "out_time", "dup_frames", "drop_frames",
        )
    }
}
