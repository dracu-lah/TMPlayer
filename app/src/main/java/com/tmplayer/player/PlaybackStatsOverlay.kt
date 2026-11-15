package com.tmplayer.player

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.tmplayer.R

/**
 * The playback stats readout (PR5): a small corner box over the picture with two lines of
 * [PlaybackStatsText]. Built only when the Advanced switch is on, so with it off there is no view,
 * no tick and no cost. Never focusable and never clickable: the television's remote and a finger
 * both pass through to the picture, and it sits under the controls, so they draw over it.
 *
 * Top end, the corner the skip pill, the next-up card and the undo chip leave free (they take the
 * bottom ones).
 */
class PlaybackStatsOverlay(root: FrameLayout, below: View, tv: Boolean) {

    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    val view: TextView = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = if (tv) 14f else 11f
        typeface = Typeface.MONOSPACE
        maxLines = 2
        background = context.getDrawable(R.drawable.bg_player_chip)
        setPadding(px(10), px(6), px(10), px(6))
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    init {
        val margin = NextUpCard.marginPx(root)
        root.addView(
            view,
            root.indexOfChild(below),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP,
            ).apply {
                marginEnd = margin
                rightMargin = margin
                topMargin = margin
            },
        )
    }

    /** Draws [lines] and shows the box; an empty list hides it. */
    fun show(lines: List<String>) {
        if (lines.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.text = lines.joinToString("\n")
        view.visibility = View.VISIBLE
    }

    /** Takes the box off the screen for good. */
    fun release() {
        (view.parent as? FrameLayout)?.removeView(view)
    }
}
