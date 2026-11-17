package com.tmplayer.ios

/**
 * The promo fixture's file names as a person would save their own videos, for the iOS app's
 * screenshots: upstream's demo library names its invented videos the way release groups do
 * ("The.Quiet.Orbit.2024.2160p.WEB-DL.DDP5.1.HEVC.mkv", "[Kumo] Skybound Academy - 01 (1080p).mkv"),
 * and nothing this app shows may read like piracy (the maintainer, 2026-11-16). The group tag, the
 * source and codec words and the dots go; the title, the year in brackets, the episode, the
 * resolution and the extension stay, so the app reads the same title, year and episode from it.
 */
object PromoNames {
    private val extension = Regex("""\.(mkv|mp4)$""", RegexOption.IGNORE_CASE)
    private val group = Regex("""^\[[^\]]*\]\s*""")
    private val bracketedQuality = Regex("""\((\d{3,4}p)\)""")
    private val releaseWords = Regex("""[.\s](WEB-DL|WEBRip|BluRay|x264|x265|HEVC|DDP5\.1)(?=[.\s]|$)""", RegexOption.IGNORE_CASE)
    private val year = Regex("""\b((?:19|20)\d\d)\b""")

    fun clean(raw: String): String {
        val ext = extension.find(raw)?.value.orEmpty()
        var stem = raw.removeSuffix(ext)
        stem = group.replace(stem, "")
        stem = bracketedQuality.replace(stem) { it.groupValues[1] }
        while (true) {
            val next = releaseWords.replace(stem, "")
            if (next == stem) break
            stem = next
        }
        stem = stem.replace('.', ' ').replace(Regex("""\s+"""), " ").trim()
        stem = year.replace(stem) { "(${it.groupValues[1]})" }
        return stem + ext
    }
}
