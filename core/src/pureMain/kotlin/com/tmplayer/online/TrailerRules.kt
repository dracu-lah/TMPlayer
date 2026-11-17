package com.tmplayer.online

/** Where a trailer plays: in TMPlayer's own sheet or screen, or handed to the YouTube app or the browser. */
enum class TrailerRoute { InApp, External }

/** Where an in-app trailer is, as the page has reported it ([TrailerRules.next]). */
enum class TrailerPhase {
    /** The page is loading and the player has not said it is ready. */
    Loading,

    /** Ready, not playing yet. */
    Ready,
    Playing,
    Paused,
    Buffering,
    Ended,

    /** It cannot play here: the host closes it and opens the trailer outside ([TrailerState.failure] says why). */
    Failed,
}

/** Why an in-app trailer gave up. Every one of them falls back to the external open. */
enum class TrailerFailure {
    /** The owner does not allow it embedded, or YouTube refused the embed (101, 150, 152, 153). */
    NotEmbeddable,

    /** Removed or private (100). */
    NotFound,

    /** A bad request or the player's own failure (2, 5, and any code YouTube adds later). */
    Broken,

    /** YouTube's script could not be loaded: no network, or something blocks it (-1). */
    Unreachable,

    /** No `ready` within [TrailerRules.READY_TIMEOUT_MS]. */
    TimedOut,
}

/** An in-app trailer's state. [failure] is set once [phase] is [TrailerPhase.Failed], and never cleared. */
data class TrailerState(val phase: TrailerPhase = TrailerPhase.Loading, val failure: TrailerFailure? = null) {
    /** The host closes the player and opens the trailer outside, saying `trailer.unavailable_in_app`. */
    val fallsBack: Boolean get() = phase == TrailerPhase.Failed

    /** Play and pause do something: the player has said it is ready and has not failed. */
    val controllable: Boolean get() = phase != TrailerPhase.Loading && phase != TrailerPhase.Failed
}

/**
 * When a trailer plays in the app and when it goes outside, shared by every platform
 * (private/TRAILERS-PLAN.md). Pure: the platform feeds it the setting, what it knows of the
 * network and the page's messages ([TrailerEmbed.parseMessage]), and does what it answers.
 */
object TrailerRules {

    /** No `ready` from the page within this long, and the trailer opens outside. */
    const val READY_TIMEOUT_MS: Long = 12_000L

    /**
     * The YouTube errors that are known to mean "not here": 2 (bad id), 5 (the HTML5 player
     * failed), 100 (removed or private), 101 and 150 (not allowed embedded), 153 (no referrer
     * or origin). Any other code falls back too ([failure]), so a code YouTube adds later never
     * leaves a black screen.
     */
    val FALLBACK_ERRORS: Set<Int> = setOf(2, 5, 100, 101, 150, 153)

    /**
     * In the app when the viewer's setting `trailers_in_app` is on ([enabled]), [key] is a
     * YouTube id, the device is [online] and the platform [canEmbed] (a web view is there).
     * Anything else opens outside, as before.
     */
    fun route(enabled: Boolean, key: String?, online: Boolean, canEmbed: Boolean): TrailerRoute =
        if (enabled && online && canEmbed && TrailerEmbed.isValidKey(key)) TrailerRoute.InApp else TrailerRoute.External

    /** What a YouTube error [code] means here. Every code falls back. */
    fun failure(code: Int): TrailerFailure = when (code) {
        -1 -> TrailerFailure.Unreachable
        100 -> TrailerFailure.NotFound
        101, 150, 152, 153 -> TrailerFailure.NotEmbeddable
        else -> TrailerFailure.Broken
    }

    /** [state] after the page said [message]. A failed trailer stays failed; null (unreadable) changes nothing. */
    fun next(state: TrailerState, message: TrailerMessage?): TrailerState {
        if (state.fallsBack || message == null) return state
        return when (message) {
            TrailerMessage.Ready -> if (state.phase == TrailerPhase.Loading) TrailerState(TrailerPhase.Ready) else state
            is TrailerMessage.Error -> TrailerState(TrailerPhase.Failed, failure(message.code))
            is TrailerMessage.State -> when (message.code) {
                1 -> TrailerState(TrailerPhase.Playing)
                2 -> TrailerState(TrailerPhase.Paused)
                3 -> TrailerState(TrailerPhase.Buffering)
                0 -> TrailerState(TrailerPhase.Ended)
                // Unstarted and cued: ready once the player is there, still loading before.
                else -> if (state.phase == TrailerPhase.Loading) state else TrailerState(TrailerPhase.Ready)
            }
        }
    }

    /** [state] when [READY_TIMEOUT_MS] has passed since the page was loaded: still loading falls back. */
    fun timedOut(state: TrailerState): TrailerState =
        if (state.phase == TrailerPhase.Loading) TrailerState(TrailerPhase.Failed, TrailerFailure.TimedOut) else state
}
