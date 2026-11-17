package android.view.accessibility

/** Whether a screen reader is on: VoiceOver on iOS. */
class AccessibilityManager {
    val isEnabled: Boolean get() = platform.UIKit.UIAccessibilityIsVoiceOverRunning()
    val isTouchExplorationEnabled: Boolean get() = platform.UIKit.UIAccessibilityIsVoiceOverRunning()
    fun interface TouchExplorationStateChangeListener {
        fun onTouchExplorationStateChanged(enabled: Boolean)
    }

    fun addTouchExplorationStateChangeListener(listener: TouchExplorationStateChangeListener): Boolean = true
    fun removeTouchExplorationStateChangeListener(listener: TouchExplorationStateChangeListener): Boolean = true

    fun getRecommendedTimeoutMillis(originalTimeout: Int, uiContentFlags: Int): Int = originalTimeout

    companion object {
        const val FLAG_CONTENT_TEXT = 2
        const val FLAG_CONTENT_CONTROLS = 4
        const val FLAG_CONTENT_ICONS = 1
    }
}
