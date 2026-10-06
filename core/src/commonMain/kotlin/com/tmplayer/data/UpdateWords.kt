package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * The update popup's sentences that every target says the same way, so the phone, the TV and the
 * desktop cannot drift apart. The lines that depend on how the app was installed stay with each
 * platform's popup.
 */
object UpdateWords {

    /** "TMPlayer 1.20.0 is out", with a note when Settings re-offers a version that was skipped. */
    fun title(release: Release, skipped: Boolean = false): String =
        if (skipped) L.updateTitleSkipped(release.version) else L.updateTitle(release.version)

    fun youHave(installed: String): String = L.updateYouHave(installed)

    /** The side bar item, and the Settings row with "to". */
    fun item(release: Release): String = L.updateItem(release.version)
    fun settingsRow(release: Release): String = L.updateSettingsRow(release.version)

    val UPDATE_NOW: String get() = L.updateUpdateNow
    val TRY_AGAIN: String get() = L.commonTryAgain
    val LATER: String get() = L.updateLater
    val SKIP: String get() = L.updateSkip
    val OPEN_RELEASE_PAGE: String get() = L.updateOpenReleasePageShort

    val UNREACHABLE: String get() = L.updateUnreachable
    val DAMAGED: String get() = L.updateDamaged
    val NO_INSTALLER: String get() = L.updateNoInstaller
}
