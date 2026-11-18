package com.tmplayer.data

/**
 * The episode forms only an anime's files take, which [MediaName] falls back on when its own
 * readings find no episode (T11). A fansub names a long run by its absolute number, so these are
 * what a 500 episode show's files look like:
 *
 *  - "[Group] Show - 123 [1080p]", "Show - 123v2" (a re-release) and "Show - 12-13" or "- 12~13"
 *    (two episodes in one file) are read by [MediaName] itself: its dashed form takes the number and
 *    the range after it, as for any other show,
 *  - "[Group] Show - 5": one digit, which only a fansub's file is read as an episode (elsewhere
 *    "Part - 2" is a title),
 *  - "Show 123v2": the version marker proves the number is an episode,
 *  - "Show - OVA 02", "Show OVA2", "Show - SP01", "Show - Special 3", "Show - Recap 1": a special,
 *    filed as season 0 like every other special.
 *
 * Creditless openings and endings ("NCOP1", "NCED") are extras, not episodes ([MediaName] reads
 * them as one). Everything here is a pattern over one short name, and a name that matches nothing
 * is left to the caller.
 */
internal object AnimeName {

    /** An episode found: its place, and where in the text its marker starts (the title ends there). */
    data class Found(val season: Int?, val number: Int, val at: Int, val end: Int)

    private val DASHED_SPECIAL = Regex(
        """\s-\s(?:ova|oad|ona|sp|special|recap)s?\s?0*(\d{1,3})?(?:v\d)?(?=\s|$|\[|\()""",
        RegexOption.IGNORE_CASE,
    )

    private val BARE_SPECIAL = Regex(
        """\s(?:ova|oad|ona|sp)s?\s?0*(\d{1,3})(?:v\d)?(?=\s|$|\[|\()""",
        RegexOption.IGNORE_CASE,
    )

    // Not a size: "HDRip - 1.4GB" reads as "- 1 4GB" once the dot is a space.
    private val ONE_DIGIT = Regex("""\s-\s(\d)(?:v\d)?(?=\s|$|\[|\()(?!\s?\d)""")

    private val QUALITY = Regex("""\b(?:hdrip|webrip|web-dl|bluray|brrip|dvdrip|hdtv|hq|hdcam|camrip|hevc|x26[45]|h26[45])\b""", RegexOption.IGNORE_CASE)

    private val VERSIONED = Regex("""\s0*(\d{1,4})v\d(?=\s|$|\[|\()""", RegexOption.IGNORE_CASE)

    /**
     * The anime episode in [text] (a name with its decoration and extension already removed), or
     * null. [fansub] says the name began with a group's bracket, which is what lets a single digit
     * after a dash count as an episode.
     */
    fun find(text: String, fansub: Boolean): Found? {
        // A bare "- SP" is a scene group's tag at the end of a release ("...AV1.-.SP"), a special only
        // behind a fansub's own bracket or with a number ("- SP01").
        DASHED_SPECIAL.find(text)?.takeUnless { m -> m.groupValues[1].isEmpty() && !fansub && m.value.contains("sp", ignoreCase = true) && !m.value.contains("special", ignoreCase = true) }?.let { return special(it) }
        BARE_SPECIAL.find(text)?.let { return special(it) }
        if (fansub) {
            // Not after a release's quality tags: "HQ HDRip - 1" is a size cut short, not episode one.
            ONE_DIGIT.find(text)?.takeUnless { QUALITY.containsMatchIn(text.substring(0, it.range.first)) }?.let { m ->
                val n = m.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return@let
                return Found(null, n, m.range.first, m.range.last + 1)
            }
        }
        VERSIONED.find(text)?.takeIf { it.range.first > 0 }?.let { m ->
            val n = m.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            return Found(null, n, m.range.first, m.range.last + 1)
        }
        return null
    }

    /** "OVA" with no number is the first. A special's number is read as written, never past three digits. */
    private fun special(match: MatchResult): Found =
        Found(0, match.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: 1, match.range.first, match.range.last + 1)
}
