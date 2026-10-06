package com.tmplayer.i18n

/**
 * The one-time "Now in Español" card. With nothing picked in Settings the app follows the device,
 * so a device set to Spanish opens the app in Spanish on its own. That switch is said once, in the
 * new language, with a way to change it: somebody who reads English better than the language their
 * phone happens to be in should not have to hunt for the setting.
 */
object LanguageNotice {

    /**
     * Whether to show the card now. [saved] is the Settings choice ("" follows the system),
     * [active] the language the app is in, [announced] the language the card was last shown for,
     * and [hasCatalog] whether [active] has a catalog at all: a language with none reads in English,
     * and announcing it would be a card in English saying the app is in something else.
     *
     * Never for English, never for the pseudo-locale, and never after a pick in Settings, which is
     * already the viewer's own answer.
     */
    fun shouldShow(saved: String, active: String, announced: String, hasCatalog: Boolean): Boolean =
        saved.isBlank() &&
            hasCatalog &&
            active != Languages.ENGLISH &&
            active != Languages.PSEUDO &&
            Languages.find(active) != null &&
            !announced.equals(active, ignoreCase = true)

    /** The language's own name for the card's title, "Español (Latinoamérica)" for `es-419`. */
    fun name(tag: String): String = Languages.find(tag)?.name ?: tag
}
