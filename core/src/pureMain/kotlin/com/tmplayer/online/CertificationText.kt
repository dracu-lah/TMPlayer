package com.tmplayer.online

/**
 * An age rating as the facts line writes it (Raju 5.1): a bare "A" meant nothing to a viewer who
 * does not know India's board, and the line read like a typo. A one letter rating becomes a word
 * where the word is known ("A" is for adults, "U" for everyone), and is left out where it is not;
 * a longer one ("PG-13", "TV-MA", "UA 16+") says something by itself and stays as it is.
 */
object CertificationText {

    /**
     * [raw] as the facts line should show it, or null for nothing. [adults] and [everyone] are the
     * words for "A" and "U" in the UI language.
     */
    fun readable(raw: String?, adults: String, everyone: String): String? {
        val rating = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (rating.length > 1) return rating
        return when (rating.uppercase()) {
            "A" -> adults
            "U" -> everyone
            // Any other lone letter is a board's private code: a letter on its own says nothing.
            else -> null
        }
    }
}
