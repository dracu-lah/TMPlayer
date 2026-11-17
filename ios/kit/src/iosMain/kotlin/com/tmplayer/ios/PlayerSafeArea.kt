package com.tmplayer.ios

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Nothing of the player's text under the iPhone's notch, Dynamic Island or home indicator (the
 * maintainer, 2026-11-12), where upstream's own layout leaves it there.
 *
 * Upstream insets its controls, top bar and corner offers by the window's bars and cutout
 * (`PlayerActivity.insetTheControls`), but not:
 * - its status sheet (the loading screen, the end, an error), whose padding clears an Android
 *   phone's camera hole but not the island, which reaches about 50 points in from the side held to
 *   the left, nor the home indicator: the sheet's scroller takes the safe area on every side;
 * - its subtitles, which follow the picture and so reach the island when a video fills the width:
 *   the subtitle view takes it at the sides only, so the text keeps its size (a fraction of the
 *   view's height) and only its lines are shorter. Its bottom padding fraction already clears the
 *   home indicator.
 * - its loader ("Reading the video"), whose words are inset by the window's insets read as it
 *   comes up, in onCreate, before the player's views are in a window: none then, on Android too.
 *   Its ComposeView takes the safe area on every side, so its blurred backdrop stops there too.
 * Each gets padding only where it overlaps the unsafe edges, on top of its own, checked at every
 * layout (a turn of the screen, a new picture shape) and whenever the window's insets reach it
 * (the status bar coming or going with the player's row, which moves no view). The backdrop
 * picture and scrim behind the sheet still fill the screen, as on Android.
 */
internal object PlayerSafeArea {
    fun keepClear(activity: Activity) {
        val sheet: View? = activity.findViewById(com.tmplayer.R.id.status_sheet)
        (sheet?.parent as? View)?.let { keepInside(it, vertical = true) }
        val subtitles: View? = activity.findViewById(com.tmplayer.R.id.subtitles)
        subtitles?.let { keepInside(it, vertical = false) }
        // The loader has no id: upstream's PlayerLoaderHost adds it right above the status overlay.
        val root: android.view.ViewGroup? = activity.findViewById(com.tmplayer.R.id.player_root)
        val status: View? = activity.findViewById(com.tmplayer.R.id.status_overlay)
        val loader = root?.takeIf { status != null }?.let { it.getChildAt(it.indexOfChild(status) + 1) }
        (loader as? androidx.compose.ui.platform.ComposeView)?.let { keepInside(it, vertical = true) }
    }

    private fun keepInside(view: View, vertical: Boolean) {
        val base = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        fun apply() {
            val safe = WindowInsetsCompat.ofKeyWindow()
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: return
            var top: View = view
            while (true) top = top.parent as? View ?: break
            if (top.width <= 0 || top.height <= 0 || view.width <= 0) return
            // Where the view is laid out, without a slide it is part way through (the subtitles' lift).
            val at = IntArray(2).also(view::getLocationInWindow)
            val left = at[0] - view.translationX.roundToInt()
            val y = at[1] - view.translationY.roundToInt()
            val want = intArrayOf(
                base[0] + max(0, safe.left - left),
                base[1] + if (vertical) max(0, safe.top - y) else 0,
                base[2] + max(0, left + view.width - (top.width - safe.right)),
                base[3] + if (vertical) max(0, y + view.height - (top.height - safe.bottom)) else 0,
            )
            if (want[0] != view.paddingLeft || want[1] != view.paddingTop || want[2] != view.paddingRight || want[3] != view.paddingBottom) {
                view.setPadding(want[0], want[1], want[2], want[3])
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        // Insets reach the tree before its layout pass: measured after it. Upstream sets its insets
        // listener on the player's root only (insetTheControls), never on these views.
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            view.post { apply() }
            insets
        }
        view.post { apply() }
    }
}
