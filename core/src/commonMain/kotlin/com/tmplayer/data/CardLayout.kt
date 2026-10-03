package com.tmplayer.data

/**
 * How a screen full of cards is arranged.
 *
 * Both browsing screens hold a list of cards and differ only in how many fit across, so one type
 * covers both. [List] puts one wide row per item, giving a long release file name room to be read
 * in full; [Grid] trades that width for artwork and fewer presses to reach the bottom of a chat.
 */
enum class CardLayout {
    List,
    Grid,
    ;

    fun toggled(): CardLayout = if (this == List) Grid else List

    companion object {
        /**
         * Reads a stored name back, falling back to [fallback] for anything unrecognised.
         *
         * Stored by name rather than as a boolean so the preference stays readable, and so a third
         * arrangement later is a new entry rather than a migration.
         */
        fun decode(stored: String?, fallback: CardLayout): CardLayout =
            entries.firstOrNull { it.name == stored } ?: fallback
    }
}
