package android.view

/**
 * The listeners of a View tree, Android's: for layout, drawing and a change of focus. They are kept
 * and removed as Android's are. Nothing calls them on iOS: the tree is drawn by one host that
 * lays out and draws it by itself, and a focus moved by a key or a remote has no such callback, so
 * what Android's player does with them (a television's row remembering its last button) does not
 * happen here, on a screen driven by touch.
 */
class ViewTreeObserver internal constructor() {
    fun interface OnGlobalFocusChangeListener {
        fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?)
    }

    fun interface OnGlobalLayoutListener {
        fun onGlobalLayout()
    }

    fun interface OnPreDrawListener {
        fun onPreDraw(): Boolean
    }

    private val focus = ArrayList<OnGlobalFocusChangeListener>()
    private val layout = ArrayList<OnGlobalLayoutListener>()
    private val draw = ArrayList<OnPreDrawListener>()

    val isAlive: Boolean get() = true

    fun addOnGlobalFocusChangeListener(listener: OnGlobalFocusChangeListener) {
        focus += listener
    }

    fun removeOnGlobalFocusChangeListener(listener: OnGlobalFocusChangeListener) {
        focus -= listener
    }

    fun addOnGlobalLayoutListener(listener: OnGlobalLayoutListener) {
        layout += listener
    }

    fun removeOnGlobalLayoutListener(listener: OnGlobalLayoutListener) {
        layout -= listener
    }

    fun addOnPreDrawListener(listener: OnPreDrawListener) {
        draw += listener
    }

    fun removeOnPreDrawListener(listener: OnPreDrawListener) {
        draw -= listener
    }
}
