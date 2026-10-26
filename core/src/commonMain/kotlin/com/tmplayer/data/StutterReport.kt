package com.tmplayer.data

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The opt-in "could not keep up" report (HX2b): one small event when PlaybackWatch decides a
 * video could not play smoothly on this device, so the budget table in [DecodeJudge] can be tuned
 * from real devices. Sent only with crash reporting on, through the same scrubber and the same
 * Sentry client as a crash.
 *
 * What it holds is [Facts] and nothing else: the picture's format, the decoder, what the judge
 * predicted and the watch measured, and the device. No title, file name, chat, message or user id
 * is ever put in: [Facts] has no field that could carry one, and every text field is cut to a
 * short run of letters, digits and a few signs ([clean]).
 */
object StutterReport {

    /** Marks the event as this kind, so the gate treats it as one; nothing else uses this logger. */
    const val LOGGER = "tmplayer.stutter"

    /** The one fixed sentence the event carries; nothing of the video is ever in it. */
    const val MESSAGE = "Video could not keep up"

    /** Everything the report may hold. */
    data class Facts(
        val codec: String,
        /** The codecs string (`hvc1.2.4.L120.B0`), or empty. */
        val profile: String,
        val bitDepth: Int,
        val width: Int,
        val height: Int,
        val fps: Float,
        val bitrateKbps: Int,
        val container: String,
        val decoder: String,
        val hardware: Boolean,
        /** [DecodeJudge.Verdict] name. */
        val prediction: String,
        val speedPercent: Int,
        val dropped: Int,
        val frames: Int,
        val underruns: Int,
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val cores: Int,
        val abi: String,
        val appVersion: String,
        /** [DecodeJudge.Picture.sizeBucket]. */
        val sizeBucket: Int,
    ) {
        /** What the daily limit counts: codec, bit depth and size bucket. */
        val kind: String get() = "${clean(codec)}:${if (bitDepth > 8) 10 else 8}:$sizeBucket"
    }

    /** The only tag keys the event may carry. */
    val ALLOWED_TAGS = setOf(
        "codec", "profile", "bit_depth", "width", "height", "fps", "bitrate_kbps", "container",
        "decoder", "decoder_type", "prediction", "speed_percent", "dropped_frames", "frames", "underruns",
        "manufacturer", "model", "android", "cores", "abi", "app_version",
    )

    /** The event, with the facts as tags. The SDK adds the device, OS and app contexts when it captures. */
    fun build(facts: Facts): SentryEvent = SentryEvent().apply {
        logger = LOGGER
        level = SentryLevel.WARNING
        message = Message().apply { formatted = MESSAGE }
        // One issue per kind of video on one device model, not one per play.
        setFingerprints(listOf("video-could-not-keep-up", facts.kind, clean(facts.model)))
        tags = tagsOf(facts).toMutableMap()
    }

    fun tagsOf(facts: Facts): Map<String, String> = linkedMapOf(
        "codec" to clean(facts.codec),
        "profile" to clean(facts.profile),
        "bit_depth" to facts.bitDepth.toString(),
        "width" to facts.width.toString(),
        "height" to facts.height.toString(),
        "fps" to ((facts.fps * 10).toInt() / 10.0).toString(),
        "bitrate_kbps" to facts.bitrateKbps.toString(),
        "container" to clean(facts.container),
        "decoder" to clean(facts.decoder),
        "decoder_type" to if (facts.hardware) "hardware" else "software",
        "prediction" to clean(facts.prediction),
        "speed_percent" to facts.speedPercent.toString(),
        "dropped_frames" to facts.dropped.toString(),
        "frames" to facts.frames.toString(),
        "underruns" to facts.underruns.toString(),
        "manufacturer" to clean(facts.manufacturer),
        "model" to clean(facts.model),
        "android" to clean(facts.androidVersion),
        "cores" to facts.cores.toString(),
        "abi" to clean(facts.abi),
        "app_version" to clean(facts.appVersion),
    )

    fun isStutter(event: SentryEvent): Boolean = event.logger == LOGGER

    /**
     * The gate's treatment of a stutter event: the same scrubber as a crash (which empties tags,
     * extras, breadcrumbs, user and every context but device, OS and app), after which only the
     * allowed tags come back, each cut by [clean]. The message is fixed again, so nothing the SDK
     * or a processor added to it survives.
     */
    fun scrub(event: SentryEvent): SentryEvent {
        val wanted = event.tags.orEmpty().filterKeys { it in ALLOWED_TAGS }
        val fingerprint = event.getFingerprints()
        CrashReportScrubber.scrub(event)
        event.tags = wanted.mapValues { (_, v) -> clean(v) }.toMutableMap()
        event.setFingerprints(fingerprint?.map(::clean))
        event.message = Message().apply { formatted = MESSAGE }
        event.threads = null
        event.exceptions = null
        return event
    }

    /** Letters, digits and `. - _ + :` and spaces, at most 64 characters: no path survives a cut like this. */
    fun clean(text: String): String = text.filter { it.isLetterOrDigit() || it in ".-_+: " }.trim().take(MAX_TEXT)

    private const val MAX_TEXT = 64
}

/**
 * At most one report per kind of video (codec, bit depth, size bucket) per device per day, kept
 * in a small private file: the UTC day and the kinds already sent. Nothing else.
 */
class StutterLimits(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Whether a report of [kind] may go now, counting it if so. */
    @Synchronized
    fun admit(kind: String): Boolean {
        val today = clock() / DAY_MILLIS
        val seen = read(today)
        if (kind in seen) return false
        write(today, (seen + kind).takeLast(MAX_KINDS))
        return true
    }

    private fun read(today: Long): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        val json = JSONObject(file.readText())
        if (json.getLong("day") != today) return emptyList()
        val kinds = json.getJSONArray("kinds")
        (0 until kinds.length()).map { kinds.getString(it) }
    }.getOrDefault(emptyList())

    private fun write(day: Long, kinds: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".part")
            partial.writeText(JSONObject().put("day", day).put("kinds", JSONArray(kinds)).toString())
            partial.renameTo(file)
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_KINDS = 50
    }
}

/**
 * The decision to send, apart from Sentry so it is tested alone: nothing is sent, counted or kept
 * when [enabled] says crash reporting is off, and a kind goes at most once a day.
 */
class StutterReporter(
    private val enabled: () -> Boolean,
    private val limits: StutterLimits,
    private val send: (SentryEvent) -> Unit = { Sentry.captureEvent(it) },
) {

    /** True when the event was handed on. */
    fun report(facts: StutterReport.Facts): Boolean {
        if (!enabled()) return false
        if (!limits.admit(facts.kind)) return false
        send(StutterReport.build(facts))
        return true
    }
}
