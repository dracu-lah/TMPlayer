package com.tmplayer.desktop.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where a launch asks the window to go (WP-52): the Linux launcher's actions (Continue watching,
 * Downloads, Search) start `tmplayer --go=...`, and a second launch hands the same argument to the
 * running window. The place waits here until a shell is up to take it; a locked profile's shell
 * takes it too, behind its PIN, so nothing opens that the PIN has not let in.
 */
object LaunchGo {

    enum class Place(val arg: String) {
        Resume("resume"),
        Downloads("downloads"),
        Search("search"),
        ;

        companion object {
            /** `--go=downloads` (any case) as a place; null for any other argument. */
            fun from(argument: String): Place? {
                val value = argument.trim().lowercase().removePrefix("--go=").takeIf { argument.trim().lowercase().startsWith("--go=") }
                return entries.firstOrNull { it.arg == value }
            }
        }
    }

    /** The place nobody has taken yet. */
    val pending = MutableStateFlow<Place?>(null)

    /** Takes [place] to [shell]: Resume is Continue watching, the History page's first tab. */
    fun apply(place: Place, shell: ShellState) {
        when (place) {
            Place.Resume -> shell.go(Destination.History)
            Place.Downloads -> shell.go(Destination.Downloads)
            Place.Search -> shell.paletteOpen = true
        }
    }
}
