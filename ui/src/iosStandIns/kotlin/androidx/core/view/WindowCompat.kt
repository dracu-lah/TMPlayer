package androidx.core.view

import android.view.View
import android.view.Window
import androidx.core.graphics.Insets
import kotlinx.cinterop.useContents

/** System bar control: iOS shows and hides its bars itself, so these keep their settings only. */
object WindowCompat {
    fun setDecorFitsSystemWindows(window: Window, decorFitsSystemWindows: Boolean) = Unit
    fun getInsetsController(window: Window, view: View): WindowInsetsControllerCompat = WindowInsetsControllerCompat(window, view)
}

/**
 * Hiding and showing Android's system bars, which on iOS are the status bar and the home
 * indicator of the window's screen (Window.systemBarsHidden): hidden together, as the player hides
 * them, and shown again together; iOS hides the status bar of a phone on its side anyway.
 */
class WindowInsetsControllerCompat(private val window: Window?, view: View?) {
    var systemBarsBehavior: Int = BEHAVIOR_DEFAULT
    var isAppearanceLightStatusBars: Boolean = false
    var isAppearanceLightNavigationBars: Boolean = false

    fun hide(types: Int) {
        if (types and BARS != 0) window?.systemBarsHidden = true
    }

    fun show(types: Int) {
        if (types and BARS != 0) window?.systemBarsHidden = false
    }

    companion object {
        const val BEHAVIOR_DEFAULT = 1
        const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2
        private val BARS = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    }
}

/**
 * The window's insets, Android's, from iOS's safe area: the top is the status bar's (and the Dynamic
 * Island's or notch's, held upright), the bottom the home indicator's, which Android calls its
 * navigation bar, and the sides the cutout's when the phone lies on its side. In pixels.
 */
class WindowInsetsCompat internal constructor(private val left: Int, private val top: Int, private val right: Int, private val bottom: Int) {
    fun getInsets(typeMask: Int): Insets {
        val topTypes = Type.statusBars() or Type.captionBar() or Type.displayCutout() or Type.tappableElement()
        val sideTypes = Type.navigationBars() or Type.displayCutout() or Type.tappableElement()
        val bottomTypes = Type.navigationBars() or Type.tappableElement() or Type.mandatorySystemGestures()
        return Insets.of(
            if (typeMask and sideTypes != 0) left else 0,
            if (typeMask and topTypes != 0) top else 0,
            if (typeMask and sideTypes != 0) right else 0,
            if (typeMask and bottomTypes != 0) bottom else 0,
        )
    }

    fun getInsetsIgnoringVisibility(typeMask: Int): Insets = getInsets(typeMask)

    fun isVisible(typeMask: Int): Boolean = true

    // Android's numbers for each kind, so masks built from them mean the same.
    object Type {
        fun statusBars(): Int = 1
        fun navigationBars(): Int = 2
        fun captionBar(): Int = 4
        fun ime(): Int = 8
        fun systemGestures(): Int = 16
        fun mandatorySystemGestures(): Int = 32
        fun tappableElement(): Int = 64
        fun displayCutout(): Int = 128
        fun systemBars(): Int = statusBars() or navigationBars() or captionBar()
    }

    companion object {
        /** The key window's safe area now, in pixels. */
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        fun ofKeyWindow(): WindowInsetsCompat? {
            val window = platform.UIKit.UIApplication.sharedApplication.keyWindow ?: return null
            val density = android.content.res.ResourceFiles.density
            return window.safeAreaInsets.useContents {
                WindowInsetsCompat((left * density).toInt(), (top * density).toInt(), (right * density).toInt(), (bottom * density).toInt())
            }
        }
    }
}
