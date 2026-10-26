package java.awt.event

import java.awt.AWTEvent

class MouseEvent(id: Int) : AWTEvent(id) {
    companion object {
        const val MOUSE_PRESSED = 501
    }
}

/** A key as AWT numbers it; Compose for Web never hands one out, so the checks on it stay false. */
class KeyEvent(id: Int, val keyCode: Int) : AWTEvent(id) {
    /** AWT's description of the event, where the desktop reads a remote's raw key code; none here. */
    fun paramString(): String = ""

    companion object {
        const val KEY_PRESSED = 401
        const val VK_TAB = 9
        const val VK_PAGE_UP = 33
        const val VK_PAGE_DOWN = 34
        const val VK_END = 35
        const val VK_HOME = 36
        const val VK_LEFT = 37
        const val VK_UP = 38
        const val VK_RIGHT = 39
        const val VK_DOWN = 40
        const val VK_CONTEXT_MENU = 525
        const val VK_KP_UP = 224
        const val VK_KP_DOWN = 225
        const val VK_KP_LEFT = 226
        const val VK_KP_RIGHT = 227
    }
}
