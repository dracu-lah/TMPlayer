package com.tmplayer.data

/**
 * The letter a chat's or an account's picture shows while there is none: the first letter or
 * digit of its name, upper case, the way Telegram draws a missing face. Punctuation, symbols and
 * emoji in front are skipped, so "(YEDHU)" reads "Y" and "★ Films" reads "F". A letter keeps the
 * marks written on it ("é" as two code points, a Malayalam or Devanagari vowel sign), so a
 * letter is never cut in half. A name with no letter or digit at all (only emoji, only symbols,
 * blank) has none, and the caller keeps its plain picture.
 *
 * The web app's `webapp/src/ui/initial.ts` is the same rule in TypeScript; keep the two alike.
 */
object NameInitial {

    fun of(name: String?): String? {
        if (name.isNullOrEmpty()) return null
        var i = 0
        while (i < name.length) {
            val c = name[i]
            // A letter outside the basic plane (some CJK, old scripts) is a surrogate pair; a
            // pair that is not a letter (an emoji) is skipped whole.
            if (c.isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate()) {
                i += 2
                continue
            }
            if (c.isLetterOrDigit()) {
                var end = i + 1
                while (end < name.length && name[end].isMark()) end++
                return name.substring(i, end).uppercase()
            }
            i++
        }
        return null
    }

    private fun Char.isMark(): Boolean = when (category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }
}
