package com.tmplayer.i18n

/** One language the app can be shown in. [name] is the endonym, the same in every UI language. */
data class AppLanguage(val tag: String, val name: String, val rtl: Boolean = false)

/**
 * The languages TMPlayer ships, and how a device's own language list picks one.
 *
 * Resolution, on every platform: the saved choice in Settings (empty means "follow the system"),
 * else the first system language that matches a shipped one by language and then region, else
 * English.
 */
object Languages {
    const val ENGLISH = "en"

    /** The debug-only pseudo-locale: accented, bracketed and stretched English. */
    const val PSEUDO = "en-XA"

    /**
     * English, Spanish (asked for in issue #2) and Arabic for now. The other languages from
     * docs/PLAN.md R1 come back once these have settled.
     */
    val all: List<AppLanguage> = listOf(
        AppLanguage("en", "English"),
        AppLanguage("es-419", "Español (Latinoamérica)"),
        AppLanguage("ar", "العربية", rtl = true),
    )

    val tags: List<String> = all.map { it.tag }

    fun find(tag: String): AppLanguage? = all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    /** True for a language written right to left, so a screen can mirror its layout. */
    fun isRtl(tag: String): Boolean = find(tag)?.rtl ?: false

    /**
     * The tag to show the app in. [saved] is the Settings value ("" for the system), [system] the
     * device's preferred languages as BCP 47 tags (`es-CL`, `ar-EG`, Android's `in-ID`), in
     * order. [pseudo] lets `en-XA` through, which only a debug build does.
     */
    fun resolve(saved: String?, system: List<String>, pseudo: Boolean = false): String {
        if (!saved.isNullOrBlank()) {
            if (pseudo && saved.equals(PSEUDO, ignoreCase = true)) return PSEUDO
            find(saved)?.let { return it.tag }
        }
        for (tag in system) {
            if (pseudo && normalise(tag).equals(PSEUDO, ignoreCase = true)) return PSEUDO
            match(tag)?.let { return it }
        }
        return ENGLISH
    }

    /** The shipped tag for one system tag, or null when none fits. */
    fun match(systemTag: String): String? {
        val parts = normalise(systemTag).split('-')
        val language = parts.first().lowercase()
        return when (language) {
            // Spanish everywhere gets the Latin American catalog: the only one there is, and far
            // closer than English.
            "es" -> "es-419"
            else -> all.firstOrNull { it.tag == language }?.tag
        }
    }

    /** Underscores to hyphens and Android's legacy codes to today's (`in` to `id`). */
    private fun normalise(tag: String): String {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val language = when (val l = parts.first().lowercase()) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> l
        }
        return (listOf(language) + parts.drop(1)).joinToString("-")
    }
}
