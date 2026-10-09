package com.tmplayer.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tmplayer.data.DeviceForm
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Strings
import com.tmplayer.i18n.Translator

/**
 * The tour's pages, in the order every device shows them. Shown once before signing in, and again
 * whenever Settings asks for it, on the phone, the television and the desktop alike.
 *
 * Two pages (there were six until CP42): what TMPlayer is, with the language and the posters choice
 * on it, then how it works. The sign in screens that follow explain themselves, so the tour no
 * longer walks through them page by page.
 */
enum class OnboardingPage {
    /**
     * What TMPlayer is: unofficial (the line Telegram's API terms ask for before sign in), no server
     * of its own, nothing collected. It also holds the language row and the posters switch, so both
     * choices are made on screen rather than by a default nobody saw.
     */
    Welcome,

    /**
     * Where the videos come from, how to get a first one in (forward it to Saved Messages), folders
     * and favourites, and how the sign in goes. Illustrated with the chat list.
     */
    HowItWorks,
    ;

    /** True for the pages illustrated with a screenshot of the app itself. */
    val illustrated: Boolean get() = this == HowItWorks
}

/** Which screen the app opens on, from the two things that decide it. */
enum class Entry { Tour, SignIn, App }

object Onboarding {

    /** Every page, in order. */
    val pages: List<OnboardingPage> = OnboardingPage.entries

    /** Where "Help translate" points, from Settings' language picker and from About. */
    const val TRANSLATE_URL = "https://tmplayer.org/translate/"

    /**
     * The tour comes first whenever it has not been seen, signed in or not: that is both the first
     * run and Settings asking for it again. Then the sign in screen until Telegram is ready.
     *
     * The flag is the same `overview_seen` the six page tour set, on purpose: somebody who already
     * went through the old tour is not walked through the new one.
     */
    fun entry(signedIn: Boolean, overviewSeen: Boolean): Entry = when {
        !overviewSeen -> Entry.Tour
        !signedIn -> Entry.SignIn
        else -> Entry.App
    }

    /** The `form` argument the catalog's wording selects on. */
    fun formKey(form: DeviceForm): String = when (form) {
        DeviceForm.Tv -> "tv"
        DeviceForm.Desktop -> "desktop"
        DeviceForm.Phone -> "phone"
    }

    /**
     * The language picker's choices: the system's own first (tag ""), then each language by its own
     * name. Every shipped language is offered, machine translated or reviewed. A debug build adds
     * the en-XA pseudo-locale last, for checking that text fits and comes from the catalog.
     */
    fun languages(pseudo: Boolean = Translator.pseudoEnabled): List<LanguageChoice> =
        listOf(LanguageChoice("", null)) + Languages.all.map { LanguageChoice(it.tag, it.name) } +
            if (pseudo) listOf(LanguageChoice(Languages.PSEUDO, PSEUDO_NAME)) else emptyList()

    /** The pseudo-locale's row, debug builds only, so not in the catalog. */
    const val PSEUDO_NAME = "Pseudo-locale (en-XA)"
}

/** One row of the language picker. [name] is null for "System default". */
data class LanguageChoice(val tag: String, val name: String?)

/** A point on a tour page: what it says in a few words and the sentence that explains it. */
data class OnboardingPoint(val kind: Kind, val title: String, val body: String) {
    enum class Kind { Unofficial, NoServer, NoData, FirstVideo, Folders, SignIn }
}

/** A page's words, in the wording of the device reading them. */
data class OnboardingCopy(
    val title: String,
    val body: String,
    val points: List<OnboardingPoint> = emptyList(),
)

/**
 * A page's words for [form]: a touch screen is tapped, a television is driven with a remote, and a
 * computer is clicked, so where the words describe operating the app they say so in each one's terms.
 */
fun Strings.onboarding(page: OnboardingPage, form: DeviceForm): OnboardingCopy {
    val f = Onboarding.formKey(form)
    return when (page) {
        OnboardingPage.Welcome -> OnboardingCopy(
            onboardingAboutTitle,
            onboardingAboutBody(f),
            listOf(
                OnboardingPoint(OnboardingPoint.Kind.Unofficial, onboardingAboutUnofficialTitle, authUnofficial),
                OnboardingPoint(OnboardingPoint.Kind.NoServer, onboardingAboutServerTitle, onboardingAboutServerBody(f)),
                OnboardingPoint(OnboardingPoint.Kind.NoData, onboardingAboutDataTitle, onboardingAboutDataBody),
            ),
        )
        OnboardingPage.HowItWorks -> OnboardingCopy(
            onboardingHowTitle,
            onboardingHowBody,
            listOf(
                OnboardingPoint(OnboardingPoint.Kind.FirstVideo, onboardingHowFirstTitle, onboardingHowFirstBody),
                OnboardingPoint(OnboardingPoint.Kind.Folders, onboardingHowFoldersTitle, onboardingHowFoldersBody),
                OnboardingPoint(OnboardingPoint.Kind.SignIn, onboardingHowSigninTitle, onboardingHowSigninBody(f)),
            ),
        )
    }
}

/**
 * Where the tour is. Back walks the pages in reverse, Next walks on and finishes on the last, and
 * Skip finishes. Snapshot state, so a screen that reads [index] recomposes when it moves.
 *
 * On the [firstRun], before signing in, the Welcome page has to be passed: it carries the line
 * Telegram's API terms ask a third party client to show before sign in, so Skip appears only
 * after it and Back on the first page does not leave. Asked for again from Settings, both do.
 *
 * [choosingLanguage] is the language picker opened from the Welcome page's language row. It sits
 * over the page rather than being a page of its own, and Back closes it before anything else.
 */
class TourState(
    val firstRun: Boolean = true,
    val pages: List<OnboardingPage> = Onboarding.pages,
    start: Int = 0,
) {
    var index by mutableIntStateOf(start.coerceIn(0, pages.lastIndex))
    var choosingLanguage by mutableStateOf(false)

    val page: OnboardingPage get() = pages[index]
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == pages.lastIndex

    /** Whether Skip is offered on this page. Never on the last, where Start is the same press. */
    val canSkip: Boolean get() = !isLast && (!firstRun || index > pages.indexOf(OnboardingPage.Welcome))

    /** Whether Back on this page leaves the tour rather than doing nothing. */
    val canLeave: Boolean get() = !firstRun

    /** Whether Back has anything to do here: close the picker, go back a page, or leave. */
    val backEnabled: Boolean get() = choosingLanguage || !isFirst || canLeave

    /** Moves on a page; false when this was the last, which means the tour is done. */
    fun next(): Boolean {
        choosingLanguage = false
        if (isLast) return false
        index++
        return true
    }

    /**
     * Closes the language picker, or moves back a page; false on the first page with the picker
     * closed, which means leaving the tour if [canLeave].
     */
    fun back(): Boolean {
        if (choosingLanguage) {
            choosingLanguage = false
            return true
        }
        if (isFirst) return false
        index--
        return true
    }
}
