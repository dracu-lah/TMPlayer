package com.tmplayer.desktop.player

import com.tmplayer.data.DecodeJudge
import com.tmplayer.data.StutterReport

/**
 * The desktop's Report stutter (WP-25, PO-09): what mpv's decoder saw at the press, as the same
 * [StutterReport.Facts] the phone sends, so a Windows stutter reads like an Android one. Built from
 * properties only ([MpvFormats.Props]), never throws, and holds no title, path or name: every text
 * is a codec, a decoder or a system name, cut by [StutterReport.clean] when the report is built.
 *
 * The line it logs goes into Send logs; the report itself goes only with crash reporting on, and
 * only when the numbers show a stutter ([StutterReport.worthSending]), one a day for each kind.
 */
internal object DesktopStutter {

    /** What the facts are read from: mpv's properties, a fake in tests. */
    fun facts(p: MpvFormats.Props, appVersion: String, system: System = System.current()): StutterReport.Facts? {
        val codec = p.string("video-codec-name") ?: p.string("video-format") ?: return null
        val width = (p.int("video-params/w") ?: 0).coerceIn(0, MAX_SIDE)
        val height = (p.int("video-params/h") ?: 0).coerceIn(0, MAX_SIDE)
        val fps = (p.double("container-fps") ?: p.double("estimated-vf-fps") ?: 0.0).takeIf { it.isFinite() && it > 0.0 }?.toFloat() ?: 0f
        val depth = p.string("video-params/pixelformat")?.let { BIT_DEPTH.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 8
        val hwdec = p.string("hwdec-current")
        val hardware = !hwdec.isNullOrBlank() && hwdec != "no"
        val dropped = listOfNotNull(p.int("frame-drop-count"), p.int("decoder-frame-drop-count")).sumOf { it.coerceAtLeast(0) }
        val frames = (p.int("estimated-frame-number") ?: 0).coerceAtLeast(0)

        val bitrate = ((p.double("video-bitrate") ?: 0.0) / 1000.0).takeIf { it.isFinite() }?.toInt()?.coerceIn(0, MAX_KBPS) ?: 0
        val picture = DecodeJudge.Picture("video/$codec", bitDepth = depth, width = width, height = height, fps = fps)
        return StutterReport.Facts(
            codec = codec,
            // mpv names a profile ("Main 10"), not a codecs string; the codec name and depth stand in.
            profile = p.string("video-params/pixelformat") ?: codec,
            bitDepth = depth,
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = bitrate,
            container = p.string("file-format").orEmpty(),
            decoder = if (hardware) hwdec.orEmpty() else "mpv-software",
            hardware = hardware,
            prediction = StutterReport.UNKNOWN,
            // mpv plays at the speed asked and drops frames to keep it: the drops are the symptom.
            speedPercent = 100,
            dropped = dropped,
            frames = maxOf(frames, dropped),
            underruns = 0,
            manufacturer = system.name,
            model = system.arch,
            androidVersion = system.version,
            cores = system.cores,
            abi = system.arch,
            appVersion = appVersion,
            sizeBucket = picture.sizeBucket,
            symptom = "",
            fpsSource = if (p.double("container-fps") != null) "container" else "estimated",
            source = "desktop",
            preferSoftware = !hardware,
            softwareBy = if (hardware) StutterReport.UNKNOWN else "setting",
        )

    }

    /** The one line Send logs carries: numbers and codec names only. */
    fun line(facts: StutterReport.Facts): String =
        "Report stutter: ${StutterReport.clean(facts.codec)} ${facts.bitDepth}-bit ${facts.width}x${facts.height} " +
            "${facts.fps} fps, ${if (facts.hardware) "hardware ${StutterReport.clean(facts.decoder)}" else "software"}, " +
            "${facts.dropped} of ${facts.frames} frames dropped, ${facts.bitrateKbps} kbps, ${StutterReport.clean(facts.container)}"

    /** The system the desktop runs on, as Java reports it. */
    data class System(val name: String, val version: String, val arch: String, val cores: Int) {
        companion object {
            fun current() = System(
                name = java.lang.System.getProperty("os.name").orEmpty(),
                version = java.lang.System.getProperty("os.version").orEmpty(),
                arch = java.lang.System.getProperty("os.arch").orEmpty(),
                cores = Runtime.getRuntime().availableProcessors(),
            )
        }
    }

    private val BIT_DEPTH = Regex("p(\\d{2})(?:le|be)?$")
    private const val MAX_SIDE = 16_384
    private const val MAX_KBPS = 1_000_000
}
