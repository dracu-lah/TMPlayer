package com.tmplayer.ui.onboarding

/**
 * The one contextual card after the first sign in (CP42), which is not built yet: it needs CP41's
 * "hide Telegram's default groups" setting, and the account, which the tour before sign in does
 * not have. This is where its rule lives so CP41 only has to draw the card and remember the answer.
 *
 * The card asks "Show everything" or "Only my folders" when the account has Telegram folders,
 * with CP41's confirm prompt behind "Only my folders". With no folders there is nothing to choose
 * between, so it is skipped. It is asked once per install, and never on a tour Settings replays.
 *
 * Where it goes: the Android shell (`MainActivity`, beside the language card) and the desktop
 * shell (`Shell.kt`, `Browse`); both carry a "CP41 hook" comment at that spot.
 */
object FirstSignIn {

    /** Whether to show the card: once, and only for an account with at least one Telegram folder. */
    fun shouldAsk(folderCount: Int, alreadyAsked: Boolean): Boolean = !alreadyAsked && folderCount > 0
}
