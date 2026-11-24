package com.tmplayer.data

/**
 * What stands between a sign out asked from outside Settings (the TV's account menu, the phone's
 * popover, the desktop's big screen menu) and its question (WP-02): signing out is Settings' press, so
 * with the app lock's PIN set and Settings' own gate shut the PIN comes first.
 *
 * The lock is read from a store that answers a moment after the screen is up: until it has, nothing is
 * decided ([Wait]), never "no PIN". A lock not yet read fails closed.
 */
enum class SignOutGate {
    /** The lock has not been read: show nothing yet. */
    Wait,

    /** A PIN is set and Settings is shut: the PIN pad first. */
    Pin,

    /** Ask the question. */
    Open,
    ;

    companion object {
        /** [lock] is null until the store has answered; [settingsUnlocked] is Settings' gate for this visit; [pinGiven] the PIN just typed. */
        fun of(lock: AppLockSettings?, settingsUnlocked: Boolean, pinGiven: Boolean): SignOutGate = when {
            settingsUnlocked || pinGiven -> Open
            lock == null -> Wait
            lock.pinSet -> Pin
            else -> Open
        }

        /** [of] with Settings' gate as [SettingsUnlock] has it now: the same answer on every route. */
        fun now(lock: AppLockSettings?, pinGiven: Boolean): SignOutGate = of(lock, SettingsUnlock.unlocked.value, pinGiven)
    }
}
