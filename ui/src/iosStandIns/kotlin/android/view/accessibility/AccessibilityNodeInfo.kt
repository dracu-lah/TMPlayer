package android.view.accessibility

/** What a screen reader is told of a View, Android's; the iOS port carries the three fields screens set. */
class AccessibilityNodeInfo {
    var className: CharSequence? = null
    var contentDescription: CharSequence? = null
    var stateDescription: CharSequence? = null
}
