package androidx.core.view.accessibility

/** What accessibility services read of a View, androidx's; its actions, by Android's ids. */
class AccessibilityNodeInfoCompat private constructor() {
    class AccessibilityActionCompat(val id: Int, val label: CharSequence?) {
        companion object {
            val ACTION_CLICK = AccessibilityActionCompat(16, null)
            val ACTION_LONG_CLICK = AccessibilityActionCompat(32, null)
            val ACTION_SCROLL_FORWARD = AccessibilityActionCompat(4096, null)
            val ACTION_SCROLL_BACKWARD = AccessibilityActionCompat(8192, null)
            val ACTION_EXPAND = AccessibilityActionCompat(262144, null)
            val ACTION_COLLAPSE = AccessibilityActionCompat(524288, null)
            val ACTION_DISMISS = AccessibilityActionCompat(1048576, null)
        }
    }
}

/** What an accessibility action does when a service performs it, androidx's. */
fun interface AccessibilityViewCommand {
    fun perform(view: android.view.View, arguments: CommandArguments?): Boolean

    open class CommandArguments
}
