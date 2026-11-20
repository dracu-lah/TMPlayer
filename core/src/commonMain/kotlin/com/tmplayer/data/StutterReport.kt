package com.tmplayer.data

import com.tmplayer.player.FrameRates
import com.tmplayer.player.PlaybackWatch
import com.tmplayer.player.StutterCause
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
 * Sentry client as a crash. It is sent only for a stutter the viewer would have seen
 * ([isReal], HX37): a clock that ran true with no frame lost and no sound dropped is not one.
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
        /** What showed the stutter ([PlaybackWatch.symptomOf]): `slow`, `dropped`, `underruns` or `late`. */
        val symptom: String = "",
        /** Where [fps] came from ([FrameRates.source]): `container`, `derived` or `unknown`. */
        val fpsSource: String = "unknown",
        /**
         * Where the bytes came from, `remote` (streamed from Telegram), `cached` or `downloaded`, or
         * `unknown`: four of the first reports had a hardware decoder, no dropped frame and a slow
         * clock, which a slow line explains and a slow decoder does not.
         */
        val source: String = "unknown",
        /** Milliseconds of video buffered ahead of the playhead when the report was made, or -1 when not known. */
        val bufferedAheadMs: Int = -1,
        /**
         * What the playback trace says caused it ([StutterCause], L2): `decoder_bound`,
         * `network_bound`, `render_or_sync`, `thermal`, `memory` or `unknown`. Anything else is
         * reported as `unknown` ([verdictOf]).
         */
        val verdict: String = UNKNOWN_VERDICT,
        /**
         * The remedy codes the ladder had taken when the report was made (`ex,sw`, see
         * [com.tmplayer.player.Remedy.code]), or empty for the defaults: whether the picture that
         * stuttered was already on a changed path.
         */
        val remedies: String = "",
        /** Ladder hops taken in this video so far. */
        val hops: Int = 0,
        /**
         * Why this decoder (Sentry triage, 2026-11-18): the decoder path the player was built on,
         * as `DecoderPolicy.shortClass()` writes it (`async_default+pic_hw+snd_hw+tun_auto+out_0`),
         * or empty. Only `a` to `z`, digits, `_` and `+` survive ([policyOf]).
         */
        val policy: String = "",
        /** The viewer's "Prefer software video" setting: on, off, or null when not known. */
        val preferSoftware: Boolean? = null,
        /** What put the picture in software ([softwareByOf]), one of [SOFTWARE_BY]. */
        val softwareBy: String = UNKNOWN,
        /** Whether the app's process runs 32 or 64 bit, 0 when not known: a 64-bit TV can run the app as 32-bit. */
        val processBits: Int = 0,
        /** The hardware decoders the device lists for the picture's type, as [hwDecodersOf] writes them, or empty. */
        val hwDecoders: String = "",
        /**
         * How the device's hardware decoder fits the picture (HX71), one of [HW_FITS]: `rated` (as
         * the file asks), `level` (only with the level its size needs), `rate` (only past its rated
         * frame rate, which the player still prefers to FFmpeg), `none` (no hardware decoder takes
         * it), or `unknown`. A hardware stutter with `rate` is the chip's rating, not a mystery.
         */
        val hwFit: String = UNKNOWN,
        /**
         * Plays started on this device in the last 7 days with this ABI, decoder and engine
         * ([playsSame]) and in all ([playsAll]), from [PlayCounts]; -1 when not known. Sent only
         * as a bucket ([bucketOf]), never as the number. Filled by [StutterReporter].
         */
        val playsSame: Int = -1,
        val playsAll: Int = -1,
    ) {
        /** What the daily limit counts: codec, bit depth and size bucket. */
        val kind: String get() = "${clean(codec)}:${if (bitDepth > 8) 10 else 8}:$sizeBucket"
    }

    /** The only tag keys the event may carry. */
    val ALLOWED_TAGS = setOf(
        "codec", "profile", "bit_depth", "width", "height", "fps", "bitrate_kbps", "container",
        "decoder", "decoder_type", "prediction", "speed_percent", "dropped_frames", "frames", "underruns",
        "manufacturer", "model", "android", "cores", "abi", "app_version", "symptom", "fps_source",
        "source", "buffered_ahead_ms", "verdict", "remedies", "hops",
        "policy", "prefer_software", "software_by", "process_bits", "hw_decoders", "hw_fit", "plays_7d_same", "plays_7d_all",
    )

    /** A count as a coarse bucket, never the number itself: `0`, `1`, `2-4`, `5-9`, `10-19`, `20-49`, `50+`, or `unknown`. */
    fun bucketOf(count: Int): String = when {
        count < 0 -> UNKNOWN
        count == 0 -> "0"
        count == 1 -> "1"
        count < 5 -> "2-4"
        count < 10 -> "5-9"
        count < 20 -> "10-19"
        count < 50 -> "20-49"
        else -> "50+"
    }

    /** Every value [bucketOf] gives. */
    val BUCKETS = setOf("0", "1", "2-4", "5-9", "10-19", "20-49", "50+", "unknown")

    const val UNKNOWN = "unknown"

    /**
     * The words "what put the picture in software" may be: `none` (the device's decoder plays
     * it), `setting` (Prefer software video), `ladder_r2` (a remedy ladder hop, rung R2),
     * `plan_r2` (the class's start plan: what worked before, or the known device list), `switch`
     * (the player's own "try the other decoder" after the device's decoder failed, or the
     * viewer's press of it), `selector` (the device's decoder did not take the format, or the
     * one that would was left out, so Media3 gave it to FFmpeg) and `unknown`.
     */
    val SOFTWARE_BY = setOf("none", "setting", "ladder_r2", "plan_r2", "switch", "selector", UNKNOWN)

    /**
     * What put the picture in software, the latest cause first: a decoder switch after a
     * failure, a ladder hop, the start plan, the setting, else the track selector. A picture the
     * device's decoder plays is `none`. The prediction ([Facts.prediction]) is the device's
     * verdict alone and says HARDWARE through all of these, which is why they are told apart here.
     */
    fun softwareByOf(software: Boolean, switched: Boolean, ladderHop: Boolean, plan: Boolean, setting: Boolean): String = when {
        !software -> "none"
        switched -> "switch"
        ladderHop -> "ladder_r2"
        plan -> "plan_r2"
        setting -> "setting"
        else -> "selector"
    }

    /** The words [Facts.hwFit] may be: the names of the player's decoder fits, lower case, and `unknown`. */
    val HW_FITS = setOf("rated", "level", "rate", "none", UNKNOWN)

    private val POLICY = Regex("[a-z0-9_+]{1,64}")

    /** [text] when it is a policy class as `DecoderPolicy.shortClass()` writes it, else `unknown`. */
    fun policyOf(text: String): String = text.takeIf { POLICY.matches(it) } ?: UNKNOWN

    /**
     * The hardware decoders listed for a type, each `name:profile.level` with its highest
     * profile and that profile's highest level as the system numbers them (for HEVC, profile 2 is
     * Main 10), joined by `+`, as many whole entries as fit in one tag; `none` when there is none.
     * A decoder name is the chip's, the same on every device of the model, never the viewer's.
     */
    fun hwDecodersOf(decoders: List<Pair<String, Pair<Int, Int>?>>): String {
        val entries = decoders.mapNotNull { (name, top) ->
            val n = clean(name).filter { it != '+' && it != ' ' && it != ':' }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (top == null) n else "$n:${top.first.coerceIn(0, MAX_CODE)}.${top.second.coerceIn(0, MAX_CODE)}"
        }
        if (entries.isEmpty()) return "none"
        var out = entries.first().take(MAX_TEXT)
        for (entry in entries.drop(1)) {
            if (out.length + 1 + entry.length > MAX_TEXT) break
            out += "+$entry"
        }
        return out
    }

    /** The words a verdict may be reported as: the causes of [StutterCause], lower case. */
    val VERDICTS: Set<String> = StutterCause.entries.map { verdictName(it) }.toSet()

    const val UNKNOWN_VERDICT = "unknown"

    fun verdictName(cause: StutterCause): String = cause.name.lowercase()

    /** [text] when it is one of [VERDICTS], else `unknown`: nothing but a cause's name rides in this tag. */
    fun verdictOf(text: String): String = text.lowercase().takeIf { it in VERDICTS } ?: UNKNOWN_VERDICT

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
        "fps" to (FrameRates.plausible(facts.fps) * 10).toInt().div(10.0).toString(),
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
        "symptom" to clean(facts.symptom),
        "fps_source" to clean(facts.fpsSource),
        "source" to clean(facts.source),
        "buffered_ahead_ms" to facts.bufferedAheadMs.coerceIn(-1, 3_600_000).toString(),
        "verdict" to verdictOf(facts.verdict),
        "remedies" to clean(facts.remedies),
        "hops" to facts.hops.coerceIn(0, 10).toString(),
        "policy" to policyOf(facts.policy),
        "prefer_software" to when (facts.preferSoftware) { true -> "on"; false -> "off"; null -> UNKNOWN },
        "software_by" to (facts.softwareBy.takeIf { it in SOFTWARE_BY } ?: UNKNOWN),
        "process_bits" to (facts.processBits.takeIf { it == 32 || it == 64 }?.toString() ?: UNKNOWN),
        "hw_decoders" to clean(facts.hwDecoders).ifEmpty { UNKNOWN },
        "hw_fit" to (facts.hwFit.lowercase().takeIf { it in HW_FITS } ?: UNKNOWN),
        "plays_7d_same" to bucketOf(facts.playsSame),
        "plays_7d_all" to bucketOf(facts.playsAll),
    )

    /**
     * Whether the facts show a stutter the viewer would see (HX37): the same definition the watch
     * answers by ([PlaybackWatch.symptomOf]). A report without one (a clock that ran true, no
     * frame lost, no sound dropped out) is a false alarm and is not sent, whoever asked.
     */
    fun isReal(facts: Facts): Boolean = PlaybackWatch.symptomOf(
        speed = facts.speedPercent / 100.0,
        dropped = facts.dropped,
        frames = facts.frames,
        underruns = facts.underruns,
        late = facts.symptom == PlaybackWatch.LATE,
    ) != null

    /**
     * Whether the report is worth sending (Sentry triage, 2026-11-18): a real stutter ([isReal])
     * of a picture whose frame rate and codecs string are known. The rows with a frame rate of
     * 0.0 were the false alarms, and one without a profile cannot be told from another of its
     * codec, so neither is sent.
     */
    fun worthSending(facts: Facts): Boolean =
        isReal(facts) && FrameRates.plausible(facts.fps) > 0f && clean(facts.profile).isNotEmpty()

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

    /**
     * Adds the device's chipset to a scrubbed stutter event (S3), checked as a crash's is
     * ([CrashContext.tagsOf]): left off when it does not fit. Not one of [ALLOWED_TAGS], which
     * the facts fill: the platform adds it after the scrub.
     */
    fun withChipset(event: SentryEvent, chipset: String): SentryEvent {
        val checked = CrashContext.tagsOf(chipset, playback = null)[CrashContext.CHIPSET] ?: return event
        event.tags = (event.tags.orEmpty() + (CrashContext.CHIPSET to checked)).toMutableMap()
        return event
    }

    /** Letters, digits and `. - _ + :` and spaces, at most 64 characters: no path survives a cut like this. */
    fun clean(text: String): String = PlayCounts.clean(text)

    private const val MAX_TEXT = PlayCounts.MAX_TEXT

    /** A bound on a profile or level number: Android's are bit flags well under this. */
    private const val MAX_CODE = 1 shl 26
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
    /** The plays started, the stutter rate's denominator; null keeps none. */
    private val plays: PlayCounts? = null,
) {

    /**
     * A play started with [decoder] in [engine] on a device of [abi]: counted only while
     * reporting is on, so nothing is kept for a viewer who never agreed. Writes a small file, so
     * off the main thread.
     */
    fun played(abi: String, decoder: String, engine: String) {
        if (!enabled()) return
        plays?.note(PlayCounts.keyOf(abi, decoder, engine))
    }

    /** Reporting was turned off: the counts go too. */
    fun forgetPlays() {
        plays?.clear()
    }

    /** True when the event was handed on. [engine] is the one that played, for the plays counted beside it. */
    fun report(facts: StutterReport.Facts, engine: String = ENGINE): Boolean {
        if (!enabled()) return false
        // Before the daily limit: a false alarm must not use up the day's one report of its kind.
        if (!StutterReport.worthSending(facts)) return false
        if (!limits.admit(facts.kind)) return false
        val counted = plays?.week(PlayCounts.keyOf(facts.abi, facts.decoder, engine))
        send(StutterReport.build(if (counted == null) facts else facts.copy(playsSame = counted.first, playsAll = counted.second)))
        return true
    }

    companion object {
        /** The engine the stutter watch runs in. */
        const val ENGINE = "media3"
    }
}
