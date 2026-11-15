package com.tmplayer.player

import android.view.KeyEvent
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackKeysTest {

    @Test
    fun `the caption key opens the subtitles picker and the audio key the audio picker`() {
        assertEquals(C.TRACK_TYPE_TEXT, TrackKeys.pickerFor(KeyEvent.KEYCODE_CAPTIONS))
        assertEquals(C.TRACK_TYPE_AUDIO, TrackKeys.pickerFor(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK))
    }

    @Test
    fun `the keys the player already answers are left alone`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_BACK,
        ).forEach { assertNull(TrackKeys.pickerFor(it)) }
    }
}
