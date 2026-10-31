package com.tmplayer.data

/**
 * The colour a name wears where there is no picture: a chat with no photo, an account with none,
 * a file with no thumbnail. Eight pairs, the same eight the app's generated covers wear
 * (`CoverArt.Pairs` reads them from here), so a chat's circle and a video's cover are the same
 * small family of colours rather than two palettes that nearly match.
 *
 * The pair a name gets is a hash of the name itself, so it is the same on every launch, every
 * device and in every platform's build, and a chat keeps its colour when it is renamed only in
 * case or spacing. [NameInitial] writes the letter on it.
 *
 * The hash is FNV-1a over the name's UTF-16 code units, low byte then high, folded once before it
 * is cut down to the palette. Never `String.hashCode`: the web app's `webapp/src/ui/colour.ts` is
 * the same rule in TypeScript and cannot reproduce the JVM's, and a chat that is one colour in
 * the apps and another in the browser reads as a bug. Keep the two alike, pairs included.
 */
object NameColour {

    /**
     * Eight hand-tuned dark pairs, the lighter stop first, as 0xAARRGGBB: indigo, teal, plum,
     * rust, forest, sea blue, violet and olive. Every stop keeps white text at 6.9:1 or better,
     * so the letter on the colour is readable with no scrim at all. The dark stop sits 65% of the
     * way back toward the light one rather than near black (user, 2026-10-31: a video card's
     * fallback gradient read as plain black on an OLED screen, especially with "Pure black" on).
     */
    val Pairs: List<Pair<Int, Int>> = listOf(
        0xFF34397A.toInt() to 0xFF292D64.toInt(),
        0xFF145A5A.toInt() to 0xFF10494A.toInt(),
        0xFF6A2A5E.toInt() to 0xFF54214B.toInt(),
        0xFF7A3524.toInt() to 0xFF60291C.toInt(),
        0xFF245C34.toInt() to 0xFF1C492A.toInt(),
        0xFF1F4A7E.toInt() to 0xFF183A66.toInt(),
        0xFF4A3680.toInt() to 0xFF3A2A67.toInt(),
        0xFF6B5719.toInt() to 0xFF554514.toInt(),
    )

    /** The pair [name] wears, the lighter stop first. A blank or missing name has one too. */
    fun pairOf(name: String?): Pair<Int, Int> = Pairs[indexOf(name)]

    /** Which of the [Pairs] [name] wears: 0 until [Pairs].size, never negative. */
    fun indexOf(name: String?): Int {
        var h = BASIS
        for (c in key(name)) {
            val code = c.code
            h = (h xor (code and 0xFF)) * PRIME
            h = (h xor (code ushr 8)) * PRIME
        }
        // Folded before it is cut down: FNV spreads the low bits well, but the fold costs nothing
        // and keeps names that differ only in their last letter out of neighbouring pairs.
        h = h xor (h ushr 16)
        val size = Pairs.size
        return ((h % size) + size) % size
    }

    /**
     * The name as the hash reads it: the ends trimmed, runs of blanks as one space, lower case.
     * Lower case is the invariant mapping on both platforms (Kotlin's `lowercase` and
     * JavaScript's `toLowerCase` agree over everything a chat is called); it is only there so a
     * chat renamed from "films" to "FILMS" keeps its colour.
     *
     * A blank is one of four characters spelt out here, not `Char.isWhitespace` and not the web's
     * `\s`: those two disagree over a no-break space, which chat titles do carry, and the pair
     * would then differ between the apps and the browser for that one chat.
     */
    private fun key(name: String?): String {
        val text = name ?: return ""
        val out = StringBuilder(text.length)
        var blank = false
        for (c in text) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                blank = true
                continue
            }
            if (blank && out.isNotEmpty()) out.append(' ')
            blank = false
            out.append(c)
        }
        return out.toString().lowercase()
    }

    private val BASIS = 0x811C9DC5.toInt()
    private const val PRIME = 0x01000193
}
