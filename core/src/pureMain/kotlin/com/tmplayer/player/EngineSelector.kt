package com.tmplayer.player

import com.tmplayer.data.DecoderHint
import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.KnownDevice
import com.tmplayer.data.KnownDevices
import com.tmplayer.data.PlayerEngine

/** The engine the viewer picked in Settings, Playback: [Automatic] lets [EngineSelector] decide. */
enum class EngineChoice(val key: String) {
    Automatic("automatic"),
    ExoPlayer("exoplayer"),
    Vlc("vlc"),
    ;

    companion object {
        /** The stored choice; anything unknown, blank or missing means [Automatic]. */
        fun from(stored: String?): EngineChoice {
            val clean = stored?.trim()?.lowercase() ?: return Automatic
            return entries.firstOrNull { it.key == clean || it.name.lowercase() == clean } ?: Automatic
        }
    }
}

/** Why [EngineSelector] picked what it did, for the log, the stutter report and the status sheet. */
enum class EngineReason {
    /** The engine the viewer picked in Settings. */
    UserChoice,

    /** VLC was wanted (picked, listed or learned) but its native library is not on this device. */
    VlcUnavailable,

    /** The 3D view is on: it is drawn on ExoPlayer's picture, so the device decoder keeps it. */
    Stereo3d,

    /** The device is on the known device list ([KnownDevices]). */
    KnownDevice,

    /** ExoPlayer failed to decode on this device before, and VLC never did. */
    DeviceHistory,

    /** Nothing says otherwise: ExoPlayer, the default. */
    Default,

    /** ExoPlayer failed to decode this video and the one automatic switch went to VLC. */
    SwitchedAfterFailure,

    /** VLC crashed in native code on this device before ([EngineCrashes]): Automatic leaves it alone. */
    VlcCrashed,
}

/** What went wrong in an engine, as far as choosing one goes: only [Decoder] is the engine's fault. */
enum class EngineFailure {
    /** The decoder or renderer gave up, or drew frozen or broken frames. */
    Decoder,

    /** The bytes stopped coming (a network cut, a stall): another engine would wait just the same. */
    Source,

    /** Anything else (a file gone, a format nothing plays). */
    Other,
}

/**
 * How engines have done on this device, stored by the player: decoding failures of each, counted
 * since the viewer last changed the engine setting. Counts only go up, which is what keeps
 * [EngineSelector] from going back and forth between videos.
 */
data class EngineHistory(val exoPlayerFailures: Int = 0, val vlcFailures: Int = 0) {

    fun failed(engine: PlayerEngine): EngineHistory = when (engine) {
        PlayerEngine.ExoPlayer -> copy(exoPlayerFailures = bump(exoPlayerFailures))
        PlayerEngine.Vlc -> copy(vlcFailures = bump(vlcFailures))
    }

    /** As stored: `exo=2;vlc=0`. */
    fun encode(): String = "exo=$exoPlayerFailures;vlc=$vlcFailures"

    companion object {
        val None = EngineHistory()
        private const val MAX = 1_000

        private fun bump(n: Int): Int = (n + 1).coerceAtMost(MAX)

        /** The stored history, or [None] for anything missing or unreadable; a bad part counts as 0. */
        fun decode(stored: String?): EngineHistory {
            if (stored.isNullOrBlank() || stored.length > 200) return None
            var exo = 0
            var vlc = 0
            for (part in stored.split(';')) {
                val key = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim().toIntOrNull()?.coerceIn(0, MAX) ?: continue
                when (key) {
                    "exo" -> exo = value
                    "vlc" -> vlc = value
                }
            }
            return EngineHistory(exo, vlc)
        }
    }
}

/** What is known of the video about to play: its video format (a MIME type), when known. */
data class VideoFacts(val mime: String? = null, val width: Int = 0, val height: Int = 0)

/** Everything [EngineSelector] decides from. */
data class EngineInput(
    val choice: EngineChoice,
    val device: DeviceFacts,
    val history: EngineHistory = EngineHistory.None,
    val video: VideoFacts = VideoFacts(),
    /** The 3D view is on for this video. */
    val stereo3d: Boolean = false,
    /** libVLC's native library loaded on this device (false on a wrong ABI or a build without it). */
    val vlcAvailable: Boolean = false,
    /** The known device list: [KnownDevices.effective] of the feed's, or the shipped one. */
    val known: List<KnownDevice> = KnownDevices.builtIn,
    /** Native crashes while VLC played on this device ([EngineCrashes], [VlcCrashes.count]). */
    val vlcCrashes: Int = 0,
)

/**
 * The engine a video plays in, and why: [engine], [reason], the decoder to ask ExoPlayer for
 * while the decoder setting is Automatic ([decoder], null for no opinion), and the known device
 * entry behind it ([entryId]).
 */
data class EngineDecision(
    val engine: PlayerEngine,
    val reason: EngineReason,
    val decoder: DecoderHint? = null,
    val entryId: String? = null,
)

/**
 * Picks the engine a video plays in (PR1). Pure, so every rule is in one decision table test.
 *
 * The rules, first that applies wins:
 *  1. The 3D view stays on the device decoder in ExoPlayer: VLC cannot draw it. This wins even
 *     over the viewer's VLC, as a missing library does: the reason says why.
 *  2. The viewer's choice wins over everything learned or listed: ExoPlayer as picked, VLC as
 *     picked when its library is here (ExoPlayer, [EngineReason.VlcUnavailable], when not). So a
 *     device on the list that plays fine is put right by picking ExoPlayer.
 *  3. Automatic on a device on the known list: what the entry prefers.
 *  4. Automatic after ExoPlayer failed to decode [HISTORY_FAILURES] times on this device and VLC
 *     never did: VLC. Counts only rise, so once VLC has failed too this is ExoPlayer for good:
 *     across videos at most one change to VLC and one back, never a loop.
 *  5. Otherwise ExoPlayer.
 * Whatever would be VLC without the library is ExoPlayer with [EngineReason.VlcUnavailable].
 * Whatever Automatic would make VLC (rules 3, 4 and the switch after a failure) is ExoPlayer with
 * [EngineReason.VlcCrashed] once VLC crashed natively here [EngineCrashes.AUTOMATIC_LIMIT] times
 * (PR6); the viewer's own VLC (rule 2) stays.
 *
 * During one video ([afterFailure]): at most one automatic switch, only from ExoPlayer to VLC,
 * only on a decoding failure and only under Automatic. A video that fails in VLC is never sent
 * back by itself; the status sheet offers the other engine ([toOffer]) and the viewer decides.
 */
object EngineSelector {

    /** ExoPlayer decoding failures on this device after which Automatic starts on VLC. */
    const val HISTORY_FAILURES = 2

    /** Automatic switches during one video. */
    const val SWITCHES_PER_VIDEO = 1

    fun choose(input: EngineInput): EngineDecision {
        if (input.stereo3d) return EngineDecision(PlayerEngine.ExoPlayer, EngineReason.Stereo3d, DecoderHint.Device)
        when (input.choice) {
            EngineChoice.ExoPlayer -> return EngineDecision(PlayerEngine.ExoPlayer, EngineReason.UserChoice)
            EngineChoice.Vlc -> return vlcOr(input, EngineReason.UserChoice)
            EngineChoice.Automatic -> Unit
        }
        val entry = KnownDevices.find(input.known, input.device, input.video.mime)
        if (entry != null) {
            val decision = when (entry.engine) {
                PlayerEngine.Vlc -> automaticVlcOr(input, EngineReason.KnownDevice, entry.decoder)
                PlayerEngine.ExoPlayer, null -> EngineDecision(PlayerEngine.ExoPlayer, EngineReason.KnownDevice, entry.decoder)
            }
            return decision.copy(entryId = entry.id.ifEmpty { null })
        }
        val history = input.history
        if (history.exoPlayerFailures >= HISTORY_FAILURES && history.vlcFailures == 0) {
            return automaticVlcOr(input, EngineReason.DeviceHistory)
        }
        return EngineDecision(PlayerEngine.ExoPlayer, EngineReason.Default)
    }

    /**
     * The engine to switch to by itself after [running] failed with [failure] during this video,
     * which has switched [switches] times already, or null to stay and show the status sheet.
     */
    fun afterFailure(input: EngineInput, running: EngineDecision, failure: EngineFailure, switches: Int): EngineDecision? {
        if (input.choice != EngineChoice.Automatic) return null
        if (failure != EngineFailure.Decoder) return null
        if (switches >= SWITCHES_PER_VIDEO) return null
        if (running.engine != PlayerEngine.ExoPlayer) return null
        if (input.stereo3d || !input.vlcAvailable) return null
        if (vlcCrashedHere(input)) return null
        return EngineDecision(PlayerEngine.Vlc, EngineReason.SwitchedAfterFailure)
    }

    /** VLC crashed natively on this device often enough that Automatic no longer picks it (PR6). */
    fun vlcCrashedHere(input: EngineInput): Boolean = input.vlcCrashes >= EngineCrashes.AUTOMATIC_LIMIT

    /** The other engine for the status sheet to offer after [running] failed, or null when there is none. */
    fun toOffer(input: EngineInput, running: PlayerEngine): PlayerEngine? = when (running) {
        PlayerEngine.ExoPlayer -> if (input.vlcAvailable && !input.stereo3d) PlayerEngine.Vlc else null
        PlayerEngine.Vlc -> PlayerEngine.ExoPlayer
    }

    /** VLC as Automatic wants it: [vlcOr], unless VLC crashed natively here. */
    private fun automaticVlcOr(input: EngineInput, reason: EngineReason, decoder: DecoderHint? = null): EngineDecision =
        if (input.vlcAvailable && vlcCrashedHere(input)) EngineDecision(PlayerEngine.ExoPlayer, EngineReason.VlcCrashed, decoder)
        else vlcOr(input, reason, decoder)

    private fun vlcOr(input: EngineInput, reason: EngineReason, decoder: DecoderHint? = null): EngineDecision =
        if (input.vlcAvailable) EngineDecision(PlayerEngine.Vlc, reason)
        else EngineDecision(PlayerEngine.ExoPlayer, EngineReason.VlcUnavailable, decoder)
}
