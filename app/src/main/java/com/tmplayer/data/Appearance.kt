package com.tmplayer.data

/**
 * What the viewer chose in Settings, under Appearance.
 *
 * Stored by name rather than by ordinal, so reordering this list later cannot silently move
 * everybody's setting to the entry that happens to sit at their old number.
 */
/**
 * Each entry carries two names. [label] is the one word that goes in a segment, short enough that
 * three of them fit across a phone without any being cut off. [description] is the sentence that
 * says what the word actually means, for the line under the picker and for a screen reader.
 */
enum class ThemeChoice(val label: String, val description: String) {
    /** Whatever the phone itself is set to, which is what almost everybody wants. */
    System("System", "Match the phone"),
    Light("Light", "Always light"),
    Dark("Dark", "Always dark"),
    ;

    companion object {
        val Default = System

        fun from(stored: String?): ThemeChoice =
            entries.firstOrNull { it.name == stored } ?: Default
    }
}
