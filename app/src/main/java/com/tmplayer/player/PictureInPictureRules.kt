package com.tmplayer.player

import android.os.Build

/**
 * When leaving the app puts the video into picture in picture by itself (HX9). The manual entries
 * (More, the row) never ask these questions: the viewer asked for those, whatever the setting.
 */
object PictureInPictureRules {

    /**
     * Android 12 and later: the value for `setAutoEnterEnabled`. Never on a television (Home there
     * means leaving the video), never with the setting off, and never over a pause, a sheet or
     * the lock.
     */
    fun autoEnter(settingOn: Boolean, tv: Boolean, playing: Boolean, sheetUp: Boolean, locked: Boolean): Boolean =
        settingOn && !tv && playing && !sheetUp && !locked

    /**
     * Android 8 to 11: whether `onUserLeaveHint` enters. From 12 the system enters on its own
     * (see [autoEnter]) and asking again would only race it.
     */
    fun entersOnLeaveHint(
        sdk: Int,
        settingOn: Boolean,
        tv: Boolean,
        playing: Boolean,
        casting: Boolean,
        sheetUp: Boolean,
    ): Boolean =
        sdk in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            settingOn && !tv && playing && !casting && !sheetUp
}
