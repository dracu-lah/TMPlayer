package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What [WhatsNew]'s sheet says, apart from when it shows (that needs the update feed's version
 * compare, which the browser build does not have): the release the bundled highlights describe,
 * the full changelog's address and the lines, translated with the catalog. The web app's Settings
 * reads this too, through WebRules.
 *
 * Release prep moves the keys and [VERSION] together: new `whatsnew.highlight_*` keys and this
 * constant.
 */
object ReleaseHighlights {

    /** The release the bundled highlights describe. */
    const val VERSION = "1.27.0"

    /** Where "Full changelog" and the Settings "Changelog" row go. */
    const val CHANGELOG = "https://tmplayer.org/changelog/"

    /** The highlights, in the order the sheet lists them. */
    val highlights: List<String>
        get() = listOf(
            L.whatsnewHighlightSearch,
            L.whatsnewHighlightCopies,
            L.whatsnewHighlightCarousel,
            L.whatsnewHighlightPlayer,
            L.whatsnewHighlightSort,
        )
}
