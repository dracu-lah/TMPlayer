package com.tmplayer

import android.view.KeyEvent

/**
 * The remote's Search key, when nothing on screen took it.
 *
 * The window then runs its own default: `PhoneWindow.launchDefaultSearch`, which closes the system
 * dialogs first. On some Android 14 TV boxes (Hisilicon MB300, Sentry 1.28.5) the system refuses
 * that broadcast with a `SecurityException` thrown inside the framework, on our main thread, where
 * nothing of ours can catch it, and the app dies. Taking the key ends it there: a screen that wants
 * Search (the TV Home search) handles it before this runs, and everywhere else the key does nothing
 * instead of crashing.
 */
internal object SearchKeyGuard {
    fun swallows(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_SEARCH
}
