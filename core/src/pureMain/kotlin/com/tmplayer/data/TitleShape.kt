package com.tmplayer.data

/**
 * The shape of a file name: what is left when the letters and digits are taken out and only how
 * the name is built remains. "@CC_Night_Train_2024.mp4" is "@A_Aa_Aa_9.a9", and so is every other
 * name a channel stamps the same way. It is how name reports are grouped (`scripts/names/ingest.mjs`
 * does the same by hand): a hundred reports of one shape are one parser fix, not a hundred.
 *
 * - A letter with a case is "a" (lower) or "A" (upper); a letter with none (Malayalam, Tamil,
 *   Chinese) is "x", so a title in its own script is told from a Latin one.
 * - A digit, of any script, is "9". A vowel sign or an accent is part of its letter and shows nothing.
 * - A character outside the basic plane (an emoji, a mathematical letter) is "*".
 * - Everything else is a separator and is kept as it is, so "_", ".", "-", "(", "[", "@" and a
 *   space each show; any kind of white space is a plain space.
 * - A run of one kind is one character: "Night" is "Aa", "2024" is "9", "__" is "_". Two kinds that
 *   follow each other stay two: "720p" is "9a", "S01E02" is "A9A9".
 *
 * Never throws and never grows the name: the output is at most [MAX_LENGTH] characters.
 */
object TitleShape {

    const val MAX_LENGTH = 160

    fun of(name: String): String {
        val out = StringBuilder(minOf(name.length, MAX_LENGTH))
        var last = '\u0000'
        var index = 0
        while (index < name.length && out.length < MAX_LENGTH) {
            val char = name[index]
            // A vowel sign or an accent belongs to the letter before it: a word is one run.
            if (char.category == CharCategory.NON_SPACING_MARK || char.category == CharCategory.COMBINING_SPACING_MARK ||
                char.category == CharCategory.ENCLOSING_MARK
            ) {
                index++
                continue
            }
            val symbol = when {
                char.isHighSurrogate() && index + 1 < name.length && name[index + 1].isLowSurrogate() -> {
                    index++
                    '*'
                }
                char.isSurrogate() -> '*'
                char.isUpperCase() || char.isTitleCase() -> 'A'
                char.isLowerCase() -> 'a'
                char.isLetter() -> 'x'
                char.isDigit() -> '9'
                char.isWhitespace() -> ' '
                else -> char
            }
            index++
            // A run of letters, digits or one separator is one character.
            if (symbol == last) continue
            out.append(symbol)
            last = symbol
        }
        return out.toString()
    }
}
