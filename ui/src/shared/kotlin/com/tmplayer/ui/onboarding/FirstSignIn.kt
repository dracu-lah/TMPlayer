package com.tmplayer.ui.onboarding

import com.tmplayer.data.AuthState

/**
 * The one contextual card after the first sign in (CP42): "Show everything" or "Only my folders",
 * which is the Settings option that hides Telegram's default groups ([com.tmplayer.ui.browse.DefaultGroups]).
 * It is not a tour page because it needs the account, which the tour before sign in does not have.
 *
 * The card is armed when a sign in screen is shown ([isSignInStep], then
 * `SettingsStore.armFirstSignInCard`), so somebody signed in before this existed is never asked. It
 * waits for the shell and the chat list, then asks when the account has Telegram folders; with no
 * folders there is nothing to choose between, so it is skipped and never comes back. "Only my
 * folders" goes through the same confirm prompt as the Settings switch, favourites count and all.
 * It is asked once per install.
 *
 * Drawn by the Android shell (`MainActivity`, beside the language card) and the desktop shell
 * (`Shell.kt`, `Browse`).
 */
object FirstSignIn {

    /** What the shell does about the card right now. */
    enum class Decision {
        /** Not armed, or not yet: the chat list (and with it the folder list) has not arrived. */
        Wait,
        /** Show the card. */
        Ask,
        /** No folders on this account: mark the card dealt with without showing it. */
        Skip,
    }

    /** Whether to show the card: once, and only for an account with at least one Telegram folder. */
    fun shouldAsk(folderCount: Int, alreadyAsked: Boolean): Boolean = !alreadyAsked && folderCount > 0

    /**
     * The card's next step. [pending] is the armed flag, [chatsLoaded] whether the chat list has
     * come from TDLib (the folder list comes before it, so an empty one then really means none).
     */
    fun decide(pending: Boolean, chatsLoaded: Boolean, folderCount: Int): Decision = when {
        !pending || !chatsLoaded -> Decision.Wait
        shouldAsk(folderCount, alreadyAsked = false) -> Decision.Ask
        else -> Decision.Skip
    }

    /** Whether [auth] is a screen of the sign in itself, which is what arms the card. */
    fun isSignInStep(auth: AuthState): Boolean = when (auth) {
        AuthState.ChooseMethod, is AuthState.Qr, is AuthState.Phone, is AuthState.Code, is AuthState.Password -> true
        AuthState.Connecting, AuthState.Ready, is AuthState.Failed -> false
    }
}
