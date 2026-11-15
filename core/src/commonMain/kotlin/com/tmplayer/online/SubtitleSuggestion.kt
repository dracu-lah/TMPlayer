package com.tmplayer.online

import com.tmplayer.player.TrackLanguages

/**
 * Subtitles found for the viewer (U5): when a video has none in the viewer's subtitle language and
 * the switch under Settings, Online subtitles is on, the player fetches the best match by the
 * file's hash, then by its name, and says where it came from. The rules, with no network and no
 * screen, so every one is a test:
 *
 * - [decide] whether to look at all, and whether the viewer is asked first (a metered line);
 * - [candidates] the hits worth trying, best first, never one the viewer turned down ("Not this
 *   one, try the next"), never a machine translation, a hash match before a name match, and a
 *   subtitle for hearing-impaired viewers only after the ordinary ones;
 * - [sourceOf] what the viewer is told about where it came from.
 */
object SubtitleSuggestion {

    /** Why nothing is fetched. */
    enum class Skip {
        /** The switch is off (the default). */
        Off,

        /** This build carries no OpenSubtitles key, or the service refused it. */
        Unavailable,

        /** The kids lock is on: nothing is fetched from the internet by itself. */
        KidsLock,

        /** The viewer set subtitles to Off, or never on by themselves: nothing is offered unasked. */
        WantsNone,

        /** No language to look for. */
        NoLanguage,

        /** The video already has a subtitle in that language. */
        HasLanguage,

        /** Looked at already for this video: once, whatever the answer. */
        Handled,
    }

    sealed interface Decision {
        data class Skipped(val why: Skip) : Decision

        /** On a metered line the viewer is asked before anything is sent. */
        data object AskMetered : Decision

        data object Fetch : Decision
    }

    data class Gate(
        val enabled: Boolean,
        val available: Boolean,
        val kidsLock: Boolean,
        val viewerWantsNone: Boolean,
        /** The viewer's subtitle language, or null when none can be told. */
        val language: String?,
        /** The languages of the subtitle tracks the video has now, files loaded over it included. */
        val trackLanguages: List<String?>,
        val handled: Boolean,
        val metered: Boolean,
        /** The viewer said yes to "find subtitles over mobile data?" for this video. */
        val meteredAccepted: Boolean = false,
    )

    fun decide(gate: Gate): Decision {
        fun skip(why: Skip) = Decision.Skipped(why)
        return when {
            !gate.enabled -> skip(Skip.Off)
            !gate.available -> skip(Skip.Unavailable)
            gate.kidsLock -> skip(Skip.KidsLock)
            gate.viewerWantsNone -> skip(Skip.WantsNone)
            gate.language.isNullOrBlank() || TrackLanguages.normalise(gate.language) == null -> skip(Skip.NoLanguage)
            gate.trackLanguages.any { TrackLanguages.same(it, gate.language) } -> skip(Skip.HasLanguage)
            gate.handled -> skip(Skip.Handled)
            gate.metered && !gate.meteredAccepted -> Decision.AskMetered
            else -> Decision.Fetch
        }
    }

    /** The key a hit is turned down under: provider and id. */
    fun keyOf(hit: SubtitleHit): String = hit.provider.name + ":" + hit.id

    /**
     * The hits to try for [language], best first: only that language, none in [rejected], no machine
     * translation (unless [allowMachine], the viewer's own switch), and no file without an id. A hash
     * match (the very file) comes before a name match; within each, an ordinary subtitle before a
     * hearing-impaired one; the search's own order (its relevance and downloads) breaks the rest.
     */
    fun candidates(
        hits: List<SubtitleHit>,
        language: String?,
        rejected: Set<String>,
        allowMachine: Boolean = false,
    ): List<SubtitleHit> {
        if (language.isNullOrBlank()) return emptyList()
        return hits
            .filter { it.id.isNotBlank() && keyOf(it) !in rejected && TrackLanguages.same(it.language, language) }
            .filter { allowMachine || !it.machine }
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<SubtitleHit>> { it.value.hashMatch }
                    .thenBy { it.value.hearingImpaired }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** What the viewer is told: the service by name, and whether the match is to this very file. */
    data class Source(val provider: String, val byFile: Boolean)

    fun sourceOf(hit: SubtitleHit): Source = Source(hit.provider.label, byFile = hit.hashMatch)
}
