package com.tmplayer.player

import com.tmplayer.i18n.Strings

/**
 * What the player says once, the first time a video plays on a phone: the gestures the screen no
 * longer has buttons for. The tour does not teach them up front (CP42); they are told at the moment
 * they are useful, and only the ones that are switched on in Settings.
 */
object FirstRunHint {

    /** One line per gesture: the double tap always, then each swipe that is on. */
    fun phone(prefs: TouchPrefs, s: Strings): List<String> = buildList {
        add(s.playerDoubleTapHint(prefs.doubleTapMs / 1000))
        if (prefs.seekGesture) add(s.settingsSeekGesture)
        if (prefs.brightnessGesture) add(s.settingsBrightnessGesture)
        if (prefs.volumeGesture) add(s.settingsVolumeGesture)
    }
}
