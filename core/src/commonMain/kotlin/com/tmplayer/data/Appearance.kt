package com.tmplayer.data

/**
 * What the viewer chose in Settings, under Appearance.
 *
 * Stored by name rather than by ordinal, so reordering this list cannot silently move everybody's
 * setting to whichever entry now sits at their old number.
 *
 * @property label the one word shown in a segment, short enough that three fit across a phone
 * @property description the sentence under the picker, also read out by a screen reader
 */
enum class ThemeChoice(val label: String, val description: String) {
    /** Whatever the phone itself is set to, which is what almost everybody wants. */
    System("System", "Match the phone"),
    Light("Light", "Always light"),
    Dark("Dark", "Always dark"),
    ;

    /**
     * The same sentence for a television, which has no system setting to match.
     *
     * Android TV has no light mode of its own, so "System" cannot mean "follow the device" there.
     * It means the dark panel, which is the right default for a screen watched in the evening.
     */
    val tvDescription: String
        get() = when (this) {
            System -> "The usual dark panel"
            Light -> "Always light"
            Dark -> "Always dark"
        }

    companion object {
        val Default = System

        fun from(stored: String?): ThemeChoice =
            entries.firstOrNull { it.name == stored } ?: Default
    }
}
