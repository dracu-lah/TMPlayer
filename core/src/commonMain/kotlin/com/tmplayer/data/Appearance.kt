package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What the viewer chose in Settings, under Appearance.
 *
 * Stored by name rather than by ordinal, so reordering this list cannot silently move everybody's
 * setting to whichever entry now sits at their old number.
 *
 * @property label the one word shown in a segment, short enough that three fit across a phone
 * @property description the sentence under the picker, also read out by a screen reader
 */
enum class ThemeChoice(private val labelText: () -> String, private val descriptionText: () -> String) {
    /** Whatever the phone itself is set to, which is what almost everybody wants. */
    System({ L.themeSystem }, { L.themeSystemDetail }),
    Light({ L.themeLight }, { L.themeLightDetail }),
    Dark({ L.themeDark }, { L.themeDarkDetail }),
    ;

    /** The one word shown in a segment, in the UI language. */
    val label: String get() = labelText()

    /** The sentence under the picker, in the UI language. */
    val description: String get() = descriptionText()

    /**
     * The same sentence for a television, which has no system setting to match.
     *
     * Android TV has no light mode of its own, so "System" cannot mean "follow the device" there.
     * It means the dark panel, which is the right default for a screen watched in the evening.
     */
    val tvDescription: String
        get() = when (this) {
            System -> L.themeSystemTvDetail
            Light -> L.themeLightDetail
            Dark -> L.themeDarkDetail
        }

    companion object {
        val Default = System

        fun from(stored: String?): ThemeChoice =
            entries.firstOrNull { it.name == stored } ?: Default
    }
}
