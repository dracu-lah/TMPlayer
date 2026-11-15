package com.tmplayer.player

import android.view.KeyEvent
import androidx.media3.common.C

/**
 * The remote keys that open a track picker. Many remotes carry a CC or Subtitle key and an
 * audio-language key; each opens the picker the row's button opens, so the viewer is one press
 * from the list instead of walking the row.
 */
object TrackKeys {
    /** [C.TRACK_TYPE_TEXT] or [C.TRACK_TYPE_AUDIO] for the picker [keyCode] opens, else null. */
    fun pickerFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_CAPTIONS -> C.TRACK_TYPE_TEXT
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> C.TRACK_TYPE_AUDIO
        else -> null
    }
}
