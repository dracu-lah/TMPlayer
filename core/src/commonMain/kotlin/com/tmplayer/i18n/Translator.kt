package com.tmplayer.i18n

import com.tmplayer.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The UI language for the whole process. Catalogs are `i18n/<tag>.json` on the classpath (the
 * resources of `:core`, so the same files on Android and the desktop), nested objects whose keys
 * join with dots: `{"browse": {"videos_count": "..."}}` is `browse.videos_count`.
 *
 * Compose reads [active] through `LocalStrings` in `:ui`, so a change recomposes in place. Code
 * outside Compose (notifications, the tray, the track picker) reads [L], which is always the
 * current language.
 */
object Translator {
    private const val TAG = "Translator"

    /** Lets the `en-XA` pseudo-locale resolve. Set by debug builds only. */
    @Volatile
    var pseudoEnabled: Boolean = false

    private val englishPatterns: Map<String, Icu.Pattern> by lazy {
        val source = resource(Languages.ENGLISH) ?: error("i18n/en.json is missing from the classpath")
        parse(source).mapValues { Icu.parse(it.value) }
    }

    private val state: MutableStateFlow<Messages> by lazy { MutableStateFlow(english()) }

    /** The current language's text, as a flow for Compose. */
    val active: StateFlow<Messages> get() = state.asStateFlow()

    /** The current language's text. */
    val messages: Messages get() = state.value

    /**
     * Picks the language from the saved setting ("" or null follows the system) and the device's
     * preferred tags, loads it, and returns the tag chosen. Cheap when nothing changes.
     */
    fun select(saved: String?, system: List<String>): String {
        val tag = Languages.resolve(saved, system, pseudoEnabled)
        use(tag)
        return tag
    }

    /** Switches to [tag], one of [Languages.tags] or [Languages.PSEUDO]. */
    fun use(tag: String) {
        if (state.value.tag == tag) return
        state.value = load(tag)
        Logger.i(TAG, "UI language $tag")
    }

    /** English, the source of truth every other catalog falls back to key by key. */
    fun english(): Messages = Messages(Languages.ENGLISH, englishPatterns, englishPatterns)

    /** The messages for [tag], read from the classpath. A missing catalog is all English. */
    fun load(tag: String): Messages = when (tag) {
        Languages.ENGLISH -> english()
        Languages.PSEUDO -> pseudo(englishPatterns)
        else -> build(tag, resource(tag), englishPatterns)
    }

    /** The pseudo-locale over [english]: same keys and arguments, accented text. */
    internal fun pseudo(english: Map<String, Icu.Pattern>): Messages =
        Messages(Languages.PSEUDO, english.mapValues { it.value.mapText(Pseudo::accent) }, english, pseudo = true)

    /**
     * [tag]'s messages from its catalog JSON over [english]. A translation that does not parse, or
     * that uses an argument the English does not pass, is dropped so that key reads in English.
     */
    internal fun build(tag: String, json: String?, english: Map<String, Icu.Pattern>): Messages {
        val own = HashMap<String, Icu.Pattern>()
        if (json != null) {
            val entries = runCatching { parse(json) }
                .onFailure { Logger.w(TAG, "i18n/$tag.json does not parse", it) }
                .getOrDefault(emptyMap())
            for ((key, text) in entries) {
                val reference = english[key] ?: continue
                val pattern = runCatching { Icu.parse(text) }.getOrNull()
                if (pattern == null || !reference.args.keys.containsAll(pattern.args.keys)) {
                    Logger.w(TAG, "$tag: $key does not match the English, using English")
                    continue
                }
                own[key] = pattern
            }
        }
        return Messages(tag, own, english)
    }

    /** A catalog's JSON as dotted keys to message text. */
    fun parse(json: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, node: JSONObject) {
            for (name in node.keys()) {
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                when (val value = node.get(name)) {
                    is JSONObject -> walk(key, value)
                    is String -> out[key] = value
                    else -> throw IllegalArgumentException("$key is not text")
                }
            }
        }
        walk("", JSONObject(json))
        return out
    }

    /** `i18n/<tag>.json` from the classpath, or null when that language has no catalog yet. */
    fun resource(tag: String): String? =
        Translator::class.java.getResourceAsStream("/i18n/$tag.json")?.use { it.readBytes().decodeToString() }
}
