package com.tmplayer.data

import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult

/**
 * Why a name could not be asked about: nothing to look up in it (TS). Pure, so every platform says
 * the same thing and a test can run it over every name of the corpus.
 */
enum class NameDiagnosis {
    /** The name holds no title once the release words are gone ("1080p.mkv", "VID_2024.mp4"). */
    NoTitle,

    /** The title that is left is a letter or two, too short to match safely. */
    TooShort,
    ;

    companion object {
        /** The most letters and digits of a title still called too short. */
        const val SHORT = 2

        /** What is wrong with the name behind [name], or null when it has a title worth asking about. */
        fun of(name: ParsedName): NameDiagnosis? {
            val title = name.title.trim()
            val letters = title.count { it.isLetter() }
            // Punctuation and symbols are no title.
            if (title.none { it.isLetterOrDigit() }) return NoTitle
            return when {
                // Nothing but a number and a stray letter ("1080p"): a release word that was taken for the title.
                title.isEmpty() || (letters <= 1 && title.any { it.isDigit() }) -> NoTitle
                letters <= SHORT && name.year == null -> TooShort
                else -> null
            }
        }

        /** [of] for a file name. */
        fun ofFile(fileName: String): NameDiagnosis? = of(MediaName.parse(fileName))
    }
}

/**
 * The one line a title page says when it has no poster (TS): why, in plain words, and the one
 * action that fixes it. Never for a video that has a match, and never while the answer is still on
 * its way or the device is offline (it cannot be told from "nothing found"), so it never guesses.
 */
object PosterWhy {

    enum class Reason {
        /** Lookups are off in Settings: nothing is asked. Fix: turn them on. */
        LookupsOff,

        /** The viewer turned the match down. Fix: choose the right poster. */
        MarkedWrong,

        /** The name holds nothing to ask about ([NameDiagnosis]). Fix: choose the right poster. */
        NotRecognised,

        /** Asked, and no provider had a title like it. Fix: choose the right poster. */
        NothingFound,

        /** A title was found, but nothing in the file backs it (no year, length or episode). Fix: choose the right poster. */
        Unsure,
    }

    data class Why(val reason: Reason, val diagnosis: NameDiagnosis? = null) {
        /** Whether the fix is turning lookups on, else choosing the right poster ("Fix match"). */
        val turnsOnLookups: Boolean get() = reason == Reason.LookupsOff
    }

    /**
     * @param lookupsOn Settings' switch for posters and details.
     * @param markedWrong the viewer's "Wrong match".
     * @param picked the viewer chose a title by hand: nothing to explain.
     * @param settled the answer for the video is in (not on its way).
     * @param matched a match is shown.
     * @param query what was asked ([MetaQuery.of]), null when the name asked nothing.
     * @param remembered the provider's answer kept in memory for [query], if any ([OnlineMetadata.peek]).
     */
    fun of(
        lookupsOn: Boolean,
        markedWrong: Boolean,
        picked: Boolean,
        settled: Boolean,
        matched: Boolean,
        name: ParsedName,
        query: MetaQuery?,
        remembered: MetaResult?,
    ): Why? {
        if (matched || picked) return null
        if (!lookupsOn) return Why(Reason.LookupsOff)
        if (markedWrong) return Why(Reason.MarkedWrong)
        if (!settled) return null
        if (query == null) return Why(Reason.NotRecognised, NameDiagnosis.of(name) ?: NameDiagnosis.NoTitle)
        return when (remembered) {
            is MetaResult.Found -> Why(Reason.Unsure)
            MetaResult.NoMatch -> Why(Reason.NothingFound)
            else -> null
        }
    }
}
