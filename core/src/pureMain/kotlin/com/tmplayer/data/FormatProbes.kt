package com.tmplayer.data

import com.tmplayer.platform.LruMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the player saw in a video's tracks, kept per video for as long as the app runs, so the
 * details page can show HDR, codec and sound badges ([FormatFacts]) for a video that has been
 * played, without opening it again.
 *
 * Bounded: the [CAPACITY] most recently used videos, the oldest out (a viewer who plays 100,000
 * videos holds 256 small records). Filled only by a player that already has the tracks, never by a
 * per tile question to TDLib: a tile reads [FormatFacts.resolutionOf] from the message instead.
 * Not stored on disk: a restart forgets them and the size based badge never needed them.
 */
object FormatProbes {

    /** Most videos remembered. */
    const val CAPACITY = 256

    private val probes = LruMap<String, FormatFacts.Probe>(CAPACITY)
    private val version = MutableStateFlow(0)

    /** Counts up whenever a probe is stored or changed, so a page showing one can look again. */
    val changes: StateFlow<Int> get() = version

    /** The probe of the video [id] ([MediaItem.id]), or null where no player has seen it this session. */
    operator fun get(id: String): FormatFacts.Probe? = if (id.isEmpty()) null else probes[id.take(MAX_KEY)]

    /**
     * Remembers [probe] for [id]. A probe with nothing in it is not kept, and one that equals what
     * is already kept changes nothing (a player lists its tracks several times as they settle).
     */
    fun record(id: String, probe: FormatFacts.Probe) {
        if (id.isEmpty() || probe.isEmpty()) return
        val key = id.take(MAX_KEY)
        // A file can list hundreds of sound tracks; a few are all a badge row can speak for.
        val kept = if (probe.audio.size > MAX_AUDIO) probe.copy(audio = probe.audio.take(MAX_AUDIO)) else probe
        if (probes[key] == kept) return
        probes[key] = kept
        version.value = version.value + 1
    }

    /** Forgets everything (a test, or an account switch). */
    fun clear() {
        probes.clear()
        version.value = version.value + 1
    }

    private fun FormatFacts.Probe.isEmpty(): Boolean =
        videoMime == null && videoCodecs == null && width <= 0 && height <= 0 && frameRate <= 0f &&
            transfer == FormatFacts.Transfer.Unknown && bitDepth <= 0 && !hdr10Plus && audio.isEmpty()

    private const val MAX_KEY = 128
    private const val MAX_AUDIO = 16
}
