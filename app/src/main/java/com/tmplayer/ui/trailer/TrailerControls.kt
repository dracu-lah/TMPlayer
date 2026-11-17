package com.tmplayer.ui.trailer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import com.tmplayer.data.MeteredDecision
import com.tmplayer.data.MeteredPolicy
import com.tmplayer.data.MobileData
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerFailure
import com.tmplayer.online.TrailerPhase
import com.tmplayer.online.TrailerRules
import com.tmplayer.online.TrailerState

/**
 * One in-app trailer's state, from the page's messages and the ready wait, through core's
 * [TrailerRules]: the dialog shows it and falls back when [TrailerState.fallsBack]. Pure, so the
 * state machine is tested without a WebView (TrailerSessionTest).
 */
@Stable
class TrailerSession {
    var state by mutableStateOf(TrailerState())
        private set

    /** A message from the page, read as outside data: anything unreadable changes nothing. */
    fun onMessage(raw: String?) {
        state = TrailerRules.next(state, TrailerEmbed.parseMessage(raw))
    }

    /** [TrailerRules.READY_TIMEOUT_MS] has passed since the page was loaded. */
    fun onTimeout() {
        state = TrailerRules.timedOut(state)
    }

    /** No page at all: the WebView could not be made, or the key was not a video id. */
    fun onBroken() {
        if (!state.fallsBack) state = TrailerState(TrailerPhase.Failed, TrailerFailure.Broken)
    }
}

/**
 * The remote in the trailer's full screen (TV): the dialog holds every key, the WebView none.
 * OK plays or pauses (unless the Close button is up and has the focus: then OK is Close's),
 * Left and Right move ten seconds, Up and Down bring the Close button back, Back closes.
 */
object TrailerKeys {

    enum class Action { Toggle, Play, Pause, SeekBack, SeekForward, ShowChrome, Close, Swallow }

    /** How far Left and Right move the trailer. */
    const val SEEK_SECONDS = 10

    /**
     * What [key] does, or null when it is not the dialog's and goes on to the focused button.
     * Acted on as the key goes down ([down]); Back as it comes up, so its up never reaches the
     * page under the dialog. A key's other half is swallowed.
     */
    fun action(key: Key, down: Boolean, repeat: Int, chromeShown: Boolean): Action? = when (key) {
        Key.Back, Key.Escape -> if (down) Action.Swallow else Action.Close
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
            chromeShown -> null
            down && repeat == 0 -> Action.Toggle
            else -> Action.Swallow
        }
        Key.MediaPlayPause -> if (down && repeat == 0) Action.Toggle else Action.Swallow
        Key.MediaPlay -> if (down && repeat == 0) Action.Play else Action.Swallow
        Key.MediaPause -> if (down && repeat == 0) Action.Pause else Action.Swallow
        Key.DirectionLeft, Key.MediaRewind -> if (down) Action.SeekBack else Action.Swallow
        Key.DirectionRight, Key.MediaFastForward -> if (down) Action.SeekForward else Action.Swallow
        Key.DirectionUp, Key.DirectionDown -> if (down) Action.ShowChrome else Action.Swallow
        else -> null
    }

    /** The page's script for [action], or null when it is not one for the page. */
    fun script(action: Action): String? = when (action) {
        Action.Toggle -> TrailerEmbed.TOGGLE
        Action.Play -> TrailerEmbed.PLAY
        Action.Pause -> TrailerEmbed.PAUSE
        Action.SeekBack -> TrailerEmbed.seekBy(-SEEK_SECONDS)
        Action.SeekForward -> TrailerEmbed.seekBy(SEEK_SECONDS)
        else -> null
    }
}

/**
 * Whether a trailer may stream in TMPlayer on this connection, by the same rule as a video
 * ([MeteredPolicy.decide] with the viewer's "On mobile data" answer): not while offline, not on
 * mobile data under "Never". A trailer is far under the size that asks first, so "Ask" lets it
 * play. False sends it to the YouTube app or the browser, as before.
 */
fun trailerMayStream(offline: Boolean, metered: Boolean, mobileData: MobileData, television: Boolean): Boolean =
    !offline && MeteredPolicy.decide(
        metered = metered,
        wifiOnly = MeteredPolicy.wifiOnlyInForce(mobileData.blocks, television),
        alreadyDownloaded = false,
        warnedThisSession = false,
        sizeBytes = TRAILER_BYTES,
        asks = mobileData.asks,
    ) == MeteredDecision.Allow

/** A long trailer at 1080p, more than most are. */
internal const val TRAILER_BYTES = 80L * 1024 * 1024
