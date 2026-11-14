package com.tmplayer.data

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/** The engine that plays a video in the app on Android: Media3 ExoPlayer, or libVLC (PR2). */
enum class PlayerEngine(val key: String) {
    ExoPlayer("exoplayer"),
    Vlc("vlc"),
    ;

    companion object {
        /** The engine stored or sent as [key] (any case), or null for anything else. */
        fun ofKey(key: String?): PlayerEngine? {
            val clean = key?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == clean }
        }
    }
}

/**
 * Which decoder a known device's entry asks ExoPlayer for: the device's own ([Device], hardware
 * first) or the app's FFmpeg decoder ([Software]). Pure, so it stands apart from the settings'
 * `VideoDecoder`, which the player maps it onto while that setting is Automatic.
 */
enum class DecoderHint(val key: String) {
    Device("device"),
    Software("software"),
    ;

    companion object {
        fun ofKey(key: String?): DecoderHint? {
            val clean = key?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == clean }
        }
    }
}

/**
 * What the player knows of the device it runs on, read from `android.os.Build` on Android:
 * [hardware] is `Build.HARDWARE` (`sun50iw6p1` on an Allwinner H6), [board] `Build.BOARD`,
 * [socModel] `Build.SOC_MODEL` (Android 12 and later, empty before), [sdk] `Build.VERSION.SDK_INT`.
 */
data class DeviceFacts(
    val manufacturer: String = "",
    val model: String = "",
    val device: String = "",
    val hardware: String = "",
    val board: String = "",
    val socModel: String = "",
    val sdk: Int = 0,
)

/**
 * One device class that plays better with something other than the defaults: the chipsets and
 * models it covers, the Android versions ([sdkMin] to [sdkMax], both inclusive, null for any),
 * the video formats ([codecs], MIME types or their prefixes, empty for all) and what to prefer.
 *
 * Within a group the values are alternatives; across groups every group given must match, so an
 * entry with a chipset and a model covers only that model on that chipset. An entry with neither
 * chipsets nor models would cover every device and is never made ([KnownDevices.parse] drops it).
 */
data class KnownDevice(
    val id: String,
    val chipsets: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val sdkMin: Int? = null,
    val sdkMax: Int? = null,
    val codecs: List<String> = emptyList(),
    val engine: PlayerEngine? = null,
    val decoder: DecoderHint? = null,
    val note: String = "",
) {

    /** Whether this entry covers [device] playing a video of [mime] (null when not known yet). */
    fun matches(device: DeviceFacts, mime: String?): Boolean {
        if (chipsets.isEmpty() && models.isEmpty()) return false
        if (chipsets.isNotEmpty() && chipsets.none { chipsetMatches(it, device) }) return false
        if (models.isNotEmpty() && models.none { modelMatches(it, device) }) return false
        if (sdkMin != null && device.sdk < sdkMin) return false
        if (sdkMax != null && device.sdk > sdkMax) return false
        if (codecs.isNotEmpty()) {
            // A format not known yet is not taken to be the one listed: the defaults stay.
            val video = mime?.trim()?.lowercase().orEmpty()
            if (video.isEmpty() || codecs.none { video.startsWith(it) }) return false
        }
        return true
    }

    private fun chipsetMatches(token: String, device: DeviceFacts): Boolean =
        listOf(device.hardware, device.board, device.socModel).any { value ->
            val v = value.trim().lowercase()
            v.isNotEmpty() && (v == token || (token.length >= PREFIX_MIN && v.startsWith(token)))
        }

    private fun modelMatches(token: String, device: DeviceFacts): Boolean =
        listOf(device.model, device.device).any { it.trim().lowercase() == token }

    private companion object {
        /** A chipset token this long or longer also covers the names it begins (`sun50iw6` for `sun50iw6p1`). */
        const val PREFIX_MIN = 5
    }
}

/**
 * The known device list (PR1): devices whose own decoder or ExoPlayer is known to play badly, and
 * what to prefer on them while the engine is Automatic. The app ships [builtIn]; the update feed
 * may send a fresher list, which is outside data: [parse] skips every bad entry and keeps the
 * rest, and [effective] falls back to the shipped list when the feed has nothing usable (offline,
 * stale or garbage).
 *
 * The feed's shape, either the object or the bare array:
 * ```
 * {"devices": [{"id": "allwinner-h6", "chipsets": ["sun50iw6"], "models": [], "sdkMin": 24,
 *   "sdkMax": null, "codecs": ["video/hevc"], "engine": "vlc", "decoder": "software", "note": ""}]}
 * ```
 */
object KnownDevices {

    /** At most this many entries are read from a feed; the rest is ignored. */
    const val MAX_ENTRIES = 500

    /** At most this many values in one matcher list. */
    const val MAX_VALUES = 50

    private const val MAX_TOKEN = 64
    private const val MAX_NOTE = 300
    private const val MIN_CHIPSET = 2
    private const val MAX_SDK = 1000

    /** The list the app ships, used whenever the feed has nothing better. */
    val builtIn: List<KnownDevice> = listOf(
        // Tanix TX6 and other Allwinner H6 boxes (Build.HARDWARE sun50iw6p1): user reports
        // (player reliability plan, 2026-11-15) of a frozen, blocky or stuttering picture in the
        // built-in player with Automatic, while VLC and MX Player play the same videos fine.
        KnownDevice(
            id = "allwinner-h6",
            chipsets = listOf("sun50iw6"),
            engine = PlayerEngine.Vlc,
            note = "Allwinner H6 (Tanix TX6): frozen or blocky picture on ExoPlayer, fine in VLC (user reports)",
        ),
    )

    /**
     * The entries of [text], a feed's list, never throwing: text that is not JSON, or JSON of
     * another shape, gives an empty list, and an entry that is not an object, has no usable
     * preference or nothing to match on is skipped.
     */
    fun parse(text: String?): List<KnownDevice> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("devices")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<KnownDevice>()
        for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val entry = array.optJSONObject(index) ?: continue
            entryOf(entry)?.let { out += it }
        }
        return out
    }

    /**
     * The list to use: [feed]'s entries over the shipped ones with the same id, the shipped ones
     * the feed does not name, and the feed's new ones; just [builtIn] for an empty feed.
     */
    fun effective(feed: List<KnownDevice>, shipped: List<KnownDevice> = builtIn): List<KnownDevice> {
        if (feed.isEmpty()) return shipped
        val fed = feed.mapNotNull { it.id.ifEmpty { null } }.toSet()
        return shipped.filter { it.id !in fed } + feed
    }

    /** The first entry covering [device] for a video of [mime], or null. */
    fun find(list: List<KnownDevice>, device: DeviceFacts, mime: String?): KnownDevice? =
        list.firstOrNull { it.matches(device, mime) }

    private fun entryOf(entry: JSONObject): KnownDevice? {
        val engine = PlayerEngine.ofKey(entry.opt("engine") as? String)
        val decoder = DecoderHint.ofKey(entry.opt("decoder") as? String)
        if (engine == null && decoder == null) return null
        val chipsets = tokens(entry.opt("chipsets")).filter { it.length >= MIN_CHIPSET }
        val models = tokens(entry.opt("models"))
        if (chipsets.isEmpty() && models.isEmpty()) return null
        val sdkMin = sdkOf(entry.opt("sdkMin"))
        val sdkMax = sdkOf(entry.opt("sdkMax"))
        if (sdkMin != null && sdkMax != null && sdkMin > sdkMax) return null
        return KnownDevice(
            id = (entry.opt("id") as? String)?.trim()?.take(MAX_TOKEN).orEmpty(),
            chipsets = chipsets,
            models = models,
            sdkMin = sdkMin,
            sdkMax = sdkMax,
            codecs = tokens(entry.opt("codecs")),
            engine = engine,
            decoder = decoder,
            note = (entry.opt("note") as? String)?.take(MAX_NOTE).orEmpty(),
        )
    }

    /** The strings of a JSON array, trimmed, lower case, without blanks, duplicates or overlong ones. */
    private fun tokens(value: Any?): List<String> {
        val array = value as? JSONArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            if (out.size >= MAX_VALUES) break
            val text = (array.opt(index) as? String)?.trim()?.lowercase() ?: continue
            if (text.isEmpty() || text.length > MAX_TOKEN) continue
            out += text
        }
        return out.toList()
    }

    /** An Android API level, or null for one missing, not a whole number, or out of reason. */
    private fun sdkOf(value: Any?): Int? {
        val number = value as? Number ?: return null
        val d = number.toDouble()
        if (d.isNaN() || d.isInfinite() || d != kotlin.math.floor(d)) return null
        if (d < 1 || d > MAX_SDK) return null
        return d.toInt()
    }
}
