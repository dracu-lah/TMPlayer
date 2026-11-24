package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What [WhatsNew]'s sheet says, apart from when it shows (that needs the update feed's version
 * compare, which the browser build does not have): the release the bundled highlights describe,
 * the full changelog's address and the lines, translated with the catalog. The web app's Settings
 * reads this too, through WebRules.
 *
 * The lines are grouped under a few headings and each line says which forms of the app it is for
 * ([Form]): a television is told about its remote, a phone about its touch screen, so nobody reads
 * a line about a key they do not have. Only what is on the release is claimed: a line goes when its
 * change is reverted.
 *
 * Release prep moves the keys and [VERSION] together: new `whatsnew.line_*` keys, this table and
 * this constant. [VERSION] is the version the beta is cut as: the sheet waits for an install that
 * has not reached it.
 */
object ReleaseHighlights {

    /** The release the bundled highlights describe. */
    const val VERSION = "1.29.0"

    /** Where "Full changelog" and the Settings "Changelog" row go. */
    const val CHANGELOG = "https://tmplayer.org/changelog/"

    /** The forms a line can be for: the apps' [DeviceForm]s, and the web app. */
    enum class Form {
        Tv,
        Phone,
        Desktop,
        Web,
        ;

        companion object {
            fun of(form: DeviceForm): Form = when (form) {
                DeviceForm.Tv -> Tv
                DeviceForm.Phone -> Phone
                DeviceForm.Desktop -> Desktop
            }

            /** The form named by [name] (the web app asks with a word), the web's own when it is not one. */
            fun parse(name: String?): Form = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Web
        }
    }

    /** One heading and the lines under it, for one form. */
    data class Section(val title: String, val lines: List<String>)

    private val ALL: Set<Form> = Form.entries.toSet()
    private val TV = setOf(Form.Tv)
    private val NOT_WEB = setOf(Form.Tv, Form.Phone, Form.Desktop)
    private val NOT_TV = setOf(Form.Phone, Form.Desktop, Form.Web)
    private val PHONE = setOf(Form.Phone)
    private val DESKTOP = setOf(Form.Desktop)
    private val WEB = setOf(Form.Web)

    private class Line(val forms: Set<Form>, val text: () -> String)

    private class Group(val title: () -> String, val lines: List<Line>)

    // Read at call time: the lines follow the language the catalog speaks.
    private val groups: List<Group>
        get() = listOf(
            Group(
                { L.whatsnewSectionPlayer },
                listOf(
                    Line(TV) { L.whatsnewLineTvSeek },
                    Line(ALL) { L.whatsnewLineChapters },
                    Line(NOT_WEB) { L.whatsnewLineSubtitles },
                    Line(setOf(Form.Phone, Form.Desktop)) { L.whatsnewLineLanguages },
                    Line(DESKTOP) { L.whatsnewLineDecoderDesktop },
                ),
            ),
            Group(
                { L.whatsnewSectionBrowse },
                listOf(
                    Line(TV) { L.whatsnewLineTvLists },
                    Line(TV) { L.whatsnewLineTvOptions },
                    Line(TV) { L.whatsnewLineTvWatchLater },
                    Line(NOT_WEB) { L.whatsnewLinePlayAll },
                    Line(WEB) { L.whatsnewLinePlayAllWeb },
                    Line(WEB) { L.whatsnewLineWebHistory },
                ),
            ),
            Group(
                { L.whatsnewSectionAccount },
                listOf(
                    Line(TV) { L.whatsnewLineAccountTv },
                    Line(NOT_TV) { L.whatsnewLineAccount },
                    Line(TV) { L.whatsnewLineSettingsTv },
                    Line(PHONE) { L.whatsnewLineSettingsTablet },
                    Line(ALL) { L.whatsnewLineSettingsOrder },
                    Line(DESKTOP) { L.whatsnewLineDesktopKeys },
                    Line(WEB) { L.whatsnewLineWebSave },
                ),
            ),
            Group(
                { L.whatsnewSectionSync },
                listOf(Line(ALL) { L.whatsnewLineSync }),
            ),
        )

    /** The headings and lines for [form], in the order the sheet lists them; a heading with no line left is dropped. */
    fun sectionsFor(form: Form): List<Section> =
        groups.mapNotNull { group ->
            val lines = group.lines.filter { form in it.forms }.map { it.text() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) null else Section(group.title(), lines)
        }

    /** The same for the form of this device. */
    fun sectionsFor(form: DeviceForm): List<Section> = sectionsFor(Form.of(form))

    /** The lines for [form] without their headings, for a reader that has no room for them. */
    fun highlightsFor(form: Form): List<String> = sectionsFor(form).flatMap { it.lines }

    /** The lines of the phone's sheet, without headings. */
    val highlights: List<String>
        get() = highlightsFor(Form.Phone)
}
