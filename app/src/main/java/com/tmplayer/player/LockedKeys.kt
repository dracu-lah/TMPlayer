package com.tmplayer.player

import android.view.KeyEvent

/**
 * What a key does while the player's screen is locked, on a phone with a keyboard, a tablet, a
 * television's remote, a Fire TV remote or a game controller alike.
 *
 * The lock is for a video handed to a child, or a phone propped on a lap, so almost every key is
 * ignored, the media keys and the D-pad included: a press only brings up the unlock pill, which
 * says how to get out. Two exceptions. The volume keys are the system's, as they are under any
 * lock screen, and nobody locks a player to stop the sound being turned down. And holding a
 * select key (OK on a remote, Enter on a keyboard, A on a controller) for [HOLD_MS] unlocks: a
 * remote has no touch screen to tap the pill on, and a held press is the one thing a mash of the
 * buttons does not do by accident. Home stays the system's on every device, which no app can
 * take away and none should.
 */
object LockedKeys {
    enum class Kind {
        /** Handed on to the system untouched: the volume. */
        System,

        /** Held for [HOLD_MS], unlocks; let go sooner, nothing. */
        Unlock,

        /** Swallowed; a press shows the way out. */
        Ignored,
    }

    /** How long a select key is held to unlock: long enough that a tap or a mash never does. */
    const val HOLD_MS = 2_000L

    /** A finger on the unlock pill: a second, since a tap is too easy for a child's hand (WP-37). */
    const val TOUCH_HOLD_MS = 1_000L

    fun kind(keyCode: Int): Kind = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        -> Kind.System
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> Kind.Unlock
        else -> Kind.Ignored
    }
}
