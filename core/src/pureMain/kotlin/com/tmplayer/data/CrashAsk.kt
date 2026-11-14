package com.tmplayer.data

/**
 * The one-time "Help fix crashes?" card (S1), apart from Compose so it is tested alone.
 *
 * Crash reports stay consent: nothing is sent until somebody says Yes, here, in the setup's step,
 * in Settings or on the question after a crash. This card only makes sure the question is put
 * once to the installs that never saw it: one signed in before the setup existed, or one that
 * pressed Skip all before the setup's last page. A returning install that answered either way,
 * anywhere, is never asked again ([Inputs.asked]).
 *
 * The card is marked asked the moment it shows ([Decision.ShowAndMarkAsked]), not when it is
 * answered: a viewer who leaves the app with it up has been asked, and it does not come back on
 * every start. While this run lasts it stays up until answered ([Inputs.shownThisRun]).
 */
object CrashAsk {

    /**
     * The documented alternative to consent (S1): crash reports on by default with an easy way to
     * turn them off. NOT enabled, and nothing reads it as true: switching it on is a change of the
     * legal basis, and docs/distribution/crash-consent.md lists everything that would have to
     * change with it (the privacy page's basis, the first-run notice, Play's Data safety form,
     * F-Droid's build kept off). Not legal advice.
     */
    const val OPT_OUT_DEFAULT_ON: Boolean = false

    /** What the card offers, in reading order and in the order a remote or Tab walks it. */
    enum class Control { Yes, No, Privacy }

    /** Yes, No, then the privacy page: Yes first, as the remote lands on it. */
    val ORDER: List<Control> = listOf(Control.Yes, Control.No, Control.Privacy)

    /**
     * Where the focus starts. A television's remote needs a focused control to do anything, so it
     * lands on Yes. A phone has no focus, and a desktop keeps the keyboard where it was: the card
     * is not blocking, and Tab reaches it.
     */
    fun firstFocus(form: DeviceForm): Control? = if (form == DeviceForm.Tv) ORDER.first() else null

    /** Everything the decision reads. */
    data class Inputs(
        /** Whether this build can send a report at all: no DSN (a fork, CI) means never. */
        val available: Boolean,
        /** An answer stored, either way, or the question seen before (`SettingsStore.crashReportsAsked`). */
        val asked: Boolean,
        /** The shell is up: signed in, the setup and the chat choice done, nothing covering it. */
        val inShell: Boolean,
        /** The kids lock is in force: a child does not consent for the household. */
        val kidsLock: Boolean = false,
        /** A safe start after crashes (`SafeMode` on Android): that run says so in its own notice first. */
        val safeMode: Boolean = false,
        /** Another card or dialog is up (an update, a crash question, the language card, What's new). */
        val otherUp: Boolean = false,
        /** This run showed the card already, so it was marked asked and stays until answered. */
        val shownThisRun: Boolean = false,
        /** This run answered it. */
        val answeredThisRun: Boolean = false,
    )

    enum class Decision {
        /** Not now, or never again. */
        Hide,

        /** Up, already marked asked this run. */
        Show,

        /** Up for the first time: show it and write down that it was asked. */
        ShowAndMarkAsked,
    }

    fun decide(inputs: Inputs): Decision = with(inputs) {
        when {
            !available || answeredThisRun -> Decision.Hide
            kidsLock || safeMode || !inShell || otherUp -> Decision.Hide
            shownThisRun -> Decision.Show
            asked -> Decision.Hide
            else -> Decision.ShowAndMarkAsked
        }
    }

    /**
     * What Back means on a remote, with the card holding the focus: No. Leaving the question is
     * never consent, and the answer is stored so it is not asked again.
     */
    val BACK: Control = Control.No
}
