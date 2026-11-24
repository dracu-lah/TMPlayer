package com.tmplayer.desktop.ui

import com.tmplayer.data.SettingsStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.os.SystemAccessibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * "Follow the system" for the two accessibility switches the OS also has (WP-50): high contrast and
 * reduce motion. The viewer's own choice always wins; a shared store flag that is already on
 * (a choice made before the desktop followed the system, or a phone's backup) counts as a choice
 * for on; otherwise the OS decides, and an OS that says nothing means off.
 */
internal object SystemDefaults {

    /** High contrast as the window draws it, following the choice, the shared flag and the OS as they change. */
    fun highContrast(settings: SettingsStore, prefs: DesktopPrefs): Flow<Boolean> = combine(
        settings.highContrast,
        prefs.state.map { it.highContrast }.distinctUntilChanged(),
        SystemAccessibility.reading.map { it.highContrast }.distinctUntilChanged(),
        { stored, chosen, system -> effective(chosen, stored, system) },
    ).distinctUntilChanged()

    /** Reduce motion as the app animates, the same way. */
    fun reduceMotion(settings: SettingsStore, prefs: DesktopPrefs): Flow<Boolean> = combine(
        settings.reduceMotion,
        prefs.state.map { it.reduceMotion }.distinctUntilChanged(),
        SystemAccessibility.reading.map { it.reduceMotion }.distinctUntilChanged(),
        { stored, chosen, system -> effective(chosen, stored, system) },
    ).distinctUntilChanged()

    /** What is shown selected on the row: the choice, else on for a stored on, else "follow the system" (null). */
    fun shown(chosen: Boolean?, stored: Boolean): Boolean? = chosen ?: if (stored) true else null

    /** What the app does: [shown] when it is a choice, else the OS's reading. */
    fun effective(chosen: Boolean?, stored: Boolean, system: Boolean?): Boolean = shown(chosen, stored) ?: (system == true)
}
