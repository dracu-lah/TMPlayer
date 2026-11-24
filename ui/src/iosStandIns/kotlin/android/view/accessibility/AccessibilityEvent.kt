package android.view.accessibility

/** An accessibility event as Android names it; the iOS port only carries the type. */
class AccessibilityEvent {
    var eventType: Int = 0

    companion object {
        const val TYPE_VIEW_ACCESSIBILITY_FOCUSED = 0x8000
        const val TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED = 0x10000
    }
}
