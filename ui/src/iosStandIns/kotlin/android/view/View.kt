package android.view

import android.content.Context

/**
 * A key press as Android reports it. An iPad keyboard's keys reach the player's activity this way
 * (com.tmplayer.ios.AndroidViewHost), held keys repeating as Android repeats them: [repeatCount]
 * counts the repeats, and the first, at the long press timeout, is the long press.
 */
class KeyEvent(
    val action: Int,
    val keyCode: Int,
    val repeatCount: Int = 0,
    /** When the key went down. */
    val downTime: Long = android.os.SystemClock.uptimeMillis(),
) {
    val isLongPress: Boolean get() = repeatCount == 1
    val eventTime: Long = android.os.SystemClock.uptimeMillis()
    val source: Int get() = 0

    /** The modifiers held with the key, and the character it typed (0 for none): an iPad keyboard's Shift, Control and Option arrive with later work. */
    val isShiftPressed: Boolean get() = false
    val isCtrlPressed: Boolean get() = false
    val isAltPressed: Boolean get() = false
    val isMetaPressed: Boolean get() = false
    val unicodeChar: Int get() = 0
    var isTracking: Boolean = false
        private set
    val isCanceled: Boolean get() = false
    fun startTracking() {
        isTracking = true
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_ENTER = 66
        const val KEYCODE_BACK = 4
        const val KEYCODE_SEARCH = 84
        const val KEYCODE_MENU = 82
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_0 = 7
        const val KEYCODE_1 = 8
        const val KEYCODE_2 = 9
        const val KEYCODE_3 = 10
        const val KEYCODE_4 = 11
        const val KEYCODE_5 = 12
        const val KEYCODE_6 = 13
        const val KEYCODE_7 = 14
        const val KEYCODE_8 = 15
        const val KEYCODE_9 = 16
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_C = 31
        const val KEYCODE_F = 34
        const val KEYCODE_J = 38
        const val KEYCODE_K = 39
        const val KEYCODE_L = 40
        const val KEYCODE_M = 41
        const val KEYCODE_SPACE = 62
        const val KEYCODE_MEDIA_STOP = 86
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MEDIA_REWIND = 89
        const val KEYCODE_MEDIA_FAST_FORWARD = 90
        const val KEYCODE_PAGE_UP = 92
        const val KEYCODE_PAGE_DOWN = 93
        const val KEYCODE_BUTTON_A = 96
        const val KEYCODE_BUTTON_B = 97
        const val KEYCODE_BUTTON_X = 99
        const val KEYCODE_BUTTON_Y = 100
        const val KEYCODE_BUTTON_L1 = 102
        const val KEYCODE_BUTTON_R1 = 103
        const val KEYCODE_BUTTON_START = 108
        const val KEYCODE_BUTTON_SELECT = 109
        const val KEYCODE_MOVE_HOME = 122
        const val KEYCODE_MOVE_END = 123
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        const val KEYCODE_NUMPAD_0 = 144
        const val KEYCODE_NUMPAD_1 = 145
        const val KEYCODE_NUMPAD_2 = 146
        const val KEYCODE_NUMPAD_3 = 147
        const val KEYCODE_NUMPAD_4 = 148
        const val KEYCODE_NUMPAD_5 = 149
        const val KEYCODE_NUMPAD_6 = 150
        const val KEYCODE_NUMPAD_7 = 151
        const val KEYCODE_NUMPAD_8 = 152
        const val KEYCODE_NUMPAD_9 = 153
        const val KEYCODE_NUMPAD_ENTER = 160
        const val KEYCODE_VOLUME_MUTE = 164
        const val KEYCODE_INFO = 165
        const val KEYCODE_CHANNEL_UP = 166
        const val KEYCODE_CHANNEL_DOWN = 167
        const val KEYCODE_MEDIA_SKIP_FORWARD = 272
        const val KEYCODE_MEDIA_SKIP_BACKWARD = 273
        const val KEYCODE_CAPTIONS = 175
        const val KEYCODE_MEDIA_AUDIO_TRACK = 222
        const val KEYCODE_GUIDE = 172
        const val KEYCODE_HEADSETHOOK = 79
        const val KEYCODE_3D_MODE = 206
        const val KEYCODE_TV_ZOOM_MODE = 255
        const val KEYCODE_A = 29
        const val KEYCODE_Z = 54
        const val KEYCODE_COMMA = 55
        const val KEYCODE_PERIOD = 56
        const val KEYCODE_TAB = 61
        const val KEYCODE_DEL = 67
        const val KEYCODE_MINUS = 69
        const val KEYCODE_EQUALS = 70
        const val KEYCODE_LEFT_BRACKET = 71
        const val KEYCODE_RIGHT_BRACKET = 72
        const val KEYCODE_SLASH = 76
        const val KEYCODE_PLUS = 81
        const val KEYCODE_FORWARD_DEL = 112
    }
}

/**
 * A touch, Android's: every finger on the screen, each with its pointer id and where it is in the
 * receiving View's coordinates (and on the screen, the raw position), and what happened. A finger
 * going down or up while others stay is ACTION_POINTER_DOWN or ACTION_POINTER_UP, with the
 * finger's index in [actionIndex] and in [action]'s upper byte, as Android encodes it. A pointer's
 * motion that is not a touch (an iPad trackpad's hover or scroll) carries its source and axes.
 */
class MotionEvent private constructor(
    actionMasked: Int,
    private val fromSource: Int,
    private val ids: IntArray,
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val rawXs: FloatArray,
    private val rawYs: FloatArray,
    actionIndex: Int,
    val downTime: Long,
    val eventTime: Long,
    private val axes: Map<Int, Float>,
) {
    /** One finger, or one mouse pointer, at [x], [y]. */
    constructor(
        actionMasked: Int,
        fromSource: Int = InputDevice.SOURCE_TOUCHSCREEN,
        x: Float = 0f,
        y: Float = 0f,
        downTime: Long = android.os.SystemClock.uptimeMillis(),
        eventTime: Long = android.os.SystemClock.uptimeMillis(),
    ) : this(actionMasked, fromSource, intArrayOf(0), floatArrayOf(x), floatArrayOf(y), floatArrayOf(x), floatArrayOf(y), 0, downTime, eventTime, emptyMap())

    var actionMasked: Int = actionMasked
        private set

    /** Which pointer a POINTER_DOWN or POINTER_UP is about. */
    var actionIndex: Int = actionIndex
        private set

    /** The masked action with the pointer's index in its upper byte, as Android packs it. */
    val action: Int
        get() = if (actionMasked == ACTION_POINTER_DOWN || actionMasked == ACTION_POINTER_UP) {
            actionMasked or (actionIndex shl ACTION_POINTER_INDEX_SHIFT)
        } else {
            actionMasked
        }

    val pointerCount: Int get() = ids.size
    val source: Int get() = fromSource

    /** The first pointer's place, as Android's getX() and getY(). */
    val x: Float get() = xs[0]
    val y: Float get() = ys[0]

    /** Where the first pointer is on the screen, the same in every View it passes through. */
    val rawX: Float get() = rawXs[0]
    val rawY: Float get() = rawYs[0]

    val buttonState: Int get() = if (fromSource == InputDevice.SOURCE_MOUSE && actionMasked != ACTION_HOVER_MOVE) BUTTON_PRIMARY else 0

    fun getX(pointerIndex: Int): Float = xs[pointerIndex]

    fun getY(pointerIndex: Int): Float = ys[pointerIndex]

    fun getRawX(pointerIndex: Int): Float = rawXs[pointerIndex]

    fun getRawY(pointerIndex: Int): Float = rawYs[pointerIndex]

    fun getPointerId(pointerIndex: Int): Int = ids[pointerIndex]

    /** The index of the pointer with id [pointerId] in this event, or -1, as Android's. */
    fun findPointerIndex(pointerId: Int): Int = ids.indexOf(pointerId)

    fun getToolType(pointerIndex: Int): Int = if (fromSource == InputDevice.SOURCE_MOUSE) TOOL_TYPE_MOUSE else TOOL_TYPE_FINGER

    fun getAxisValue(axis: Int): Float = when (axis) {
        AXIS_X -> x
        AXIS_Y -> y
        else -> axes[axis] ?: 0f
    }

    fun getAxisValue(axis: Int, pointerIndex: Int): Float = when (axis) {
        AXIS_X -> xs[pointerIndex]
        AXIS_Y -> ys[pointerIndex]
        else -> axes[axis] ?: 0f
    }

    fun isFromSource(source: Int): Boolean = source == fromSource

    /** Android's setAction: the masked action and, for a pointer going down or up, its index. */
    fun setAction(action: Int) {
        actionMasked = action and ACTION_MASK
        actionIndex = (action and ACTION_POINTER_INDEX_MASK) shr ACTION_POINTER_INDEX_SHIFT
    }

    /** Moves the first pointer to [x], [y] and the others with it, as Android's. */
    fun setLocation(x: Float, y: Float) = offsetLocation(x - xs[0], y - ys[0])

    /** Moves every pointer by the same amount, into another View's coordinates. */
    fun offsetLocation(deltaX: Float, deltaY: Float) {
        for (i in xs.indices) {
            xs[i] += deltaX
            ys[i] += deltaY
        }
    }

    fun recycle() = Unit

    companion object {
        const val ACTION_MASK = 0xff
        const val ACTION_POINTER_INDEX_MASK = 0xff00
        const val ACTION_POINTER_INDEX_SHIFT = 8
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_OUTSIDE = 4
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_MOVE = 7
        const val ACTION_SCROLL = 8
        const val ACTION_HOVER_ENTER = 9
        const val ACTION_HOVER_EXIT = 10
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_VSCROLL = 9
        const val AXIS_HSCROLL = 10
        const val BUTTON_PRIMARY = 1
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_MOUSE = 3

        fun obtain(other: MotionEvent): MotionEvent = MotionEvent(
            other.actionMasked, other.fromSource, other.ids.copyOf(), other.xs.copyOf(), other.ys.copyOf(),
            other.rawXs.copyOf(), other.rawYs.copyOf(), other.actionIndex, other.downTime, other.eventTime, other.axes,
        )

        fun obtain(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, metaState: Int): MotionEvent =
            MotionEvent(action and ACTION_MASK, InputDevice.SOURCE_TOUCHSCREEN, x, y, downTime, eventTime)

        /**
         * For the iOS host: the fingers on the screen now, in window pixels, each with its pointer
         * id; [actionIndex] names the one a POINTER_DOWN or POINTER_UP is about.
         */
        fun touches(actionMasked: Int, actionIndex: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, downTime: Long): MotionEvent =
            MotionEvent(
                actionMasked, InputDevice.SOURCE_TOUCHSCREEN, ids, xs.copyOf(), ys.copyOf(), xs.copyOf(), ys.copyOf(),
                actionIndex, downTime, android.os.SystemClock.uptimeMillis(), emptyMap(),
            )

        /** For the iOS host: a trackpad's or mouse's pointer moving or scrolling, with its scroll axes. */
        fun pointer(actionMasked: Int, x: Float, y: Float, vscroll: Float = 0f, hscroll: Float = 0f): MotionEvent {
            val now = android.os.SystemClock.uptimeMillis()
            return MotionEvent(
                actionMasked, InputDevice.SOURCE_MOUSE, intArrayOf(0), floatArrayOf(x), floatArrayOf(y), floatArrayOf(x), floatArrayOf(y),
                0, now, now, mapOf(AXIS_VSCROLL to vscroll, AXIS_HSCROLL to hscroll),
            )
        }
    }
}

object InputDevice {
    const val SOURCE_TOUCHSCREEN = 0x00001002
    const val SOURCE_MOUSE = 0x00002002
    const val SOURCE_DPAD = 0x00000201
}

class WindowManager {
    class LayoutParams {
        var dimAmount: Float = 0f
        var flags: Int = 0
        var windowAnimations: Int = 0
        var screenBrightness: Float = -1f
        var preferredDisplayModeId: Int = 0
        var layoutInDisplayCutoutMode: Int = 0

        companion object {
            const val FLAG_KEEP_SCREEN_ON = 0x00000080
            const val FLAG_DIM_BEHIND = 0x00000002
            const val FLAG_SECURE = 0x00002000
            const val BRIGHTNESS_OVERRIDE_NONE = -1f
            const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT = 0
            const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1
            const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3
            /** iOS shows the keyboard only for a field a person taps, so the hidden state is its own. */
            const val SOFT_INPUT_STATE_ALWAYS_HIDDEN = 3
        }
    }
}

/**
 * A dialog or activity window. Its brightness is the device's and keeping the screen on is iOS's
 * idle timer (docs/IOS.md, the system jobs); its other attributes are kept for whoever
 * reads them.
 */
class Window internal constructor() {
    val context: android.content.Context get() = androidx.compose.ui.platform.IosAppContext

    /**
     * Android's system bars hidden in this window (WindowInsetsControllerCompat): on iOS the status
     * bar and the home indicator of the screen that shows it, which [onSystemBars] tells.
     */
    var systemBarsHidden: Boolean = false
        internal set(value) {
            if (field == value) return
            field = value
            onSystemBars?.invoke()
        }

    /** The shell's: the system bars were hidden or shown. */
    var onSystemBars: (() -> Unit)? = null

    // The device's brightness before a window set its own, put back when it lets go.
    private var systemBrightness: Double? = null

    /** The window's brightness on iOS is the device's (maintainer, 2026-11-11): set while asked, put back after. */
    private fun applyBrightness(level: Float) {
        val screen = platform.UIKit.UIScreen.mainScreen
        if (level >= 0f) {
            if (systemBrightness == null) systemBrightness = screen.brightness
            screen.brightness = level.coerceIn(0f, 1f).toDouble()
        } else {
            restoreBrightness()
        }
    }

    /** Puts back the device's own brightness, as Android does when the window goes. */
    fun restoreBrightness() {
        systemBrightness?.let { platform.UIKit.UIScreen.mainScreen.brightness = it }
        systemBrightness = null
    }

    var attributes: WindowManager.LayoutParams = WindowManager.LayoutParams()
        set(value) {
            field = value
            applyBrightness(value.screenBrightness)
        }
    fun setDimAmount(amount: Float) {
        attributes.dimAmount = amount
    }
    fun setWindowAnimations(resId: Int) = Unit
    /** The window's flags, Android's; [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] reaches iOS. */
    var flags: Int = 0
        private set

    fun addFlags(flags: Int) = setFlags(flags, flags)

    /** The keyboard's state when the window opens: iOS has no such setting (WindowManager.LayoutParams). */
    fun setSoftInputMode(mode: Int) = Unit

    fun clearFlags(flags: Int) = setFlags(0, flags)

    fun setFlags(flags: Int, mask: Int) {
        this.flags = (this.flags and mask.inv()) or (flags and mask)
        applyKeepScreenOn()
    }

    private var shown = true

    /** The device stays awake while any window in front asks it to, as on Android. */
    private fun applyKeepScreenOn() {
        val on = shown && flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (on) keepingOn.add(this) else keepingOn.remove(this)
        platform.UIKit.UIApplication.sharedApplication.idleTimerDisabled = keepingOn.isNotEmpty()
    }

    /**
     * For the iOS shell: the activity behind this window in front or not. As on Android, a window
     * out of sight neither keeps the screen on nor holds its brightness; back in front, both are
     * as it set them.
     */
    fun shellShown(shown: Boolean) {
        this.shown = shown
        applyKeepScreenOn()
        if (shown) applyBrightness(attributes.screenBrightness) else restoreBrightness()
    }

    /** For the iOS shell: the activity is gone, and with it what its window asked of the device. */
    fun shellReset() {
        flags = 0
        shown = true
        applyKeepScreenOn()
        restoreBrightness()
        attributes = WindowManager.LayoutParams()
    }
    fun setBackgroundDrawableResource(resId: Int) = Unit
    fun setBackgroundDrawable(drawable: android.graphics.drawable.Drawable?) = Unit
    val decorView: View get() = View.Shared
    var statusBarColor: Int = 0
    var navigationBarColor: Int = 0

    companion object {
        val Shared = Window()

        private val keepingOn = HashSet<Window>()

        /** A window of an activity's own (android.app.Activity.window). */
        fun forActivity(): Window = Window()
    }
}

object HapticFeedbackConstants {
    const val CLOCK_TICK = 4
    const val CONFIRM = 16
    const val REJECT = 17
    const val KEYBOARD_TAP = 3
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val CONTEXT_CLICK = 6
    const val SEGMENT_TICK = 26
    const val SEGMENT_FREQUENT_TICK = 27
    const val GESTURE_START = 12
    const val GESTURE_END = 13
    const val TOGGLE_ON = 21
    const val TOGGLE_OFF = 22
}

class TextureView(context: android.content.Context?) : View(context, null, 0)
