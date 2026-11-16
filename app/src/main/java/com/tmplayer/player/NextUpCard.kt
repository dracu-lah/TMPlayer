package com.tmplayer.player

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tmplayer.R
import com.tmplayer.i18n.L

/**
 * The bottom-right card that offers the next episode before this one ends: "Next: S01E05,
 * starting in 12 s", with Hide (phone only) and Play now.
 *
 * On a television it comes up with D-pad focus on Play now, so OK starts the episode and Back puts
 * the card away, which is why it has no Hide button there; the activity routes the remote's keys to
 * it while it is up and the row is down. The button takes the row's own focus treatment, a solid
 * white seat with dark text.
 *
 * Built in code and added to [root] under [below], the overlay container, so the gesture HUD and
 * the phone's flashes still draw over it. The activity owns when it shows and what pressing does.
 */
class NextUpCard(
    root: FrameLayout,
    below: View,
    private val tv: Boolean,
    onHide: () -> Unit,
    onPlay: () -> Unit,
) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    private val text = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = if (tv) 18f else 14f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = px(if (tv) 340 else 260)
    }

    /** Play now, which a television focuses as the card comes up. */
    val play: Button = button(L.playerPlayNow, onPlay)

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.getDrawable(R.drawable.bg_player_chip)
        // Play now sits in the card's bottom end corner: 12 dp from both edges there, with its
        // focus fill rounded at the card's 22 dp less those 12, so the two corners run parallel.
        setPadding(px(18), px(14), px(12), px(12))
        visibility = View.GONE
        isClickable = true
        addView(text)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                // On a television Back puts the card away, which is the shorter path than steering
                // across to a second button with the D-pad.
                if (!tv) addView(button(L.commonHide, onHide))
                addView(play)
            },
        )
    }

    init {
        root.addView(
            view,
            root.indexOfChild(below),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                SideInsets.atEnd(this, marginPx(root))
                bottomMargin = bottomPx(root)
            },
        )
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /** Writes [label] and the seconds left, and shows the card. True when it has just come up. */
    fun show(label: String, secondsLeft: Long): Boolean {
        text.text = L.playerNextUpCard(label, secondsLeft)
        if (isShown) return false
        view.visibility = View.VISIBLE
        return true
    }

    /** Hides the card. True when it held focus, which the caller then has to put somewhere. */
    fun hide(): Boolean {
        val hadFocus = view.hasFocus()
        view.visibility = View.GONE
        return hadFocus
    }

    private fun button(label: String, action: () -> Unit) =
        Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(context.getColor(R.color.accent))
            isAllCaps = false
            setOnClickListener { action() }
            if (tv) {
                textSize = 16f
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_focused),
                        GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = px(10).toFloat()
                        },
                    )
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(Color.BLACK, context.getColor(R.color.accent)),
                    ),
                )
                setPadding(px(20), 0, px(20), 0)
            }
        }

    companion object {
        /**
         * Clear of the screen edge, before the system's own insets are added. In dp, so the offers
         * stand the same distance in on every screen: these were raw pixels, which put them 24 dp
         * in on a 2x television and 16 dp on a 3x phone.
         */
        private const val MARGIN_DP = 24f

        /** Clear of the transport row's cluster: the 2x television's figure, as dp. */
        private const val BOTTOM_DP = 110f

        fun marginPx(view: android.view.View): Int = (MARGIN_DP * view.resources.displayMetrics.density).toInt()

        fun bottomPx(view: android.view.View): Int = (BOTTOM_DP * view.resources.displayMetrics.density).toInt()
    }
}

/**
 * The screen's side insets as start and end, and the margins of a chip that stands at one of them.
 *
 * A chip laid out at [android.view.Gravity.END] sits on the left right to left, so a bare
 * `rightMargin` put it flush against the left edge (and under a cutout there) in Arabic, Hebrew
 * and Persian. The relative margin is what a [FrameLayout] reads once it is set; the absolute one
 * is kept for the side it means left to right.
 */
internal data class SideInsets(val start: Int, val end: Int) {
    companion object {
        fun of(left: Int, right: Int, rtl: Boolean): SideInsets =
            if (rtl) SideInsets(start = right, end = left) else SideInsets(start = left, end = right)

        fun atEnd(params: FrameLayout.LayoutParams, px: Int) {
            params.marginEnd = px
            params.rightMargin = px
        }

        fun atStart(params: FrameLayout.LayoutParams, px: Int) {
            params.marginStart = px
            params.leftMargin = px
        }
    }
}
