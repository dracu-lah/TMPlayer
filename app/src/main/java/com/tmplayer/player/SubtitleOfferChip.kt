package com.tmplayer.player

import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.Motion

/**
 * The offer under "Find subtitles for me" (U5): one line that says what was found and from where
 * (or asks about mobile data), and under it the one thing that can be done ("Not this one, try the
 * next", "Find them"). Bottom left, above the "Back to" chip, drawn the way the skip pill and that
 * chip are, so the corner offers read as one family.
 *
 * A phone taps it. A television never focuses it, as it never focuses the skip pill: the activity
 * hands it Up, a key that otherwise only raises the row, and the second line says so. It goes by
 * itself after [SHOW_MS], so it never stays over the picture.
 */
class SubtitleOfferChip(
    root: FrameLayout,
    below: View,
    private val tv: Boolean,
    private val onAction: () -> Unit,
) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    private val label = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = if (tv) 18f else 15f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private val hint = TextView(context).apply {
        setTextColor(context.getColor(R.color.accent))
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = context.getDrawable(R.drawable.bg_player_chip)
        setPadding(px(22), px(if (tv) 12 else 11), px(22), px(if (tv) 10 else 11))
        visibility = View.GONE
        isFocusable = false
        if (!tv) {
            isClickable = true
            setOnClickListener { onAction() }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (moving()) v.animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(PRESS_MS).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MS).start()
                }
                false
            }
        }
        addView(label)
        addView(hint)
    }

    init {
        root.addView(
            view,
            root.indexOfChild(below),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM,
            ).apply {
                leftMargin = NextUpCard.marginPx(root)
                marginStart = NextUpCard.marginPx(root)
                bottomMargin = bottomOver(root)
            },
        )
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /** Puts the offer up: [message] over [action], which a television's line prefixes with the key. */
    fun show(message: String, action: String) {
        label.text = message
        hint.text = if (tv) L.onlineOfferUp(action = action) else action
        label.requestLayout()
        view.requestLayout()
        if (isShown) return
        view.visibility = View.VISIBLE
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        if (moving()) {
            view.alpha = 0f
            view.translationX = -px(SLIDE_DP).toFloat() * if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
            view.animate().alpha(1f).translationX(0f).setDuration(IN_MS).start()
        } else {
            view.alpha = 1f
            view.translationX = 0f
        }
    }

    fun hide() {
        if (!isShown) return
        view.animate().cancel()
        if (!moving()) {
            view.visibility = View.GONE
            return
        }
        view.animate().alpha(0f).setDuration(OUT_MS)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
    }

    /** Takes the view off the screen for good (the video is over). */
    fun release() {
        view.animate().cancel()
        (view.parent as? FrameLayout)?.removeView(view)
    }

    private fun moving(): Boolean = !Motion.reduced && com.tmplayer.ui.theme.systemAnimatorsEnabled(context)

    companion object {
        const val SHOW_MS = 15_000L
        private const val SLIDE_DP = 24
        private const val IN_MS = 220L
        private const val OUT_MS = 160L
        private const val PRESS_MS = 90L
        private const val PRESSED = 0.94f

        /** Room for the "Back to" chip under it. */
        private const val ABOVE_UNDO_DP = 64

        /** The bottom margin that clears the "Back to" chip, which sits at the same corner. */
        fun bottomOver(root: View): Int = NextUpCard.bottomPx(root) + (ABOVE_UNDO_DP * root.resources.displayMetrics.density).toInt()
    }
}
