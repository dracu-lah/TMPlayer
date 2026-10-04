package com.tmplayer.data

/**
 * The update popup's sentences that every target says the same way, so the phone, the TV and the
 * desktop cannot drift apart. The lines that depend on how the app was installed stay with each
 * platform's popup.
 */
object UpdateWords {

    /** "TMPlayer 1.20.0 is out", with a note when Settings re-offers a version that was skipped. */
    fun title(release: Release, skipped: Boolean = false): String =
        "TMPlayer ${release.version} is out" + if (skipped) " (you skipped it)" else ""

    fun youHave(installed: String): String = "You have $installed."

    /** The side bar item, and the Settings row with "to". */
    fun item(release: Release): String = "Update ${release.version}"
    fun settingsRow(release: Release): String = "Update to ${release.version}"

    const val UPDATE_NOW = "Update now"
    const val TRY_AGAIN = "Try again"
    const val LATER = "Remind me later"
    const val SKIP = "Skip this version"
    const val OPEN_RELEASE_PAGE = "Open release page"

    const val UNREACHABLE = "Could not reach GitHub. Try again in a moment."
    const val DAMAGED = "The download was damaged and was not installed."
    const val NO_INSTALLER = "Android could not open the installer. Install it by hand from the release page."
}
