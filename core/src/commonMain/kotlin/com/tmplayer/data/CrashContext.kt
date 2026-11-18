package com.tmplayer.data

import com.tmplayer.player.PlaybackWatch
import io.sentry.SentryEvent

/**
 * The few tags a crash report may carry beside the stack trace (S2): what explains a player bug,
 * and nothing else. The playback engine, the video decoder, the device's chipset, the video's
 * codec and size class, and whether the video was stuttering. Never a title, a chat name or a
 * file name: there is no field that could hold one, and every value must match a narrow pattern
 * or a fixed list, so anything unexpected is dropped rather than cleaned and sent ([tagsOf]).
 *
 * [CrashReportScrubber] empties the tags first, the SDK's own among them; these are put back after
 * it, so a tag a new SDK version adds never survives (the test asserts the exact list).
 *
 * The chipset is the device's, so it goes on every Android report, a crash from an earlier run
 * included. What was playing belongs to this run only: it goes on a crash or a handled error of
 * this run, never on a crash record or an ANR read at the next start.
 */
object CrashContext {

    /** Every tag key a crash report may carry, in order. A new one is added here and on the privacy page. */
    val ALLOWED_TAGS: List<String> = listOf(ENGINE, CHIPSET, DECODER, CODEC, SIZE_CLASS, STUTTER, HEAP, HEAP_USED)

    /** The engines a report may name. ExoPlayer is Media3's; the others are for PR1 and the desktop. */
    val ENGINES: Set<String> = setOf("media3", "libvlc", "mpv", "external")

    /** The size classes, by the picture's shorter side ([sizeClass]). */
    val SIZE_CLASSES: Set<String> = setOf("sd", "hd", "fhd", "qhd", "uhd")

    /** The stutter verdicts: what [PlaybackWatch.symptomOf] answers. */
    val STUTTERS: Set<String> = setOf(PlaybackWatch.SLOW, PlaybackWatch.DROPPED, PlaybackWatch.UNDERRUNS, PlaybackWatch.LATE)

    /** The app's heap ceiling in MB, rounded down to a class, so a repeat out of memory says how small the device's heap was. */
    val HEAPS: Set<String> = setOf("64", "128", "256", "512", "1024+")

    /** How much of that ceiling was in use when the report was made. */
    val HEAP_USES: Set<String> = setOf("0-49", "50-74", "75-89", "90+")

    /**
     * The two memory tags from the heap's [maxBytes] ceiling and the [usedBytes] in use. Classes
     * only, never the exact figures, and nothing about what is on screen or what was open (Sentry
     * 161258214, an out of memory on a BRAVIA with no way to tell how small its heap was).
     */
    fun heapTags(maxBytes: Long, usedBytes: Long): Map<String, String> {
        if (maxBytes <= 0 || usedBytes < 0) return emptyMap()
        val mb = maxBytes / (1024 * 1024)
        val percent = (usedBytes * 100 / maxBytes).coerceIn(0, 100)
        return mapOf(
            HEAP to when {
                mb < 128 -> "64"
                mb < 256 -> "128"
                mb < 512 -> "256"
                mb < 1024 -> "512"
                else -> "1024+"
            },
            HEAP_USED to when {
                percent < 50 -> "0-49"
                percent < 75 -> "50-74"
                percent < 90 -> "75-89"
                else -> "90+"
            },
        )
    }

    /** What was playing, as the player last said. */
    data class Playback(
        val engine: String,
        /** The decoder's name as the platform gives it (`c2.mtk.hevc.decoder`), or empty. */
        val decoder: String,
        /** The codec family (`hevc`, `avc`, `av1`), or empty. */
        val codec: String,
        val width: Int,
        val height: Int,
        /** The stutter verdict, or empty while it played smoothly. */
        val stutter: String = "",
        /** When it was said. */
        val atMillis: Long,
        /** Said by the player while the video plays: it lasts until [stopped]. Otherwise it fades after [FRESH_MS]. */
        val ongoing: Boolean = false,
    )

    @Volatile
    private var playback: Playback? = null

    /**
     * The player starts or changes a video. For the player to call: it holds the playback session,
     * so it is the one that knows when it ends ([stopped]).
     */
    fun playing(engine: String, decoder: String, codec: String, width: Int, height: Int, now: Long = System.currentTimeMillis()) {
        playback = Playback(engine, decoder, codec, width, height, atMillis = now, ongoing = true)
    }

    /** The player has stopped: a crash after this is not about that video. */
    fun stopped() {
        playback = null
    }

    /**
     * The watch said a video could not keep up ([StutterReport]): its facts are kept for a crash
     * that follows soon after, with the verdict. Kept as the player's ongoing session when there
     * is one, so its [stopped] still clears it.
     */
    fun stuttered(facts: StutterReport.Facts, engine: String, now: Long = System.currentTimeMillis()) {
        val ongoing = playback?.ongoing == true
        playback = Playback(
            engine = engine,
            decoder = facts.decoder,
            codec = facts.codec,
            width = facts.width,
            height = facts.height,
            stutter = facts.symptom,
            atMillis = now,
            ongoing = ongoing,
        )
    }

    /** What was playing, if it still says anything about a crash now. */
    fun playbackNow(now: Long = System.currentTimeMillis()): Playback? =
        playback?.takeIf { it.ongoing || now - it.atMillis in 0..FRESH_MS }

    /** How long a stutter's facts are kept without the player saying it still plays. */
    const val FRESH_MS: Long = 10L * 60 * 1000

    /**
     * The tags, each checked against its own pattern or list and left out when it does not fit.
     * [chipset] is the device's (empty on a computer); [playback] null when nothing was playing.
     */
    fun tagsOf(chipset: String, playback: Playback?, memory: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        chipset.trim().takeIf { CHIPSET_TEXT.matches(it) && safe(it) }?.let { put(CHIPSET, it) }
        memory.filter { (key, value) -> (key == HEAP && value in HEAPS) || (key == HEAP_USED && value in HEAP_USES) }.let(::putAll)
        playback ?: return@buildMap
        playback.engine.takeIf { it in ENGINES }?.let { put(ENGINE, it) }
        playback.decoder.trim().takeIf { DECODER_TEXT.matches(it) && safe(it) }?.let { put(DECODER, it) }
        playback.codec.trim().lowercase().takeIf { CODEC_TEXT.matches(it) && safe(it) }?.let { put(CODEC, it) }
        sizeClass(playback.width, playback.height)?.let { put(SIZE_CLASS, it) }
        playback.stutter.takeIf { it in STUTTERS }?.let { put(STUTTER, it) }
    }

    /**
     * Puts [tags] on an event the scrubber has been through: only the allowed keys, and only
     * values that pass [tagsOf]'s checks again, so nothing else can be slipped in by a caller.
     */
    fun tag(event: SentryEvent, tags: Map<String, String>): SentryEvent {
        val kept = tags.filter { (key, value) -> key in ALLOWED_TAGS && checks(key, value) }
        event.tags = if (kept.isEmpty()) null else ALLOWED_TAGS.mapNotNull { k -> kept[k]?.let { k to it } }.toMap(LinkedHashMap())
        return event
    }

    /** The picture's size class by its shorter side, so a portrait video counts as its landscape twin; null when not known. */
    fun sizeClass(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val short = minOf(width, height)
        return when {
            short < 720 -> "sd"
            short < 1080 -> "hd"
            short < 1440 -> "fhd"
            short < 2160 -> "qhd"
            else -> "uhd"
        }
    }

    private fun checks(key: String, value: String): Boolean = when (key) {
        CHIPSET -> CHIPSET_TEXT.matches(value) && safe(value)
        ENGINE -> value in ENGINES
        DECODER -> DECODER_TEXT.matches(value) && safe(value)
        CODEC -> CODEC_TEXT.matches(value) && safe(value)
        SIZE_CLASS -> value in SIZE_CLASSES
        STUTTER -> value in STUTTERS
        HEAP -> value in HEAPS
        HEAP_USED -> value in HEAP_USES
        else -> false
    }

    /** Nothing the scrubber would cut: no path, no video file name. */
    private fun safe(value: String): Boolean = CrashReportScrubber.redact(value) == value

    const val ENGINE = "engine"
    const val CHIPSET = "chipset"
    const val DECODER = "decoder"
    const val CODEC = "codec"
    const val SIZE_CLASS = "size_class"
    const val STUTTER = "stutter"
    const val HEAP = "heap_mb"
    const val HEAP_USED = "heap_used"

    /** `Qualcomm SM8550`, `Mediatek MT6765`, `mt8695`: letters, digits, spaces and `. _ -`. */
    private val CHIPSET_TEXT = Regex("""[A-Za-z0-9][A-Za-z0-9 ._-]{0,47}""")

    /** `c2.mtk.hevc.decoder`, `OMX.amlogic.avc.decoder.awesome`: no spaces. */
    private val DECODER_TEXT = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}""")

    /** `hevc`, `avc`, `av1`, `vp9`, `mpeg2`. */
    private val CODEC_TEXT = Regex("""[a-z0-9][a-z0-9.-]{0,15}""")
}
