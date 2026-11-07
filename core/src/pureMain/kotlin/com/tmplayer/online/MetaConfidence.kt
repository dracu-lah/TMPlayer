package com.tmplayer.online

import com.tmplayer.data.ParsedName
import kotlin.math.abs
import kotlin.math.roundToInt

/** What made a provider's answer trustworthy enough to show, for the detail page's "Matched as". */
enum class MatchBasis {
    /** The title, and a year within one of the provider's. */
    TitleAndYear,

    /** The title, and a length within [MetaConfidence.runtimeAgrees] of the provider's runtime. */
    Runtime,

    /** A show's title, for a file that names its episode (S01E02, an air date). */
    ShowCode,

    /** The viewer picked this title for the video ("Choose the right poster"): nothing else has to back it. */
    Chosen,
}

/** A provider's answer for one video, with what it rests on. */
data class MatchedMeta(val info: MetaInfo, val basis: MatchBasis)

/**
 * Whether a provider's answer may stand for a video, on top of [MetaMatch]'s title check.
 *
 * A title alone is not enough: a file called "Night Train" with no year could be any of the films
 * of that name, and the wrong poster on a tile misleads more than none. So a film is shown with
 * the provider's picture and words only when the year agrees as well, or when the file's length
 * matches the provider's runtime. A show is trusted on its title when the file names an episode,
 * since a show's year in a file name is often its season's. Otherwise the tile keeps the video's
 * own picture and full cleaned title, and the detail page says the name came from the file.
 */
object MetaConfidence {

    /** Lengths within this share of the runtime agree, or within [RUNTIME_SLACK_SEC], whichever is more. */
    const val RUNTIME_SHARE = 0.05

    const val RUNTIME_SLACK_SEC = 5 * 60

    /**
     * What [info] rests on for the file read as [name], or null when nothing backs it. [durationSec]
     * is the file's length (0 when unknown) and [runtimeMin] the provider's (null when not asked
     * for, or not known).
     */
    fun basis(name: ParsedName, info: MetaInfo, durationSec: Int = 0, runtimeMin: Int? = null): MatchBasis? {
        val wanted = name.year
        val year = info.year
        if (wanted != null && year != null) {
            val agrees = abs(wanted - year) <= 1
            // A later season's year on a show from earlier: the year is the season's.
            val laterSeason = info.kind == MetaKind.Show && name.isSeries && wanted > year
            if (agrees || laterSeason) return MatchBasis.TitleAndYear
        }
        if (runtimeAgrees(durationSec, runtimeMin)) return MatchBasis.Runtime
        if (info.kind == MetaKind.Show && name.isSeries) return MatchBasis.ShowCode
        return null
    }

    /** Whether a file of [durationSec] seconds is a title of [runtimeMin] minutes. False when either is unknown. */
    fun runtimeAgrees(durationSec: Int, runtimeMin: Int?): Boolean {
        if (durationSec <= 0 || runtimeMin == null || runtimeMin <= 0) return false
        val runtime = runtimeMin * 60
        val slack = maxOf(RUNTIME_SLACK_SEC, (runtime * RUNTIME_SHARE).roundToInt())
        return abs(durationSec - runtime) <= slack
    }

    /** Whether a runtime could still settle it: the file's length is known and nothing else backs the match. */
    fun needsRuntime(name: ParsedName, info: MetaInfo, durationSec: Int): Boolean =
        durationSec > 0 && basis(name, info) == null
}
