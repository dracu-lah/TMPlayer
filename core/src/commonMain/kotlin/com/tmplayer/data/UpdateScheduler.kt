package com.tmplayer.data

import kotlinx.coroutines.delay

/** What [UpdateScheduler] remembers between launches. [SettingsStore.updatePrefs] is the real one. */
interface UpdatePrefs {
    /** "Tell me when a new version is out". */
    suspend fun notify(): Boolean

    /** When a check last got an answer, epoch milliseconds; 0 for never. */
    suspend fun lastCheck(): Long
    suspend fun setLastCheck(at: Long)

    /** The version the viewer said to skip, or blank. */
    suspend fun skippedVersion(): String
    suspend fun setSkippedVersion(version: String)

    /** "Remind me later": no popup before this, epoch milliseconds. */
    suspend fun snoozedUntil(): Long
    suspend fun setSnoozedUntil(at: Long)

    /** The version the popup was last shown for, so it shows once per version. */
    suspend fun popupShownFor(): String
    suspend fun setPopupShownFor(version: String)
}

/**
 * When TMPlayer asks whether a new version is out, the same on the phone, the TV and the desktop.
 *
 * - On every launch, including the first and before anybody has signed in, [FIRST_CHECK_DELAY_MS]
 *   after the first frame, so a cold start is never slowed by it.
 * - Not more often than every [THROTTLE_MS], by the time of the last check that got an answer. A
 *   failed one writes nothing, so the next launch tries again.
 * - While the app runs, again every [REPEAT_MS]: a TV left on for days still hears of a release.
 * - The Settings button ([checkNow]) ignores the throttle and the skipped version.
 *
 * The popup's rules are here too, so each platform only decides *where* it is drawn: once per
 * version ([shouldPopUp], [popupShown]), not while snoozed ([remindLater]), never for a skipped
 * version ([skip]).
 *
 * [now] and [check] are injected so the rules can be tested against a fake clock.
 */
class UpdateScheduler(
    private val prefs: UpdatePrefs,
    private val now: () -> Long = System::currentTimeMillis,
    private val check: suspend (quiet: Boolean, skipped: String) -> Boolean = Updates::check,
    private val onSkip: () -> Unit = Updates::skip,
) {

    /** The launch check and the repeat timer. Runs until its scope is cancelled. */
    suspend fun run() {
        delay(FIRST_CHECK_DELAY_MS)
        while (true) {
            runCatching { checkIfDue() }
            delay(REPEAT_MS)
        }
    }

    /** The scheduled check: only with the setting on, and only once the throttle has run out. */
    suspend fun checkIfDue(): Boolean {
        if (!prefs.notify()) return false
        val last = prefs.lastCheck()
        val at = now()
        // Never checked is due, and so is a clock set back past the last check, or it would never
        // be due again.
        if (last != 0L && at - last < THROTTLE_MS && at >= last) return false
        val answered = check(true, prefs.skippedVersion())
        if (answered) prefs.setLastCheck(at)
        return answered
    }

    /** The Settings button: now, whatever the throttle says, offering a skipped version too. */
    suspend fun checkNow() {
        val at = now()
        if (check(false, prefs.skippedVersion())) prefs.setLastCheck(at)
    }

    /** Whether the popup should open by itself for [version]. Tapping the item opens it regardless. */
    suspend fun shouldPopUp(version: String): Boolean =
        prefs.skippedVersion() != version &&
            now() >= prefs.snoozedUntil() &&
            prefs.popupShownFor() != version

    /** The popup has been seen for [version] and does not open by itself again. */
    suspend fun popupShown(version: String) {
        prefs.setPopupShownFor(version)
    }

    /**
     * "Remind me later": the popup comes back on the first launch after [SNOOZE_MS]. The side bar
     * item stays throughout.
     */
    suspend fun remindLater() {
        prefs.setSnoozedUntil(now() + SNOOZE_MS)
        prefs.setPopupShownFor("")
    }

    /** "Skip this version": the item and the popup go for [version]; a newer one shows again. */
    suspend fun skip(version: String) {
        prefs.setSkippedVersion(version)
        onSkip()
    }

    companion object {
        const val FIRST_CHECK_DELAY_MS = 10_000L
        const val THROTTLE_MS = 6 * 60 * 60_000L
        const val REPEAT_MS = THROTTLE_MS
        const val SNOOZE_MS = 24 * 60 * 60_000L

        /** How long after the side bar item appears the popup follows it, so the viewer sees where it lives. */
        const val POPUP_DELAY_MS = 2_000L
    }
}
