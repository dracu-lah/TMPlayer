package com.tmplayer.data

/**
 * What the viewer chose in Settings, under Appearance.
 *
 * Stored by name rather than by ordinal, so reordering this list later cannot silently move
 * everybody's setting to the entry that happens to sit at their old number.
 */
enum class ThemeChoice(val label: String) {
    /** Whatever the phone itself is set to, which is what almost everybody wants. */
    System("Match the phone"),
    Light("Light"),
    Dark("Dark"),
    ;

    companion object {
        val Default = System

        fun from(stored: String?): ThemeChoice =
            entries.firstOrNull { it.name == stored } ?: Default
    }
}
