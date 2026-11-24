package com.tmplayer.data

/**
 * "What's new": a short sheet on the first run of a new version, with the highlights bundled in
 * the catalog so they are translated with everything else, and the same sheet from Settings.
 *
 * The highlights describe one release, [VERSION], held in [ReleaseHighlights] (which the web app
 * reads too). Release prep moves the two together: new `whatsnew.line_*` keys and
 * [ReleaseHighlights.VERSION]. A patch release that keeps them shows nothing, because the viewer
 * has already seen those highlights.
 */
object WhatsNew {

    /** The release the bundled highlights describe. */
    const val VERSION = ReleaseHighlights.VERSION

    /** Where "Full changelog" and the Settings "Changelog" row go. */
    const val CHANGELOG = ReleaseHighlights.CHANGELOG

    /** The highlights, in the order the sheet lists them, without headings: the phone's lines. */
    val highlights: List<String>
        get() = ReleaseHighlights.highlights

    /** The headings and lines the sheet shows on a device of [form]: the TV's about its remote, the phone's about touch. */
    fun sections(form: DeviceForm): List<ReleaseHighlights.Section> = ReleaseHighlights.sectionsFor(form)

    /** What a launch does: show the sheet or not, and the version to remember, if any. */
    data class Launch(val show: Boolean, val remember: String?)

    /**
     * What to do on a launch of [installed] when the last version this install opened was
     * [lastSeen] ("" for none).
     *
     * - A version that is not a release (a development build's "0" or "dev") changes nothing.
     * - No [lastSeen] is a fresh install, or one from before this existed: remember [installed]
     *   and show nothing. A new install has no "before" to compare with, and the tour is already
     *   on screen.
     * - A newer [installed] is remembered, and the sheet shows when [highlightsFor] is newer than
     *   [lastSeen] and not newer than [installed]: the highlights are for a version this viewer has
     *   just reached.
     * - The same or an older version (a downgrade) changes nothing.
     */
    fun onLaunch(lastSeen: String, installed: String, highlightsFor: String = VERSION): Launch {
        val release = release(installed) ?: return Launch(show = false, remember = null)
        val seen = release(lastSeen) ?: return Launch(show = false, remember = release)
        if (UpdateFeed.compare(release, seen) <= 0) return Launch(show = false, remember = null)
        val show = UpdateFeed.compare(highlightsFor, seen) > 0 && UpdateFeed.compare(highlightsFor, release) <= 0
        return Launch(show = show, remember = release)
    }

    /**
     * The x.y.z of a version string, without a build suffix such as the promo build's `-promo`, or
     * null when there is none.
     */
    internal fun release(version: String): String? =
        Regex("""^v?(\d+\.\d+\.\d+)""").find(version.trim())?.groupValues?.get(1)
}
