package com.tmplayer.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.tmplayer.data.DeviceForm
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Strings
import com.tmplayer.i18n.Translator

/**
 * The tour's pages, in the order every device shows them. Shown once before signing in, and again
 * whenever Settings asks for it, on the phone, the television and the desktop alike.
 */
enum class OnboardingPage {
    /** The language the app speaks, the system's preselected. */
    Language,

    /** What TMPlayer is: unofficial, no server of its own, nothing collected. */
    About,
    SignIn,
    Chats,
    Videos,

    /**
     * Posters, cast, ratings and trailers from TMDB, TVmaze and AniList: on unless the viewer turns
     * it off here, since a lookup sends a video's cleaned title to them and they should know.
     */
    Posters,
    ;

    /** True for the pages illustrated with a screenshot of the app itself. */
    val illustrated: Boolean get() = this == SignIn || this == Chats || this == Videos || this == Posters
}

/** Which screen the app opens on, from the two things that decide it. */
enum class Entry { Tour, SignIn, App }

object Onboarding {

    /** Every page, in order. */
    val pages: List<OnboardingPage> = OnboardingPage.entries

    /** Where the tour's "Help translate" points. */
    const val TRANSLATE_URL = "https://tmplayer.org/translate/"

    /**
     * The tour comes first whenever it has not been seen, signed in or not: that is both the first
     * run and Settings asking for it again. Then the sign in screen until Telegram is ready.
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
     * The Language page's choices: the system's own first (tag ""), then each language by its own
     * name. Every shipped language is offered, machine translated or reviewed. A debug build adds
     * the en-XA pseudo-locale last, for checking that text fits and comes from the catalog.
     */
    fun languages(pseudo: Boolean = Translator.pseudoEnabled): List<LanguageChoice> =
        listOf(LanguageChoice("", null)) + Languages.all.map { LanguageChoice(it.tag, it.name) } +
            if (pseudo) listOf(LanguageChoice(Languages.PSEUDO, PSEUDO_NAME)) else emptyList()

    /** The pseudo-locale's row, debug builds only, so not in the catalog. */
    const val PSEUDO_NAME = "Pseudo-locale (en-XA)"
}

/** One row of the Language page. [name] is null for "System default". */
data class LanguageChoice(val tag: String, val name: String?)

/** A point on the About page: what it promises and the sentence that explains it. */
data class OnboardingPoint(val kind: Kind, val title: String, val body: String) {
    enum class Kind { Unofficial, NoServer, NoData }
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
        OnboardingPage.Language -> OnboardingCopy(onboardingLanguageTitle, onboardingLanguageBody)
        OnboardingPage.About -> OnboardingCopy(
            onboardingAboutTitle,
            onboardingAboutBody(f),
            listOf(
                OnboardingPoint(OnboardingPoint.Kind.Unofficial, onboardingAboutUnofficialTitle, authUnofficial),
                OnboardingPoint(OnboardingPoint.Kind.NoServer, onboardingAboutServerTitle, onboardingAboutServerBody(f)),
                OnboardingPoint(OnboardingPoint.Kind.NoData, onboardingAboutDataTitle, onboardingAboutDataBody),
            ),
        )
        OnboardingPage.SignIn -> OnboardingCopy(onboardingSigninTitle(f), onboardingSigninBody(f))
        OnboardingPage.Chats -> OnboardingCopy(onboardingChatsTitle(f), onboardingChatsBody(f))
        OnboardingPage.Videos -> OnboardingCopy(onboardingVideosTitle, onboardingVideosBody(f))
        OnboardingPage.Posters -> OnboardingCopy(onboardingPostersTitle, onboardingPostersBody)
    }
}

/**
 * Where the tour is. Back walks the pages in reverse, Next walks on and finishes on the last, and
 * Skip finishes. Snapshot state, so a screen that reads [index] recomposes when it moves.
 *
 * On the [firstRun], before signing in, the About page has to be passed: it carries the line
 * Telegram's API terms ask a third party client to show before sign in, so Skip appears only
 * after it and Back on the first page does not leave. Asked for again from Settings, both do.
 */
class TourState(
    val firstRun: Boolean = true,
    val pages: List<OnboardingPage> = Onboarding.pages,
    start: Int = 0,
) {
    var index by mutableIntStateOf(start.coerceIn(0, pages.lastIndex))

    val page: OnboardingPage get() = pages[index]
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == pages.lastIndex

    /** Whether Skip is offered on this page. Never on the last, where Start is the same press. */
    val canSkip: Boolean get() = !isLast && (!firstRun || index > pages.indexOf(OnboardingPage.About))

    /** Whether Back on this page leaves the tour rather than doing nothing. */
    val canLeave: Boolean get() = !firstRun

    /** Moves on a page; false when this was the last, which means the tour is done. */
    fun next(): Boolean {
        if (isLast) return false
        index++
        return true
    }

    /** Moves back a page; false on the first, which means leaving the tour if [canLeave]. */
    fun back(): Boolean {
        if (isFirst) return false
        index--
        return true
    }
}
