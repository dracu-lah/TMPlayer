package com.tmplayer.player

/**
 * The mini player (U8): leaving the player with Back keeps the video playing in a bar docked above
 * the bottom navigation, instead of closing it. Tapping the bar expands the player at the same
 * place, and an X or a swipe down dismisses it.
 *
 * This is the pure state of that bar, shared by the phone and the desktop. It holds no player: the
 * shell keeps the one engine it already has and only changes what is drawn, so a docked video is
 * never a second decoder. A new video while one is docked replaces it in the same engine
 * ([MiniPlayerState.opened]).
 *
 * The TV has no mini player ([MiniPlayerState.allowedOn]): its remote has nothing to point at a
 * bar with, and a 1 GB stick should not hold a decoder behind a screen nobody can steer.
 */
enum class MiniPhase {
    /** Nothing is playing and nothing is docked. */
    Closed,

    /** The full player is up. */
    Full,

    /** The player is out of sight and the bar shows it. */
    Docked,
}

/** What is true of the player at the moment Back is pressed. */
data class MiniContext(
    /** The phone or the desktop, and not the TV ([MiniPlayerState.allowedOn]). */
    val platformAllowed: Boolean = true,
    /** The viewer's own setting; on unless they turned it off. */
    val enabled: Boolean = true,
    /** The video is open (a video still loading can be docked). */
    val opened: Boolean = true,
    val ended: Boolean = false,
    val failed: Boolean = false,
    /** A sheet that waits for an answer: "Still watching?", a mobile data question, a no-room card. */
    val asking: Boolean = false,
    /** A video on a TV through Cast or DLNA: the phone or desktop is only the remote. */
    val casting: Boolean = false,
    /** The player's screen lock (kids lock): Back is the lock's to refuse, never the bar's to bypass. */
    val locked: Boolean = false,
    /** Picture in picture is up: it is the mini window already. */
    val inPictureInPicture: Boolean = false,
)

/** Why Back closes the player rather than docking it; [Dock] means it docks. */
enum class MiniVerdict { Dock, Disabled, Ended, Failed, Asking, Casting, Locked, PictureInPicture, NotOpened }

data class MiniPlayerState(
    val phase: MiniPhase = MiniPhase.Closed,
    /** A cast started while docked: the bar is hidden until it ends, the player is still held. */
    val casting: Boolean = false,
) {
    /** The bar is drawn: docked, and nothing is being cast. */
    val barVisible: Boolean get() = phase == MiniPhase.Docked && !casting

    /** The full player is drawn. */
    val expanded: Boolean get() = phase == MiniPhase.Full

    /** Something is playing or held: the engine must stay alive. */
    val holdsPlayer: Boolean get() = phase != MiniPhase.Closed

    /** A video was opened (also while another is docked): the full player, one engine. */
    fun opened(): MiniPlayerState = MiniPlayerState(MiniPhase.Full, casting)

    /** Back or the minimise button: dock when [verdict] says so, close otherwise. */
    fun leave(context: MiniContext): MiniPlayerState = when {
        phase != MiniPhase.Full -> this
        verdict(context) == MiniVerdict.Dock -> copy(phase = MiniPhase.Docked)
        else -> MiniPlayerState(casting = casting)
    }

    /** A tap on the bar. */
    fun expand(): MiniPlayerState = if (phase == MiniPhase.Docked) copy(phase = MiniPhase.Full) else this

    /** The X, a swipe down, the media session's stop, or anything that ends the video. */
    fun dismiss(): MiniPlayerState = MiniPlayerState(casting = casting)

    /** The video ended or failed with nobody looking: nothing to keep a bar for. */
    fun stoppedOnItsOwn(): MiniPlayerState = if (phase == MiniPhase.Docked) MiniPlayerState(casting = casting) else this

    /** A cast session began or ended; a docked bar hides for it and comes back after. */
    fun casting(on: Boolean): MiniPlayerState = if (casting == on) this else copy(casting = on)

    companion object {
        /** Phone and desktop only. */
        fun allowedOn(tv: Boolean): Boolean = !tv

        fun verdict(context: MiniContext): MiniVerdict = when {
            !context.platformAllowed || !context.enabled -> MiniVerdict.Disabled
            context.inPictureInPicture -> MiniVerdict.PictureInPicture
            context.casting -> MiniVerdict.Casting
            context.locked -> MiniVerdict.Locked
            context.failed -> MiniVerdict.Failed
            context.asking -> MiniVerdict.Asking
            context.ended -> MiniVerdict.Ended
            !context.opened -> MiniVerdict.NotOpened
            else -> MiniVerdict.Dock
        }

        /** The thin progress line's fill, 0 to 1, safe for an unknown or absurd duration. */
        fun progress(positionMs: Long, durationMs: Long): Float {
            if (durationMs <= 0L || positionMs <= 0L) return 0f
            return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
        }

        /** A swipe down of at least this many dp dismisses the bar. */
        const val DISMISS_SWIPE_DP = 36
    }
}
