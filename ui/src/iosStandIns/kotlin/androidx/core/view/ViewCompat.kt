package androidx.core.view

import android.view.View
import android.view.ViewGroup

/** What a View does with the window's insets as they reach it, androidx's. */
fun interface OnApplyWindowInsetsListener {
    fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat
}

/** androidx's helpers over a View, on the toolkit's own Views. */
object ViewCompat {
    /** The insets of the window the View is in: iOS's safe area, once the View is attached. */
    fun getRootWindowInsets(view: View): WindowInsetsCompat? = if (view.isAttachedToWindow) WindowInsetsCompat.ofKeyWindow() else null

    fun getStateDescription(view: View): CharSequence? = view.stateDescription

    fun setStateDescription(view: View, stateDescription: CharSequence?) {
        view.stateDescription = stateDescription
    }

    /**
     * Names what an accessibility action does on [view] (VoiceOver's label), androidx's; with
     * [command], what performing it does instead of the View's own.
     */
    fun replaceAccessibilityAction(
        view: View,
        replacedAction: androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat,
        label: CharSequence?,
        command: androidx.core.view.accessibility.AccessibilityViewCommand?,
    ) {
        view.accessibilityActionLabels[replacedAction.id] = label
    }

    /** Where the system's edge gestures stand aside, Android 10's; iOS's edge swipe is the screens' own. */
    fun setSystemGestureExclusionRects(view: View, rects: List<android.graphics.Rect>) = view.setSystemGestureExclusionRects(rects)

    /** Called with the window's insets each time they reach [view]: on attach, a size change, or asked for. */
    fun setOnApplyWindowInsetsListener(view: View, listener: OnApplyWindowInsetsListener?) {
        view.windowInsetsListener = listener
    }

    /**
     * The insets handed down the tree from [view], as Android's dispatch: a View's listener answers
     * for it and what it returns goes on to its children.
     */
    fun dispatchApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val listener = view.windowInsetsListener as? OnApplyWindowInsetsListener
        val after = listener?.onApplyWindowInsets(view, insets) ?: insets
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) dispatchApplyWindowInsets(view.getChildAt(i), after)
        }
        return after
    }

    /** Hands the window's insets down again from the top of [view]'s tree. */
    fun requestApplyInsets(view: View) {
        var top = view
        while (true) top = top.parent as? View ?: break
        if (!top.isAttachedToWindow) return
        WindowInsetsCompat.ofKeyWindow()?.let { dispatchApplyWindowInsets(top, it) }
    }
}

/** androidx's KTX: new padding on the sides named, the rest as it is. */
fun View.updatePadding(
    left: Int = paddingLeft,
    top: Int = paddingTop,
    right: Int = paddingRight,
    bottom: Int = paddingBottom,
) = setPadding(left, top, right, bottom)

/** androidx's KTX: changes the LayoutParams in place and sets them again. */
inline fun <reified T : ViewGroup.LayoutParams> View.updateLayoutParams(block: T.() -> Unit) {
    val params = layoutParams as T
    block(params)
    layoutParams = params
}

/** androidx's KTX: [action] once, before the View is next drawn. */
inline fun View.doOnPreDraw(crossinline action: (view: View) -> Unit) {
    post { action(this) }
}
