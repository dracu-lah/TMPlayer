package com.tmplayer.ios

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A hardware keyboard's keys (an iPad's) as Android's key events, for an activity in front. iOS
 * hands each press and release once, with the key's HID usage; Android repeats a held key, the
 * first repeat at the long press timeout and then one every 50 ms, and the player counts on it
 * (holding an arrow keeps seeking). An Esc nobody takes falls back to Back, as Android's keyboards
 * map it, and the rest of that press is Back's.
 */
internal class KeyInput(private val send: (KeyEvent) -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val held = HashMap<Int, Job>()
    private var escapeIsBack = false

    /** A key went down; true when the activity took it (or the key is not one Android knows). */
    fun down(usage: Long): Boolean {
        val code = HidKeys.android(usage) ?: return false
        if (code in held) return true
        val downTime = android.os.SystemClock.uptimeMillis()
        val took = press(code, 0, downTime)
        held[code] = scope.launch {
            delay(KEY_REPEAT_TIMEOUT_MS)
            var repeat = 1
            while (true) {
                press(code, repeat++, downTime)
                delay(KEY_REPEAT_DELAY_MS)
            }
        }
        return took
    }

    fun up(usage: Long): Boolean {
        val code = HidKeys.android(usage) ?: return false
        held.remove(code)?.cancel() ?: return false
        if (code == KeyEvent.KEYCODE_ESCAPE && escapeIsBack) {
            escapeIsBack = false
            return guarded(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        return guarded(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Every held key let go, as when the screen goes away with a finger still on a key. */
    fun releaseAll() {
        held.values.forEach { it.cancel() }
        held.clear()
        escapeIsBack = false
    }

    private fun press(code: Int, repeat: Int, downTime: Long): Boolean {
        val escape = code == KeyEvent.KEYCODE_ESCAPE
        if (escape && repeat == 0) escapeIsBack = false
        if (escape && escapeIsBack) return guarded(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, repeat, downTime))
        val took = guarded(KeyEvent(KeyEvent.ACTION_DOWN, code, repeat, downTime))
        if (took || !escape || repeat > 0) return took
        escapeIsBack = true
        return guarded(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, downTime))
    }

    /** A key the activity fails on is logged and taken, never thrown into UIKit. */
    private fun guarded(event: KeyEvent): Boolean = runCatching { send(event) }
        .onFailure { com.tmplayer.platform.Logger.w("KeyInput", "key ${event.keyCode} failed", it) }
        .getOrDefault(true)

    private companion object {
        const val KEY_REPEAT_TIMEOUT_MS = 500L
        const val KEY_REPEAT_DELAY_MS = 50L
    }
}

/** iOS's keyboard HID usages (UIKeyboardHIDUsage) to Android's key codes. */
internal object HidKeys {
    fun android(usage: Long): Int? = when (usage) {
        in 4L..29L -> KeyEvent.KEYCODE_A + (usage - 4L).toInt()
        in 30L..38L -> KeyEvent.KEYCODE_1 + (usage - 30L).toInt()
        39L -> KeyEvent.KEYCODE_0
        40L -> KeyEvent.KEYCODE_ENTER
        41L -> KeyEvent.KEYCODE_ESCAPE
        42L -> KeyEvent.KEYCODE_DEL
        43L -> KeyEvent.KEYCODE_TAB
        44L -> KeyEvent.KEYCODE_SPACE
        45L -> KeyEvent.KEYCODE_MINUS
        46L -> KeyEvent.KEYCODE_EQUALS
        47L -> KeyEvent.KEYCODE_LEFT_BRACKET
        48L -> KeyEvent.KEYCODE_RIGHT_BRACKET
        54L -> KeyEvent.KEYCODE_COMMA
        55L -> KeyEvent.KEYCODE_PERIOD
        56L -> KeyEvent.KEYCODE_SLASH
        74L -> KeyEvent.KEYCODE_MOVE_HOME
        75L -> KeyEvent.KEYCODE_PAGE_UP
        76L -> KeyEvent.KEYCODE_FORWARD_DEL
        77L -> KeyEvent.KEYCODE_MOVE_END
        78L -> KeyEvent.KEYCODE_PAGE_DOWN
        79L -> KeyEvent.KEYCODE_DPAD_RIGHT
        80L -> KeyEvent.KEYCODE_DPAD_LEFT
        81L -> KeyEvent.KEYCODE_DPAD_DOWN
        82L -> KeyEvent.KEYCODE_DPAD_UP
        86L -> KeyEvent.KEYCODE_MINUS
        87L -> KeyEvent.KEYCODE_PLUS
        88L -> KeyEvent.KEYCODE_NUMPAD_ENTER
        in 89L..97L -> KeyEvent.KEYCODE_NUMPAD_1 + (usage - 89L).toInt()
        98L -> KeyEvent.KEYCODE_NUMPAD_0
        99L -> KeyEvent.KEYCODE_PERIOD
        127L -> KeyEvent.KEYCODE_VOLUME_MUTE
        128L -> KeyEvent.KEYCODE_VOLUME_UP
        129L -> KeyEvent.KEYCODE_VOLUME_DOWN
        else -> null
    }
}
