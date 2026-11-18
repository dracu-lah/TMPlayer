package com.tmplayer

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeyGuardTest {
    @Test
    fun takesSearchOnly() {
        assertTrue(SearchKeyGuard.swallows(KeyEvent.KEYCODE_SEARCH))
        for (k in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_ASSIST,
        )) assertFalse(k.toString(), SearchKeyGuard.swallows(k))
    }
}
