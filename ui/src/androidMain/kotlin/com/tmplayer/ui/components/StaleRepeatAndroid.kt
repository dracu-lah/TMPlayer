package com.tmplayer.ui.components

import androidx.compose.ui.input.key.KeyEvent

/** A repeat (a held key) that was made more than [STALE_REPEAT_MS] ago and is only now handled. */
fun platformStaleRepeat(event: KeyEvent): Boolean {
    val native = event.nativeKeyEvent
    return native.repeatCount > 0 && android.os.SystemClock.uptimeMillis() - native.eventTime > STALE_REPEAT_MS
}
